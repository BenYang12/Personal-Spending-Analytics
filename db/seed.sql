-- ML phases can only find patterns that exist, so I ned to plant them
-- fixed-amount monthly subscriptions, weekday coffee and weekend dining habits, steady groceries, rent, payroll -- and exactly one planted anomaly
-- Instead of hand-typing 300 INSERT rows, I should write generative SQL

-- Reproducible randomness: same seed -> same "random" data on every rebuild,
-- so ML experiments in Phases 3-4 are comparable across database resets.
SELECT setseed(0.42);

-- Two accounts: daily spending from checking, habits/fun on the credit card.
INSERT INTO accounts (name, type) VALUES
    ('Everyday Checking', 'checking'),   -- id 1 (BIGSERIAL starts at 1)
    ('Rewards Credit',    'credit');     -- id 2

-- SUBSCRIPTIONS: same merchant, same amount, same day each month — the exact
-- (merchant, amount, cadence) signature Step 12's recurring-charge rule hunts.
-- CROSS JOIN = every merchant paired with every month: 4 x 6 = 24 rows.
INSERT INTO transactions (plaid_transaction_id, account_id, posted_date, amount, merchant, category)
SELECT 'seed-sub-' || s.merchant || '-' || to_char(d, 'YYYY-MM'),
       2,
       (d + (s.day - 1) * interval '1 day')::date,
       s.amount,
       s.merchant,
       'Subscriptions'
FROM (VALUES ('Netflix', 15.49, 3),
             ('Spotify', 11.99, 7),
             ('iCloud',   2.99, 12),
             ('Planet Fitness', 24.99, 15)) AS s(merchant, amount, day)
CROSS JOIN generate_series('2026-01-01'::date, '2026-06-01'::date, interval '1 month') AS d;

-- RENT: 1st of each month, fixed. PAYROLL: 25th, NEGATIVE amount = money IN
-- (Plaid's convention: positive is outflow — we adopt it now so Step 8's real
-- Plaid data needs zero changes).
INSERT INTO transactions (plaid_transaction_id, account_id, posted_date, amount, merchant, category)
SELECT 'seed-rent-' || to_char(d, 'YYYY-MM'), 1, d::date, 1250.00, 'Oak Street Apartments', 'Rent'
FROM generate_series('2026-01-01'::date, '2026-06-01'::date, interval '1 month') AS d;

INSERT INTO transactions (plaid_transaction_id, account_id, posted_date, amount, merchant, category)
SELECT 'seed-pay-' || to_char(d, 'YYYY-MM'), 1,
       (d + interval '24 days')::date, -2150.00, 'Acme Corp Payroll', 'Income'
FROM generate_series('2026-01-01'::date, '2026-06-01'::date, interval '1 month') AS d;


-- GROCERIES: roughly every 3-4 days (random() < 0.28 keeps ~28% of days),
-- amount varies $35-90, merchant picked randomly from an array.
-- round(...::numeric, 2) because money is exact decimals, never floats.
INSERT INTO transactions (plaid_transaction_id, account_id, posted_date, amount, merchant, category)
SELECT 'seed-groc-' || to_char(d, 'YYYY-MM-DD'),
       1,
       d::date,
       round((35 + random() * 55)::numeric, 2),
       (ARRAY['Harris Teeter', 'Trader Joes', 'Food Lion'])[1 + floor(random() * 3)::int],
       'Groceries'
FROM generate_series('2026-01-01'::date, '2026-06-30'::date, interval '1 day') AS d
WHERE random() < 0.28;

-- COFFEE: weekdays only. isodow = ISO day of week, Mon=1 .. Sun=7, so < 6
-- means Mon-Fri. About half of all weekdays, small amounts — high frequency,
-- low ticket size (a distinct behavioral signature for clustering).
INSERT INTO transactions (plaid_transaction_id, account_id, posted_date, amount, merchant, category)
SELECT 'seed-cof-' || to_char(d, 'YYYY-MM-DD'),
       2,
       d::date,
       round((4.50 + random() * 3)::numeric, 2),
       'Blue Bottle Coffee',
       'Coffee'
FROM generate_series('2026-01-01'::date, '2026-06-30'::date, interval '1 day') AS d
WHERE extract(isodow FROM d) < 6 AND random() < 0.5;

-- WEEKEND DINING: Fri-Sun (isodow >= 5), bigger tickets. This plants the
-- weekend-vs-weekday signal that becomes a clustering feature in Step 12.
INSERT INTO transactions (plaid_transaction_id, account_id, posted_date, amount, merchant, category)
SELECT 'seed-dine-' || to_char(d, 'YYYY-MM-DD'),
       2,
       d::date,
       round((25 + random() * 70)::numeric, 2),
       (ARRAY['Bartaco', 'Sushi Nine', 'Chipotle', 'Cookout'])[1 + floor(random() * 4)::int],
       'Dining'
FROM generate_series('2026-01-01'::date, '2026-06-30'::date, interval '1 day') AS d
WHERE extract(isodow FROM d) >= 5 AND random() < 0.6;


-- THE PLANTED ANOMALY: one huge charge, novel merchant, novel category, on
-- the card that otherwise averages ~$30/transaction. In Step 17 we measure
-- whether IsolationForest and the z-score baseline both catch it.
-- Keep this id memorable: 'seed-anomaly-1' is our ground-truth label.
INSERT INTO transactions (plaid_transaction_id, account_id, posted_date, amount, merchant, category)
VALUES ('seed-anomaly-1', 2, '2026-05-17', 1899.00, 'TechWorld Electronics', 'Shopping');


