"""Tests for my per-transaction anomaly features.

The property I care about most here is CAUSALITY: a feature must never see a
transaction's own value or anything that came after it. That kind of leakage
doesn't crash anything — it just makes my offline metrics look better than
production ever will. It's invisible without a test that specifically looks for
it, so that's the first test below.
"""

import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).parent.parent))

from anomaly_features import (  # noqa: E402
    ANOMALY_FEATURES,
    build_transaction_features,
    zscore_baseline,
)
from categories import canonical, is_inflow  # noqa: E402


def make(rows: list[dict]) -> pd.DataFrame:
    df = pd.DataFrame(rows)
    df["posted_date"] = pd.to_datetime(df["posted_date"])
    df["category"] = df["category"].map(canonical)
    df["is_inflow"] = df["category"].map(is_inflow)
    df["month"] = df["posted_date"].values.astype("datetime64[M]")
    return df


def txn(day: str, amount: float, merchant: str = "Cafe",
        category: str = "dining", account_id: int = 1) -> dict:
    return dict(transaction_id=f"t-{day}-{amount}-{merchant}", account_id=account_id,
                posted_date=day, amount=amount, merchant=merchant,
                category=category, pending=False)


class TestCausality:
    def test_first_transaction_has_no_history_to_deviate_from(self):
        """The very first transaction must score neutrally, not anomalously.

        If a z-score included the row's own amount, the first transaction would
        get a non-zero score out of nowhere. Seeing 0.0 here is my evidence that
        shift(1) is doing its job.
        """
        features = build_transaction_features(make([txn("2026-05-01", 50.0)]))
        assert features["amount_zscore_in_category"].iloc[0] == 0.0

    def test_a_later_spike_does_not_change_an_earlier_score(self):
        """The leakage test I most wanted.

        I score three modest charges, then append a $10,000 charge and score
        again. The first three scores must be byte-identical: a transaction's
        features cannot depend on the future. If they shifted, my expanding
        windows would be leaking and every metric I report would be inflated.
        """
        base = [txn("2026-05-01", 20.0), txn("2026-05-02", 22.0), txn("2026-05-03", 21.0)]
        before = build_transaction_features(make(base))
        after = build_transaction_features(make(base + [txn("2026-05-20", 10_000.0)]))

        np.testing.assert_allclose(
            before["amount_zscore_in_category"].to_numpy(),
            after["amount_zscore_in_category"].to_numpy()[:3],
        )

    def test_history_is_kept_per_account(self):
        # Account 2's spending must not inform account 1's baseline. "Unusual"
        # means unusual for THIS person; cross-account bleed would flatten that
        # into a global average and destroy the whole premise.
        rows = [txn(f"2026-05-0{d}", 10.0, account_id=1) for d in range(1, 6)]
        rows += [txn(f"2026-05-0{d}", 5000.0, account_id=2) for d in range(1, 6)]
        features = build_transaction_features(make(rows))

        account_1 = features[features["account_id"] == 1]
        # Account 1's charges are all $10; against its own history they're
        # perfectly normal, however extreme account 2 looks.
        assert account_1["amount_zscore_in_category"].abs().max() < 1.0


class TestAmountFeatures:
    def test_a_large_charge_scores_a_high_zscore(self):
        rows = [txn(f"2026-05-{d:02d}", 20.0) for d in range(1, 11)]
        rows.append(txn("2026-05-20", 900.0))
        features = build_transaction_features(make(rows))
        assert features["amount_zscore_in_category"].iloc[-1] > 3.0
        assert zscore_baseline(features).iloc[-1]

    def test_novel_category_falls_back_to_account_wide_history(self):
        """Regression test for a real hole in my baseline.

        My planted $1,899 electronics charge originally scored z = 0.0, because
        it was the first ever Shopping charge and the category had no history —
        so the NaN got filled with a neutral zero and the baseline was
        structurally blind to the exact case I cared about. Falling back to the
        account's overall distribution fixed it, and this test pins that down.
        """
        rows = [txn(f"2026-05-{d:02d}", 20.0, "Cafe", "dining") for d in range(1, 11)]
        rows.append(txn("2026-05-20", 1899.0, "TechWorld", "shopping"))
        features = build_transaction_features(make(rows))

        anomaly = features.iloc[-1]
        assert anomaly["amount_zscore_in_category"] > 3.0
        assert anomaly["merchant_novelty"] == 1.0

    def test_refunds_are_excluded_entirely(self):
        # Real Plaid data has negative amounts (refunds) sitting in ordinary
        # spending categories. Left in, they dragged my minimum z-score to -739
        # and would have me flagging people for getting money back.
        rows = [txn(f"2026-05-0{d}", 20.0) for d in range(1, 6)]
        rows.append(txn("2026-05-10", -50.0, "Cafe", "dining"))
        features = build_transaction_features(make(rows))
        assert len(features) == 5
        assert (features["amount"] > 0).all()

    def test_income_is_excluded(self):
        rows = [txn(f"2026-05-0{d}", 20.0) for d in range(1, 6)]
        rows.append(dict(transaction_id="pay", account_id=1, posted_date="2026-05-25",
                         amount=-3000.0, merchant="Payroll", category="Income", pending=False))
        features = build_transaction_features(make(rows))
        # A paycheck is a large amount by definition; scoring it would fill my
        # flag list with salary.
        assert len(features) == 5


