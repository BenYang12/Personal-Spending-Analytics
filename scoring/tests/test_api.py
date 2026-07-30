"""Tests for my scoring API.

TestClient calls the app IN-PROCESS — no server, no port, no network. It's the
same idea as MockMvc in Spring: I get the real routing, real validation and real
serialisation, at unit-test speed. That means these run in CI without anyone
having to start uvicorn first.

What I'm testing here is the CONTRACT, not model accuracy. Whether
IsolationForest is any good was settled in ml/EVALUATION.md against injected
anomalies. What these tests protect is everything around the model: that bad
input gets rejected, that status codes are right, that scores can't silently
invert, and that a mismatched feature set can never be scored. Those are the
failures that would be invisible in production.
"""

import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).parent.parent))

import app as app_module  # noqa: E402
from scorer import Scorer  # noqa: E402

MODEL_DIR = Path(__file__).parent.parent.parent / "ml" / "models"

# A valid behaviour vector I reuse across tests. Its values describe a
# weekend-heavy, dining-heavy month.
VALID_FEATURES = {
    "txn_count": 24.0, "avg_ticket": 41.5, "ticket_variability": 0.72,
    "merchant_diversity": 0.66, "weekend_ratio": 0.78, "recurring_share": 0.09,
    "fixed_share": 0.41, "spend_to_income": 0.63, "share_dining": 0.52,
    "share_groceries": 0.11, "share_subscriptions": 0.08, "share_transport": 0.09,
    "share_shopping": 0.14, "share_entertainment": 0.06,
}

NORMAL_TXN = {
    "amount": 12.40, "amount_log": 2.59, "amount_zscore_in_category": 0.3,
    "amount_ratio_to_category_median": 1.1, "merchant_novelty": 0.0,
    "merchant_frequency": 14.0, "is_weekend": 0.0, "day_of_month": 12.0,
    "days_since_previous": 1.0, "category_rarity": 0.2,
    "merchant_txns_same_day": 1.0, "account_txns_same_day": 2.0,
}

SUSPICIOUS_TXN = {
    "amount": 1899.0, "amount_log": 7.55, "amount_zscore_in_category": 68.8,
    "amount_ratio_to_category_median": 259.8, "merchant_novelty": 1.0,
    "merchant_frequency": 0.0, "is_weekend": 1.0, "day_of_month": 17.0,
    "days_since_previous": 0.0, "category_rarity": 1.0,
    "merchant_txns_same_day": 1.0, "account_txns_same_day": 3.0,
}


@pytest.fixture(scope="module")
def client():
    """A TestClient with models loaded.

    Using `with TestClient(...)` matters: the context manager is what triggers
    the lifespan startup hook. Without it the app would come up with no models
    and every test would hit 503 — a mistake I'd rather document than repeat.

    scope="module" so the models load once for the whole file instead of per
    test. Loading takes ~700ms; paying that on every test would make the suite
    slow enough that I'd stop running it.
    """
    with TestClient(app_module.app) as test_client:
        yield test_client


class TestHealth:
    def test_health_reports_loaded_models(self, client):
        response = client.get("/health")
        assert response.status_code == 200

        body = response.json()
        assert body["status"] == "ok"
        assert body["models_loaded"] is True
        # The feature counts are part of my contract with the Spring backend;
        # if a retrain changed them, this test should fail loudly and make me
        # go update the caller too.
        assert body["behaviour_features"] == 14
        assert body["anomaly_features"] == 12
        assert body["archetype_count"] == 6

    def test_health_needs_no_credentials(self, client):
        # Health checks that require auth are a classic way for monitoring to
        # silently stop working.
        assert client.get("/health").status_code == 200


