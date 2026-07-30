# `/ml` — Offline training pipeline

Everything here runs on my laptop, never in a request. It reads a CSV snapshot of
my transactions and writes fitted models to `models/`, which my FastAPI scoring
service (Phase 5) loads at startup.

That separation is the architectural point of the whole project: **training is
slow, stateful and needs the full dataset; scoring is fast, stateless and needs
one row.** Keeping them apart means my API never waits on model fitting, and I
can retrain without redeploying anything.

## Setup

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
cp .env.example .env          # then set LEDGERLENS_API_KEY to match the backend
```

## Running the pipeline

Order matters — each step consumes the previous one's output.

```bash
.venv/bin/python export.py          # 1. pull my real transactions from the Spring API
.venv/bin/python synthesize.py      # 2. generate synthetic users with KNOWN archetypes
.venv/bin/python features.py        # 3. build per-(account, month) behaviour vectors
.venv/bin/python train_clusters.py  # 4. fit scaler + KMeans, name the archetypes
.venv/bin/python train_anomaly.py   # 5. fit IsolationForest, tune contamination
.venv/bin/python evaluate.py        # 6. compare against the baseline -> EVALUATION.md
.venv/bin/python -m pytest tests/   #    28 tests on the feature engineering
```

The backend must be running for step 1 (`cd ../backend && ./gradlew bootRun`).

## What each module does

| File | Role |
|---|---|
| `export.py` | Pulls `/api/export/transactions.csv`. Read-only; Python never touches Postgres. |
| `synthesize.py` | Generates 60 users across 5 designed archetypes, with ground-truth labels held in a separate file. |
| `categories.py` | Reconciles my two category vocabularies (`Groceries` vs `FOOD_AND_DRINK`) into one canonical taxonomy. |
| `features.py` | Monthly behaviour vectors — 14 features, almost all ratios. |
| `archetypes.py` | Rule-based centroid readout: cluster → human name + the evidence for it. |
| `train_clusters.py` | StandardScaler + KMeans, k chosen by silhouette, validated by ARI. |
| `anomaly_features.py` | 12 per-transaction features, all causal (past-only). |
| `inject.py` | Plants labelled anomalies of three kinds so precision/recall exist at all. |
| `train_anomaly.py` | IsolationForest; contamination tuned on a validation split. |
| `evaluate.py` | The comparison table. Generates `EVALUATION.md`. |

## Results

**Clustering** — k=6 by silhouette (0.487). Validated against the 5 known
synthetic archetypes: **ARI 0.986, purity 0.994**. Unsupervised learning normally
gives no way to check whether clusters mean anything; because I generated users
whose behaviour I chose, I get a real number instead of a vibe.

**Anomaly detection** — on held-out test accounts with freshly injected anomalies:

| Method | Precision | Recall | F1 | Avg. precision |
|---|---|---|---|---|
| IsolationForest | 0.645 | 0.833 | **0.727** | **0.794** |
| z-score baseline | 0.333 | 0.292 | 0.311 | 0.271 |

Full protocol, per-anomaly-type breakdown, and limitations: **[EVALUATION.md](EVALUATION.md)**.

## Two design decisions I'd defend in an interview

**Features are ratios, not dollar amounts.** If I fed raw totals to KMeans, my
clusters would be "rich / medium / poor" — true, useless, and something a SQL
`GROUP BY` already tells me. Ratios make a $2k/month student and a $12k/month
engineer land in the same archetype when they *behave* the same way.

**Fixed vs discretionary spending are separated.** My first version didn't do
this, and housing came out at 55% of all spend, so every "behaviour" ratio was
really measuring someone's rent. Rent now gets exactly one feature
(`fixed_share`) and stays out of the rest.

## The bug worth reading about

My first anomaly model scored **0% recall on card-testing bursts** (several tiny
charges at one new merchant in a day) — and so did the baseline. Those bursts
were two-thirds of my injected anomalies, dragging overall recall to 0.29.

The rows explained it instantly: each charge was *small*, so every amount-based
feature said "normal", and nothing described the pattern the charges made
*together*. No amount of hyperparameter tuning fixes a signal that isn't in the
features. Adding `merchant_txns_same_day` and `account_txns_same_day` took
validation F1 from **0.29 to 0.62**.

The diagnostic that found it was the per-anomaly-type recall breakdown, which is
why `evaluate.py` always prints one.

## Honesty notes

- Most training data is **synthetic**. My real Plaid sandbox history is 234
  transactions across a handful of accounts — 27 account-months, only 10 with
  more than 8 transactions. Fitting a 14-feature, 6-cluster model on that would
  be theatre. The synthetic users validate the *pipeline*; my real months are then
  scored by the model it produces.
- The anomalies I detect are ones **I designed**. Real fraud is adversarial and
  adapts.
- Plaid gives dates, not timestamps, so time-of-day — a strong fraud signal —
  isn't available to me.
