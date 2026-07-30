"""Turn raw transactions into per-(account, month) behaviour vectors.

This module is the craft of the whole project. Clustering can only find
structure that my features expose, so the choice of features IS the choice of
what "spending archetype" means.

My guiding rule: describe BEHAVIOUR, not volume. If I fed raw dollar totals to
KMeans, my clusters would be "rich", "medium", "poor" — true, useless, and
something a SQL query already tells me. So almost every feature here is a RATIO
or a RATE, which makes a $2k/month student and a $12k/month engineer land in the
same cluster when they behave the same way. That is the insight I'm selling.

I import these functions in tests and in the scoring service rather than
copy-pasting the logic, because a feature computed one way at training time and
another way at scoring time is the classic silent ML bug.
"""

from pathlib import Path

import numpy as np
import pandas as pd

from categories import CANONICAL, canonical, is_inflow

DATA_DIR = Path(__file__).parent / "data"

# The feature columns, in a FIXED order. scikit-learn works on positional
# arrays, so this order is part of my model's contract: if it changed between
# training and scoring, every prediction would be silently wrong. My scoring
# service imports this list rather than hardcoding names.
#
# One structural decision shows up all over this list: I split spending into
# FIXED (housing — rent, utilities, loan payments) and DISCRETIONARY
# (everything else), and I compute the behaviour ratios over the discretionary
# part only. My first attempt didn't, and housing came out at 55% of all spend,
# so every ratio was really measuring "how big is your rent" — a fact about
# someone's lease, not their behaviour. Rent gets exactly one feature
# (fixed_share, how locked-in they are) and then steps out of the way.
DISCRETIONARY_CATEGORIES = [c for c in CANONICAL if c not in ("housing", "other")]

BEHAVIOUR_FEATURES = [
    "txn_count",
    "avg_ticket",
    "ticket_variability",
    "merchant_diversity",
    "weekend_ratio",
    "recurring_share",
    "fixed_share",
    "spend_to_income",
] + [f"share_{c}" for c in DISCRETIONARY_CATEGORIES]


def load_transactions(*paths: Path) -> pd.DataFrame:
    """Read one or more transaction CSVs and normalise them into one frame.

    I accept several files because I train on my synthetic users AND my real
    Plaid data, and they must go through byte-identical preprocessing.
    """
    frames = []
    for path in paths:
        if not Path(path).exists():
            continue
        frame = pd.read_csv(path, parse_dates=["posted_date"])
        frames.append(frame)
    if not frames:
        raise FileNotFoundError("no transaction CSVs found — run export.py / synthesize.py first")

    df = pd.concat(frames, ignore_index=True)

    # Everything downstream depends on these three normalisations, so I do them
    # exactly once, here.
    df["category"] = df["category"].map(canonical)
    df["is_inflow"] = df["category"].map(is_inflow)
    # Truncating each date to the first of its month gives me my grouping key.
    df["month"] = df["posted_date"].values.astype("datetime64[M]")
    return df


def flag_recurring(df: pd.DataFrame) -> pd.DataFrame:
    """Mark transactions that look like subscriptions.

    My rule: the same (account, merchant) charged a near-constant amount in at
    least 3 distinct months. I check three months rather than two because two
    coincidental $12 lunches at the same place shouldn't count as a
    subscription, and I require the amount to be stable because a merchant I
    visit monthly for random amounts is a habit, not a recurring charge.

    I use a relative tolerance (5% of the mean) instead of an absolute dollar
    one so it works for a $2.99 iCloud plan and a $1,250 rent payment alike.
    This flag earns its keep twice: it becomes the recurring_share feature here,
    and it powers the "your subscriptions" view in my dashboard later.
    """
    outflow = df[~df["is_inflow"]].copy()

    # Rounding to the dollar tolerates the cent-level drift real subscriptions
    # have (tax changes, FX) while still separating genuinely different charges.
    outflow["amount_key"] = outflow["amount"].round(0)

    grouped = outflow.groupby(["account_id", "merchant", "amount_key"]).agg(
        months=("month", "nunique"),
        mean_amount=("amount", "mean"),
        std_amount=("amount", "std"),
    ).reset_index()

    # std is NaN for a single-row group; that's not variability, it's an unknown,
    # so I treat it as zero variation.
    grouped["std_amount"] = grouped["std_amount"].fillna(0.0)

    recurring_keys = grouped[
        (grouped["months"] >= 3)
        & (grouped["std_amount"] <= 0.05 * grouped["mean_amount"].abs().clip(lower=0.01))
    ][["account_id", "merchant", "amount_key"]]

    df = df.copy()
    df["amount_key"] = df["amount"].round(0)
    # An indicator column on the right side survives the merge as True; rows
    # that don't match come back NaN, which I fill with False.
    recurring_keys = recurring_keys.assign(is_recurring=True)
    df = df.merge(recurring_keys, on=["account_id", "merchant", "amount_key"], how="left")
    # .eq(True) rather than .fillna(False).astype(bool): unmatched rows come back
    # as NaN in an object column, and pandas warns that silently downcasting that
    # is going away. Comparing to True treats NaN as False without the guesswork.
    df["is_recurring"] = df["is_recurring"].eq(True)
    # Inflows are never subscriptions, whatever the pattern looks like.
    df.loc[df["is_inflow"], "is_recurring"] = False
    return df.drop(columns=["amount_key"])


