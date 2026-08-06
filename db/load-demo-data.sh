#!/usr/bin/env bash
set -e

# Demo transactions are intentionally opt-in. PostgreSQL runs this file only
# when it initializes a fresh data volume; normal first-run users start with an
# empty account list and are invited to connect through Plaid instead.
if [ "${LEDGERLENS_DEMO_DATA:-false}" != "true" ]; then
  echo "Ledger Lens demo data disabled; starting with an empty database."
  exit 0
fi

echo "Loading Ledger Lens demo accounts and transactions."
psql --set ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --file /demo/seed.sql
