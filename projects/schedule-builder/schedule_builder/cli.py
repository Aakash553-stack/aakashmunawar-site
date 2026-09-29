"""Command-line interface.

    python -m schedule_builder 198:112 198:205 640:151
    python -m schedule_builder 198:112 198:205 640:151 -n 5 --prefer late-start gaps
    python -m schedule_builder 198:211 198:344 640:250 --open-only --prefer switches
    python -m schedule_builder 198:112 198:205 640:151 --stats
"""

import argparse
import sys
import time

from .data import Catalog
from .ranking import (CRITERIA, DEFAULT_PREFERENCES, daily_meetings, group_equivalent, rank,
                      resolve_preferences)
from .search import backtrack, brute_force


def clock(minutes):
    h, m = divmod(round(minutes), 60)
    return f"{h % 12 or 12}:{m:02d}{'am' if h < 12 else 'pm'}"


def hours(minutes):
    return f"{minutes / 60:.1f} h".replace(".0 h", " h")


def print_schedule(rank_no, total, schedule, m, alternatives):
    print(f"#{rank_no} of {total:,}  |  {m.days_on_campus} days on campus  |  "
          f"{hours(m.gap_minutes)} of gaps  |  {m.campus_switches} campus switch"
          f"{'' if m.campus_switches == 1 else 'es'}  |  avg first class {clock(m.avg_start)}")

    for sec in schedule:
        print(f"   {sec.course} sec {sec.section:<3} {section_details(sec)}  "
              f"{sec.title.title()}  ({sec.instructors or 'TBA'})")

    for day, meetings in daily_meetings(schedule).items():
        for i, (mt, sec) in enumerate(meetings):
            where = " ".join(filter(None, [mt.campus, mt.location])) or "location TBA"
            print(f"   {day if i == 0 else '':<4} {clock(mt.start):>7}-{clock(mt.end):<7} "
                  f"{sec.course} {mt.type:<6} {where}")
    for sec in schedule:
        if not sec.scheduled_meetings:
            print(f"   ({sec.course} sec {sec.section} has no scheduled meeting time)")

    swaps = [(course, alts) for course, alts in alternatives.items() if alts]
    if swaps:
        print("   Same days, times and campuses also with:")
        for course, alts in swaps:
            listed = ", ".join(f"sec {a.section} {section_details(a)}" for a in alts)
            print(f"     {course}: {listed}")
    print()


def section_details(sec):
    status = "open" if sec.open else "CLOSED"
    extra = f", {sec.eligibility}" if sec.eligibility else ""
    return f"(index {sec.index}, {status}{extra})"


def print_stats(course_sections):
    t0 = time.perf_counter()
    bt_schedules, bt = backtrack(course_sections)
    t1 = time.perf_counter()
    bf_schedules, bf = brute_force(course_sections)
    t2 = time.perf_counter()

    assert {frozenset(s) for s in bt_schedules} == {frozenset(s) for s in bf_schedules}

    print("Search statistics (backtracking vs. brute force)")
    print(f"  Complete combinations:    {bf.total_combinations:>12,}  (brute force checks every one)")
    print(f"  Partial schedules visited:{bt.nodes_visited:>12,}  (backtracking)")
    print(f"  Conflict checks:          {bt.conflict_checks:>12,}  vs. {bf.conflict_checks:,} for brute force")
    print(f"  Branches pruned:          {bt.branches_pruned:>12,}  eliminating "
          f"{bt.combinations_skipped:,} combinations without generating them")
    print(f"  Time:                     {(t1 - t0) * 1000:>9,.0f} ms  vs. {(t2 - t1) * 1000:,.0f} ms")
    print(f"  Both find the same {len(bt_schedules):,} conflict-free schedules.")
    print()


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="schedule_builder",
        description="Find and rank conflict-free Rutgers schedules from real Schedule of Classes data.",
        epilog="Criteria: " + "; ".join(f"{k} = {v}" for k, v in CRITERIA.items()),
    )
    parser.add_argument("courses", nargs="+", help="course codes, e.g. 198:112 640:151")
    parser.add_argument("-n", type=int, default=3, help="how many schedules to show (default 3)")
    parser.add_argument("--prefer", nargs="+", default=[], metavar="CRITERION",
                        help=f"ranking criteria in priority order (default: {' '.join(DEFAULT_PREFERENCES)})")
    parser.add_argument("--open-only", action="store_true",
                        help="only use sections that were open when the data was downloaded")
    parser.add_argument("--stats", action="store_true",
                        help="compare backtracking with brute-force enumeration")
    args = parser.parse_args(argv)

    catalog = Catalog()
    try:
        preferences = resolve_preferences(args.prefer)
        codes = list(dict.fromkeys(catalog.normalize_code(c) for c in args.courses))
        course_sections = {c: catalog.get(c, open_only=args.open_only) for c in codes}
    except (KeyError, ValueError) as exc:
        print(exc.args[0], file=sys.stderr)
        return 1

    print(f"{catalog.term}, New Brunswick. Data retrieved {catalog.retrieved_at[:10]}; "
          f"sections and open/closed status change often.")
    for code, sections in course_sections.items():
        print(f"  {code} {catalog.titles[code].title()}: {len(sections)} "
              f"{'open ' if args.open_only else ''}sections")
    print(f"Ranking by: {', then '.join(CRITERIA[p] for p in preferences)}\n")

    empty = [c for c, s in course_sections.items() if not s]
    if empty:
        print(f"No {'open ' if args.open_only else ''}sections for {', '.join(empty)}.")
        return 1

    if args.stats:
        print_stats(course_sections)

    schedules, _ = backtrack(course_sections)
    if not schedules:
        print("No conflict-free combination exists for these courses.")
        return 0

    grouped = group_equivalent(rank(schedules, preferences))
    print(f"{len(schedules):,} conflict-free schedules found, {len(grouped):,} distinct timetables "
          f"(schedules that differ only in room or recitation instructor are grouped). "
          f"Top {min(args.n, len(grouped))}:\n")
    for i, (schedule, m, alternatives) in enumerate(grouped[:args.n], 1):
        print_schedule(i, len(grouped), schedule, m, alternatives)
    return 0