def monthly_features(df: pd.DataFrame, min_transactions: int = 5) -> pd.DataFrame:
    """Build one behaviour vector per (account, month).

    I drop months with fewer than `min_transactions` rows. A month with two
    transactions has no stable "behaviour" to describe — its ratios swing wildly
    on a single purchase, and feeding that noise to KMeans would blur every
    cluster boundary. Dropping thin months is a judgement call I'd defend: I'd
    rather score fewer months honestly than all months badly.
    """
    if "is_recurring" not in df.columns:
        df = flag_recurring(df)

    rows = []
    for (account_id, month), group in df.groupby(["account_id", "month"]):
        spend = group[~group["is_inflow"]]
        income = group[group["is_inflow"]]

        # The fixed/discretionary split described at the top of this file.
        fixed = spend[spend["category"] == "housing"]
        disc = spend[spend["category"] != "housing"]

        # I require enough DISCRETIONARY transactions, not just enough rows. A
        # month containing rent and four coffees tells me nothing about
        # behaviour, however many records it holds.
        if len(disc) < min_transactions:
            continue

        total_spend = float(spend["amount"].sum())
        disc_spend = float(disc["amount"].sum())
        if total_spend <= 0 or disc_spend <= 0:
            continue

        total_income = float(-income["amount"].sum()) if len(income) else 0.0

        feature_row = {
            "account_id": int(account_id),
            "month": month,

            # How OFTEN I make a discretionary purchase. Frequency separates the
            # daily-coffee user from the once-a-week shopper regardless of
            # amounts. Rent is excluded: it's one row a month for everybody, so
            # counting it just adds a constant.
            "txn_count": float(len(disc)),

            # Typical purchase size. I use the MEDIAN, not the mean: one $1,899
            # laptop shouldn't redefine a month of $8 coffees. Anomalies are
            # exactly what I'm hunting elsewhere, so I don't want them
            # distorting the behaviour baseline here.
            "avg_ticket": float(disc["amount"].median()),

            # Spread of purchase sizes, scaled by the median so it's comparable
            # across income levels. High = a mix of small and large purchases;
            # low = everything about the same size.
            # I use the INTERQUARTILE RANGE rather than the standard deviation.
            # With std, a single large purchase produced values of 30+ while
            # typical months sat near 1 — a tail that heavy drags KMeans
            # centroids around, because Euclidean distance has no defence
            # against outliers. The IQR ignores the extremes by construction,
            # which is what I want from a "typical spread" feature.
            "ticket_variability": float(
                (disc["amount"].quantile(0.75) - disc["amount"].quantile(0.25))
                / max(disc["amount"].median(), 1.0)
            ),

            # Unique merchants per transaction. Near 1.0 = I never repeat a
            # merchant; near 0.1 = I go to the same few places constantly.
            "merchant_diversity": float(disc["merchant"].nunique() / len(disc)),

            # Share of discretionary spend falling Fri-Sun. This is the cleanest
            # behavioural signal I have: it's about WHEN I spend, which no
            # category tells me. Rent would only add noise here — it posts on
            # the 1st, whatever weekday that happens to be.
            "weekend_ratio": float(
                disc.loc[disc["posted_date"].dt.dayofweek >= 4, "amount"].sum() / disc_spend
            ),

            # Share of discretionary spend locked into recurring charges — the
            # "subscription creep" signal, and the spending someone could
            # plausibly cancel.
            "recurring_share": float(
                disc.loc[disc["is_recurring"], "amount"].sum() / disc_spend
            ),

            # The one feature where rent DOES belong: how much of my money is
            # committed before I make a single choice. Denominator is total
            # spend, since that's the whole point of the ratio.
            "fixed_share": float(fixed["amount"].sum() / total_spend),

            # Spend as a fraction of income. I cap it at 3.0 because a month
            # where income wasn't captured produces a meaningless huge ratio
            # that would dominate the scaler. 1.0 when I have no income data —
            # a neutral "spent about what I earned" rather than a fake extreme.
            "spend_to_income": float(min(total_spend / total_income, 3.0)) if total_income > 0 else 1.0,
        }

        # Category SHARES, not dollars — the ratio rule again. These tell me
        # what kind of spender someone is; the dollar totals only tell me how
        # much money they have. Computed over discretionary spend, so they
        # describe choices rather than obligations.
        by_category = disc.groupby("category")["amount"].sum()
        for category in DISCRETIONARY_CATEGORIES:
            feature_row[f"share_{category}"] = float(by_category.get(category, 0.0) / disc_spend)

        rows.append(feature_row)

    features = pd.DataFrame(rows)
    if features.empty:
        raise ValueError("no account-months survived filtering — is my input data too sparse?")
    return features.sort_values(["account_id", "month"]).reset_index(drop=True)


def feature_matrix(features: pd.DataFrame) -> np.ndarray:
    """Extract the model input in the exact column order the model expects."""
    return features[BEHAVIOUR_FEATURES].to_numpy(dtype=float)


def main() -> None:
    df = load_transactions(
        DATA_DIR / "synthetic_transactions.csv",
        DATA_DIR / "transactions.csv",
    )
    df = flag_recurring(df)
    features = monthly_features(df)

    out = DATA_DIR / "monthly_features.csv"
    features.to_csv(out, index=False)
    print(f"built {len(features)} account-months x {len(BEHAVIOUR_FEATURES)} features -> {out}")
    print(f"recurring charges detected: {int(df['is_recurring'].sum())} of {len(df)} transactions")
    print("\nfeature summary:")
    print(features[BEHAVIOUR_FEATURES].describe().T[["mean", "std", "min", "max"]].round(3).to_string())


if __name__ == "__main__":
    main()
