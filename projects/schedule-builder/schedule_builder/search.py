"""Find every conflict-free combination of sections, one per course.

`backtrack` is the real algorithm. `brute_force` enumerates every
combination and exists only to measure how much work backtracking saves
(and to cross-check its results in the tests).
"""

import math
from dataclasses import dataclass
from itertools import product

from .conflicts import sections_conflict


@dataclass
class SearchStats:
    total_combinations: int = 0     # product of section counts: the brute-force space
    nodes_visited: int = 0          # partial schedules the search stepped into
    conflict_checks: int = 0        # section-vs-section comparisons made
    branches_pruned: int = 0        # sections rejected because they conflicted
    combinations_skipped: int = 0   # complete combinations eliminated by those prunes


def backtrack(course_sections):
    """Return (schedules, stats).

    `course_sections` maps course code -> list of candidate sections. Each
    schedule is a tuple of sections, one per course.

    The search builds a schedule one course at a time. Before adding a
    section it checks that section against the sections already chosen; on
    a conflict it skips that section immediately, which discards every
    completion of that partial schedule in one step. That early rejection
    is what separates backtracking from brute force.
    """
    levels = list(course_sections.values())
    stats = SearchStats(total_combinations=math.prod(len(l) for l in levels))
    # remaining[i] = number of complete schedules below one choice at depth i
    remaining = [math.prod(len(l) for l in levels[i + 1:]) for i in range(len(levels))]

    schedules = []
    chosen = []

    def extend(depth):
        stats.nodes_visited += 1
        if depth == len(levels):
            schedules.append(tuple(chosen))
            return
        for candidate in levels[depth]:
            if has_conflict(candidate, chosen, stats):
                stats.branches_pruned += 1
                stats.combinations_skipped += remaining[depth]
                continue                      # prune: never explore below this
            chosen.append(candidate)
            extend(depth + 1)
            chosen.pop()                      # undo the choice and try the next one

    extend(0)
    return schedules, stats


def has_conflict(candidate, chosen, stats):
    for existing in chosen:
        stats.conflict_checks += 1
        if sections_conflict(candidate, existing):
            return True
    return False


def brute_force(course_sections):
    """Check every complete combination. For comparison only."""
    levels = list(course_sections.values())
    stats = SearchStats(total_combinations=math.prod(len(l) for l in levels))
    schedules = []
    for combo in product(*levels):
        stats.nodes_visited += 1
        ok = True
        for i in range(len(combo)):
            for j in range(i + 1, len(combo)):
                stats.conflict_checks += 1
                if sections_conflict(combo[i], combo[j]):
                    ok = False
                    break
            if not ok:
                break
        if ok:
            schedules.append(combo)
    return schedules, stats
