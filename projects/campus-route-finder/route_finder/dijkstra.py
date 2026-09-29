"""Dijkstra's shortest-path algorithm, written out by hand.

Works on any graph object with `neighbors(node_id)` returning edges that have
`.to` and `.meters` (see graph.CampusGraph). All edge weights are distances,
so they are non-negative, which Dijkstra's algorithm requires.
"""

import math
from dataclasses import dataclass

from .priority_queue import MinPriorityQueue


def dijkstra(graph, source, target=None):
    """Shortest distances from `source` to every reachable node.

    Returns (dist, prev): dist[node] is the shortest distance in meters, and
    prev[node] is the node before it on that shortest path. If `target` is
    given, the search stops as soon as the target is settled, since its
    distance can no longer change.
    """
    dist = {source: 0.0}
    prev = {}
    settled = set()

    frontier = MinPriorityQueue()
    frontier.push(source, 0.0)

    while frontier:
        # The closest node not yet settled. Because edge weights are
        # non-negative, no later path can reach it more cheaply: its distance
        # is final.
        node, d = frontier.pop()
        if node in settled:
            continue  # stale entry left behind by an earlier improvement
        settled.add(node)
        if node == target:
            break

        # Relax each edge: is going through `node` a shorter way to reach
        # the neighbor than anything found so far?
        for edge in graph.neighbors(node):
            if edge.to in settled:
                continue
            candidate = d + edge.meters
            if candidate < dist.get(edge.to, math.inf):
                dist[edge.to] = candidate
                prev[edge.to] = node
                frontier.push(edge.to, candidate)  # lazy "decrease-key"

    return dist, prev


def reconstruct_path(prev, source, target):
    """Walk the `prev` links back from target to source."""
    path = [target]
    while path[-1] != source:
        path.append(prev[path[-1]])
    return path[::-1]


@dataclass
class Route:
    path: list      # node ids, start to end
    meters: float   # total length


def shortest_route(graph, start, end):
    """Shortest route between two locations, or None if unreachable."""
    dist, prev = dijkstra(graph, start, target=end)
    if end not in dist:
        return None
    return Route(reconstruct_path(prev, start, end), dist[end])


def nearest_of_category(graph, start, category, n):
    """The `n` closest locations of a category, by route distance.

    One full Dijkstra run from `start` gives the shortest distance to every
    node at once; the matching locations are then sorted by that distance.
    """
    dist, prev = dijkstra(graph, start)
    matches = [
        loc_id for loc_id, loc in graph.locations.items()
        if loc.category == category and loc_id != start and loc_id in dist
    ]
    matches.sort(key=dist.get)
    return [Route(reconstruct_path(prev, start, m), dist[m]) for m in matches[:n]]
