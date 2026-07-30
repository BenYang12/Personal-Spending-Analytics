"""My FastAPI scoring service — the ONLINE half of the offline/online split.

This service is deliberately dumb. It cannot train, it cannot reach my database,
and it holds no state beyond three pickles it loaded at boot. Everything
interesting was decided offline in /ml; this just applies those decisions fast.

That constraint is what makes the architecture work. Because the service is
stateless, I can run five copies behind a load balancer and it makes no
difference which one answers. Because it can't reach Postgres, a bug here can
never corrupt my financial data — the worst it can do is return a bad number,
which my Spring backend already knows how to survive (Step 19 handles that).

Coming from Spring, the mental mapping I find useful:

    @RestController          ->  the @app.post decorators below
    @Service                 ->  scorer.py
    DTO record + @Valid      ->  the Pydantic models in schemas.py
    embedded Tomcat          ->  uvicorn
    @ExceptionHandler        ->  the exception_handler functions
    /actuator/health         ->  my /health endpoint
    ApplicationContext boot  ->  the lifespan function

The biggest genuine difference is async. `async def` means this runs on an event
loop rather than a thread per request. That's a win for IO-bound work, but my
scoring is CPU-bound NumPy — see the note on the endpoints for why that matters
and what I'd do about it at real scale.
"""

import logging
import os
import time
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request, status
from fastapi.responses import JSONResponse

from feature_bridge import (
    NotEnoughDataError,
    month_features,
    transaction_features,
)
from schemas import (
    AnomalyRequest,
    AnomalyResponse,
    ArchetypeRequest,
    ArchetypeResponse,
    HealthResponse,
    ScoreMonthRequest,
    ScoreTransactionsRequest,
)
from scorer import FeatureContractError, ModelNotLoadedError, Scorer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-5s [%(name)s] %(message)s",
)
log = logging.getLogger("scoring")

# Configurable, not hardcoded: locally this points at ../ml/models, but in a
# container it'll be a mounted volume. Same reasoning as my Spring datasource
# URL living in application.yml instead of in Java.
MODEL_DIR = Path(os.environ.get("LEDGERLENS_MODEL_DIR", "../ml/models"))

