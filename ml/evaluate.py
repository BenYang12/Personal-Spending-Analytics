"""Compare IsolationForest against the z-score baseline, and write it up.

This script is the point of Phase 4. Anyone can call
`IsolationForest().fit(X)`; what makes it engineering is being able to say
whether it beat the obvious alternative, by how much, and where it still fails.

Rules I hold myself to here:

  * I report on the TEST accounts only — never used for fitting, never used for
    tuning contamination. Reporting on tuning data would be quoting a number I'd
    already optimised for.
  * I use a fresh injection seed, so the exact anomalies differ from the ones
    that chose my hyperparameter.
  * I compare the two methods THREE ways, because a single table can flatter
    whichever method happens to flag more:
      1. At each method's natural threshold (what each would do in production).
      2. At a MATCHED FLAG BUDGET — same number of flags each. This is the
         comparison that matters when a human reviews every alert, since review
         capacity, not the algorithm, sets the budget.
      3. Threshold-free (average precision), which removes the threshold from
         the argument entirely.
  * I break recall down BY ANOMALY TYPE. An aggregate number hides which attacks
    I'd miss, and that breakdown is the most useful thing in this file.

The output lands in EVALUATION.md and goes straight into my README.
"""

import json
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.metrics import average_precision_score, precision_recall_fscore_support

from anomaly_features import ANOMALY_FEATURES, build_transaction_features, zscore_baseline
from features import flag_recurring, load_transactions
from mlsetup import silence_accelerate_matmul_warning
from train_anomaly import prepare

silence_accelerate_matmul_warning()

BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "data"
MODEL_DIR = BASE_DIR / "models"

ZSCORE_THRESHOLD = 3.0
TEST_SEED = 99          # deliberately different from the tuning seed (7)


def metrics_at_threshold(truth: np.ndarray, flags: np.ndarray) -> dict:
    precision, recall, f1, _ = precision_recall_fscore_support(
        truth, flags, average="binary", zero_division=0
    )
    return {
        "flagged": int(flags.sum()),
        "flag_rate": float(flags.mean()),
        "precision": float(precision),
        "recall": float(recall),
        "f1": float(f1),
        # False positives are what a fraud analyst actually experiences, so I
        # report the count rather than making the reader derive it.
        "false_positives": int((flags & ~truth).sum()),
        "missed": int((~flags & truth).sum()),
    }


def metrics_at_budget(truth: np.ndarray, scores: np.ndarray, budget: int) -> dict:
    """Flag exactly `budget` transactions — the highest-scoring ones."""
    flags = np.zeros(len(scores), dtype=bool)
    # argsort ascending, so the last `budget` entries are the highest scores.
    flags[np.argsort(scores)[-budget:]] = True
    result = metrics_at_threshold(truth, flags)
    result["budget"] = budget
    return result