class TestArchetypeEndpoint:
    def test_scores_a_month_and_explains_itself(self, client):
        response = client.post("/archetype", json={
            "account_id": 2, "month": "2026-05", "features": VALID_FEATURES,
        })
        assert response.status_code == 200
        body = response.json()

        assert body["archetype"]                       # a name, not just an index
        assert 0 <= body["cluster"] < 6
        assert body["model_version"] != "unloaded"

        # The evidence is what my dashboard shows the user, so an unexplained
        # assignment is a broken response even if the cluster is right.
        assert len(body["evidence"]) == 3
        top = body["evidence"][0]
        assert {"feature", "your_value", "population_average",
                "std_devs_from_average"} <= top.keys()

    def test_weekend_heavy_month_is_explained_by_weekend_ratio(self, client):
        """A behavioural sanity check, not an accuracy claim.

        I don't assert a specific archetype NAME here — that would break every
        time I retrain and the clusters renumber, which is exactly the brittle
        test I'd end up deleting. Instead I assert the WEAK, durable property:
        for a month with a 0.78 weekend ratio against a 0.42 average, weekend
        behaviour should be among the top explanations. If it isn't, my scaler
        or my feature ordering is wrong.
        """
        response = client.post("/archetype", json={
            "account_id": 2, "month": "2026-05", "features": VALID_FEATURES,
        })
        features_cited = {e["feature"] for e in response.json()["evidence"]}
        assert "weekend_ratio" in features_cited

    def test_missing_features_are_a_400_not_a_500(self, client):
        """The status code is the point of this test.

        4xx tells my Spring client "your request is wrong, don't retry"; 5xx
        says "I'm broken, try again later". Returning 500 here would turn a
        config mismatch into an infinite retry storm.
        """
        partial = {k: VALID_FEATURES[k] for k in list(VALID_FEATURES)[:5]}
        response = client.post("/archetype", json={
            "account_id": 2, "month": "2026-05", "features": partial,
        })
        assert response.status_code == 400
        assert response.json()["error"] == "feature_contract_violation"
        # The error must name what's missing, or whoever hits this is stuck.
        assert "missing" in response.json()["detail"]

    def test_unknown_feature_is_rejected_rather_than_ignored(self, client):
        """Silently dropping an unexpected feature is the dangerous option.

        If the caller sends a feature I don't know about, we disagree about what
        model is running. Ignoring it would let the request score "successfully"
        against a different feature set than the caller believes — a confident
        wrong answer, which is the worst failure mode I have.
        """
        response = client.post("/archetype", json={
            "account_id": 2, "month": "2026-05",
            "features": {**VALID_FEATURES, "share_crypto": 0.4},
        })
        assert response.status_code == 400
        assert "share_crypto" in response.json()["detail"]

    @pytest.mark.parametrize("bad_value", ["NaN", "Infinity", '"nan"'])
    def test_non_finite_values_are_rejected(self, client, bad_value):
        """NaN and infinity must never reach the model.

        Two things I learned writing this test.

        First, I have to send a RAW body rather than `json=`. httpx refuses to
        serialise a Python float('nan'), and rightly so — NaN is not valid JSON.
        But a hand-rolled client absolutely can put a bare `NaN` token on the
        wire, so this is a real request I could receive, not a hypothetical.

        Second, and more important: **Pydantic does not save me here.** A field
        typed `float` happily accepts the `NaN` token, `Infinity`, and even the
        string `"nan"` coerced to a float. So my schema declarations — which
        catch every other kind of bad input in this file — let all three
        straight through. The explicit finiteness check in `_to_array` is what
        actually stops them, and without it NaN would flow into the model and
        come back as a nonsense prediction with nothing raising.

        That's the general lesson: schema validation covers types and shapes,
        not numeric sanity. I need both.
        """
        body = (
            '{"account_id": 2, "month": "2026-05", "features": {'
            + f'"txn_count": {bad_value}, '
            + ", ".join(f'"{k}": {v}' for k, v in VALID_FEATURES.items() if k != "txn_count")
            + "}}"
        )
        response = client.post("/archetype", content=body,
                               headers={"Content-Type": "application/json"})
        assert response.status_code == 400
        assert response.json()["error"] == "feature_contract_violation"

    @pytest.mark.parametrize("bad_month", ["May 2026", "2026", "2026-5-1", ""])
    def test_malformed_month_is_rejected_by_the_schema(self, client, bad_month):
        # 422 is FastAPI's own validation failure, raised from the type hints
        # before any of my code runs.
        response = client.post("/archetype", json={
            "account_id": 2, "month": bad_month, "features": VALID_FEATURES,
        })
        assert response.status_code == 422


class TestAnomalyEndpoint:
    def test_scores_a_batch(self, client):
        response = client.post("/anomaly", json={"transactions": [
            {"transaction_id": "t1", "features": NORMAL_TXN},
            {"transaction_id": "t2", "features": SUSPICIOUS_TXN},
        ]})
        assert response.status_code == 200
        body = response.json()

        assert len(body["results"]) == 2
        # Order must be preserved: my backend zips these results back onto its
        # own transaction list, so a reordering would mislabel every row.
        assert [r["transaction_id"] for r in body["results"]] == ["t1", "t2"]

    def test_the_suspicious_transaction_scores_higher_than_the_normal_one(self, client):
        """The sign-convention regression test.

        IsolationForest's raw score_samples is BACKWARDS — higher means more
        normal. I flip it in the scorer so my API means what it says. If someone
        (me, later) removes that negation, this test fails instead of my
        dashboard quietly labelling every ordinary coffee as fraud.
        """
        response = client.post("/anomaly", json={"transactions": [
            {"transaction_id": "normal", "features": NORMAL_TXN},
            {"transaction_id": "suspicious", "features": SUSPICIOUS_TXN},
        ]})
        normal, suspicious = response.json()["results"]
        assert suspicious["anomaly_score"] > normal["anomaly_score"]
        assert suspicious["is_anomaly"] is True

    def test_flagged_transactions_explain_themselves(self, client):
        response = client.post("/anomaly", json={"transactions": [
            {"transaction_id": "t1", "features": SUSPICIOUS_TXN},
        ]})
        result = response.json()["results"][0]
        assert result["is_anomaly"] is True
        assert result["reasons"], "a flagged charge with no explanation is 'computer says no'"
        # The reasons should reference the actual evidence, not be generic.
        assert any("merchant" in r.lower() or "usual" in r.lower() for r in result["reasons"])

    def test_normal_transactions_carry_no_reasons(self, client):
        """Regression test for a contradiction I shipped and caught in testing.

        My catch-all fallback reason fired for every transaction regardless of
        the verdict, so an ordinary $12 coffee came back marked NOT anomalous
        while carrying the text "unusual combination of amount, timing and
        merchant history". My dashboard renders that string, so it would have
        shown the user a contradiction on rows it had just called normal.
        """
        response = client.post("/anomaly", json={"transactions": [
            {"transaction_id": "t1", "features": NORMAL_TXN},
        ]})
        result = response.json()["results"][0]
        assert result["is_anomaly"] is False
        assert result["reasons"] == []

    def test_empty_batch_is_rejected(self, client):
        response = client.post("/anomaly", json={"transactions": []})
        assert response.status_code == 422

    def test_oversized_batch_is_rejected(self, client):
        # An unbounded request body is a memory-exhaustion risk, so the cap is
        # a real safety limit rather than an arbitrary number.
        oversized = [{"transaction_id": f"t{i}", "features": NORMAL_TXN} for i in range(1001)]
        response = client.post("/anomaly", json={"transactions": oversized})
        assert response.status_code == 422

    def test_one_bad_transaction_fails_the_whole_batch(self, client):
        """All-or-nothing, on purpose.

        A partial batch would leave my Spring caller working out which rows
        succeeded and which vanished. A clean 400 is far easier to handle
        correctly, and the caller can retry the whole thing once it's fixed.
        """
        response = client.post("/anomaly", json={"transactions": [
            {"transaction_id": "good", "features": NORMAL_TXN},
            {"transaction_id": "bad", "features": {"amount": 5.0}},
        ]})
        assert response.status_code == 400
        assert "missing" in response.json()["detail"]


