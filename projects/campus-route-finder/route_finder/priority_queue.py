"""A min-priority queue backed by a binary heap (Python's heapq).

Dijkstra's algorithm needs one operation above all: "give me the unvisited
node with the smallest known distance". A binary heap does that in O(log n)
per push/pop, instead of O(n) for scanning a list.

heapq has no decrease-key operation (lowering the priority of an item already
in the heap). The standard workaround, used here, is *lazy deletion*: when a
node's distance improves, push a new entry with the lower priority and leave
the old one in the heap. The old entry is now stale; Dijkstra recognizes and
skips it when it is eventually popped (the node is already settled).
"""

import heapq
from itertools import count


class MinPriorityQueue:
    def __init__(self):
        self._heap = []
        # Tie-breaker: entries with equal priority pop in insertion order, and
        # the items themselves never need to be comparable.
        self._order = count()

    def push(self, item, priority):
        heapq.heappush(self._heap, (priority, next(self._order), item))

    def pop(self):
        """Remove and return (item, priority) with the smallest priority."""
        priority, _, item = heapq.heappop(self._heap)
        return item, priority

    def __len__(self):
        return len(self._heap)

    def __bool__(self):
        return bool(self._heap)
