package com.aakashmunawar.studygroups.matching;

import static org.junit.jupiter.api.Assertions.*;

import com.aakashmunawar.studygroups.matching.WeeklySchedule.Block;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class WeeklyScheduleTest {

    static final int MON = 1, TUE = 2, FRI = 5;

    static int hm(int h, int m) {
        return h * 60 + m;
    }

    @Test
    void mergesOverlappingAndTouchingBlocks() {
        WeeklySchedule s = WeeklySchedule.of(List.of(
                new Block(MON, hm(9, 30), hm(11, 0)),
                new Block(MON, hm(9, 0), hm(10, 0)),      // overlaps: merged
                new Block(MON, hm(11, 0), hm(12, 0)),     // touches: merged
                new Block(MON, hm(14, 0), hm(15, 0))));   // separate
        assertEquals(List.of(new Interval(hm(9, 0), hm(12, 0)), new Interval(hm(14, 0), hm(15, 0))),
                s.day(MON));
        assertEquals(240, s.totalMinutes());
        assertEquals(180, s.longestBlock());
    }

    @Test
    void intersectionKeepsOnlyCommonTime() {
        WeeklySchedule a = WeeklySchedule.of(List.of(new Block(MON, hm(9, 0), hm(12, 0)),
                new Block(TUE, hm(13, 0), hm(15, 0))));
        WeeklySchedule b = WeeklySchedule.of(List.of(new Block(MON, hm(11, 0), hm(13, 0)),
                new Block(FRI, hm(9, 0), hm(10, 0))));
        assertEquals(WeeklySchedule.of(List.of(new Block(MON, hm(11, 0), hm(12, 0)))), a.intersect(b));
    }

    @Test
    void backToBackBlocksDoNotOverlap() {
        WeeklySchedule a = WeeklySchedule.of(List.of(new Block(MON, hm(9, 0), hm(10, 0))));
        WeeklySchedule b = WeeklySchedule.of(List.of(new Block(MON, hm(10, 0), hm(11, 0))));
        assertTrue(a.intersect(b).isEmpty());
    }

    @Test
    void oneLongBlockAgainstManyShortOnes() {
        WeeklySchedule wide = WeeklySchedule.of(List.of(new Block(MON, hm(8, 0), hm(18, 0))));
        List<Block> shorts = new ArrayList<>();
        for (int h = 6; h < 20; h += 2) {
            shorts.add(new Block(MON, hm(h, 0), hm(h, 45)));
        }
        WeeklySchedule narrow = WeeklySchedule.of(shorts);
        // 8:00, 10:00, 12:00, 14:00, 16:00 fall inside 8:00-18:00; 6:00 and 18:00 do not.
        assertEquals(5 * 45, wide.intersect(narrow).totalMinutes());
        assertEquals(wide.intersect(narrow), narrow.intersect(wide));
    }

    @Test
    void atLeastDropsShortBlocks() {
        WeeklySchedule s = WeeklySchedule.of(List.of(new Block(MON, hm(9, 0), hm(9, 59)),
                new Block(MON, hm(13, 0), hm(14, 0))));
        assertEquals(List.of(new Interval(hm(13, 0), hm(14, 0))), s.atLeast(60).day(MON));
    }

    @Test
    void intersectAllOfNothingIsEmpty() {
        assertTrue(WeeklySchedule.intersectAll(List.of()).isEmpty());
    }

    @Test
    void rejectsInvalidBlocks() {
        assertThrows(IllegalArgumentException.class, () -> WeeklySchedule.of(List.of(new Block(0, 0, 60))));
        assertThrows(IllegalArgumentException.class, () -> WeeklySchedule.of(List.of(new Block(8, 0, 60))));
        assertThrows(IllegalArgumentException.class, () -> WeeklySchedule.of(List.of(new Block(MON, 60, 60))));
        assertThrows(IllegalArgumentException.class, () -> WeeklySchedule.of(List.of(new Block(MON, -5, 60))));
        assertThrows(IllegalArgumentException.class, () -> WeeklySchedule.of(List.of(new Block(MON, 0, 1441))));
    }

    // --- Randomized comparison against the brute-force minute grid ---------------

    static List<Block> randomBlocks(Random rnd, int maxBlocks) {
        List<Block> blocks = new ArrayList<>();
        int n = rnd.nextInt(maxBlocks + 1);
        for (int i = 0; i < n; i++) {
            int day = 1 + rnd.nextInt(7);
            // Quarter-hour boundaries like a real UI, plus some arbitrary minutes.
            int start = rnd.nextBoolean() ? 15 * rnd.nextInt(96) : rnd.nextInt(1439);
            int end = Math.min(Interval.MINUTES_PER_DAY, start + 1 + rnd.nextInt(300));
            blocks.add(new Block(day, start, end));
        }
        return blocks;
    }

    @Test
    void matchesBruteForceOnRandomSchedules() {
        Random rnd = new Random(20260929);
        for (int trial = 0; trial < 3_000; trial++) {
            List<Block> a = randomBlocks(rnd, 12);
            List<Block> b = randomBlocks(rnd, 12);
            WeeklySchedule sa = WeeklySchedule.of(a);
            WeeklySchedule sb = WeeklySchedule.of(b);
            MinuteGrid ga = MinuteGrid.of(a);
            MinuteGrid gb = MinuteGrid.of(b);

            assertEquals(ga.runs(), sa.blocks(), "normalization, trial " + trial);
            MinuteGrid both = ga.and(gb);
            WeeklySchedule inter = sa.intersect(sb);
            assertEquals(both.runs(), inter.blocks(), "intersection, trial " + trial);
            assertEquals(both.runs().stream().mapToInt(x -> x.end() - x.start()).sum(), inter.totalMinutes());
            assertEquals(both.runsAtLeast(60), inter.atLeast(60).blocks(), "atLeast, trial " + trial);
            assertEquals(inter, sb.intersect(sa), "commutative, trial " + trial);
        }
    }

    @Test
    void intersectAllMatchesBruteForce() {
        Random rnd = new Random(7);
        for (int trial = 0; trial < 500; trial++) {
            int members = 1 + rnd.nextInt(6);
            List<WeeklySchedule> schedules = new ArrayList<>();
            MinuteGrid common = null;
            for (int k = 0; k < members; k++) {
                List<Block> blocks = randomBlocks(rnd, 15);
                schedules.add(WeeklySchedule.of(blocks));
                MinuteGrid g = MinuteGrid.of(blocks);
                common = common == null ? g : common.and(g);
            }
            assertEquals(common.runs(), WeeklySchedule.intersectAll(schedules).blocks(), "trial " + trial);
        }
    }
}
