"""Load the raw Chicago CSVs into a normalized SQLite database.

Reads data/raw/*.csv (produced by fetch_data.py), (re)creates the tables in
sql/schema.sql, and writes crime.db in the project root. Safe to re-run: the
schema drops and recreates every table.

Usage:
    python scripts/load_data.py
"""

import csv
import sqlite3
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RAW_DIR = ROOT / "data" / "raw"
SCHEMA = ROOT / "sql" / "schema.sql"
DB_PATH = ROOT / "crime.db"


def read_csv(name):
    with (RAW_DIR / name).open(newline="") as f:
        return list(csv.DictReader(f))


def to_bool(value):
    return 1 if value.strip().lower() == "true" else 0


def to_int(value):
    """'004' -> 4, '' -> None."""
    value = value.strip()
    return int(value) if value else None


def to_float(value):
    value = value.strip()
    return float(value) if value else None


def to_timestamp(value):
    """'2024-09-01T13:05:00.000' -> '2024-09-01 13:05:00'."""
    return value.replace("T", " ")[:19]


def load(conn):
    incidents = read_csv("incidents.csv")
    iucr_lookup = read_csv("iucr_codes.csv")
    area_lookup = read_csv("community_areas.csv")
    district_lookup = read_csv("districts.csv")

    # -- crime_categories: every primary type in the lookup or the incidents --
    category_names = sorted(
        {r["primary_description"] for r in iucr_lookup}
        | {r["primary_type"] for r in incidents}
    )
    conn.executemany(
        "INSERT INTO crime_categories (name) VALUES (?)",
        [(name,) for name in category_names],
    )
    category_id = dict(conn.execute("SELECT name, category_id FROM crime_categories"))

    # -- incident_types: official IUCR list; FBI code comes from the incidents --
    fbi_code = {r["iucr"]: r["fbi_code"] for r in incidents}
    types = {
        r["iucr"]: (
            r["iucr"],
            category_id[r["primary_description"]],
            r["secondary_description"],
            fbi_code.get(r["iucr"]),
            1 if r["index_code"] == "I" else 0,
            to_bool(r["active"]),
        )
        for r in iucr_lookup
    }
    # Any code used in the incidents but missing from the lookup (none as of
    # the 2026 download) is added from the incident's own fields.
    for r in incidents:
        if r["iucr"] not in types:
            types[r["iucr"]] = (r["iucr"], category_id[r["primary_type"]],
                                r["description"], r["fbi_code"], 0, 1)
    conn.executemany("INSERT INTO incident_types VALUES (?, ?, ?, ?, ?, ?)",
                     types.values())

    # -- districts: boundary file names, plus any district only seen in data --
    districts = {int(r["dist_num"]): r["dist_label"] for r in district_lookup}
    beat_ids = {int(r["beat"]) for r in incidents}
    for d in {int(r["district"]) for r in incidents} | {b // 100 for b in beat_ids}:
        districts.setdefault(d, None)
    conn.executemany("INSERT INTO districts VALUES (?, ?)", sorted(districts.items()))

    # -- beats: home district is encoded in the beat number (0421 -> 4) --
    conn.executemany("INSERT INTO beats VALUES (?, ?)",
                     [(b, b // 100) for b in sorted(beat_ids)])

    # -- community_areas --
    conn.executemany(
        "INSERT INTO community_areas VALUES (?, ?)",
        sorted((int(r["area_numbe"]), r["community"]) for r in area_lookup),
    )
    valid_areas = {int(r["area_numbe"]) for r in area_lookup}

    # -- location_types --
    conn.executemany(
        "INSERT INTO location_types (name) VALUES (?)",
        [(name,) for name in sorted({r["location_description"] for r in incidents}
                                    - {""})],
    )
    location_type_id = dict(conn.execute("SELECT name, location_type_id FROM location_types"))

    # -- incidents --
    def incident_row(r):
        area = to_int(r["community_area"])
        return (
            int(r["id"]),
            r["case_number"],
            to_timestamp(r["date"]),
            r["iucr"],
            location_type_id.get(r["location_description"]),
            int(r["district"]),
            int(r["beat"]),
            area if area in valid_areas else None,  # '0' / blank -> unknown
            to_int(r["ward"]),
            r["block"],
            to_float(r["latitude"]),
            to_float(r["longitude"]),
            to_bool(r["arrest"]),
            to_bool(r["domestic"]),
            to_timestamp(r["updated_on"]),
        )

    conn.executemany(
        "INSERT INTO incidents VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        map(incident_row, incidents),
    )
    return len(incidents)


def main():
    started = time.perf_counter()
    DB_PATH.unlink(missing_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.executescript(SCHEMA.read_text())

    with conn:  # one transaction for the whole load
        csv_rows = load(conn)

    problems = conn.execute("PRAGMA foreign_key_check").fetchall()
    if problems:
        raise SystemExit(f"Foreign key violations: {problems[:10]}")
    loaded = conn.execute("SELECT COUNT(*) FROM incidents").fetchone()[0]
    if loaded != csv_rows:
        raise SystemExit(f"Row count mismatch: CSV {csv_rows:,} vs table {loaded:,}")

    conn.execute("ANALYZE")
    print(f"Loaded {DB_PATH.name} in {time.perf_counter() - started:.1f}s")
    for (table,) in conn.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
    ).fetchall():
        count = conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        print(f"  {table:<18} {count:>9,}")
    conn.close()


if __name__ == "__main__":
    main()
