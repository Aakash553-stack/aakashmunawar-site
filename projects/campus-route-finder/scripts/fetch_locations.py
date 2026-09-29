"""Fetch real coordinates for Rutgers-New Brunswick locations from OpenStreetMap.

The list below names each location by its OpenStreetMap element (type + id).
This script asks the Overpass API for those exact elements and writes their
coordinates, as OSM has them, to data/locations.json. Nothing is typed in by
hand except which elements to use and how to categorize them.

For buildings (OSM ways/relations) the coordinate is the center of the
building's bounding box, as computed by Overpass (`out center`).

Map data (c) OpenStreetMap contributors, available under the Open Database
License (ODbL): https://www.openstreetmap.org/copyright

Usage:
    python scripts/fetch_locations.py
"""

import json
import urllib.parse
import urllib.request
from datetime import date
from pathlib import Path

OVERPASS_URL = "https://overpass-api.de/api/interpreter"
USER_AGENT = "campus-route-finder/1.0 (student portfolio project)"
OUT_PATH = Path(__file__).resolve().parent.parent / "data" / "locations.json"

# (OSM element, category, campus, short label for display)
# Category and campus are assigned here; OSM tags for these are inconsistent.
# The label is only a shorter display name; the full OSM name is kept too.
LOCATIONS = [
    # College Avenue campus
    ("relation/19181000", "dining",         "College Avenue", "Brower Commons"),
    ("way/251381216",     "library",        "College Avenue", "Alexander Library"),
    ("way/251382259",     "student_center", "College Avenue", "College Ave Student Center"),
    ("way/251382286",     "academic",       "College Avenue", "Scott Hall"),
    ("way/251382144",     "academic",       "College Avenue", "Murray Hall"),
    ("node/2924047495",   "bus_stop",       "College Avenue", "The Yard (bus stop)"),
    # Busch campus
    ("way/251381349",     "dining",         "Busch", "Busch Dining Hall"),
    ("way/251381343",     "student_center", "Busch", "Busch Student Center"),
    ("way/251381956",     "library",        "Busch", "Library of Science & Medicine"),
    ("way/251381866",     "academic",       "Busch", "Hill Center"),
    ("way/637575869",     "academic",       "Busch", "Weeks Hall of Engineering"),
    ("node/2924045306",   "bus_stop",       "Busch", "Science Buildings (bus stop)"),
    # Livingston campus
    ("way/251381978",     "dining",         "Livingston", "Livingston Dining Commons"),
    ("way/251381998",     "student_center", "Livingston", "Livingston Student Center"),
    ("way/251381942",     "library",        "Livingston", "James Dickson Carr Library"),
    ("way/251382004",     "academic",       "Livingston", "Lucy Stone Hall"),
    ("way/251382356",     "academic",       "Livingston", "Tillett Hall"),
    ("node/2305223108",   "bus_stop",       "Livingston", "Livingston Plaza (bus stop)"),
    # Cook and Douglass campuses (adjacent, treated as one area)
    ("way/251382147",     "dining",         "Cook/Douglass", "Neilson Dining Hall"),
    ("way/251381461",     "student_center", "Cook/Douglass", "Cook Student Center"),
    ("way/251381599",     "student_center", "Cook/Douglass", "Douglass Student Center"),
    ("way/251381606",     "library",        "Cook/Douglass", "Douglass Library"),
    ("way/251381436",     "academic",       "Cook/Douglass", "College Hall"),
    ("node/4386539642",   "bus_stop",       "Cook/Douglass", "Biel Road (bus stop)"),
    # Waypoint: the Raritan River crossing between College Ave and Busch
    ("way/38606657",      "waypoint",       "Raritan River", "Landing Lane Bridge"),
]


def slugify(label):
    keep = "".join(c.lower() if c.isalnum() else "-" for c in label.split(" (")[0])
    return "-".join(part for part in keep.split("-") if part)


def overpass(query):
    data = urllib.parse.urlencode({"data": query}).encode()
    req = urllib.request.Request(OVERPASS_URL, data=data, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.load(resp)["elements"]


def main():
    ids_by_type = {"node": [], "way": [], "relation": []}
    for element, *_ in LOCATIONS:
        osm_type, osm_id = element.split("/")
        ids_by_type[osm_type].append(osm_id)

    parts = "".join(f"{t}(id:{','.join(ids)});" for t, ids in ids_by_type.items() if ids)
    elements = overpass(f"[out:json][timeout:60];({parts});out center tags;")
    found = {f"{e['type']}/{e['id']}": e for e in elements}

    missing = [el for el, *_ in LOCATIONS if el not in found]
    if missing:
        raise SystemExit(f"Not found in OpenStreetMap: {missing}")

    locations = []
    for element, category, campus, label in LOCATIONS:
        e = found[element]
        point = e.get("center", e)  # nodes carry lat/lon directly
        tags = e.get("tags", {})
        locations.append({
            "id": slugify(label),
            "label": label,
            "osm_name": tags.get("name") or tags.get("bridge:name"),
            "category": category,
            "campus": campus,
            "lat": round(point["lat"], 7),
            "lon": round(point["lon"], 7),
            "osm": element,
            "osm_url": f"https://www.openstreetmap.org/{element}",
        })

    OUT_PATH.write_text(json.dumps({
        "source": "OpenStreetMap via the Overpass API",
        "license": "Map data (c) OpenStreetMap contributors, ODbL "
                   "(https://www.openstreetmap.org/copyright)",
        "retrieved": date.today().isoformat(),
        "locations": locations,
    }, indent=2) + "\n")
    print(f"Wrote {len(locations)} locations to {OUT_PATH}")


if __name__ == "__main__":
    main()
