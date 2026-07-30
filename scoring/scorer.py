"""Loads my trained models once, then scores requests against them.

I keep all model handling in this class rather than in the FastAPI routes, for
the same reason my Spring controllers are thin and the real work lives in
@Service beans: routes should parse and respond, services should think. It also
means I can unit-test scoring without any HTTP involved.

The two rules this file exists to enforce:

  1. **Load once, at startup — never per request.** Unpickling an
     IsolationForest with 200 trees takes real time. Doing that inside a request
     handler would make every call pay for it, and under any concurrency I'd
     have several copies in memory at once. Load at boot, keep in memory, score
     in microseconds.

  2. **Never trust feature ORDER from the caller.** scikit-learn consumes a
     positional array; it has no idea my third column is `merchant_diversity`.
     If the caller's ordering ever drifted from the model's, every prediction
     would be confidently wrong and absolutely nothing would raise. So I accept
     names, look up the order the model was TRAINED with, and build the array
     myself. A mismatch becomes a loud error instead of a silent lie.
"""

from __future__ import annotations

import json
from pathlib import Path

import joblib
import numpy as np


class FeatureContractError(ValueError):
    """Raised when a request's features don't match what the model expects.

    Its own exception type so my API layer can turn it into a 400 (the caller
    sent something wrong) rather than a 500 (I broke). That distinction matters
    to whoever is debugging at 2am.
    """


class ModelNotLoadedError(RuntimeError):
    """Raised when scoring is attempted but the models never loaded."""


