"""Train the spending-archetype clustering model (offline).

This runs on my laptop, never in a request. It writes three artifacts to
models/ that my FastAPI scoring service loads at startup:

    scaler.pkl        - the fitted StandardScaler
    kmeans.pkl        - the fitted KMeans
    cluster_meta.json - feature order, chosen k, and my archetype names

That split is the whole architecture: training is slow, stateful and needs the
full dataset; scoring is fast, stateless and needs one row. Keeping them apart
means my API never waits on model fitting, and I can retrain without deploying.

Two things I do here that a tutorial usually skips:

  1. I choose k with BOTH the elbow method and silhouette scores, and I say
     which one decided it. Picking k=5 because it looks nice is not a method.
  2. I VALIDATE the clustering against the known archetypes of my synthetic
     users (Adjusted Rand Index). Unsupervised learning normally offers no way
     to check whether clusters mean anything; because I manufactured users with
     known behaviour, I get a real number instead of a vibe.
"""

import json
from pathlib import Path

import joblib
import matplotlib
import numpy as np
import pandas as pd

# I select the non-interactive backend BEFORE importing pyplot, because this
# script runs headless (and will run in CI) where no display exists.
matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

from sklearn.cluster import KMeans  # noqa: E402
from sklearn.metrics import adjusted_rand_score, silhouette_score  # noqa: E402
from sklearn.preprocessing import StandardScaler  # noqa: E402

from archetypes import name_clusters  # noqa: E402
from features import BEHAVIOUR_FEATURES, feature_matrix  # noqa: E402
from mlsetup import silence_accelerate_matmul_warning  # noqa: E402

silence_accelerate_matmul_warning()

BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "data"
MODEL_DIR = BASE_DIR / "models"

# The range of k I'm willing to consider. Below 3 the archetypes are too coarse
# to be useful advice; above 8 I can't describe them in plain English, and an
# archetype I can't name is an archetype I can't ship.
K_RANGE = range(2, 9)
RANDOM_STATE = 42


def choose_k(scaled: np.ndarray) -> tuple[int, pd.DataFrame]:
    """Score every candidate k, then pick one on silhouette.

    The elbow method plots inertia (within-cluster sum of squares) and looks for
    the bend. It always decreases as k grows — k = one cluster per point gives
    inertia 0 — so it can't be optimised directly, only eyeballed.

    Silhouette measures how much closer each point sits to its own cluster than
    to the next nearest, in [-1, 1]. It has an actual maximum, so I can let it
    decide and keep the elbow plot as a sanity check that the two roughly agree.
    """
    results = []
    for k in K_RANGE:
        # n_init=10: KMeans converges to a local optimum that depends on where
        # the centroids start, so I run it 10 times and keep the best. This is
        # the difference between a stable model and one that changes every run.
        kmeans = KMeans(n_clusters=k, n_init=10, random_state=RANDOM_STATE)
        labels = kmeans.fit_predict(scaled)
        results.append({
            "k": k,
            "inertia": float(kmeans.inertia_),
            "silhouette": float(silhouette_score(scaled, labels)),
        })

    scores = pd.DataFrame(results)
    best_k = int(scores.loc[scores["silhouette"].idxmax(), "k"])
    return best_k, scores


def plot_diagnostics(scores: pd.DataFrame, chosen_k: int) -> Path:
    """Save the elbow and silhouette curves side by side.

    I keep this image because "how did you pick k?" is a question I will be
    asked, and a plot answers it faster than a paragraph.
    """
    fig, (elbow_ax, sil_ax) = plt.subplots(1, 2, figsize=(11, 4))

    elbow_ax.plot(scores["k"], scores["inertia"], marker="o")
    elbow_ax.set_title("Elbow: inertia vs k")
    elbow_ax.set_xlabel("k")
    elbow_ax.set_ylabel("inertia (within-cluster SSE)")
    elbow_ax.axvline(chosen_k, linestyle="--", alpha=0.6)

    sil_ax.plot(scores["k"], scores["silhouette"], marker="o", color="darkorange")
    sil_ax.set_title("Silhouette vs k (higher is better)")
    sil_ax.set_xlabel("k")
    sil_ax.set_ylabel("mean silhouette")
    sil_ax.axvline(chosen_k, linestyle="--", alpha=0.6)

    fig.suptitle(f"Choosing k — silhouette picked k={chosen_k}")
    fig.tight_layout()
    out = MODEL_DIR / "choose_k.png"
    fig.savefig(out, dpi=120)
    plt.close(fig)
    return out


