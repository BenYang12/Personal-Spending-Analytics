"""Inject synthetic anomalies so I can actually MEASURE detection quality.

This module exists because of a problem specific to anomaly detection: my data
has no labels. Nobody told me which of my 8,000 transactions were fraudulent, so
I cannot compute precision or recall, and without those I have no way to say
whether IsolationForest is better than a one-line rule. "The model flagged 47
transactions" is not a result — I'd have no idea how many were real.

So I manufacture the labels. I take clean data, insert charges I know are
anomalous, and then ask each method how many it found (recall) and how much of
what it flagged was actually planted (precision). This is standard practice when
ground truth is unavailable, and being upfront about it is important: these
numbers measure performance against MY THREE ANOMALY PATTERNS, not against real
fraud. I'd rather report an honest, clearly-scoped number than an unfalsifiable
claim.

I model three patterns, chosen because they fail differently:

  1. AMOUNT SPIKE — a charge many times the account's usual size, in a category
     it already uses. A z-score should find this easily.
  2. NOVEL-CATEGORY SPLURGE — a large charge at a never-seen merchant in a
     never-used category. This is the pattern that defeated my first baseline.
  3. CARD-TESTING BURST — several small charges at a new merchant on one day.
     Individually unremarkable; suspicious only as a pattern. I expect BOTH
     methods to struggle here, and I want that visible rather than hidden.
"""

import numpy as np
import pandas as pd

# Merchants that appear nowhere in my generated data, so "novel merchant" really
# is novel rather than accidentally familiar.
FRAUD_MERCHANTS = [
    "QuickCash Kiosk", "Global Prepaid Ltd", "Duty Free Terminal 4",
    "Crypto Exchange XYZ", "Luxury Watch Outlet", "Anonymous Gift Cards",
]

ANOMALY_KINDS = ["amount_spike", "novel_category_splurge", "card_testing_burst"]


def inject_anomalies(
    spend: pd.DataFrame,
    n_per_kind: int = 8,
    seed: int = 7,
) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Add labelled anomalies to a transaction frame.

    Returns (combined_frame, injected_rows). The combined frame carries an
    `is_injected_anomaly` boolean — my ground truth, and the only place a label
    is allowed to exist. It never enters either model's feature set.
    """
    rng = np.random.default_rng(seed)
    original = spend.copy()
    original["is_injected_anomaly"] = False
    original["anomaly_kind"] = None

    # I only target accounts with enough history for "unusual" to be defined. An
    # anomaly on an account with three prior transactions would be unfair to
    # both methods, and unfair tests teach me nothing.
    eligible = (
        original.groupby("account_id").size().loc[lambda s: s >= 40].index.tolist()
    )
    if not eligible:
        raise ValueError("no accounts have enough history to inject into")

    injected: list[dict] = []

    for kind in ANOMALY_KINDS:
        for i in range(n_per_kind):
            account_id = int(rng.choice(eligible))
            account_rows = original[original["account_id"] == account_id]
            typical = float(account_rows["amount"].median())
            # I place anomalies in the LATER part of each account's history, so
            # there's prior history to deviate from. An anomaly in row 1 is
            # undetectable by construction.
            late_dates = account_rows["posted_date"].sort_values()
            anchor = late_dates.iloc[int(len(late_dates) * 0.6)]

            if kind == "amount_spike":
                # A category the account already uses, so ONLY the amount is
                # unusual. This isolates the amount signal.
                category = str(account_rows["category"].mode().iloc[0])
                merchant = str(account_rows.loc[
                    account_rows["category"] == category, "merchant"
                ].mode().iloc[0])
                injected.append(dict(
                    transaction_id=f"inj-{kind}-{i}",
                    account_id=account_id,
                    posted_date=anchor + pd.Timedelta(days=2),
                    # 8-15x typical: large enough to be genuinely anomalous,
                    # small enough that I'm not testing a trivial case.
                    amount=round(typical * float(rng.uniform(8, 15)), 2),
                    merchant=merchant,
                    category=category,
                    pending=False,
                    is_inflow=False,
                    is_recurring=False,
                    is_injected_anomaly=True,
                    anomaly_kind=kind,
                ))

            elif kind == "novel_category_splurge":
                # Never-seen merchant, and a category this account has never
                # used. This is my planted $1,899 electronics charge in
                # generalised form.
                used = set(account_rows["category"].unique())
                unused = [c for c in ("shopping", "entertainment", "transport", "dining")
                          if c not in used] or ["shopping"]
                injected.append(dict(
                    transaction_id=f"inj-{kind}-{i}",
                    account_id=account_id,
                    posted_date=anchor + pd.Timedelta(days=3),
                    amount=round(typical * float(rng.uniform(12, 25)), 2),
                    merchant=str(rng.choice(FRAUD_MERCHANTS)),
                    category=str(rng.choice(unused)),
                    pending=False,
                    is_inflow=False,
                    is_recurring=False,
                    is_injected_anomaly=True,
                    anomaly_kind=kind,
                ))

            else:  # card_testing_burst
                # 4 small charges, same merchant, same day. Each one is boring;
                # the cluster is the signal. I expect this to be the hard case.
                merchant = str(rng.choice(FRAUD_MERCHANTS))
                burst_day = anchor + pd.Timedelta(days=4)
                for j in range(4):
                    injected.append(dict(
                        transaction_id=f"inj-{kind}-{i}-{j}",
                        account_id=account_id,
                        posted_date=burst_day,
                        amount=round(float(rng.uniform(1.0, 6.0)), 2),
                        merchant=merchant,
                        category="shopping",
                        pending=False,
                        is_inflow=False,
                        is_recurring=False,
                        is_injected_anomaly=True,
                        anomaly_kind=kind,
                    ))

    injected_frame = pd.DataFrame(injected)
    injected_frame["month"] = injected_frame["posted_date"].values.astype("datetime64[M]")

    combined = pd.concat([original, injected_frame], ignore_index=True)
    combined = combined.sort_values(["account_id", "posted_date"]).reset_index(drop=True)
    return combined, injected_frame
