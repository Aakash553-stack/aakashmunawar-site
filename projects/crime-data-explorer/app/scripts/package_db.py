"""Compress the project's crime.db into backend/data/crime.db.gz for deployment.

Run after scripts/fetch_data.py and scripts/load_data.py (from the project
root) whenever the data is refreshed, then commit the new crime.db.gz.
"""

import gzip
import shutil
import sqlite3
from pathlib import Path

APP = Path(__file__).resolve().parent.parent
SOURCE = APP.parent / "crime.db"
TARGET = APP / "backend" / "data" / "crime.db.gz"


def main():
    if not SOURCE.exists():
        raise SystemExit(f"{SOURCE} not found. Build it with scripts/load_data.py first.")
    with sqlite3.connect(SOURCE) as conn:
        rows = conn.execute("SELECT COUNT(*) FROM incidents").fetchone()[0]
        ok = conn.execute("PRAGMA integrity_check").fetchone()[0]
    if ok != "ok":
        raise SystemExit(f"integrity_check failed: {ok}")
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    # mtime=0 keeps the archive byte-identical for identical input.
    with SOURCE.open("rb") as src, TARGET.open("wb") as raw, \
            gzip.GzipFile(filename="crime.db", mode="wb", compresslevel=9, fileobj=raw, mtime=0) as dst:
        shutil.copyfileobj(src, dst, length=4 * 1024 * 1024)
    print(f"{rows:,} incidents: {SOURCE.stat().st_size / 1e6:.1f} MB -> "
          f"{TARGET.relative_to(APP)} {TARGET.stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