class TestArchetypeCatalogue:
    def test_lists_every_archetype(self, client):
        response = client.get("/archetypes")
        assert response.status_code == 200
        body = response.json()

        assert len(body["archetypes"]) == 6
        for archetype in body["archetypes"]:
            assert archetype["name"]
            assert archetype["description"]
        # Names must be unique — two clusters sharing a label would be
        # meaningless in my dashboard's legend.
        names = [a["name"] for a in body["archetypes"]]
        assert len(set(names)) == len(names)


class TestDegradedMode:
    def test_scoring_without_models_returns_503_not_500(self):
        """The graceful-degradation contract, tested directly.

        503 means "temporarily unavailable, retrying is reasonable", which is
        true — dropping the model files in fixes it with no code change. A 500
        would tell my backend the request itself was hopeless.

        I point the app at an empty directory to simulate a deploy where the
        pickles never made it into the image.
        """
        empty_scorer = Scorer(Path("/nonexistent/models"))
        original = app_module.scorer
        app_module.scorer = empty_scorer
        try:
            with TestClient(app_module.app) as degraded_client:
                health = degraded_client.get("/health").json()
                # Still ANSWERS, and tells the truth about being useless. A
                # crashed process would just look like a network failure.
                assert health["status"] == "degraded"
                assert health["models_loaded"] is False

                response = degraded_client.post("/archetype", json={
                    "account_id": 1, "month": "2026-05", "features": VALID_FEATURES,
                })
                assert response.status_code == 503
                assert response.json()["error"] == "models_not_loaded"
        finally:
            # Restore, or every test that runs after this one inherits a broken
            # scorer and the failures point somewhere completely unrelated.
            app_module.scorer = original


class TestModelContract:
    def test_feature_order_comes_from_metadata_not_hardcoded(self):
        """The invariant the whole design rests on.

        scikit-learn sees column POSITIONS, not names. If my service's idea of
        the ordering ever drifted from the model's, every prediction would be
        confidently wrong with nothing raising. Reading the order from the
        metadata written by the training run means there's exactly one source of
        truth, and this test proves the artifacts agree.
        """
        scorer = Scorer(MODEL_DIR)
        scorer.load()

        assert scorer.behaviour_features == scorer.cluster_meta["feature_order"]
        assert scorer.anomaly_features == scorer.anomaly_meta["feature_order"]
        # And the pickles themselves must agree with that metadata.
        assert scorer.scaler.n_features_in_ == len(scorer.behaviour_features)
        assert scorer.kmeans.n_features_in_ == len(scorer.behaviour_features)
        assert scorer.isoforest.n_features_in_ == len(scorer.anomaly_features)

    def test_feature_order_is_respected_not_dict_order(self):
        """Shuffling the request's key order must not change the result.

        Python dicts preserve insertion order, which makes it tempting to trust
        whatever order the caller sent. This test proves I reorder against the
        model's contract instead — the single most dangerous silent bug in this
        service, made visible.
        """
        scorer = Scorer(MODEL_DIR)
        scorer.load()

        shuffled = dict(reversed(list(VALID_FEATURES.items())))
        assert list(shuffled) != list(VALID_FEATURES)      # genuinely reordered

        assert (scorer.score_archetype(VALID_FEATURES)["cluster"]
                == scorer.score_archetype(shuffled)["cluster"])