def validate_against_truth(features: pd.DataFrame, labels: np.ndarray) -> dict:
    """Check whether my clusters recovered the archetypes I planted.

    Adjusted Rand Index compares two partitions of the same data while
    correcting for chance: 0.0 means "no better than random", 1.0 means the
    groupings are identical. Crucially it does NOT care that my cluster is
    called 2 and my archetype is called "weekend_spender" — it only asks whether
    the same months were grouped together, which is exactly the right question
    for unsupervised output.

    I only score the synthetic users, because those are the only ones whose true
    archetype I know.
    """
    label_file = DATA_DIR / "synthetic_labels.csv"
    if not label_file.exists():
        return {"note": "no synthetic labels found, skipped validation"}

    truth = pd.read_csv(label_file)
    scored = features[["account_id"]].copy()
    scored["cluster"] = labels
    merged = scored.merge(truth, on="account_id", how="inner")
    if merged.empty:
        return {"note": "no overlap with synthetic labels"}

    ari = float(adjusted_rand_score(merged["true_archetype"], merged["cluster"]))

    # A confusion-style crosstab shows me WHERE it went wrong, not just that it
    # did — e.g. two archetypes I made too similar collapsing into one cluster.
    crosstab = pd.crosstab(merged["true_archetype"], merged["cluster"])

    # For each true archetype, what fraction of its months landed in that
    # archetype's most common cluster? This is "purity", and it reads more
    # intuitively than ARI when I'm explaining the result.
    purity = float((crosstab.max(axis=1).sum()) / crosstab.to_numpy().sum())

    return {
        "adjusted_rand_index": round(ari, 3),
        "purity": round(purity, 3),
        "n_synthetic_months": int(len(merged)),
        "crosstab": crosstab.to_string(),
    }


def main() -> None:
    features = pd.read_csv(DATA_DIR / "monthly_features.csv", parse_dates=["month"])
    matrix = feature_matrix(features)
    print(f"training on {matrix.shape[0]} account-months x {matrix.shape[1]} features")

    # STANDARDISATION IS MANDATORY HERE, not a nicety. KMeans minimises squared
    # Euclidean distance, so a feature's influence is proportional to its scale.
    # txn_count runs 5-50 while share_dining runs 0-1; unscaled, transaction
    # count would be roughly the only thing the model could see. StandardScaler
    # centres each feature at 0 with unit variance so every feature gets an
    # equal vote.
    scaler = StandardScaler()
    scaled = scaler.fit_transform(matrix)

    best_k, scores = choose_k(scaled)
    print("\nk selection:")
    print(scores.round(3).to_string(index=False))

    plot_path = plot_diagnostics(scores, best_k)
    print(f"\nchose k={best_k} (highest silhouette); diagnostics -> {plot_path}")

    kmeans = KMeans(n_clusters=best_k, n_init=10, random_state=RANDOM_STATE)
    labels = kmeans.fit_predict(scaled)

    validation = validate_against_truth(features, labels)
    print("\nvalidation against known synthetic archetypes:")
    for key, value in validation.items():
        if key == "crosstab":
            print(f"\n{value}\n")
        else:
            print(f"  {key}: {value}")

    # Turn centroids into names humans can read (see archetypes.py).
    naming = name_clusters(kmeans, scaler, BEHAVIOUR_FEATURES)
    print("archetypes:")
    for cluster_id, info in sorted(naming.items()):
        share = float((labels == cluster_id).mean())
        print(f"  cluster {cluster_id}: {info['name']:<24} ({share:.0%} of months)")
        print(f"      {info['description']}")

    MODEL_DIR.mkdir(exist_ok=True)
    joblib.dump(scaler, MODEL_DIR / "scaler.pkl")
    joblib.dump(kmeans, MODEL_DIR / "kmeans.pkl")

    # The metadata file is what makes the pickles safe to use. Feature ORDER is
    # part of the model contract — scikit-learn sees positions, not names — so I
    # persist it and have the scoring service assert against it. A silent column
    # reorder would otherwise produce confident nonsense.
    meta = {
        "k": best_k,
        "feature_order": BEHAVIOUR_FEATURES,
        "archetypes": {str(cid): info for cid, info in naming.items()},
        "silhouette": float(scores.loc[scores["k"] == best_k, "silhouette"].iloc[0]),
        "validation": {k: v for k, v in validation.items() if k != "crosstab"},
        "n_training_months": int(matrix.shape[0]),
        "trained_on": "60 synthetic users (known archetypes) + my real Plaid/seed accounts",
    }
    (MODEL_DIR / "cluster_meta.json").write_text(json.dumps(meta, indent=2))

    # I also save the labelled months so I can eyeball individual assignments
    # and so my anomaly work can reuse the archetype context.
    labelled = features[["account_id", "month"]].copy()
    labelled["cluster"] = labels
    labelled["archetype"] = [naming[c]["name"] for c in labels]
    labelled.to_csv(DATA_DIR / "month_archetypes.csv", index=False)

    print(f"\nsaved scaler.pkl, kmeans.pkl, cluster_meta.json to {MODEL_DIR}")


if __name__ == "__main__":
    main()
