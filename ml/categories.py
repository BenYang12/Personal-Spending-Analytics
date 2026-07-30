"""Canonical spending categories.

I have a vocabulary problem to solve before any modelling can work. My
hand-seeded rows use labels like "Groceries" and "Coffee", while real Plaid
rows use SCREAMING_SNAKE labels like "FOOD_AND_DRINK". If I clustered on the
raw strings, my seeded months and my Plaid months would land in different
clusters purely because of naming — the model would learn my data sources, not
my spending behaviour. So I map everything onto one canonical taxonomy first.

I keep this list SHORT on purpose. Every category becomes a feature column, and
with a few hundred transactions I'd rather have 8 well-populated features than
30 mostly-zero ones.
"""

# The canonical set. I keep "other" as an explicit bucket rather than dropping
# unknown labels, because a silently discarded category would make my category
# shares stop summing to 1 and I'd never notice.
CANONICAL = [
    "dining",
    "groceries",
    "subscriptions",
    "housing",
    "transport",
    "shopping",
    "entertainment",
    "other",
]

# Categories that are money coming IN, not spending. I handle these separately
# everywhere: including a $2,150 paycheck in "total spend" would wreck every
# ratio I compute.
INFLOW = {"income", "transfer_in"}

# Everything I know how to translate. Left side = what the data says, right side
# = my canonical label. I lowercase the key before lookup so "Groceries" and
# "GROCERIES" both hit.
_MAP = {
    # --- identity entries ---
    # Data that is ALREADY canonical still has to be listed here. I learned this
    # the annoying way: my synthetic generator emits "housing" and "transport"
    # directly, and because those weren't keys, canonical() sent them to "other".
    # 60% of all spend silently became "other" and my category shares stopped
    # summing to 1. Nothing crashed — the features were just quietly wrong,
    # which is exactly how ML bugs behave.
    "dining": "dining",
    "groceries": "groceries",
    "subscriptions": "subscriptions",
    "housing": "housing",
    "transport": "transport",
    "shopping": "shopping",
    "entertainment": "entertainment",
    "other": "other",
    "income": "income",
    "transfer_out": "transfer_out",
    "transfer_in": "transfer_in",
    # --- my hand-seeded vocabulary ---
    "coffee": "dining",          # I treat coffee as dining out, not groceries:
                                 # behaviourally it's a small frequent purchase
    "dining": "dining",
    "subscriptions": "subscriptions",
    "rent": "housing",
    "shopping": "shopping",
    "income": "income",
    # --- Plaid's personal_finance_category.primary vocabulary ---
    "food_and_drink": "dining",
    "general_merchandise": "shopping",
    "rent_and_utilities": "housing",
    "transportation": "transport",
    "travel": "transport",
    "entertainment": "entertainment",
    "personal_care": "other",
    "general_services": "other",
    "medical": "other",
    "bank_fees": "other",
    "loan_payments": "housing",  # student loan / mortgage: a fixed obligation,
                                 # which is what "housing" means to my features
    "home_improvement": "housing",
    "government_and_non_profit": "other",
    "transfer_out": "transfer_out",
    "transfer_in": "transfer_in",
}


def canonical(raw_category: str) -> str:
    """Translate one raw category label into my canonical vocabulary.

    I return "other" for anything unrecognised rather than raising, because a
    new Plaid category appearing shouldn't crash my whole training run.
    """
    if raw_category is None:
        return "other"
    return _MAP.get(str(raw_category).strip().lower(), "other")


def is_inflow(canonical_category: str) -> bool:
    """True when this category is money arriving, not money spent."""
    return canonical_category in INFLOW
