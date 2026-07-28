-- ============ SOURCE OF TRUTH (immutable events) ============
-- In banking, transactions is the source of truth, facts from the bank, never edited by my analytics
-- Keeping derived data in separate tables (at bottom block) is what makes that safe. 
-- Two more financial-data rules -> money is NUMERIC (never floats), and every transaction carries a unique external ID

-- SQL files build the database
-- entities describe tables to Hibernate. 
CREATE TABLE accounts (
    id                 BIGSERIAL PRIMARY KEY,   -- our internal id, auto-increment
    plaid_account_id   TEXT UNIQUE,             -- NULL until a Plaid account is linked (Step 7)
    plaid_access_token TEXT,                    -- sandbox credential; lives ONLY in this db,
                                                -- never returned by any API endpoint
    name               TEXT NOT NULL,
    type               TEXT NOT NULL,           -- 'checking' | 'savings' | 'credit'
    sync_cursor        TEXT,                    -- Plaid /transactions/sync resume point (Step 8)
    sync_status        TEXT NOT NULL DEFAULT 'IDLE',  -- async refresh state (Step 9)
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transactions (
    id                   BIGSERIAL PRIMARY KEY,
    -- The idempotency key: Plaid's stable id per transaction. UNIQUE means a
    -- re-sync can "upsert" (insert-or-update) instead of duplicating rows.
    plaid_transaction_id TEXT NOT NULL UNIQUE,
    account_id           BIGINT NOT NULL REFERENCES accounts(id), --foreign key here!
    posted_date          DATE NOT NULL,
    -- NUMERIC(12,2) = exact decimal, 2 places. NEVER float/double for money:
    -- binary floats can't represent 0.10 exactly, and cents would drift.
    amount               NUMERIC(12,2) NOT NULL,   -- positive = money out (Plaid's convention)
    merchant             TEXT NOT NULL,
    category             TEXT NOT NULL,
    pending              BOOLEAN NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Nearly every query is "this account, this date range" — index that shape.
CREATE INDEX idx_txn_account_date ON transactions (account_id, posted_date);


-- ============ DERIVED (rebuildable from transactions at any time) ============
-- things like monthly_features and model_scores are derived: computed from transactions
-- They ccan be deleted and rebuilt at any time without losing anything
CREATE TABLE monthly_features (
    id          BIGSERIAL PRIMARY KEY,
    account_id  BIGINT NOT NULL REFERENCES accounts(id),
    month       DATE NOT NULL,          -- first day of the month, e.g. 2026-03-01
    -- JSONB instead of one column per feature: the ML feature set will evolve
    -- (Steps 12, 15) and we don't want a schema migration per experiment.
    -- Tradeoff: no type checking inside the blob — fine for derived data.
    features    JSONB NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, month)          -- recompute replaces, never duplicates
);

CREATE TABLE model_scores (
    id           BIGSERIAL PRIMARY KEY,
    -- One table scores two kinds of subject: a single transaction (anomaly)
    -- or an account-month (archetype). subject_type says which; subject_id
    -- points at transactions.id or monthly_features.id accordingly.
    subject_type TEXT NOT NULL,         -- 'TRANSACTION' | 'MONTH'
    subject_id   BIGINT NOT NULL,
    model_name   TEXT NOT NULL,         -- versioned: 'kmeans_v1', 'isoforest_v1' —
                                        -- retraining writes new rows, old scores stay comparable
    score        NUMERIC,               -- raw model output
    label        TEXT,                  -- human-readable: archetype name, or 'ANOMALY'
    scored_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (subject_type, subject_id, model_name)   -- re-scoring replaces
);

