"""Request and response shapes for my scoring API.

These are Pydantic models, and the thing that took me a moment to appreciate
coming from Spring is that **the type hints ARE the validation**. In Spring I
write a DTO record and then annotate it with @NotNull, @Positive and so on, and
add @Valid at the controller. Here, declaring `account_id: int` is already the
rule: FastAPI parses the JSON, coerces it, and returns a 422 with a precise
error path if it doesn't fit — all before my function is ever called.

The same declarations also generate the OpenAPI docs at /docs for free, which
means my API documentation cannot drift from my API. That's the part I'd
actually defend in a code review: it's not that it saves typing, it's that
there's no second source of truth to fall out of date.

One deliberate design choice runs through this file: features arrive as a
NAMED dict, never as a positional list. scikit-learn consumes a plain array and
sees positions, not names, so if the caller and the model disagreed about column
order the model would return confident nonsense with nothing raising. By taking
names and ordering them myself against the model's own metadata, a mismatch
becomes a loud 400 instead of a silent wrong answer.
"""

from typing import Literal

from pydantic import BaseModel, Field


class ArchetypeRequest(BaseModel):
    """One account-month to classify into a spending archetype."""

    account_id: int = Field(..., description="My internal account id, not Plaid's")
    month: str = Field(
        ...,
        # A regex in the schema means a malformed month is rejected at the edge
        # with a clear message, instead of turning into a confusing parse error
        # somewhere deeper in my code.
        pattern=r"^\d{4}-\d{2}$",
        description="Month being scored, as YYYY-MM",
        examples=["2026-05"],
    )
    features: dict[str, float] = Field(
        ...,
        description=(
            "The 14 behaviour features for this account-month, keyed by name. "
            "Names and count must match the trained model's feature_order; the "
            "service checks this and rejects mismatches."
        ),
    )

    # model_config replaces the old class-based Config in Pydantic v2. This
    # example shows up in the /docs UI, so anyone integrating can see a valid
    # request without reading my source.
    model_config = {
        "json_schema_extra": {
            "examples": [{
                "account_id": 2,
                "month": "2026-05",
                "features": {
                    "txn_count": 24.0, "avg_ticket": 41.5, "ticket_variability": 0.72,
                    "merchant_diversity": 0.66, "weekend_ratio": 0.78,
                    "recurring_share": 0.09, "fixed_share": 0.41,
                    "spend_to_income": 0.63, "share_dining": 0.52,
                    "share_groceries": 0.11, "share_subscriptions": 0.08,
                    "share_transport": 0.09, "share_shopping": 0.14,
                    "share_entertainment": 0.06,
                },
            }]
        }
    }


class FeatureEvidence(BaseModel):
    """Why a month got the archetype it did.

    I return this because "you are a Weekend Spender" is an assertion, and
    "78% of your discretionary spend was Fri-Sun, versus 42% typical" is an
    explanation. A model I can't explain is one I can't defend to a user, and
    my dashboard is going to show these numbers directly.
    """

    feature: str
    your_value: float
    population_average: float
    std_devs_from_average: float


class ArchetypeResponse(BaseModel):
    account_id: int
    month: str
    cluster: int = Field(..., description="Raw KMeans cluster index")
    archetype: str = Field(..., description="Human-readable name from my centroid readout")
    description: str
    # Distance to the assigned centroid, in standardised space. Small = a
    # textbook example of this archetype; large = it landed here by elimination.
    # I expose it so my dashboard can hedge its language when the fit is loose,
    # rather than stating a weak assignment with full confidence.
    distance_to_centroid: float
    evidence: list[FeatureEvidence]
    model_version: str
    # The features this score was computed from. I return them so my backend can
    # persist them in its `monthly_features` table — which means a stored score
    # is always reproducible: I can see the exact inputs that produced it months
    # later. A score with no record of its inputs is unauditable, and "why did
    # you flag me?" is a question a financial app must be able to answer.
    # Optional because /archetype's caller already has them.
    features: dict[str, float] | None = None


class TransactionFeatures(BaseModel):
    """One transaction to score for anomalousness."""

    transaction_id: str
    features: dict[str, float] = Field(
        ..., description="The 12 per-transaction features, keyed by name"
    )


class AnomalyRequest(BaseModel):
    """A BATCH of transactions.

    Batch rather than one-at-a-time on purpose: my Spring backend scores a whole
    sync at once, and one HTTP round trip per transaction would make network
    overhead dwarf the actual inference. The list cap is a deliberate limit too —
    an unbounded request is a memory-exhaustion risk, and 1,000 is comfortably
    more than any single sync of mine produces.
    """

    transactions: list[TransactionFeatures] = Field(..., min_length=1, max_length=1000)


class AnomalyResult(BaseModel):
    transaction_id: str
    # Higher = more anomalous. I normalise the sign here because
    # IsolationForest's raw score_samples is BACKWARDS (higher = more normal),
    # and leaking that quirk into my API would guarantee someone eventually
    # gets the comparison the wrong way round.
    anomaly_score: float
    is_anomaly: bool
    # The features that pushed this transaction's score up, so my dashboard can
    # say "3.2x your usual grocery spend" instead of just showing a red row.
    reasons: list[str]


class AnomalyResponse(BaseModel):
    results: list[AnomalyResult]
    flagged_count: int
    model_version: str


class RawTransaction(BaseModel):
    """One transaction exactly as my Spring backend stores it.

    This is the payload for the endpoints my backend actually calls. It sends
    rows straight from Postgres and lets this service do the feature
    engineering, so there is only ever one implementation of my 26 feature
    definitions — see feature_bridge.py for why that matters.
    """

    transaction_id: str
    account_id: int
    posted_date: str = Field(..., pattern=r"^\d{4}-\d{2}-\d{2}$", examples=["2026-05-17"])
    # Positive = money out, negative = money in. I keep Plaid's convention all
    # the way through so no layer has to remember to flip a sign.
    amount: float
    merchant: str
    category: str
    pending: bool = False


class ScoreMonthRequest(BaseModel):
    """Score one month, given the account's history.

    I take the WIDER history rather than just the target month because
    recurring-charge detection needs three months to recognise a subscription.
    Sending one month would silently produce a recurring_share of 0.
    """

    account_id: int
    month: str = Field(..., pattern=r"^\d{4}-\d{2}$", examples=["2026-05"])
    transactions: list[RawTransaction] = Field(..., min_length=1, max_length=5000)


class ScoreTransactionsRequest(BaseModel):
    """Score an account's transactions for anomalies.

    Same reasoning: every anomaly feature is relative to this account's own
    past, so I need the history, not just the new rows.
    """

    account_id: int
    transactions: list[RawTransaction] = Field(..., min_length=1, max_length=5000)


class HealthResponse(BaseModel):
    """What /health reports.

    This is more than a liveness ping. My Spring backend needs to know whether
    the MODELS actually loaded, not merely whether the process is up — a service
    answering HTTP with no models is worse than one that's down, because it
    looks healthy while being useless.
    """

    status: Literal["ok", "degraded"]
    models_loaded: bool
    model_version: str
    archetype_count: int
    behaviour_features: int
    anomaly_features: int
