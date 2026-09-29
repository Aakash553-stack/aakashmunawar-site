package com.aakashmunawar.studygroups.matching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A student's (or group's) free time across one week.
 *
 * <p>Stored as seven lists, one per ISO day (1 = Monday ... 7 = Sunday), each
 * holding intervals that are <b>sorted and non-overlapping</b>. That invariant
 * is established once, in {@link #of}, and is what lets {@link #intersect} run
 * as a linear two-pointer merge instead of comparing every pair of blocks.
 * Instances are immutable.
 */
public final class WeeklySchedule {

    public static final int DAYS = 7;
    public static final WeeklySchedule EMPTY = of(List.of());

    private final List<List<Interval>> days;   // index 0 = Monday

    private WeeklySchedule(List<List<Interval>> days) {
        this.days = days;
    }

    /** One block of free time on an ISO day. */
    public record Block(int dayOfWeek, int start, int end) {
    }

    /**
     * Build a schedule from blocks in any order. Overlapping or touching blocks
     * on the same day are merged: Mon 9:00-10:00 plus Mon 9:30-11:00 becomes
     * Mon 9:00-11:00. Sorting dominates, so this is O(n log n).
     */
    public static WeeklySchedule of(List<Block> blocks) {
        List<List<Interval>> raw = emptyDays();
        for (Block b : blocks) {
            if (b.dayOfWeek() < 1 || b.dayOfWeek() > DAYS) {
                throw new IllegalArgumentException("dayOfWeek must be 1-7: " + b.dayOfWeek());
            }
            raw.get(b.dayOfWeek() - 1).add(new Interval(b.start(), b.end()));
        }
        List<List<Interval>> merged = new ArrayList<>(DAYS);
        for (List<Interval> day : raw) {
            Collections.sort(day);
            List<Interval> out = new ArrayList<>();
            for (Interval iv : day) {
                Interval last = out.isEmpty() ? null : out.get(out.size() - 1);
                if (last != null && iv.start() <= last.end()) {        // overlaps or touches
                    out.set(out.size() - 1, new Interval(last.start(), Math.max(last.end(), iv.end())));
                } else {
                    out.add(iv);
                }
            }
            merged.add(List.copyOf(out));
        }
        return new WeeklySchedule(List.copyOf(merged));
    }

    /**
     * Time when both schedules are free. For each day, walk both sorted lists
     * with one pointer each: emit the overlap of the two current intervals, then
     * advance whichever interval ends first (it cannot overlap anything later
     * in the other list). O(a + b) for lists of length a and b.
     */
    public WeeklySchedule intersect(WeeklySchedule other) {
        List<List<Interval>> result = new ArrayList<>(DAYS);
        for (int d = 0; d < DAYS; d++) {
            List<Interval> a = days.get(d);
            List<Interval> b = other.days.get(d);
            List<Interval> out = new ArrayList<>();
            int i = 0;
            int j = 0;
            while (i < a.size() && j < b.size()) {
                int start = Math.max(a.get(i).start(), b.get(j).start());
                int end = Math.min(a.get(i).end(), b.get(j).end());
                if (start < end) {
                    out.add(new Interval(start, end));
                }
                if (a.get(i).end() < b.get(j).end()) {
                    i++;
                } else {
                    j++;
                }
            }
            result.add(List.copyOf(out));
        }
        return new WeeklySchedule(List.copyOf(result));
    }

    /** Common free time of several schedules (e.g. every member of a group). */
    public static WeeklySchedule intersectAll(List<WeeklySchedule> schedules) {
        if (schedules.isEmpty()) {
            return EMPTY;
        }
        WeeklySchedule common = schedules.get(0);
        for (int k = 1; k < schedules.size(); k++) {
            common = common.intersect(schedules.get(k));
        }
        return common;
    }

    /** Intervals on an ISO day (1 = Monday), sorted and non-overlapping. */
    public List<Interval> day(int dayOfWeek) {
        return days.get(dayOfWeek - 1);
    }

    public int totalMinutes() {
        int total = 0;
        for (List<Interval> day : days) {
            for (Interval iv : day) {
                total += iv.length();
            }
        }
        return total;
    }

    public int longestBlock() {
        int best = 0;
        for (List<Interval> day : days) {
            for (Interval iv : day) {
                best = Math.max(best, iv.length());
            }
        }
        return best;
    }

    /** Only the blocks long enough to hold a session of {@code minMinutes}. */
    public WeeklySchedule atLeast(int minMinutes) {
        List<List<Interval>> kept = new ArrayList<>(DAYS);
        for (List<Interval> day : days) {
            kept.add(day.stream().filter(iv -> iv.length() >= minMinutes).toList());
        }
        return new WeeklySchedule(List.copyOf(kept));
    }

    /** Number of days that have at least one block. */
    public int daysWithTime() {
        int n = 0;
        for (List<Interval> day : days) {
            if (!day.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    public boolean isEmpty() {
        return totalMinutes() == 0;
    }

    public List<Block> blocks() {
        List<Block> out = new ArrayList<>();
        for (int d = 0; d < DAYS; d++) {
            for (Interval iv : days.get(d)) {
                out.add(new Block(d + 1, iv.start(), iv.end()));
            }
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WeeklySchedule w && days.equals(w.days);
    }

    @Override
    public int hashCode() {
        return days.hashCode();
    }

    @Override
    public String toString() {
        return blocks().toString();
    }

    private static List<List<Interval>> emptyDays() {
        List<List<Interval>> days = new ArrayList<>(DAYS);
        for (int d = 0; d < DAYS; d++) {
            days.add(new ArrayList<>());
        }
        return days;
    }
}
