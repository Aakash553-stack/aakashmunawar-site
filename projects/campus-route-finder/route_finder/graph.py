"""The campus graph: locations are nodes, walking connections are weighted edges.

Edges are undirected and weighted by straight-line (haversine) distance in
meters between the two locations' OpenStreetMap coordinates. Two rules create
them:

1. Nearby edges: two locations on the same campus are connected if they are at
   most NEARBY_RADIUS_M apart.
2. Connector edges: campuses are far apart (the closest pair of locations on
   different campuses is over 1 km), so each pair of areas listed in
   CAMPUS_CONNECTIONS is joined by one edge between its two closest locations.
   College Avenue and Busch sit on opposite sides of the Raritan River, so
   that connection goes through the Landing Lane Bridge node instead of
   straight across the water.
"""

import json
from dataclasses import dataclass
from itertools import combinations
from pathlib import Path

from .geo import haversine_m

DATA_PATH = Path(__file__).resolve().parent.parent / "data" / "locations.json"

NEARBY_RADIUS_M = 600

CAMPUS_CONNECTIONS = [
    ("College Avenue", "Cook/Douglass"),  # same side of the river, along George St
    ("College Avenue", "Raritan River"),  # to the Landing Lane Bridge...
    ("Raritan River", "Busch"),           # ...and across it to Busch
    ("Busch", "Livingston"),              # both north of the river
]


@dataclass(frozen=True)
class Location:
    id: str
    label: str
    osm_name: str
    category: str
    campus: str
    lat: float
    lon: float
    osm: str

    @property
    def is_destination(self):
        """Waypoints (the bridge) can be routed through but are not places to go."""
        return self.category != "waypoint"


@dataclass(frozen=True)
class Edge:
    to: str
    meters: float
    kind: str  # "nearby" or "connector"


class CampusGraph:
    """Undirected weighted graph stored as an adjacency list."""

    def __init__(self, locations):
        self.locations = {loc.id: loc for loc in locations}
        self.adjacency = {loc.id: [] for loc in locations}

    def add_edge(self, a, b, kind):
        la, lb = self.locations[a], self.locations[b]
        meters = haversine_m(la.lat, la.lon, lb.lat, lb.lon)
        self.adjacency[a].append(Edge(b, meters, kind))
        self.adjacency[b].append(Edge(a, meters, kind))

    def neighbors(self, node_id):
        return self.adjacency[node_id]

    def edges(self):
        """Each undirected edge once, as (a, b, meters, kind)."""
        for a, out in self.adjacency.items():
            for e in out:
                if a < e.to:
                    yield a, e.to, e.meters, e.kind

    def __len__(self):
        return len(self.locations)


def load_locations(path=DATA_PATH):
    raw = json.loads(Path(path).read_text())["locations"]
    return [Location(**{k: r[k] for k in Location.__dataclass_fields__}) for r in raw]


def build_graph(locations=None):
    locations = load_locations() if locations is None else locations
    graph = CampusGraph(locations)

    for a, b in combinations(locations, 2):
        if a.campus == b.campus and haversine_m(a.lat, a.lon, b.lat, b.lon) <= NEARBY_RADIUS_M:
            graph.add_edge(a.id, b.id, "nearby")

    for campus_a, campus_b in CAMPUS_CONNECTIONS:
        side_a = [l for l in locations if l.campus == campus_a]
        side_b = [l for l in locations if l.campus == campus_b]
        a, b = min(((x, y) for x in side_a for y in side_b),
                   key=lambda p: haversine_m(p[0].lat, p[0].lon, p[1].lat, p[1].lon))
        graph.add_edge(a.id, b.id, "connector")

    return graph
