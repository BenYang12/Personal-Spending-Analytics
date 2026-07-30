"""Tests for my monthly behaviour features.

I test feature engineering rather than model accuracy, and that's a deliberate
choice. Model quality belongs in evaluate.py, where it's measured against
injected anomalies. What belongs in a unit test is the arithmetic: if
weekend_ratio is quietly wrong, every downstream metric is meaningless and the
model will still train happily and report a plausible-looking score. Silent
correctness bugs are the real risk in an ML pipeline, so these tests use tiny
hand-built frames where I know every answer in advance.
"""

import sys
from pathlib import Path

import pandas as pd
import pytest

# My modules sit in the parent directory, not an installed package.
sys.path.insert(0, str(Path(__file__).parent.parent))

from categories import CANONICAL, canonical, is_inflow  # noqa: E402
from features import flag_recurring, monthly_features  # noqa: E402


def make_transactions(rows: list[dict]) -> pd.DataFrame:
    """Build a normalised frame the way load_transactions would."""
    df = pd.DataFrame(rows)
    df["posted_date"] = pd.to_datetime(df["posted_date"])
    df["category"] = df["category"].map(canonical)
    df["is_inflow"] = df["category"].map(is_inflow)
    df["month"] = df["posted_date"].values.astype("datetime64[M]")
    return df


def spend_row(day: str, amount: float, merchant: str = "Shop",
              category: str = "dining", account_id: int = 1) -> dict:
    return dict(transaction_id=f"t-{merchant}-{day}-{amount}", account_id=account_id,
                posted_date=day, amount=amount, merchant=merchant,
                category=category, pending=False)


# --------------------------------------------------------------------------
# category normalisation — the bug that cost me an hour
# --------------------------------------------------------------------------

class TestCategories:
    def test_canonical_labels_map_to_themselves(self):
        """This is the regression test for a real bug I shipped.

        My synthetic generator emits already-canonical labels like "housing".
        Those weren't keys in my mapping, so canonical() sent them to "other" —
        60% of all spending silently became "other" and my category shares
        stopped summing to 1. Nothing raised. This test would have caught it
        immediately, which is exactly why it exists now.
        """
        for label in CANONICAL:
            assert canonical(label) == label

    def test_both_vocabularies_reconcile(self):
        # My seeded data and real Plaid data must land on the same label.
        assert canonical("Groceries") == canonical("groceries") == "groceries"
        assert canonical("FOOD_AND_DRINK") == "dining"
        assert canonical("Coffee") == "dining"
        assert canonical("Rent") == canonical("RENT_AND_UTILITIES") == "housing"

    def test_unknown_category_becomes_other_not_an_error(self):
        # A new Plaid category appearing must not crash a training run.
        assert canonical("SOME_BRAND_NEW_PLAID_THING") == "other"
        assert canonical(None) == "other"

    def test_income_is_inflow_and_spending_is_not(self):
        assert is_inflow(canonical("Income"))
        assert is_inflow(canonical("TRANSFER_IN"))
        assert not is_inflow(canonical("Groceries"))


# --------------------------------------------------------------------------
# recurring-charge detection
# --------------------------------------------------------------------------

class TestRecurringDetection:
    def test_detects_a_monthly_subscription(self):
        # Same merchant, same amount, three consecutive months = my rule's
        # definition of a subscription.
        df = make_transactions([
            spend_row("2026-01-03", 15.49, "Netflix", "subscriptions"),
            spend_row("2026-02-03", 15.49, "Netflix", "subscriptions"),
            spend_row("2026-03-03", 15.49, "Netflix", "subscriptions"),
        ])
        assert flag_recurring(df)["is_recurring"].all()

    def test_two_months_is_not_enough(self):
        # I require three months on purpose: two coincidental identical charges
        # at the same merchant are a coincidence, not a subscription.
        df = make_transactions([
            spend_row("2026-01-03", 15.49, "Netflix", "subscriptions"),
            spend_row("2026-02-03", 15.49, "Netflix", "subscriptions"),
        ])
        assert not flag_recurring(df)["is_recurring"].any()

    def test_varying_amounts_are_not_recurring(self):
        # Monthly visits to the same restaurant for different amounts are a
        # habit, not a recurring charge. My amount-stability check is what
        # separates the two.
        df = make_transactions([
            spend_row("2026-01-05", 22.00, "Bartaco", "dining"),
            spend_row("2026-02-05", 61.00, "Bartaco", "dining"),
            spend_row("2026-03-05", 39.50, "Bartaco", "dining"),
        ])
        assert not flag_recurring(df)["is_recurring"].any()

    def test_income_is_never_recurring(self):
        # A paycheck is the most regular thing in the data, but calling it a
        # subscription would be nonsense — and would put it in my "cancel these"
        # UI.
        df = make_transactions([
            dict(transaction_id=f"pay-{m}", account_id=1, posted_date=f"2026-0{m}-25",
                 amount=-2150.00, merchant="Acme Payroll", category="Income", pending=False)
            for m in (1, 2, 3)
        ])
        assert not flag_recurring(df)["is_recurring"].any()


# --------------------------------------------------------------------------
# monthly feature arithmetic
# --------------------------------------------------------------------------