class Scorer:
    """Holds the fitted models and turns feature dicts into predictions."""

    def __init__(self, model_dir: Path) -> None:
        self.model_dir = Path(model_dir)
        self.loaded = False

        # I declare every attribute up front, even as None. Otherwise a failed
        # load leaves the object half-built and the next access raises
        # AttributeError somewhere confusing instead of my clear error.
        self.scaler = None
        self.kmeans = None
        self.isoforest = None
        self.cluster_meta: dict = {}
        self.anomaly_meta: dict = {}
        self.behaviour_features: list[str] = []
        self.anomaly_features: list[str] = []
        self.archetypes: dict[str, dict] = {}

    # ------------------------------------------------------------------
    # loading
    # ------------------------------------------------------------------

    def load(self) -> None:
        """Read the pickles and metadata from disk.

        I let exceptions escape rather than swallowing them. If the models can't
        load I want to know at startup, with the real reason, not to discover it
        when the first user request returns something odd.
        """
        required = ["scaler.pkl", "kmeans.pkl", "isoforest.pkl",
                    "cluster_meta.json", "anomaly_meta.json"]
        missing = [name for name in required if not (self.model_dir / name).exists()]
        if missing:
            raise FileNotFoundError(
                f"missing {missing} in {self.model_dir} — I need to run the /ml pipeline first"
            )

        self.scaler = joblib.load(self.model_dir / "scaler.pkl")
        self.kmeans = joblib.load(self.model_dir / "kmeans.pkl")
        self.isoforest = joblib.load(self.model_dir / "isoforest.pkl")

        self.cluster_meta = json.loads((self.model_dir / "cluster_meta.json").read_text())
        self.anomaly_meta = json.loads((self.model_dir / "anomaly_meta.json").read_text())

        # THE CONTRACT. I read feature order from the metadata my training run
        # wrote, so the ordering always comes from the same place the model came
        # from. Hardcoding the list here would let it drift the moment I retrain
        # with a new feature.
        self.behaviour_features = self.cluster_meta["feature_order"]
        self.anomaly_features = self.anomaly_meta["feature_order"]
        self.archetypes = self.cluster_meta["archetypes"]

        # Cross-check the artifacts actually agree with each other. A scaler
        # fitted on 14 features and a KMeans fitted on 12 would mean I'd copied
        # mismatched pickles into place — catching that here beats debugging
        # nonsense predictions later.
        if self.scaler.n_features_in_ != len(self.behaviour_features):
            raise ValueError(
                f"scaler expects {self.scaler.n_features_in_} features but metadata "
                f"lists {len(self.behaviour_features)} — mismatched artifacts"
            )
        if self.kmeans.n_features_in_ != len(self.behaviour_features):
            raise ValueError("kmeans and metadata disagree on feature count")
        if self.isoforest.n_features_in_ != len(self.anomaly_features):
            raise ValueError("isoforest and metadata disagree on feature count")

        self.loaded = True

    @property
    def model_version(self) -> str:
        """A short identifier I stamp on every response.

        Without this, an anomaly score in my database is unattributable — I'd
        have no way to tell which model produced it after a retrain. Including
        k and contamination means the two settings that most change behaviour
        are visible at a glance.
        """
        if not self.loaded:
            return "unloaded"
        return (f"kmeans-k{self.cluster_meta['k']}"
                f"+iforest-c{self.anomaly_meta['contamination']}")

    # ------------------------------------------------------------------
    # feature ordering — the guard rail
    # ------------------------------------------------------------------

    def _to_array(self, features: dict[str, float], expected: list[str]) -> np.ndarray:
        """Turn a NAMED feature dict into the positional array sklearn wants.

        This little method is the whole point of the module. It's where names
        become positions, and it refuses to guess: missing features and unknown
        features are both errors, because either one means the caller and I
        disagree about the model, and quietly filling a zero would produce a
        plausible-looking wrong answer.
        """
        missing = [name for name in expected if name not in features]
        unexpected = [name for name in features if name not in expected]

        if missing or unexpected:
            problems = []
            if missing:
                problems.append(f"missing: {sorted(missing)}")
            if unexpected:
                problems.append(f"unexpected: {sorted(unexpected)}")
            raise FeatureContractError(
                "feature mismatch — " + "; ".join(problems)
                + f". This model expects exactly these {len(expected)}: {expected}"
            )

        # Build in the model's order, not the dict's. Python dicts preserve
        # insertion order, which makes it tempting to trust the caller's
        # ordering — and that would be exactly the silent bug I'm preventing.
        values = [float(features[name]) for name in expected]
        array = np.array([values], dtype=float)

        if not np.isfinite(array).all():
            # NaN or infinity propagates through the model and comes out as a
            # nonsense prediction rather than an error, so I stop it here.
            raise FeatureContractError("features contain NaN or infinity")

        return array

    def _require_loaded(self) -> None:
        if not self.loaded:
            raise ModelNotLoadedError("models are not loaded — cannot score")

    # ------------------------------------------------------------------
    # archetype scoring
    # ------------------------------------------------------------------

    def score_archetype(self, features: dict[str, float]) -> dict:
        """Classify one account-month into an archetype."""
        self._require_loaded()
        raw = self._to_array(features, self.behaviour_features)

        # I MUST apply the same scaler that was fitted during training — not a
        # fresh one. StandardScaler stores the training population's mean and
        # standard deviation, and those numbers are what make "1.4 standard
        # deviations above average" mean anything. Re-fitting on one row would
        # be meaningless (a single sample has no variance).
        scaled = self.scaler.transform(raw)

        cluster = int(self.kmeans.predict(scaled)[0])

        # Distance to the assigned centroid tells me HOW WELL this month fits.
        # transform() returns distances to every centroid, so I take the one for
        # the assigned cluster.
        distances = self.kmeans.transform(scaled)[0]
        distance_to_centroid = float(distances[cluster])

        info = self.archetypes[str(cluster)]

        return {
            "cluster": cluster,
            "archetype": info["name"],
            "description": info["description"],
            "distance_to_centroid": round(distance_to_centroid, 3),
            "evidence": self._explain_month(scaled[0], raw[0]),
            "model_version": self.model_version,
        }

    def _explain_month(self, scaled_row: np.ndarray, raw_row: np.ndarray,
                       limit: int = 3) -> list[dict]:
        """Explain an assignment by its most unusual features.

        The scaled value IS the explanation: because StandardScaler centres on
        the training mean, a scaled value of +1.4 literally means "1.4 standard
        deviations above a typical month". So I rank by absolute scaled value
        and report the top few alongside their real-world numbers, which is what
        my dashboard shows the user.
        """
        ranked = sorted(
            zip(self.behaviour_features, scaled_row, raw_row),
            key=lambda item: abs(item[1]),
            reverse=True,
        )

        evidence = []
        for name, scaled_value, raw_value in ranked[:limit]:
            index = self.behaviour_features.index(name)
            evidence.append({
                "feature": name,
                "your_value": round(float(raw_value), 3),
                # scaler.mean_ is the training population's average for this
                # feature — exactly the "typical" I want to compare against.
                "population_average": round(float(self.scaler.mean_[index]), 3),
                "std_devs_from_average": round(float(scaled_value), 2),
            })
        return evidence

    # ------------------------------------------------------------------
    # anomaly scoring
    # ------------------------------------------------------------------

    def score_anomalies(self, transactions: list[dict]) -> list[dict]:
        """Score a batch of transactions for anomalousness."""
        self._require_loaded()

        # I validate EVERY transaction before scoring ANY of them. Partial
        # results from a batch would be worse than a clean failure — my Spring
        # caller would have to work out which ones succeeded.
        rows = [
            self._to_array(txn["features"], self.anomaly_features)[0]
            for txn in transactions
        ]
        matrix = np.array(rows, dtype=float)

        # NOTE: no scaler here, and that's deliberate. IsolationForest is a tree
        # ensemble — it splits on thresholds inside one feature at a time, so
        # the relative scale of different features is irrelevant to it. KMeans
        # needed scaling because Euclidean distance mixes all features together.
        # Knowing which models need scaling and which don't is the real lesson;
        # applying the wrong scaler here would silently corrupt the input.
        raw_scores = self.isoforest.score_samples(matrix)
        predictions = self.isoforest.predict(matrix)

        results = []
        for txn, raw_score, prediction, row in zip(transactions, raw_scores, predictions, matrix):
            results.append({
                "transaction_id": txn["transaction_id"],
                # Sign flip: score_samples returns HIGHER for more NORMAL points,
                # which is backwards from every intuition about an "anomaly
                # score". I normalise it here, once, so no consumer of my API
                # can get the comparison the wrong way round.
                "anomaly_score": round(float(-raw_score), 4),
                # predict() returns -1 for outliers, 1 for inliers.
                "is_anomaly": bool(prediction == -1),
                # Reasons only for FLAGGED rows. I caught this in testing: an
                # ordinary $12 coffee came back with "unusual combination of
                # amount, timing and merchant history" attached, because my
                # catch-all fallback fired for every transaction regardless of
                # the verdict. Harmless server-side, but my dashboard renders
                # this text — so it would have shown a contradiction to the user
                # on rows it had just declared normal.
                "reasons": (
                    self._explain_anomaly(dict(zip(self.anomaly_features, row)))
                    if prediction == -1 else []
                ),
            })
        return results

    def _explain_anomaly(self, features: dict[str, float]) -> list[str]:
        """Turn feature values into plain-English reasons.

        These are RULES, not model internals, and I want to be precise about
        what that means. IsolationForest can't tell me which feature isolated a
        point — no per-feature attribution comes out of it. So I read the same
        features the model saw and describe the ones a human would find notable.

        The honest framing: these explain the EVIDENCE, not the model's
        reasoning. I'd say exactly that in an interview rather than implying I
        have real attribution. Something like SHAP would give me genuine
        attribution, and it's the obvious upgrade — but rules are deterministic,
        instant, and good enough to tell a user why their row is flagged.
        """
        reasons: list[str] = []

        zscore = features.get("amount_zscore_in_category", 0.0)
        if zscore > 3.0:
            reasons.append(
                f"Amount is {zscore:.1f} standard deviations above your usual for this category"
            )

        ratio = features.get("amount_ratio_to_category_median", 1.0)
        if ratio > 3.0:
            reasons.append(f"About {ratio:.1f}x your typical spend in this category")

        if features.get("merchant_novelty", 0.0) == 1.0:
            reasons.append("First time you've used this merchant")

        same_day = features.get("merchant_txns_same_day", 1.0)
        if same_day >= 3:
            reasons.append(
                f"{int(same_day)} charges at this merchant on the same day — "
                "a pattern that can indicate card testing"
            )

        if features.get("category_rarity", 0.0) > 0.95:
            reasons.append("This spending category is unusual for this account")

        if not reasons:
            # I never return an empty list. A flagged transaction with no
            # explanation is exactly the "computer says no" experience I don't
            # want to build.
            reasons.append("Unusual combination of amount, timing and merchant history")

        return reasons

    # ------------------------------------------------------------------
    # health
    # ------------------------------------------------------------------

    def health(self) -> dict:
        return {
            "status": "ok" if self.loaded else "degraded",
            "models_loaded": self.loaded,
            "model_version": self.model_version,
            "archetype_count": len(self.archetypes),
            "behaviour_features": len(self.behaviour_features),
            "anomaly_features": len(self.anomaly_features),
        }
