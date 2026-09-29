"""Build step (run by Vercel after installing dependencies; also fine locally).

1. Unpack backend/data/crime.db.gz -> backend/data/crime.db, so the function
   can open the database straight from its bundle.
2. Copy the project's sql/queries.sql into backend/sql/, so the analyses the
   API runs ship with the function. Vercel's project root is this app/
   folder, and files outside it are only guaranteed during the build.
"""

import gzip
import shutil
from pathlib import Path

HERE = Path(__file__).resolve().parent
PACKED = HERE / "backend" / "data" / "crime.db.gz"
UNPACKED = HERE / "backend" / "data" / "crime.db"
QUERIES_SRC = HERE.parent / "sql" / "queries.sql"
QUERIES_DST = HERE / "backend" / "sql" / "queries.sql"


def main():
    if not PACKED.exists():
        raise SystemExit(f"Missing {PACKED}. Run scripts/package_db.py first.")
    with gzip.open(PACKED, "rb") as src, UNPACKED.open("wb") as dst:
        shutil.copyfileobj(src, dst, length=4 * 1024 * 1024)
    print(f"Unpacked {UNPACKED.name}: {UNPACKED.stat().st_size / 1e6:.1f} MB")

    if not QUERIES_SRC.exists():
        raise SystemExit(f"Missing {QUERIES_SRC}. In Vercel, enable 'Include files "
                         "outside the root directory in the Build Step'.")
    QUERIES_DST.parent.mkdir(exist_ok=True)
    shutil.copyfile(QUERIES_SRC, QUERIES_DST)
    print(f"Copied {QUERIES_SRC.name}")


if __name__ == "__main__":
    main()
