"""Generate synthetic users with KNOWN spending archetypes.

Why I need this: my real data is 234 transactions across a handful of accounts,
which works out to 27 account-months and only 10 with more than 8 transactions.
Fitting a 5-cluster KMeans on that would be theatre — the clusters would be
noise and I'd have no way to tell.

So I generate synthetic users whose archetype I chose in advance. That buys me
two things, and the second is the one I actually care about:

  1. Enough account-months for clustering to be meaningful.
  2. GROUND TRUTH. Because I know each synthetic user's true archetype, I can
     measure whether my unsupervised pipeline actually recovers it (I use
     Adjusted Rand Index in train_clusters.py). Unsupervised learning normally
     gives me no way to check whether the clusters mean anything; manufacturing
     a dataset where I know the answer is how I get that check back.

I am explicit everywhere that this data is synthetic. It validates the
PIPELINE; my real Plaid months are then scored by the model it produces.
"""

from pathlib import Path

import numpy as np
import pandas as pd

DATA_DIR = Path(__file__).parent / "data"
TXN_FILE = DATA_DIR / "synthetic_transactions.csv"
LABEL_FILE = DATA_DIR / "synthetic_labels.csv"

# Fixed seed, same reason my seed.sql calls setseed(0.42): I want the identical
# dataset every rebuild so a change in my metrics means I changed the MODEL, not
# that I rolled different dice.
RNG = np.random.default_rng(42)

# Synthetic ids start at 1000 so they can never collide with the real account
# ids Postgres assigned (currently 1-14).
FIRST_SYNTHETIC_ID = 1000

MONTHS = pd.date_range("2026-01-01", "2026-06-01", freq="MS")

# Merchant pools per canonical category. I use several merchants per category so
# merchant-diversity becomes a real signal rather than a constant.
MERCHANTS = {
    "dining": ["Bartaco", "Sushi Nine", "Chipotle", "Cookout", "Blue Bottle Coffee",
               "Starbucks", "Panera", "Local Diner"],
    "groceries": ["Harris Teeter", "Trader Joes", "Food Lion", "Whole Foods", "Aldi"],
    "subscriptions": ["Netflix", "Spotify", "iCloud", "Planet Fitness", "NYTimes",
                      "Adobe CC", "Dropbox", "Hulu", "Disney Plus", "Audible"],
    "housing": ["Oak Street Apartments", "City Power & Light", "Metro Water"],
    "transport": ["Shell", "Uber", "Lyft", "City Transit", "Delta Air Lines"],
    "shopping": ["Amazon", "Target", "Best Buy", "REI", "Nordstrom", "TechWorld Electronics"],
    "entertainment": ["AMC Theatres", "Live Nation", "Steam", "Golf Club"],
}

# My five archetypes. Each is a recipe, and each dial maps to a feature I
# engineer in features.py — that alignment is deliberate: if I generate data
# along axes my features can't see, clustering can't possibly recover it.
#
#   n_subs:        how many monthly subscriptions this user carries
#   txns_per_month: (low, high) count of discretionary transactions
#   ticket:        (mean, sd) dollar size of a typical discretionary purchase
#   weekend_bias:  probability a discretionary purchase lands Fri-Sun
#   mix:           relative weights over categories for discretionary spend
ARCHETYPES = {
    "subscription_heavy": dict(
        n_subs=8, txns_per_month=(8, 14), ticket=(28, 10), weekend_bias=0.35,
        mix={"dining": 3, "groceries": 3, "shopping": 2, "transport": 1, "entertainment": 1},
    ),
    "weekend_spender": dict(
        n_subs=2, txns_per_month=(14, 22), ticket=(62, 22), weekend_bias=0.80,
        mix={"dining": 6, "entertainment": 3, "shopping": 2, "transport": 1, "groceries": 1},
    ),
    "frequent_small": dict(
        n_subs=3, txns_per_month=(34, 48), ticket=(9, 4), weekend_bias=0.40,
        mix={"dining": 6, "transport": 3, "groceries": 2, "shopping": 1},
    ),
    "big_ticket": dict(
        n_subs=2, txns_per_month=(5, 9), ticket=(210, 90), weekend_bias=0.45,
        mix={"shopping": 6, "transport": 2, "dining": 1, "entertainment": 1},
    ),
    "steady_essentials": dict(
        n_subs=2, txns_per_month=(12, 18), ticket=(46, 14), weekend_bias=0.25,
        mix={"groceries": 6, "housing": 2, "transport": 2, "dining": 1},
    ),
}

USERS_PER_ARCHETYPE = 12


def _pick(options: list[str]) -> str:
    return str(RNG.choice(options))


