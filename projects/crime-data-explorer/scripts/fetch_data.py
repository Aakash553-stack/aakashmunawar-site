"""Download a sample of Chicago PD incident data plus its reference tables.

Source: City of Chicago Data Portal (Socrata API)
  - Crimes - 2001 to Present      ijzp-q8t2
  - IUCR codes                    c7ck-438e
  - Community area boundaries     igwz-8jzy  (names only)
  - Police district boundaries    24zt-jpfn  (names only)

Writes CSVs into data/raw/. Only the standard library is used.

Usage:
    python scripts/fetch_data.py                     # default window
    python scripts/fetch_data.py --start 2025-01-01 --end 2026-01-01
"""

import argparse
import csv
import io
import time
import urllib.parse
import urllib.request
from pathlib import Path

BASE = "https://data.cityofchicago.org/resource"
RAW_DIR = Path(__file__).resolve().parent.parent / "data" / "raw"
PAGE_SIZE = 50_000

# The 24 most recent complete months at the time the project was built.
DEFAULT_START = "2024-09-01"
DEFAULT_END = "2026-09-01"  # exclusive

INCIDENT_COLUMNS = [
    "id", "case_number", "date", "block", "iucr", "primary_type",
    "description", "location_description", "arrest", "domestic", "beat",
    "district", "ward", "community_area", "fbi_code", "latitude",
    "longitude", "updated_on",
]


def fetch_csv(dataset, params, retries=3):
    url = f"{BASE}/{dataset}.csv?{urllib.parse.urlencode(params)}"
    for attempt in range(1, retries + 1):
        try:
            with urllib.request.urlopen(url, timeout=120) as resp:
                return resp.read().decode("utf-8")
        except OSError as exc:
            if attempt == retries:
                raise
            print(f"  request failed ({exc}); retrying in {2 * attempt}s")
            time.sleep(2 * attempt)


def download_incidents(start, end):
    out_path = RAW_DIR / "incidents.csv"
    where = f"date >= '{start}T00:00:00' AND date < '{end}T00:00:00'"
    offset = 0
    total = 0
    with out_path.open("w", newline="") as out:
        writer = csv.writer(out)
        writer.writerow(INCIDENT_COLUMNS)
        while True:
            text = fetch_csv("ijzp-q8t2", {
                "$select": ",".join(INCIDENT_COLUMNS),
                "$where": where,
                "$order": "id",
                "$limit": PAGE_SIZE,
                "$offset": offset,
            })
            rows = list(csv.reader(io.StringIO(text)))[1:]  # drop header
            if not rows:
                break
            writer.writerows(rows)
            total += len(rows)
            offset += PAGE_SIZE
            print(f"  incidents: {total:,} rows")
            if len(rows) < PAGE_SIZE:
                break
    return out_path, total


def download_lookup(dataset, columns, filename):
    text = fetch_csv(dataset, {"$select": ",".join(columns), "$limit": 10_000})
    out_path = RAW_DIR / filename
    out_path.write_text(text)
    return out_path, text.count("\n") - 1


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--start", default=DEFAULT_START, help="inclusive, YYYY-MM-DD")
    parser.add_argument("--end", default=DEFAULT_END, help="exclusive, YYYY-MM-DD")
    args = parser.parse_args()

    RAW_DIR.mkdir(parents=True, exist_ok=True)
    print(f"Downloading incidents from {args.start} to {args.end} (exclusive)")
    path, n = download_incidents(args.start, args.end)
    print(f"  -> {path.name}: {n:,} rows")

    lookups = [
        ("c7ck-438e", ["iucr", "primary_description", "secondary_description",
                       "index_code", "active"], "iucr_codes.csv"),
        ("igwz-8jzy", ["area_numbe", "community"], "community_areas.csv"),
        ("24zt-jpfn", ["dist_num", "dist_label"], "districts.csv"),
    ]
    for dataset, columns, filename in lookups:
        path, n = download_lookup(dataset, columns, filename)
        print(f"  -> {path.name}: {n:,} rows")


if __name__ == "__main__":
    main()
