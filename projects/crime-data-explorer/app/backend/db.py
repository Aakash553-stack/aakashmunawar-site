"""Locate crime.db and open read-only connections to it.

Where the database comes from, in order:

1. $CRIME_DB, if set.
2. backend/data/crime.db, which the Vercel build step (build.py) unpacks
   from the committed crime.db.gz.
3. The project's own crime.db, built by scripts/load_data.py (local dev).
4. backend/data/crime.db.gz, unpacked once into the temp directory. This is
   a fallback in case a deployment ships without the unpacked file.
"""

import gzip
import os
import shutil
import sqlite3
import tempfile
import threading
from contextlib import contextmanager
from pathlib import Path

HERE = Path(__file__).resolve().parent
PACKED_DB = HERE / "data" / "crime.db.gz"
BUNDLED_DB = HERE / "data" / "crime.db"
PROJECT_DB = HERE.parent.parent / "crime.db"

_lock = threading.Lock()
_resolved = None


def database_path():
    global _resolved
    if _resolved:
        return _resolved
    with _lock:
        if _resolved:
            return _resolved
        if os.environ.get("CRIME_DB"):
            path = Path(os.environ["CRIME_DB"])
        elif BUNDLED_DB.exists():
            path = BUNDLED_DB
        elif PROJECT_DB.exists():
            path = PROJECT_DB
        elif PACKED_DB.exists():
            path = Path(tempfile.gettempdir()) / "crime.db"
            if not path.exists():
                partial = path.with_suffix(".partial")
                with gzip.open(PACKED_DB, "rb") as src, partial.open("wb") as dst:
                    shutil.copyfileobj(src, dst, length=4 * 1024 * 1024)
                partial.replace(path)
        else:
            raise FileNotFoundError(
                "No crime.db found. Run scripts/fetch_data.py and scripts/load_data.py, "
                "or set CRIME_DB.")
        if not path.exists():
            raise FileNotFoundError(f"CRIME_DB points to a missing file: {path}")
        _resolved = path
        return path


@contextmanager
def connect():
    """A read-only connection, closed when the `with` block ends.

    `immutable=1` tells SQLite the file never changes, so it skips locking,
    which also makes it work on a read-only filesystem.
    """
    uri = f"file:{database_path()}?mode=ro&immutable=1"
    conn = sqlite3.connect(uri, uri=True)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
    finally:
        conn.close()
