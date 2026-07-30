"""Per-transaction features for anomaly detection.

This is a different modelling problem from the clustering, and the difference is
worth being explicit about because it changed every design decision here:

  * Clustering asks "what kind of month is this?" — one vector per account-month,
    describing a stable pattern.
  * Anomaly detection asks "is this ONE charge unusual FOR THIS PERSON?" — one
    vector per transaction, describing a deviation.

That second framing carries the key constraint: unusual *for this person*. A
$400 grocery run is routine for a family and alarming for me. So almost every
feature below is computed against the account's OWN history rather than any
global average. A model that flagged "large" charges would just be a threshold
with extra steps.

The second constraint is causality. I only ever use a transaction's PAST when
scoring it. It's tempting to compute "amount vs this account's average" over the
whole dataset, but that average would include the transaction itself and every
one after it — future information the model wouldn't have in production. That's
leakage, and it makes offline metrics look great and production behaviour
terrible. I use expanding windows that only look backwards.
"""

import numpy as np
import pandas as pd

# Fixed order, same contract as my behaviour features: scikit-learn sees
# positions, so my scoring service must build columns in exactly this sequence.
ANOMALY_FEATURES = [
    "amount",
    "amount_log",
    "amount_zscore_in_category",
    "amount_ratio_to_category_median",
    "merchant_novelty",
    "merchant_frequency",
    "is_weekend",
    "day_of_month",
    "days_since_previous",
    "category_rarity",
    # The last two exist because of a measured failure, not a hunch. My first
    # version scored 0% recall on card-testing bursts — several tiny charges at
    # one new merchant in a single day — and so did the z-score baseline. Looking
    # at the rows explained it instantly: each charge was SMALL, so every
    # amount-based feature said "normal", and nothing described the pattern the
    # charges formed together. No amount of model tuning fixes a signal that
    # isn't in the features. So I added it.
    "merchant_txns_same_day",
    "account_txns_same_day",
]

# I need some history before a deviation means anything. With two prior
# transactions, "unusual" is noise, so I fall back to neutral values until an
# account has at least this many.
MIN_HISTORY = 5


