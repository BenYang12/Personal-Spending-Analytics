"""Turn raw transactions into model features, reusing the /ml training code.

This module exists to prevent one specific bug: **training/serving skew**.

My models were trained on features built by `ml/features.py` and
`ml/anomaly_features.py`. If my backend computed those same features itself — in
Java, from the same database — there would be two implementations of 26
definitions that must agree exactly, forever. They wouldn't. Someone (me) would
eventually fix a rounding rule in one place, and the model would start receiving
numbers slightly unlike anything it trained on. Nothing would error. Predictions
would just quietly get worse, and I'd have no idea why.

So my Spring backend sends RAW TRANSACTIONS and this module computes features
using the identical functions that produced the training set. One implementation,
no drift, by construction.

The tradeoff I'm accepting: this service now imports from `../ml`, so those two
files are a deployment dependency. Locally I add the directory to sys.path; in a
container I'd COPY them into the image. The cleaner long-term answer is to
publish the feature code as a small installable package that both the training
pipeline and this service depend on by version — that way I could even score
with an older feature version if I needed to. That's the upgrade I'd make before
this went anywhere near production.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pandas as pd

# Locally the training code lives one directory up. This is the seam that a
# proper shared package would replace.
ML_DIR = Path(__file__).parent.parent / "ml"
if str(ML_DIR) not in sys.path:
    sys.path.insert(0, str(ML_DIR))

from anomaly_features import (  # noqa: E402
    ANOMALY_FEATURES,
    build_transaction_features,
)
from categories import canonical, is_inflow  # noqa: E402
from features import (  # noqa: E402
    BEHAVIOUR_FEATURES,
    flag_recurring,
    monthly_features,
)


class NotEnoughDataError(ValueError):
    """Raised when there aren't enough transactions to describe behaviour.

    Its own type so my API can answer 422 ("I understood you, but I can't do
    this") rather than pretending a two-transaction month has an archetype. I'd
    rather return an honest "not enough data" than a confident label built on
    noise — a wrong archetype shown to a user is worse than no archetype.
    """


def to_dataframe(transactions: list[dict]) -> pd.DataFrame:
    """Normalise incoming JSON transactions exactly like the training loader.

    `ml/features.load_transactions` does this same normalisation when reading
    CSVs. I can't reuse it directly because it reads from disk, so I replicate
    only its three normalisation steps — and those steps call the same
    `canonical` / `is_inflow` functions, so the category mapping can't drift.
    """
    frame = pd.DataFrame(transactions)
    frame["posted_date"] = pd.to_datetime(frame["posted_date"])

    # Category reconciliation: my backend sends whatever Plaid or my seed data
    # called it ("FOOD_AND_DRINK", "Groceries"), and this collapses both onto
    # the canonical vocabulary the models were trained on.
    frame["category"] = frame["category"].map(canonical)
    frame["is_inflow"] = frame["category"].map(is_inflow)
    frame["month"] = frame["posted_date"].values.astype("datetime64[M]")
    return frame


def month_features(transactions: list[dict], month: str) -> dict[str, float]:
    """Build the 14 behaviour features for one account-month.

    I pass the account's WIDER history in, not just the target month, because
    recurring-charge detection needs at least three months to recognise a
    subscription. Then I select the requested month's row at the end.
    """
    frame = to_dataframe(transactions)
    frame = flag_recurring(frame)

    try:
        monthly = monthly_features(frame)
    except ValueError as error:
        # monthly_features raises when nothing survives its minimum-transaction
        # filter. I translate that into my own error type so the API layer
        # doesn't have to pattern-match on somebody else's message text.
        raise NotEnoughDataError(str(error)) from error

    target = pd.Period(month, freq="M").to_timestamp()
    row = monthly[monthly["month"] == target]
    if row.empty:
        raise NotEnoughDataError(
            f"{month} has too few discretionary transactions to describe behaviour "
            "(I need at least 5)"
        )

    # Return a NAMED dict. The scorer reorders it against the model's own
    # feature_order, so ordering mistakes are impossible rather than silent.
    return {name: float(row.iloc[0][name]) for name in BEHAVIOUR_FEATURES}


def transaction_features(transactions: list[dict]) -> list[dict]:
    """Build the 12 per-transaction anomaly features.

    Again I want the account's full history, because every feature here is
    relative to that account's own past — a z-score against three transactions
    means nothing. My backend sends the whole account and I score all of it.

    Returns one entry per SCORABLE transaction. The count can be smaller than
    the input: inflows and refunds are dropped upstream, which is why each entry
    carries its own transaction_id rather than relying on positions lining up.
    """
    frame = to_dataframe(transactions)
    frame = flag_recurring(frame)

    scored = build_transaction_features(frame)
    if scored.empty:
        raise NotEnoughDataError("no scorable spending transactions in this request")

    return [
        {
            "transaction_id": str(row["transaction_id"]),
            "features": {name: float(row[name]) for name in ANOMALY_FEATURES},
        }
        for _, row in scored.iterrows()
    ]