scorer = Scorer(MODEL_DIR)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup and shutdown hooks — where I load the models exactly once.

    Everything before `yield` runs at boot, everything after at shutdown. This
    is the whole reason inference is fast: unpickling 200 IsolationForest trees
    happens here, once, not on every request.

    I deliberately do NOT crash the process when loading fails. My first
    instinct was to fail fast, the way my Spring app refuses to start without a
    database — but the situations differ. Spring without a database can do
    nothing at all. This service with no models can still answer /health and
    truthfully report "degraded", which is exactly what my backend needs in
    order to fall back gracefully. A dead process just looks like a network
    problem and tells nobody anything.
    """
    try:
        started = time.perf_counter()
        scorer.load()
        elapsed_ms = (time.perf_counter() - started) * 1000
        log.info("loaded models from %s in %.0f ms (version %s)",
                 MODEL_DIR.resolve(), elapsed_ms, scorer.model_version)
        log.info("archetypes: %s",
                 ", ".join(a["name"] for a in scorer.archetypes.values()))
    except Exception as error:
        # exception(), not error(), so the traceback lands in the logs. When
        # this fires I want the real reason, not just a one-line summary.
        log.exception("FAILED to load models — starting in degraded mode: %s", error)

    yield

    log.info("shutting down")


app = FastAPI(
    title="Ledger Lens Scoring Service",
    description=(
        "Applies my pre-trained spending-archetype and anomaly-detection models. "
        "Training happens offline in /ml; this service only ever scores."
    ),
    version="1.0.0",
    lifespan=lifespan,
)


# ---------------------------------------------------------------------------
# error handling
# ---------------------------------------------------------------------------

@app.exception_handler(FeatureContractError)
async def handle_feature_contract_error(request: Request, error: FeatureContractError):
    """A feature mismatch is the CALLER's problem, so it's a 400, not a 500.

    Getting this status right matters more than it looks: 5xx means "I'm broken,
    retry later" and would have my Spring client retrying forever, while 4xx
    means "your request is wrong, fix it and don't retry". Sending the wrong one
    turns a config mistake into a retry storm.
    """
    log.warning("feature contract violation: %s", error)
    return JSONResponse(
        status_code=status.HTTP_400_BAD_REQUEST,
        content={"error": "feature_contract_violation", "detail": str(error)},
    )


@app.exception_handler(ModelNotLoadedError)
async def handle_model_not_loaded(request: Request, error: ModelNotLoadedError):
    """503 SERVICE UNAVAILABLE — the honest code for "I can't do this right now".

    503 specifically means temporary, which is true: dropping the model files
    into place fixes it with no code change. It also tells my Spring client that
    retrying later is reasonable, which for a 500 it wouldn't be.
    """
    log.error("scoring attempted with no models loaded")
    return JSONResponse(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        content={
            "error": "models_not_loaded",
            "detail": str(error),
            "hint": "run the /ml training pipeline, then restart this service",
        },
    )


# ---------------------------------------------------------------------------
# endpoints
# ---------------------------------------------------------------------------

@app.get("/health", response_model=HealthResponse, tags=["ops"])
async def health():
    """Liveness AND readiness in one endpoint.

    The distinction I care about: the process being up is not the same as the
    service being useful. A container that answers HTTP with no models loaded
    looks healthy to a naive check while being completely unable to work — worse
    than being down, because nothing fails over. So I report `status: degraded`
    and `models_loaded: false`, and my Spring client keys off the field rather
    than the HTTP code.

    Deliberately unauthenticated: this service isn't exposed publicly (only my
    backend reaches it), and health checks that need credentials are a classic
    way to have monitoring silently stop working.
    """
    return scorer.health()


@app.post("/archetype", response_model=ArchetypeResponse, tags=["scoring"])
async def score_archetype(request: ArchetypeRequest):
    """Classify one account-month into a spending archetype.

    A note on `async def` with CPU-bound work, because it's a real subtlety and
    I'd rather be able to explain it than pretend it isn't there. `async` shines
    when a handler spends its time WAITING on IO — the event loop runs other
    requests meanwhile. My work here is NumPy arithmetic, which holds the loop
    while it computes. At my scale that's fine: this scores in well under a
    millisecond, far quicker than the network hop that delivered the request.
    If it ever grew heavy, the fix is `def` instead of `async def` — FastAPI
    then runs it in a threadpool automatically — or offloading to a worker
    process. Reaching for async everywhere without knowing why is cargo cult.
    """
    result = scorer.score_archetype(request.features)
    log.info("scored account=%s month=%s -> %s (distance %.2f)",
             request.account_id, request.month,
             result["archetype"], result["distance_to_centroid"])
    return {"account_id": request.account_id, "month": request.month, **result}


@app.post("/anomaly", response_model=AnomalyResponse, tags=["scoring"])
async def score_anomalies(request: AnomalyRequest):
    """Score a batch of transactions for anomalousness.

    Batch by design: my backend scores an entire Plaid sync at once, and a round
    trip per transaction would make network latency dwarf the actual inference.
    Validation happens across the whole batch before anything is scored, so I
    return either all results or a clean error — never a partial batch my caller
    has to reconcile.
    """
    payload = [txn.model_dump() for txn in request.transactions]
    results = scorer.score_anomalies(payload)
    flagged = sum(1 for r in results if r["is_anomaly"])

    log.info("scored %d transactions, flagged %d (%.1f%%)",
             len(results), flagged, 100 * flagged / len(results))

    return {
        "results": results,
        "flagged_count": flagged,
        "model_version": scorer.model_version,
    }


@app.exception_handler(NotEnoughDataError)
async def handle_not_enough_data(request: Request, error: NotEnoughDataError):
    """422 — I understood the request, but the data can't support an answer.

    Not a 400: nothing is malformed. Not a 200 with a guess either. A month with
    three transactions has no describable behaviour, and returning a confident
    archetype built on noise would be worse for the user than admitting I can't
    tell yet. My dashboard shows "not enough data yet" for this case.
    """
    log.info("insufficient data to score: %s", error)
    return JSONResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        content={"error": "not_enough_data", "detail": str(error)},
    )


@app.post("/score/month", response_model=ArchetypeResponse, tags=["scoring"])
async def score_month(request: ScoreMonthRequest):
    """Score a month from RAW TRANSACTIONS — the endpoint my backend calls.

    The difference from /archetype is who computes the features. There, the
    caller does. Here, I do, using the exact functions that built my training
    set. That's the whole reason this endpoint exists: my Spring backend must
    never reimplement feature engineering in Java, because two implementations
    of 26 definitions would drift and quietly feed the model unfamiliar numbers.

    I keep /archetype around anyway — it's the honest low-level API, it's what
    my tests exercise directly, and it's what I'd use to debug a specific
    feature vector.
    """
    payload = [txn.model_dump() for txn in request.transactions]
    features = month_features(payload, request.month)

    result = scorer.score_archetype(features)
    log.info("scored month account=%s month=%s from %d raw transactions -> %s",
             request.account_id, request.month, len(payload), result["archetype"])
    # I hand back the computed features so my backend can store them alongside
    # the score, making the result auditable after the fact.
    return {"account_id": request.account_id, "month": request.month,
            "features": features, **result}


@app.post("/score/transactions", response_model=AnomalyResponse, tags=["scoring"])
async def score_transactions(request: ScoreTransactionsRequest):
    """Score an account's transactions for anomalies, from raw rows.

    Note the response can contain FEWER results than transactions sent, which is
    correct rather than a bug: inflows (a paycheck is large by definition) and
    refunds (negative amounts) are excluded from anomaly scoring. Every result
    carries its own transaction_id so my backend matches by id, never by
    position — position-matching would mislabel every row after the first
    dropped one.
    """
    payload = [txn.model_dump() for txn in request.transactions]
    scorable = transaction_features(payload)

    results = scorer.score_anomalies(scorable)
    flagged = sum(1 for r in results if r["is_anomaly"])

    log.info("scored account=%s: %d raw -> %d scorable, flagged %d",
             request.account_id, len(payload), len(scorable), flagged)

    return {
        "results": results,
        "flagged_count": flagged,
        "model_version": scorer.model_version,
    }


@app.get("/archetypes", tags=["reference"])
async def list_archetypes():
    """The full archetype catalogue.

    My dashboard needs this to render a legend — to show "here are the six
    spending patterns and what each means" without having to score a month
    first. Serving it from the model metadata means the UI can never show a
    stale list after I retrain.
    """
    if not scorer.loaded:
        raise ModelNotLoadedError("models are not loaded")
    return {
        "model_version": scorer.model_version,
        "archetypes": [
            {"cluster": int(cluster_id), "name": info["name"],
             "description": info["description"], "evidence": info["evidence"]}
            for cluster_id, info in sorted(scorer.archetypes.items(), key=lambda kv: int(kv[0]))
        ],
    }