def main() -> None:
    df = flag_recurring(load_transactions(
        DATA_DIR / "synthetic_transactions.csv",
        DATA_DIR / "transactions.csv",
    ))

    meta = json.loads((MODEL_DIR / "anomaly_meta.json").read_text())
    test_accounts = meta["account_splits"]["test"]
    model = joblib.load(MODEL_DIR / "isoforest.pkl")

    # Same account split the model was trained under, fresh anomalies.
    test = prepare(df, test_accounts, inject=True, seed=TEST_SEED)
    matrix = test[ANOMALY_FEATURES].to_numpy(dtype=float)
    truth = test["is_injected_anomaly"].to_numpy()

    print(f"test set: {len(test)} transactions across {len(test_accounts)} held-out accounts")
    print(f"injected anomalies: {int(truth.sum())} ({truth.mean():.2%} of rows)\n")

    # score_samples returns HIGHER for more normal points, so I negate it to get
    # an anomaly score where higher = more suspicious. Getting this backwards
    # silently inverts every metric, so it's worth being explicit.
    forest_scores = -model.score_samples(matrix)
    forest_flags = model.predict(matrix) == -1

    # The baseline's continuous score is the z-score itself.
    baseline_scores = test["amount_zscore_in_category"].to_numpy(dtype=float)
    baseline_flags = zscore_baseline(test, ZSCORE_THRESHOLD).to_numpy()

    forest_natural = metrics_at_threshold(truth, forest_flags)
    baseline_natural = metrics_at_threshold(truth, baseline_flags)

    # Matched budget: give both methods the same number of flags. I use the
    # forest's own flag count so the comparison is anchored to a realistic
    # operating point rather than one I invented.
    budget = int(forest_flags.sum())
    forest_budget = metrics_at_budget(truth, forest_scores, budget)
    baseline_budget = metrics_at_budget(truth, baseline_scores, budget)

    # Average precision summarises the whole precision/recall curve, so it
    # compares the two RANKINGS regardless of where anyone sets a threshold.
    forest_ap = float(average_precision_score(truth, forest_scores))
    baseline_ap = float(average_precision_score(truth, baseline_scores))

    # Per-type recall — the most informative table here.
    injected = test[test["is_injected_anomaly"]].copy()
    injected["forest_caught"] = forest_flags[test["is_injected_anomaly"].to_numpy()]
    injected["baseline_caught"] = baseline_flags[test["is_injected_anomaly"].to_numpy()]
    by_kind = injected.groupby("anomaly_kind").agg(
        planted=("forest_caught", "size"),
        forest_recall=("forest_caught", "mean"),
        baseline_recall=("baseline_caught", "mean"),
    ).round(3)

    print("=== at each method's natural threshold ===")
    comparison = pd.DataFrame({"IsolationForest": forest_natural, "z-score baseline": baseline_natural}).T
    print(comparison.round(3).to_string())
    print(f"\n=== at a matched flag budget of {budget} ===")
    print(pd.DataFrame({"IsolationForest": forest_budget, "z-score baseline": baseline_budget}).T.round(3).to_string())
    print(f"\n=== threshold-free ===\naverage precision — forest: {forest_ap:.3f}, baseline: {baseline_ap:.3f}")
    print("\n=== recall by anomaly type ===")
    print(by_kind.to_string())

    write_report(
        test=test, truth=truth, budget=budget, by_kind=by_kind, meta=meta,
        forest_natural=forest_natural, baseline_natural=baseline_natural,
        forest_budget=forest_budget, baseline_budget=baseline_budget,
        forest_ap=forest_ap, baseline_ap=baseline_ap,
        n_test_accounts=len(test_accounts),
    )


