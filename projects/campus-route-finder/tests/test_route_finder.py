"""Run with:  python -m unittest discover tests"""

import math
import random
import unittest
from itertools import product

from route_finder.dijkstra import dijkstra, nearest_of_category, shortest_route
from route_finder.geo import haversine_m
from route_finder.graph import CampusGraph, Edge, Location, build_graph
from route_finder.priority_queue import MinPriorityQueue


class PriorityQueueTest(unittest.TestCase):
    def test_pops_in_priority_order(self):
        pq = MinPriorityQueue()
        values = list(range(50))
        random.Random(1).shuffle(values)
        for v in values:
            pq.push(f"item{v}", v)
        popped = [pq.pop()[1] for _ in range(len(pq))]
        self.assertEqual(popped, sorted(values))

    def test_equal_priorities_pop_in_insertion_order(self):
        pq = MinPriorityQueue()
        for name in ["a", "b", "c"]:
            pq.push(name, 1.0)
        self.assertEqual([pq.pop()[0] for _ in range(3)], ["a", "b", "c"])


class HaversineTest(unittest.TestCase):
    def test_one_degree_of_latitude(self):
        # One degree of latitude is about 111.2 km on a sphere of mean radius.
        self.assertAlmostEqual(haversine_m(40, -74, 41, -74), 111_195, delta=5)


class SmallGraphTest(unittest.TestCase):
    """A hand-made graph where the direct edge is not the shortest route."""

    def setUp(self):
        locs = [Location(i, i, i, "academic", "Test", 0.0, 0.0, "") for i in "ABCD"]
        self.g = CampusGraph(locs)
        edges = {("A", "B"): 10, ("A", "C"): 3, ("C", "B"): 4, ("B", "D"): 2, ("C", "D"): 8}
        for (a, b), w in edges.items():
            for x, y in ((a, b), (b, a)):
                self.g.adjacency[x].append(Edge(y, w, "test"))

    def test_prefers_cheaper_indirect_path(self):
        route = shortest_route(self.g, "A", "D")
        self.assertEqual(route.path, ["A", "C", "B", "D"])
        self.assertEqual(route.meters, 9)

    def test_all_distances_from_source(self):
        dist, _ = dijkstra(self.g, "A")
        self.assertEqual(dist, {"A": 0, "B": 7, "C": 3, "D": 9})


class CampusGraphTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.g = build_graph()

    def floyd_warshall(self):
        nodes = list(self.g.locations)
        d = {(a, b): 0.0 if a == b else math.inf for a, b in product(nodes, nodes)}
        for a, b, m, _ in self.g.edges():
            d[a, b] = d[b, a] = m
        for k, i, j in product(nodes, nodes, nodes):
            if d[i, k] + d[k, j] < d[i, j]:
                d[i, j] = d[i, k] + d[k, j]
        return d

    def test_graph_is_connected(self):
        dist, _ = dijkstra(self.g, next(iter(self.g.locations)))
        self.assertEqual(len(dist), len(self.g))

    def test_matches_floyd_warshall_for_every_pair(self):
        expected = self.floyd_warshall()
        for a in self.g.locations:
            dist, _ = dijkstra(self.g, a)
            for b in self.g.locations:
                self.assertAlmostEqual(dist[b], expected[a, b], places=6, msg=f"{a} -> {b}")

    def test_route_length_equals_sum_of_its_edges(self):
        route = shortest_route(self.g, "scott-hall", "livingston-dining-commons")
        legs = [next(e.meters for e in self.g.neighbors(a) if e.to == b)
                for a, b in zip(route.path, route.path[1:])]
        self.assertAlmostEqual(sum(legs), route.meters)

    def test_river_crossing_uses_the_bridge(self):
        route = shortest_route(self.g, "brower-commons", "busch-student-center")
        self.assertIn("landing-lane-bridge", route.path)

    def test_nearest_returns_closest_of_category_in_order(self):
        results = nearest_of_category(self.g, "scott-hall", "dining", 4)
        ends = [r.path[-1] for r in results]
        self.assertTrue(all(self.g.locations[e].category == "dining" for e in ends))
        self.assertEqual(ends[0], "brower-commons")
        self.assertEqual([r.meters for r in results], sorted(r.meters for r in results))
        self.assertEqual(len(results), 4)


if __name__ == "__main__":
    unittest.main()
