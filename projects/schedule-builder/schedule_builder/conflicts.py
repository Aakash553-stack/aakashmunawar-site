"""Time-conflict detection between meetings and sections."""


def meetings_overlap(a, b):
    """True if two meetings are on the same day and their times overlap.

    Times are half-open intervals [start, end): a class ending at 10:20 and
    another starting at 10:20 do not overlap. Meetings with no scheduled
    time (independent study, asynchronous online) never overlap anything.
    """
    if not (a.is_scheduled and b.is_scheduled) or a.day != b.day:
        return False
    return a.start < b.end and b.start < a.end


def sections_conflict(s1, s2):
    """Two sections conflict if any meeting of one overlaps any meeting of the other."""
    return any(meetings_overlap(a, b)
               for a in s1.scheduled_meetings
               for b in s2.scheduled_meetings)
