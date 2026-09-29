package com.aakashmunawar.studygroups.matching;

import java.util.ArrayList;
import java.util.List;

/**
 * Brute-force reference model for tests: one boolean per minute of the week
 * (7 x 1,440). Intersection is a minute-by-minute AND. Deliberately naive and
 * shares no code with WeeklySchedule, so agreement between the two is evidence
 * that the interval algorithms are right.
 */
final class MinuteGrid {

    final boolean[][] free = new boolean[7][Interval.MINUTES_PER_DAY];

    static MinuteGrid of(List<WeeklySchedule.Block> blocks) {
        MinuteGrid g = new MinuteGrid();
        for (WeeklySchedule.Block b : blocks) {
            for (int m = b.start(); m < b.end(); m++) {
                g.free[b.dayOfWeek() - 1][m] = true;
            }
        }
        return g;
    }

    MinuteGrid and(MinuteGrid o) {
        MinuteGrid g = new MinuteGrid();
        for (int d = 0; d < 7; d++) {
            for (int m = 0; m < Interval.MINUTES_PER_DAY; m++) {
                g.free[d][m] = free[d][m] && o.free[d][m];
            }
        }
        return g;
    }

    /** Maximal runs of free minutes, in day then time order. */
    List<WeeklySchedule.Block> runs() {
        List<WeeklySchedule.Block> out = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            int m = 0;
            while (m < Interval.MINUTES_PER_DAY) {
                if (!free[d][m]) {
                    m++;
                    continue;
                }
                int start = m;
                while (m < Interval.MINUTES_PER_DAY && free[d][m]) {
                    m++;
                }
                out.add(new WeeklySchedule.Block(d + 1, start, m));
            }
        }
        return out;
    }

    List<WeeklySchedule.Block> runsAtLeast(int minutes) {
        return runs().stream().filter(b -> b.end() - b.start() >= minutes).toList();
    }
}
