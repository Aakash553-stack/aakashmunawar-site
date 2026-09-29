"""Score and rank conflict-free schedules by the student's preferences.

Every criterion is computed from the real meeting days, times and campuses.
Ranking is lexicographic: schedules are sorted by the first preferred
criterion, ties are broken by the second, and so on. Criteria that take a
few whole-number values (days, switches) produce many ties, so listing them
before a continuous one (average start time) lets every criterion matter.
That is why the default order is days, then gaps, then late start.
"""

from dataclasses import dataclass

from .data import DAY_ORDER

CRITERIA = {
    "days":      "fewest distinct days on campus",
    "switches":  "fewest campus switches between back-to-back classes",
    "gaps":      "fewest idle hours between classes on the same day",
    "late-start": "latest average first-class start time",
}
# Campus switches is opt-in (--prefer switches); it is always reported.
DEFAULT_PREFERENCES = ["days", "gaps", "late-start"]


def resolve_preferences(chosen):
    """The chosen criteria in order, then the remaining defaults as tiebreakers."""
    unknown = [p for p in chosen if p not in CRITERIA]
    if unknown:
        raise ValueError(f"Unknown criteria {unknown}; choose from {list(CRITERIA)}")
    return list(dict.fromkeys(chosen)) + [p for p in DEFAULT_PREFERENCES if p not in chosen]


@dataclass(frozen=True)
class ScheduleMetrics:
    days_on_campus: int
    campus_switches: int
    gap_minutes: int
    avg_start: float      # minutes after midnight, averaged over class days
    earliest_start: int
    latest_end: int


def daily_meetings(schedule):
    """Map day -> that day's scheduled meetings, sorted by start time.

    Each item is (meeting, section) so the caller knows which course it is.
    """
    by_day = {}
    for section in schedule:
        for m in section.scheduled_meetings:
            by_day.setdefault(m.day, []).append((m, section))
    return {day: sorted(by_day[day], key=lambda ms: ms[0].start)
            for day in DAY_ORDER if day in by_day}


def is_in_person(meeting):
    return meeting.campus not in (None, "Online")


def metrics(schedule):
    days = daily_meetings(schedule)
    gap = switches = 0
    first_starts = []
    for day_meetings in days.values():
        meetings = [m for m, _ in day_meetings]
        first_starts.append(meetings[0].start)
        for prev, nxt in zip(meetings, meetings[1:]):
            gap += max(0, nxt.start - prev.end)
            if is_in_person(prev) and is_in_person(nxt) and prev.campus != nxt.campus:
                switches += 1
    in_person_days = sum(any(is_in_person(m) for m, _ in dm) for dm in days.values())
    all_meetings = [m for dm in days.values() for m, _ in dm]
    return ScheduleMetrics(
        days_on_campus=in_person_days,
        campus_switches=switches,
        gap_minutes=gap,
        avg_start=sum(first_starts) / len(first_starts) if first_starts else 0,
        earliest_start=min((m.start for m in all_meetings), default=0),
        latest_end=max((m.end for m in all_meetings), default=0),
    )


def sort_key(m, preferences):
    values = {
        "days": m.days_on_campus,
        "switches": m.campus_switches,
        "gaps": m.gap_minutes,
        "late-start": -m.avg_start,   # later is better, so negate
    }
    return tuple(values[p] for p in preferences)


def rank(schedules, preferences=DEFAULT_PREFERENCES):
    """Return [(schedule, metrics)] best first."""
    scored = [(s, metrics(s)) for s in schedules]
    # Final tiebreak on index numbers keeps the order deterministic.
    scored.sort(key=lambda sm: (sort_key(sm[1], preferences),
                                tuple(sec.index for sec in sm[0])))
    return scored


def timetable_signature(schedule):
    """Days, times, campuses and meeting types of every meeting, per course.

    Sections of the same course often share a lecture and differ only in
    room or recitation instructor. Schedules with the same signature look
    identical on a calendar and have identical metrics.
    """
    return tuple(
        (sec.course, tuple(sorted((m.day, m.start, m.end, m.campus, m.type)
                                  for m in sec.scheduled_meetings)))
        for sec in schedule
    )


def group_equivalent(ranked):
    """Collapse ranked schedules with identical timetables.

    Returns [(schedule, metrics, alternatives)] in rank order, where
    `schedule` is the best-ranked member of the group and `alternatives`
    maps course -> the other sections that can be swapped in without
    changing any time or campus.
    """
    groups = {}
    for schedule, m in ranked:
        key = timetable_signature(schedule)
        if key not in groups:
            groups[key] = (schedule, m, {sec.course: [] for sec in schedule})
        first, _, alternatives = groups[key]
        for sec, lead in zip(schedule, first):
            if sec != lead and sec not in alternatives[sec.course]:
                alternatives[sec.course].append(sec)
    return list(groups.values())