def build_transaction_features(df: pd.DataFrame) -> pd.DataFrame:
    """Compute one feature row per transaction, using only that row's past.

    Input must already be normalised by features.load_transactions (canonical
    categories, is_inflow, month).
    """
    # Anomaly detection on spending only. A paycheck is a large amount by
    # definition; including inflows would fill my flag list with salary.
    #
    # I also drop non-positive amounts, which I only noticed after looking at the
    # feature summary: real Plaid data contains refunds and credits sitting in
    # ordinary spending categories, and they arrived as negative amounts. Left in,
    # they dragged the minimum z-score to -739 and would have had me "flagging"
    # people for getting money back. A refund is not a suspicious charge.
    spend = df[(~df["is_inflow"]) & (df["amount"] > 0)].copy()

    # Sorting is load-bearing, not cosmetic: every expanding window below depends
    # on rows being in chronological order within each account.
    spend = spend.sort_values(["account_id", "posted_date"]).reset_index(drop=True)

    # Log amount tames the scale. Amounts span $2 coffees to $1,899 laptops, and
    # IsolationForest splits on raw values, so without this the tree spends all
    # its splits separating the top of the range.
    spend["amount_log"] = np.log1p(spend["amount"].clip(lower=0))

    grouped_by_category = spend.groupby(["account_id", "category"])["amount"]

    # PAST mean and std within (account, category). shift(1) is what enforces
    # causality: each row sees the statistics of the rows BEFORE it and never
    # its own value.
    past_mean = grouped_by_category.transform(lambda s: s.shift(1).expanding().mean())
    past_std = grouped_by_category.transform(lambda s: s.shift(1).expanding().std())
    past_median = grouped_by_category.transform(lambda s: s.shift(1).expanding().median())
    past_count = grouped_by_category.transform(lambda s: s.shift(1).expanding().count())

    # Account-level past statistics as a FALLBACK for the category-level ones.
    # I added these after finding that my planted $1,899 anomaly scored z = 0.0:
    # it was the first ever Shopping charge on that card, so there was no
    # category history to deviate from, and the NaN got filled with a neutral
    # zero. My baseline was structurally blind to exactly the case I most wanted
    # it to catch.
    #
    # I could have left it broken — the forest catches it via merchant_novelty,
    # so the comparison would have flattered the forest. But a benchmark I
    # crippled on purpose proves nothing. Falling back to the account's overall
    # spending distribution gives the baseline a fair shot.
    grouped_by_account = spend.groupby("account_id")["amount"]
    account_past_mean = grouped_by_account.transform(lambda s: s.shift(1).expanding().mean())
    account_past_std = grouped_by_account.transform(lambda s: s.shift(1).expanding().std())
    account_past_median = grouped_by_account.transform(lambda s: s.shift(1).expanding().median())

    # How many standard deviations from this account's own norm for this
    # category. This is the single most informative feature I have, and it's also
    # exactly the z-score baseline I'll benchmark the forest against.
    # I floor the denominator: a category where every charge was identical has
    # std 0, and dividing by it would produce inf.
    category_zscore = (spend["amount"] - past_mean) / past_std.clip(lower=1.0)
    account_zscore = (spend["amount"] - account_past_mean) / account_past_std.clip(lower=1.0)
    # Prefer the category-specific signal; fall back to account-wide when this
    # (account, category) pair has no history yet.
    spend["amount_zscore_in_category"] = category_zscore.fillna(account_zscore)

    # A ratio complements the z-score: it stays meaningful when past variance is
    # tiny, where a z-score explodes. "3x your usual" is also far easier to show
    # a user than "z = 4.1". Same category-then-account fallback as above.
    category_ratio = spend["amount"] / past_median.clip(lower=1.0)
    account_ratio = spend["amount"] / account_past_median.clip(lower=1.0)
    spend["amount_ratio_to_category_median"] = category_ratio.fillna(account_ratio)

    # First time this account has ever used this merchant? Card fraud tends to
    # show up at merchants the victim has never visited, so novelty is a genuine
    # fraud signal rather than a statistical curiosity.
    first_seen = ~spend.duplicated(subset=["account_id", "merchant"], keep="first")
    spend["merchant_novelty"] = first_seen.astype(float)

    # How established the merchant is in this account's history (count of prior
    # visits). Complements novelty: 1 prior visit is nearly as suspicious as 0.
    spend["merchant_frequency"] = (
        spend.groupby(["account_id", "merchant"]).cumcount().astype(float)
    )

    # Timing. Fraud skews toward weekends and odd hours; I have no timestamps
    # from Plaid, only dates, so day-of-week is the resolution available and I
    # won't pretend otherwise.
    spend["is_weekend"] = (spend["posted_date"].dt.dayofweek >= 5).astype(float)
    spend["day_of_month"] = spend["posted_date"].dt.day.astype(float)

    # Gap since this account's previous transaction. A burst of charges minutes
    # apart is the classic card-testing pattern; at date resolution a 0-day gap
    # is my proxy for it.
    spend["days_since_previous"] = (
        spend.groupby("account_id")["posted_date"].diff().dt.days.fillna(30.0)
    )

    # Burst features: how many charges this account made TODAY, and how many at
    # this same merchant today. Four charges at one brand-new merchant on one day
    # is card testing; the individual amounts are meaningless.
    #
    # One honesty note about causality. Unlike every other feature here, a
    # same-day count includes charges that arrive LATER the same day, which a
    # real-time scorer wouldn't have yet. That's legitimate for my architecture —
    # I score in batches after Plaid sync, so the day is complete by then — but it
    # would be leakage in a system that authorised transactions live. Same-day
    # counts would have to become rolling-window counts over the past N hours.
    spend["merchant_txns_same_day"] = spend.groupby(
        ["account_id", "merchant", "posted_date"]
    )["amount"].transform("size").astype(float)
    spend["account_txns_same_day"] = spend.groupby(
        ["account_id", "posted_date"]
    )["amount"].transform("size").astype(float)

    # How unusual this CATEGORY is for this account, as a share of prior
    # transactions. My planted anomaly is a Shopping charge on a card that had
    # never bought Shopping — this is the feature that notices that.
    category_prior_count = (
        spend.groupby(["account_id", "category"]).cumcount().astype(float)
    )
    account_prior_count = spend.groupby("account_id").cumcount().astype(float)
    spend["category_rarity"] = 1.0 - (
        category_prior_count / account_prior_count.clip(lower=1.0)
    )

    # Neutral fills for rows with no usable history. I fill rather than drop
    # because in production a new account's transactions still have to be
    # scored — I can't tell a user "no verdict, come back in a month".
    spend["amount_zscore_in_category"] = spend["amount_zscore_in_category"].fillna(0.0)
    spend["amount_ratio_to_category_median"] = (
        spend["amount_ratio_to_category_median"].replace([np.inf, -np.inf], 1.0).fillna(1.0)
    )
    spend["has_history"] = (past_count.fillna(0) >= MIN_HISTORY).astype(int)

    # Guard rail: a single inf here silently poisons StandardScaler, so I check
    # rather than trust.
    matrix = spend[ANOMALY_FEATURES]
    if not np.isfinite(matrix.to_numpy(dtype=float)).all():
        raise ValueError("non-finite values in anomaly features — a divide-by-zero slipped through")

    return spend


def zscore_baseline(spend: pd.DataFrame, threshold: float = 3.0) -> pd.Series:
    """The dumb-but-honest baseline I measure IsolationForest against.

    One rule: flag a transaction when it sits more than `threshold` standard
    deviations above the account's own past mean for that category. No training,
    no pickle, about one line of logic.

    I build this deliberately. "I used IsolationForest" is not a result — the
    question is whether it beats the obvious approach, and I can't answer that
    without the obvious approach in hand. If the baseline wins, the honest move
    is to ship the baseline.
    """
    return spend["amount_zscore_in_category"] > threshold