class TestMonthlyFeatures:
    def build(self, rows, **kwargs):
        return monthly_features(flag_recurring(make_transactions(rows)), **kwargs)

    def test_weekend_ratio_counts_friday_through_sunday(self):
        # 2026-05-15 is a Friday, 16th Sat, 17th Sun; 18th-19th are Mon/Tue.
        # $300 of $400 discretionary spend is Fri-Sun, so I expect exactly 0.75.
        features = self.build([
            spend_row("2026-05-15", 100.0),
            spend_row("2026-05-16", 100.0),
            spend_row("2026-05-17", 100.0),
            spend_row("2026-05-18", 50.0),
            spend_row("2026-05-19", 50.0),
        ], min_transactions=5)
        assert features["weekend_ratio"].iloc[0] == pytest.approx(0.75)

    def test_avg_ticket_uses_median_so_one_huge_charge_cannot_skew_it(self):
        # Four $10 charges and one $5,000 charge. The mean would be $1,008 and
        # would describe nobody's behaviour; the median stays $10. This is the
        # whole reason I chose median here — anomalies are handled elsewhere and
        # must not pollute the behaviour baseline.
        features = self.build([
            spend_row("2026-05-04", 10.0), spend_row("2026-05-05", 10.0),
            spend_row("2026-05-06", 10.0), spend_row("2026-05-07", 10.0),
            spend_row("2026-05-08", 5000.0),
        ], min_transactions=5)
        assert features["avg_ticket"].iloc[0] == pytest.approx(10.0)

    def test_category_shares_sum_to_one_over_discretionary_spend(self):
        features = self.build([
            spend_row("2026-05-04", 100.0, "Cafe", "dining"),
            spend_row("2026-05-05", 100.0, "Market", "groceries"),
            spend_row("2026-05-06", 100.0, "Target", "shopping"),
            spend_row("2026-05-07", 100.0, "Shell", "transport"),
            spend_row("2026-05-08", 100.0, "AMC", "entertainment"),
        ], min_transactions=5)
        share_columns = [c for c in features.columns if c.startswith("share_")]
        assert features[share_columns].iloc[0].sum() == pytest.approx(1.0)

    def test_housing_is_excluded_from_discretionary_ratios(self):
        """Rent must inform fixed_share and nothing else.

        This encodes the second bug I hit: with rent in the denominator, housing
        was 55% of all spend and every behaviour ratio was really measuring
        someone's rent. Here $1,000 of rent plus $500 discretionary means
        fixed_share = 2/3, while dining's share stays 1.0 of the discretionary
        part.
        """
        features = self.build([
            spend_row("2026-05-01", 1000.0, "Landlord", "housing"),
            spend_row("2026-05-04", 100.0), spend_row("2026-05-05", 100.0),
            spend_row("2026-05-06", 100.0), spend_row("2026-05-07", 100.0),
            spend_row("2026-05-08", 100.0),
        ], min_transactions=5)
        assert features["fixed_share"].iloc[0] == pytest.approx(1000 / 1500)
        assert features["share_dining"].iloc[0] == pytest.approx(1.0)
        assert features["txn_count"].iloc[0] == 5      # rent not counted

    def test_income_is_excluded_from_spending(self):
        # A paycheck is not spending. If it leaked in, total_spend would go
        # negative and every ratio would be garbage.
        features = self.build([
            dict(transaction_id="pay", account_id=1, posted_date="2026-05-25",
                 amount=-3000.0, merchant="Payroll", category="Income", pending=False),
            spend_row("2026-05-04", 100.0), spend_row("2026-05-05", 100.0),
            spend_row("2026-05-06", 100.0), spend_row("2026-05-07", 100.0),
            spend_row("2026-05-08", 100.0),
        ], min_transactions=5)
        # $500 spent against $3,000 earned.
        assert features["spend_to_income"].iloc[0] == pytest.approx(500 / 3000)

    def test_thin_months_are_dropped(self):
        # Two transactions can't describe behaviour; its ratios would swing on a
        # single purchase. I'd rather score fewer months honestly.
        with pytest.raises(ValueError, match="no account-months survived"):
            self.build([spend_row("2026-05-04", 10.0), spend_row("2026-05-05", 10.0)],
                       min_transactions=5)

    def test_merchant_diversity_is_unique_merchants_over_transactions(self):
        features = self.build([
            spend_row("2026-05-04", 10.0, "A"), spend_row("2026-05-05", 10.0, "A"),
            spend_row("2026-05-06", 10.0, "B"), spend_row("2026-05-07", 10.0, "C"),
            spend_row("2026-05-08", 10.0, "D"),
        ], min_transactions=5)
        # 4 unique merchants across 5 transactions.
        assert features["merchant_diversity"].iloc[0] == pytest.approx(0.8)

    def test_each_account_month_gets_its_own_row(self):
        rows = []
        for account_id in (1, 2):
            for month in ("2026-04", "2026-05"):
                rows += [spend_row(f"{month}-0{d}", 20.0, account_id=account_id)
                         for d in range(1, 6)]
        features = self.build(rows, min_transactions=5)
        assert len(features) == 4
        assert set(features["account_id"]) == {1, 2}
