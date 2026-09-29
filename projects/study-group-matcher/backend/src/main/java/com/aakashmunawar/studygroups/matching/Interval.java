package com.aakashmunawar.studygroups.matching;

/**
 * A half-open time range [start, end) in minutes after midnight on one day.
 * Half-open means a block ending at 10:00 and one starting at 10:00 touch
 * but do not overlap.
 */
public record Interval(int start, int end) implements Comparable<Interval> {

    public static final int MINUTES_PER_DAY = 24 * 60;

    public Interval {
        if (start < 0 || end > MINUTES_PER_DAY || start >= end) {
            throw new IllegalArgumentException("invalid interval [" + start + ", " + end + ")");
        }
    }

    public int length() {
        return end - start;
    }

    @Override
    public int compareTo(Interval o) {
        return start != o.start ? Integer.compare(start, o.start) : Integer.compare(end, o.end);
    }
}