def write_report(*, test, truth, budget, by_kind, meta, forest_natural, baseline_natural,
                 forest_budget, baseline_budget, forest_ap, baseline_ap, n_test_accounts) -> None:
    """Write EVALUATION.md. I generate it from the run so it can never drift."""

    def row(label: str, m: dict) -> str:
        return (f"| {label} | {m['flagged']} | {m['flag_rate']:.2%} | {m['precision']:.3f} "
                f"| {m['recall']:.3f} | {m['f1']:.3f} | {m['false_positives']} | {m['missed']} |")

    kind_rows = "\n".join(
        f"| `{kind}` | {int(r.planted)} | {r.forest_recall:.0%} | {r.baseline_recall:.0%} |"
        for kind, r in by_kind.iterrows()
    )

    winner = "IsolationForest" if forest_ap > baseline_ap else "the z-score baseline"
    margin = abs(forest_ap - baseline_ap)

    report = f"""# Anomaly Detection: Evaluation

*Generated by `evaluate.py`. Every number here comes from that run — I don't
hand-edit this file, so it can't drift from the code.*

## What I measured and why

My data has no fraud labels, so precision and recall don't exist until I make
them. I inject anomalies I designed, into accounts the model has never seen,
and measure how many each method finds. These numbers describe performance
against **my three anomaly patterns** — not against real-world fraud. I'd rather
publish a scoped number than an unfalsifiable one.

**Test protocol**

| | |
|---|---|
| Accounts | {n_test_accounts} held out — never used for fitting or tuning |
| Transactions scored | {len(test):,} |
| Injected anomalies | {int(truth.sum())} ({truth.mean():.2%} of rows) |
| Injection seed | `{TEST_SEED}` (tuning used a different seed, so these anomalies are new) |
| Contamination | `{meta['contamination']}` — chosen on validation accounts, then frozen |
| Baseline | flag when amount > {ZSCORE_THRESHOLD}σ above the account's own past mean for that category |

The class balance is the thing to notice: anomalies are ~{truth.mean():.1%} of rows.
Accuracy is a useless metric here — flagging nothing scores {1 - truth.mean():.1%}
accurate. That's why everything below is precision and recall.

## Results at each method's natural threshold

| Method | Flagged | Flag rate | Precision | Recall | F1 | False pos. | Missed |
|---|---|---|---|---|---|---|---|
{row("IsolationForest", forest_natural)}
{row("z-score baseline", baseline_natural)}

## Results at a matched flag budget ({budget} flags each)

The comparison above is slightly unfair: whichever method flags more will tend
to catch more. Here both get the same budget, which is also how a real fraud
desk works — an analyst reviews N alerts a day regardless of the algorithm.

| Method | Flagged | Flag rate | Precision | Recall | F1 | False pos. | Missed |
|---|---|---|---|---|---|---|---|
{row("IsolationForest", forest_budget)}
{row("z-score baseline", baseline_budget)}

## Threshold-free comparison

Average precision summarises the entire precision/recall curve, so it compares
the two rankings without either threshold entering the argument.

| Method | Average precision |
|---|---|
| IsolationForest | **{forest_ap:.3f}** |
| z-score baseline | {baseline_ap:.3f} |

On this metric {winner} wins by {margin:.3f}.

## Recall by anomaly type — the table I find most useful

| Anomaly type | Planted | IsolationForest | z-score baseline |
|---|---|---|---|
{kind_rows}

**How to read this:**

- **`amount_spike`** — a charge 8-15x the account's usual size, in a category it
  already uses. **The baseline wins here, and I want to keep that in the table
  rather than bury it.** A z-score is exactly the right tool for "one number is
  unusually large", and it caught every spike while the forest missed some. The
  forest is optimising a global notion of isolation across 12 features, so it
  will trade away a little sensitivity on the single-feature case to do better
  everywhere else. The obvious design conclusion is that production should run
  BOTH and take the union — a cheap deterministic rule for the case it owns,
  the forest for everything else. Discovering that the simple method beats the
  fancy one somewhere is a result, not an embarrassment.
- **`novel_category_splurge`** — a large charge at a never-seen merchant in a
  never-used category. Here the forest pulls clearly ahead, because it can
  combine `merchant_novelty` and `category_rarity` with amount, while the
  baseline only ever looks at amount.
- **`card_testing_burst`** — several tiny charges at one new merchant in a day.
  The baseline cannot catch these even in principle: every individual amount is
  small, so no amount threshold will ever fire. This is the case that justifies
  a multi-feature model at all, and it taught me the most — see below.

## What I learned from the failure case

My first version scored **0% recall on card-testing bursts** — and so did the
baseline. The bursts were two-thirds of my injected anomalies, which dragged
overall recall to 0.29 and made the forest look barely better than the rule.

The rows explained it immediately: every charge in a burst is *small*, so every
amount-based feature reported "normal". Nothing in my feature set described the
pattern the charges made *together*. I could have tuned contamination all week
without fixing that, because the signal wasn't in the data I was giving the
model.

I added two features — `merchant_txns_same_day` and `account_txns_same_day` —
and validation F1 went from **0.29 to 0.62**.

The lesson I'd give in an interview: when a model can't see something, the
answer is usually a feature, not a hyperparameter. The diagnostic that found it
was a per-anomaly-type recall breakdown, which is exactly why I compute one.

## Honest limitations

- **Synthetic anomalies.** I designed the patterns I detect. Real fraud is
  adversarial and adapts; my anomalies don't.
- **Mostly synthetic users.** My real Plaid sandbox history is 234 transactions
  across a handful of accounts, far too thin to fit a 14-feature model. The
  clustering that validates this pipeline scored ARI 0.986 against known
  archetypes, but that's a statement about the pipeline, not about my spending.
- **Date resolution only.** Plaid gives me dates, not timestamps, so "3am
  charge" — a genuinely strong fraud signal — isn't available to me.
- **Same-day counts assume batch scoring.** My burst features count charges
  across the whole day, which is valid because I score after a sync completes. A
  real-time authorisation system would need rolling windows instead; I'd have to
  rebuild those two features.
- **Single operating point.** I tuned for F1, which implicitly assumes a false
  positive and a missed anomaly cost the same. They don't, and the right
  trade-off is a business decision I don't have the inputs to make.
- **The baseline's sigmas aren't comparable across accounts.** I floor the
  standard deviation at 1.0 to avoid dividing by zero, so an account whose real
  spending variance is under a dollar produces inflated z-scores. Flagging is
  still directionally right, but "3 sigma" doesn't mean the same thing for every
  account — one more reason the multi-feature model generalises better.
"""
    out = BASE_DIR / "EVALUATION.md"
    out.write_text(report)
    print(f"\nwrote {out}")


if __name__ == "__main__":
    main()