class TestBurstFeatures:
    def test_same_day_merchant_burst_is_counted(self):
        """The feature pair that took burst recall from 0% to 81%."""
        rows = [txn(f"2026-05-{d:02d}", 20.0, "Cafe") for d in range(1, 11)]
        # Four small charges, one new merchant, one day — card testing.
        rows += [txn("2026-05-20", 3.0 + i, "QuickCash Kiosk", "shopping") for i in range(4)]
        features = build_transaction_features(make(rows))

        burst = features[features["merchant"] == "QuickCash Kiosk"]
        assert (burst["merchant_txns_same_day"] == 4).all()
        # And the amounts really are unremarkable, which is exactly why no
        # amount-based rule can ever catch this pattern.
        assert not zscore_baseline(features).loc[burst.index].any()

    def test_ordinary_single_charge_has_a_burst_count_of_one(self):
        rows = [txn(f"2026-05-0{d}", 20.0) for d in range(1, 6)]
        features = build_transaction_features(make(rows))
        assert (features["merchant_txns_same_day"] == 1).all()


class TestContract:
    def test_every_declared_feature_is_produced_and_finite(self):
        # ANOMALY_FEATURES is a contract with my scoring service: order and
        # presence both matter, because scikit-learn sees positions, not names.
        rows = [txn(f"2026-05-{d:02d}", 20.0 + d) for d in range(1, 11)]
        features = build_transaction_features(make(rows))

        for name in ANOMALY_FEATURES:
            assert name in features.columns, f"missing feature: {name}"

        matrix = features[ANOMALY_FEATURES].to_numpy(dtype=float)
        assert np.isfinite(matrix).all(), "non-finite value would poison the model"

    def test_zero_variance_history_does_not_divide_by_zero(self):
        # Ten identical charges give a past standard deviation of exactly 0.
        # Without my clip(lower=1.0) this produces inf and silently destroys the
        # model.
        rows = [txn(f"2026-05-{d:02d}", 20.0) for d in range(1, 11)]
        features = build_transaction_features(make(rows))
        assert np.isfinite(features["amount_zscore_in_category"]).all()


def test_raising_the_threshold_never_flags_more():
    """The baseline's threshold must behave monotonically.

    I originally wrote this test asserting that a threshold of 100 would NOT
    flag a $900 charge — and it failed, which taught me something about my own
    baseline. Ten identical $20 charges have a past standard deviation of
    exactly 0, my `clip(lower=1.0)` floors it at 1.0, and the z-score comes out
    at (900-20)/1.0 = 880. So a threshold of 100 fires easily.

    The code is right and my expectation was wrong: flagging that charge is
    correct behaviour. But it does mean my z-scores aren't comparable across
    accounts with different spending variance — with a std floor, an account
    whose real variance is below $1 produces inflated sigmas. That's a genuine
    limitation of the baseline (documented in EVALUATION.md), and it's part of
    why the multi-feature model does better.

    So I test the property that must actually hold: a stricter threshold can
    only ever flag a subset of what a looser one flags.
    """
    rows = [txn(f"2026-05-{d:02d}", 20.0 + d * 3) for d in range(1, 11)]
    rows.append(txn("2026-05-20", 900.0))
    features = build_transaction_features(make(rows))

    counts = [int(zscore_baseline(features, t).sum()) for t in (2.0, 3.0, 5.0, 50.0)]
    assert counts == sorted(counts, reverse=True), f"not monotonic: {counts}"
    # And the obvious spike is caught at the default threshold.
    assert bool(zscore_baseline(features, 3.0).iloc[-1])
