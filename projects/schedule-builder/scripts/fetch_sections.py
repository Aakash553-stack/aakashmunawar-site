"""Download course sections from the Rutgers Schedule of Classes API.

Source: https://classes.rutgers.edu/soc/api/courses.json (public, no auth).
The API returns the whole campus catalog for a term (it ignores a subject
filter), so this script downloads it once and keeps only the requested
subjects and the fields the schedule builder needs.

Term codes: 0 = Winter, 1 = Spring, 7 = Summer, 9 = Fall.

Usage:
    python scripts/fetch_sections.py                          # Fall 2026, CS + Math
    python scripts/fetch_sections.py --year 2027 --term 1 --subjects 198 640
"""

import argparse
import gzip
import json
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

API_URL = "https://classes.rutgers.edu/soc/api/courses.json"
USER_AGENT = "schedule-builder/1.0 (student portfolio project)"
DATA_DIR = Path(__file__).resolve().parent.parent / "data"
TERM_NAMES = {"0": "Winter", "1": "Spring", "7": "Summer", "9": "Fall"}
DAY_NAMES = {"M": "Mon", "T": "Tue", "W": "Wed", "H": "Thu", "F": "Fri", "S": "Sat", "U": "Sun"}


def fetch_catalog(year, term, campus):
    query = urllib.parse.urlencode({"year": year, "term": term, "campus": campus})
    req = urllib.request.Request(f"{API_URL}?{query}", headers={
        "User-Agent": USER_AGENT, "Accept-Encoding": "gzip"})
    with urllib.request.urlopen(req, timeout=180) as resp:
        body = resp.read()
        if resp.headers.get("Content-Encoding") == "gzip":
            body = gzip.decompress(body)
    return json.loads(body)


def to_minutes(military):
    """'1550' -> 950 (minutes after midnight)."""
    return int(military[:2]) * 60 + int(military[2:])


def slim_meeting(m):
    timed = bool(m.get("meetingDay") and m.get("startTimeMilitary") and m.get("endTimeMilitary"))
    building = m.get("buildingCode") or ""
    room = m.get("roomNumber") or ""
    return {
        "type": m.get("meetingModeDesc") or "",
        "day": DAY_NAMES.get(m.get("meetingDay")) if timed else None,
        "start": to_minutes(m["startTimeMilitary"]) if timed else None,
        "end": to_minutes(m["endTimeMilitary"]) if timed else None,
        "campus": (m.get("campusName") or "").title() or None,
        "location": f"{building}-{room}" if building and room else (building or None),
    }


def slim_course(c):
    return {
        "code": f"{c['subject']}:{c['courseNumber']}",
        "title": c.get("expandedTitle", "").strip() or c["title"],
        "credits": c.get("credits"),
        "sections": [
            {
                "section": s["number"],
                "index": s["index"],
                "open": s["openStatus"],
                "instructors": s.get("instructorsText") or "",
                "eligibility": s.get("sectionEligibility") or "",
                "meetings": [slim_meeting(m) for m in s["meetingTimes"]],
            }
            for s in c["sections"]
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--year", default="2026")
    parser.add_argument("--term", default="9", choices=TERM_NAMES)
    parser.add_argument("--campus", default="NB")
    parser.add_argument("--subjects", nargs="+", default=["198", "640"])
    args = parser.parse_args()

    catalog = fetch_catalog(args.year, args.term, args.campus)
    if not catalog:
        raise SystemExit("The API returned no courses for that term; it may not be published yet.")
    courses = [slim_course(c) for c in catalog if c["subject"] in args.subjects]
    courses.sort(key=lambda c: c["code"])

    term_name = f"{TERM_NAMES[args.term]} {args.year}"
    out = DATA_DIR / f"sections_{TERM_NAMES[args.term].lower()}{args.year}.json"
    DATA_DIR.mkdir(exist_ok=True)
    out.write_text(json.dumps({
        "source": API_URL,
        "term": term_name,
        "campus": args.campus,
        "subjects": args.subjects,
        "retrieved_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "note": "Times are minutes after midnight, local time. 'open' is the "
                "enrollment status at retrieval time and changes constantly.",
        "courses": courses,
    }, indent=1) + "\n")

    sections = sum(len(c["sections"]) for c in courses)
    print(f"{term_name}: kept {len(courses)} courses / {sections} sections "
          f"(of {len(catalog)} courses in the catalog) -> {out}")


if __name__ == "__main__":
    main()
