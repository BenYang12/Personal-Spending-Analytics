"""Train the anomaly-detection model (offline), and tune it honestly.

The output is models/isoforest.pkl plus its metadata. What I care about most in
this file is the DISCIPLINE of the split, so I'll state it up front:

    train accounts (60%)  - fit IsolationForest. No labels used; it's unsupervised.
    val accounts   (20%)  - inject anomalies, tune `contamination` against them.
    test accounts  (20%)  - never touched here. evaluate.py reports on it.

Tuning contamination on the same data I then report metrics from would let me
quote a number I'd already optimised for — the most common way an ML project
overstates itself. Splitting by ACCOUNT rather than by row matters too: my
features are computed from each account's own history, so rows from one account
in both train and test would leak that account's spending profile across the
boundary.

A detail worth noticing: I do NOT scale features here, even though scaling was
mandatory for KMeans. IsolationForest is a tree ensemble — it splits on
thresholds within one feature at a time, so the relative scale of different
features is irrelevant to it. Scaling would be harmless but pointless, and
knowing which models need it is the actual lesson.
"""

import json
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest
from sklearn.metrics import precision_recall_fscore_support

from anomaly_features import ANOMALY_FEATURES, build_transaction_features
from features import flag_recurring, load_transactions
from inject import inject_anomalies
from mlsetup import silence_accelerate_matmul_warning

silence_accelerate_matmul_warning()

BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "data"
MODEL_DIR = BASE_DIR / "models"

RANDOM_STATE = 42
# Contamination is IsolationForest's one big dial: my prior on what fraction of
# data is anomalous. It sets the decision threshold, so it directly trades recall
# against precision. I try a range rather than trusting a default.
CONTAMINATION_GRID = [0.005, 0.01, 0.02, 0.03, 0.05]


def split_accounts(spend: pd.DataFrame, seed: int = 42) -> dict[str, list[int]]:
    """Partition ACCOUNTS (not rows) into train/val/test."""
    accounts = np.array(sorted(spend["account_id"].unique()))
    rng = np.random.default_rng(seed)
    rng.shuffle(accounts)

    n = len(accounts)
    n_train = int(n * 0.6)
    n_val = int(n * 0.2)
    return {
        "train": accounts[:n_train].tolist(),
        "val": accounts[n_train:n_train + n_val].tolist(),
        "test": accounts[n_train + n_val:].tolist(),
    }


def prepare(df: pd.DataFrame, account_ids: list[int], inject: bool, seed: int = 7):
    """Slice to some accounts, optionally inject anomalies, then build features.

    ORDER MATTERS: I inject BEFORE computing features, so the injected rows shift
    their account's history exactly the way a real transaction would. If I
    injected afterwards, the anomalies would carry hand-made feature values and I
    would be testing my imagination instead of my pipeline.
    """
    subset = df[df["account_id"].isin(account_ids)].copy()
    spend = build_transaction_features(subset)

    if not inject:
        spend["is_injected_anomaly"] = False
        spend["anomaly_kind"] = None
        return spend

    combined, _ = inject_anomalies(spend, n_per_kind=8, seed=seed)
    # Recompute features on the combined frame so injected rows get real,
    # history-aware feature values.
    with_features = build_transaction_features(combined)
    return with_features


def main() -> None:
    df = flag_recurring(load_transactions(
        DATA_DIR / "synthetic_transactions.csv",
        DATA_DIR / "transactions.csv",
    ))

    all_spend = build_transaction_features(df)
    splits = split_accounts(all_spend)
    print(f"accounts — train: {len(splits['train'])}, "
          f"val: {len(splits['val'])}, test: {len(splits['test'])}")

    # --- fit on clean training accounts -------------------------------------
    train = prepare(df, splits["train"], inject=False)
    train_matrix = train[ANOMALY_FEATURES].to_numpy(dtype=float)
    print(f"fitting on {len(train_matrix)} training transactions (no labels used)")

    # --- tune contamination on the validation accounts ----------------------
    val = prepare(df, splits["val"], inject=True, seed=7)
    val_matrix = val[ANOMALY_FEATURES].to_numpy(dtype=float)
    val_truth = val["is_injected_anomaly"].to_numpy()
    print(f"validation: {len(val_matrix)} transactions, {int(val_truth.sum())} injected anomalies")

    results = []
    for contamination in CONTAMINATION_GRID:
        model = IsolationForest(
            n_estimators=200,          # more trees = steadier scores; cheap here
            contamination=contamination,
            max_samples=256,           # the value the original paper recommends;
                                       # small subsamples are what make isolation work
            random_state=RANDOM_STATE,
            n_jobs=-1,
        )
        model.fit(train_matrix)
        # predict() returns -1 for outliers, 1 for inliers.
        flagged = model.predict(val_matrix) == -1
        precision, recall, f1, _ = precision_recall_fscore_support(
            val_truth, flagged, average="binary", zero_division=0
        )
        results.append({
            "contamination": contamination,
            "precision": round(float(precision), 3),
            "recall": round(float(recall), 3),
            "f1": round(float(f1), 3),
            "flag_rate": round(float(flagged.mean()), 4),
        })

    grid = pd.DataFrame(results)
    print("\ncontamination tuning (on validation accounts):")
    print(grid.to_string(index=False))

    # I select on F1 because I care about both mistakes. In a real bank the
    # choice would be a business one — a fraud team that reviews every flag by
    # hand wants precision; an automatic card-freeze wants recall. F1 is the
    # neutral default when nobody has told me the cost of each error.
    best = grid.loc[grid["f1"].idxmax()]
    best_contamination = float(best["contamination"])
    print(f"\nchose contamination={best_contamination} (best validation F1={best['f1']})")

    # --- refit at the chosen setting and save ------------------------------
    final_model = IsolationForest(
        n_estimators=200,
        contamination=best_contamination,
        max_samples=256,
        random_state=RANDOM_STATE,
        n_jobs=-1,
    )
    final_model.fit(train_matrix)

    MODEL_DIR.mkdir(exist_ok=True)
    joblib.dump(final_model, MODEL_DIR / "isoforest.pkl")

    meta = {
        "feature_order": ANOMALY_FEATURES,
        "contamination": best_contamination,
        "n_estimators": 200,
        "max_samples": 256,
        "zscore_baseline_threshold": 3.0,
        "trained_on_transactions": int(len(train_matrix)),
        "account_splits": splits,
        "contamination_grid": results,
        "note": (
            "Fitted on clean training accounts only. Contamination tuned on "
            "validation accounts with injected anomalies. Test accounts are "
            "untouched here — see evaluate.py and EVALUATION.md."
        ),
    }
    (MODEL_DIR / "anomaly_meta.json").write_text(json.dumps(meta, indent=2))
    print(f"saved isoforest.pkl and anomaly_meta.json to {MODEL_DIR}")


if __name__ == "__main__":
    main()
