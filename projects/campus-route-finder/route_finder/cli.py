"""Command-line interface.

    python -m route_finder list
    python -m route_finder route "Scott Hall" "Hill Center"
    python -m route_finder nearest "Livingston Student Center" dining -n 3
    python -m route_finder plot "Scott Hall" "Hill Center"
"""

import argparse
import sys

from .dijkstra import nearest_of_category, shortest_route
from .graph import build_graph

CATEGORIES = ["dining", "library", "student_center", "academic", "bus_stop"]


class LocationNotFound(Exception):
    pass


def resolve(graph, query):
    """Find a destination by id, exact name, or unique partial name."""
    q = query.strip().lower()
    places = [l for l in graph.locations.values() if l.is_destination]

    for loc in places:
        if q in (loc.id, loc.label.lower(), loc.osm_name.lower()):
            return loc.id
    matches = [l for l in places if q in l.label.lower() or q in l.osm_name.lower()]
    if len(matches) == 1:
        return matches[0].id
    if not matches:
        raise LocationNotFound(f'No location matches "{query}". Run "list" to see all locations.')
    names = ", ".join(sorted(m.label for m in matches))
    raise LocationNotFound(f'"{query}" matches several locations: {names}. Be more specific.')


def format_distance(meters):
    return f"{meters:,.0f} m ({meters / 1000:.2f} km, {meters / 1609.344:.2f} mi)"


def print_route(graph, route):
    for i, node in enumerate(route.path):
        loc = graph.locations[node]
        if i == 0:
            print(f"   {loc.label}")
            continue
        leg = next(e.meters for e in graph.neighbors(route.path[i - 1]) if e.to == node)
        print(f"-> {loc.label:<34} +{leg:>6,.0f} m")
    print(f"Total: {format_distance(route.meters)}")


def cmd_list(graph, args):
    by_campus = {}
    for loc in graph.locations.values():
        if loc.is_destination:
            by_campus.setdefault(loc.campus, []).append(loc)
    for campus, locs in by_campus.items():
        print(campus)
        for loc in sorted(locs, key=lambda l: (l.category, l.label)):
            print(f"  {loc.category:<15} {loc.label}")


def cmd_route(graph, args):
    start, end = resolve(graph, args.start), resolve(graph, args.end)
    route = shortest_route(graph, start, end)
    if route is None:
        raise LocationNotFound("No route found between those locations.")
    print(f"Shortest route: {graph.locations[start].label} to {graph.locations[end].label}\n")
    print_route(graph, route)


def cmd_nearest(graph, args):
    start = resolve(graph, args.start)
    results = nearest_of_category(graph, start, args.category, args.n)
    print(f"Nearest {args.category.replace('_', ' ')} locations to "
          f"{graph.locations[start].label} (by route distance):\n")
    for i, route in enumerate(results, 1):
        loc = graph.locations[route.path[-1]]
        via = " -> ".join(graph.locations[p].label for p in route.path[1:-1]) or "direct"
        print(f"{i}. {loc.label:<34} {route.meters:>7,.0f} m  ({loc.campus}; via: {via})")


def cmd_plot(graph, args):
    from .plot import plot_route  # matplotlib is only needed here

    start, end = resolve(graph, args.start), resolve(graph, args.end)
    route = shortest_route(graph, start, end)
    path = plot_route(graph, route, args.out)
    print(f"Saved {path}")


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="route_finder",
        description="Shortest walking routes between Rutgers-New Brunswick locations "
                    "(OpenStreetMap coordinates, straight-line distances).",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("list", help="list all locations")

    p = sub.add_parser("route", help="shortest route between two locations")
    p.add_argument("start")
    p.add_argument("end")

    p = sub.add_parser("nearest", help="nearest N locations of a type")
    p.add_argument("start")
    p.add_argument("category", choices=CATEGORIES)
    p.add_argument("-n", type=int, default=3, help="how many to show (default 3)")

    p = sub.add_parser("plot", help="save a map of the graph with a route highlighted")
    p.add_argument("start")
    p.add_argument("end")
    p.add_argument("--out", default="output/route.png")

    args = parser.parse_args(argv)
    graph = build_graph()
    handler = {"list": cmd_list, "route": cmd_route,
               "nearest": cmd_nearest, "plot": cmd_plot}[args.command]
    try:
        handler(graph, args)
    except LocationNotFound as exc:
        print(exc, file=sys.stderr)
        return 1
    return 0
