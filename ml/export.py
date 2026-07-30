"""Pull a snapshot of my transactions out of the Spring API into a CSV.

This is the only place my ML code talks to my backend, and it only ever READS.
I deliberately did not give Python a database connection: my Postgres is the
source of truth for money, and a training script that could write to it is a
risk.
"""

import os
import sys
from pathlib import Path

import requests

DATA_DIR = Path(__file__).parent / "data"
OUT_FILE = DATA_DIR / "transactions.csv"


def load_env() -> tuple[str, str]:
    """Read my API URL and key from .env (or the real environment).

    I parse .env by hand instead of adding python-dotenv — it's four lines and
    one less dependency to justify. Real environment variables win over the
    file, which matches how my Spring config behaves.
    """
    env_path = Path(__file__).parent / ".env"
    values: dict[str, str] = {}
    if env_path.exists():
        for line in env_path.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, _, value = line.partition("=")
                values[key.strip()] = value.strip()

    url = os.environ.get("LEDGERLENS_API_URL", values.get("LEDGERLENS_API_URL", ""))
    key = os.environ.get("LEDGERLENS_API_KEY", values.get("LEDGERLENS_API_KEY", ""))
    if not url or not key:
        sys.exit("I need LEDGERLENS_API_URL and LEDGERLENS_API_KEY (copy .env.example to .env)")
    return url.rstrip("/"), key


def main() -> None:
    url, key = load_env()
    endpoint = f"{url}/api/export/transactions.csv"
    print(f"fetching {endpoint}")

    # I send the same X-API-KEY header my Spring filter checks. My export is
    # behind that gate like everything else — I didn't punch a hole in my own
    # auth just to make the ML pipeline convenient.
    response = requests.get(endpoint, headers={"X-API-KEY": key}, timeout=30)

    if response.status_code == 401:
        sys.exit("401 from the API — my LEDGERLENS_API_KEY doesn't match the backend's")
    response.raise_for_status()

    DATA_DIR.mkdir(exist_ok=True)
    OUT_FILE.write_text(response.text)

    # -1 for the header row. I print the count because a silent zero-row export
    # would send me debugging the model when the real problem was empty input.
    rows = len(response.text.strip().splitlines()) - 1
    print(f"wrote {rows} transactions to {OUT_FILE}")
    if rows == 0:
        sys.exit("exported 0 rows — is my database seeded and synced?")


if __name__ == "__main__":
    main()