def _weighted_category(mix: dict[str, int]) -> str:
    names = list(mix.keys())
    weights = np.array(list(mix.values()), dtype=float)
    return str(RNG.choice(names, p=weights / weights.sum()))


def _date_in_month(month: pd.Timestamp, weekend_bias: float) -> pd.Timestamp:
    """Pick a day in this month, honouring the archetype's weekend preference.

    I resample rather than compute a weighted day distribution because it's
    obvious what it does, and the loop is bounded by the retry cap.
    """
    days_in_month = month.days_in_month
    for _ in range(20):
        day = int(RNG.integers(1, days_in_month + 1))
        date = month + pd.Timedelta(days=day - 1)
        is_weekend = date.dayofweek >= 4          # Fri=4, Sat=5, Sun=6
        want_weekend = RNG.random() < weekend_bias
        if is_weekend == want_weekend:
            return date
    return month + pd.Timedelta(days=int(RNG.integers(0, days_in_month)))


def build_user(account_id: int, archetype: str) -> list[dict]:
    """Generate 6 months of transactions for one synthetic user."""
    spec = ARCHETYPES[archetype]
    rows: list[dict] = []

    # Each user's subscriptions are fixed for the whole period: same merchant,
    # same amount, same day of month. That's the (merchant, amount, cadence)
    # signature my recurring-charge detector looks for, so I'm generating
    # exactly the pattern I intend to detect.
    subs = RNG.choice(MERCHANTS["subscriptions"], size=spec["n_subs"], replace=False)
    sub_amounts = {s: round(float(RNG.uniform(4, 30)), 2) for s in subs}
    sub_days = {s: int(RNG.integers(1, 28)) for s in subs}

    # A monthly income and rent, so my inflow/outflow handling gets exercised.
    monthly_income = round(float(RNG.uniform(2200, 4200)), 2)
    monthly_rent = round(monthly_income * float(RNG.uniform(0.22, 0.34)), 2)

    for month in MONTHS:
        tag = month.strftime("%Y-%m")

        for merchant in subs:
            rows.append(dict(
                transaction_id=f"syn-{account_id}-sub-{merchant}-{tag}".replace(" ", ""),
                account_id=account_id,
                posted_date=(month + pd.Timedelta(days=sub_days[merchant] - 1)).date(),
                amount=sub_amounts[merchant],
                merchant=merchant,
                category="subscriptions",
                pending=False,
            ))

        rows.append(dict(
            transaction_id=f"syn-{account_id}-rent-{tag}",
            account_id=account_id, posted_date=month.date(), amount=monthly_rent,
            merchant="Oak Street Apartments", category="housing", pending=False,
        ))
        # Negative amount = money in. I keep Plaid's sign convention everywhere
        # so this data and my real data behave identically downstream.
        rows.append(dict(
            transaction_id=f"syn-{account_id}-pay-{tag}",
            account_id=account_id,
            posted_date=(month + pd.Timedelta(days=24)).date(),
            amount=-monthly_income, merchant="Acme Corp Payroll",
            category="income", pending=False,
        ))

        n_txns = int(RNG.integers(*spec["txns_per_month"]))
        for i in range(n_txns):
            category = _weighted_category(spec["mix"])
            mean, sd = spec["ticket"]
            # I clamp at $1.50 so no discretionary purchase comes out negative
            # and accidentally reads as income.
            amount = round(max(1.50, float(RNG.normal(mean, sd))), 2)
            rows.append(dict(
                transaction_id=f"syn-{account_id}-{tag}-{i}",
                account_id=account_id,
                posted_date=_date_in_month(month, spec["weekend_bias"]).date(),
                amount=amount,
                merchant=_pick(MERCHANTS[category]),
                category=category,
                pending=False,
            ))

    return rows


def main() -> None:
    all_rows: list[dict] = []
    labels: list[dict] = []

    account_id = FIRST_SYNTHETIC_ID
    for archetype in ARCHETYPES:
        for _ in range(USERS_PER_ARCHETYPE):
            all_rows.extend(build_user(account_id, archetype))
            # I keep the truth in a SEPARATE file, not a column on the
            # transactions. If the label rode along with the features I would
            # eventually leak it into training by accident.
            labels.append(dict(account_id=account_id, true_archetype=archetype))
            account_id += 1

    DATA_DIR.mkdir(exist_ok=True)
    transactions = pd.DataFrame(all_rows)
    transactions.to_csv(TXN_FILE, index=False)
    pd.DataFrame(labels).to_csv(LABEL_FILE, index=False)

    print(f"wrote {len(transactions)} synthetic transactions "
          f"for {len(labels)} users to {TXN_FILE}")
    print(f"wrote ground-truth archetypes to {LABEL_FILE}")
    print(transactions.groupby("category").size().to_string())


if __name__ == "__main__":
    main()
