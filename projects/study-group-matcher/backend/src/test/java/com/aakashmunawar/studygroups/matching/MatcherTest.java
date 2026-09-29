package com.aakashmunawar.studygroups.matching;

import static org.junit.jupiter.api.Assertions.*;

import com.aakashmunawar.studygroups.matching.Matcher.GroupCandidate;
import com.aakashmunawar.studygroups.matching.Matcher.Kind;
import com.aakashmunawar.studygroups.matching.Matcher.PeerCandidate;
import com.aakashmunawar.studygroups.matching.Matcher.Recommendation;
import com.aakashmunawar.studygroups.matching.Matcher.Seeker;
import com.aakashmunawar.studygroups.matching.WeeklySchedule.Block;
import java.util.*;
import org.junit.jupiter.api.Test;

class MatcherTest {

    static final long ME = 1, CS112 = 100, MATH151 = 200, CS205 = 300;
    static final Matcher matcher = new Matcher();   // 60-minute minimum session

    static int hm(int h, int m) { return h * 60 + m; }

    static WeeklySchedule free(int day, int startH, int endH) {
        return WeeklySchedule.of(List.of(new Block(day, hm(startH, 0), hm(endH, 0))));
    }

    static WeeklySchedule free(Block... blocks) {
        return WeeklySchedule.of(List.of(blocks));
    }

    static Block b(int day, int startH, int startM, int endH, int endM) {
        return new Block(day, hm(startH, startM), hm(endH, endM));
    }

    static Seeker seeker(WeeklySchedule time, Set<Long> courses, Set<Long> withGroup) {
        return new Seeker(ME, courses, withGroup, time);
    }

    static GroupCandidate group(long id, long course, int capacity, WeeklySchedule... members) {
        Map<Long, WeeklySchedule> m = new LinkedHashMap<>();
        for (int i = 0; i < members.length; i++) {
            m.put(1000 * id + i, members[i]);
        }
        return new GroupCandidate(id, course, capacity, m);
    }

    static List<Recommendation> run(Seeker s, List<GroupCandidate> groups, List<PeerCandidate> peers) {
        return matcher.recommend(s, groups, peers, 50);
    }

    // --- Filtering rules ----------------------------------------------------------

    @Test
    void requiresASharedCourse() {
        Seeker s = seeker(free(1, 9, 17), Set.of(CS112), Set.of());
        var recs = run(s, List.of(group(1, MATH151, 5, free(1, 9, 17))),
                List.of(new PeerCandidate(2, Set.of(MATH151), free(1, 9, 17))));
        assertTrue(recs.isEmpty());
    }

    @Test
    void skipsFullGroupsAndGroupsImAlreadyIn() {
        Seeker s = seeker(free(1, 9, 17), Set.of(CS112), Set.of());
        GroupCandidate full = group(1, CS112, 2, free(1, 9, 17), free(1, 9, 17));
        GroupCandidate mine = new GroupCandidate(2, CS112, 5, Map.of(ME, free(1, 9, 17), 7L, free(1, 9, 17)));
        GroupCandidate open = group(3, CS112, 5, free(1, 9, 17));
        var recs = run(s, List.of(full, mine, open), List.of());
        assertEquals(List.of(3L), recs.stream().map(Recommendation::id).toList());
    }

    @Test
    void skipsCoursesWhereIAlreadyHaveAGroup() {
        Seeker s = seeker(free(1, 9, 17), Set.of(CS112, MATH151), Set.of(CS112));
        var recs = run(s,
                List.of(group(1, CS112, 5, free(1, 9, 17)), group(2, MATH151, 5, free(1, 9, 17))),
                List.of(new PeerCandidate(5, Set.of(CS112), free(1, 9, 17))));
        assertEquals(List.of(2L), recs.stream().map(Recommendation::id).toList());
    }

    @Test
    void peerOnlyCountsCoursesWhereNeitherHasAGroup() {
        Seeker s = seeker(free(1, 9, 17), Set.of(CS112, MATH151, CS205), Set.of(CS205));
        // Peer is groupless in CS112 and CS205 (the latter excluded: I have a CS205 group).
        var recs = run(s, List.of(), List.of(new PeerCandidate(5, Set.of(CS112, CS205), free(1, 9, 17))));
        assertEquals(1, recs.size());
        assertEquals(List.of(CS112), recs.get(0).sharedCourseIds());
    }

    @Test
    void neverRecommendsMyself() {
        Seeker s = seeker(free(1, 9, 17), Set.of(CS112), Set.of());
        assertTrue(run(s, List.of(), List.of(new PeerCandidate(ME, Set.of(CS112), free(1, 9, 17)))).isEmpty());
    }

    // --- Overlap rules ------------------------------------------------------------

    @Test
    void groupTimeIsWhenEveryMemberIsFree() {
        Seeker s = seeker(free(1, 8, 20), Set.of(CS112), Set.of());
        // Members free 9-15 and 12-18: all three of us overlap only 12-15.
        var recs = run(s, List.of(group(1, CS112, 5, free(1, 9, 15), free(1, 12, 18))), List.of());
        assertEquals(1, recs.size());
        assertEquals(List.of(new Block(1, hm(12, 0), hm(15, 0))), recs.get(0).overlap().blocks());
        assertEquals(180, recs.get(0).usableMinutes());
    }

    @Test
    void aMemberWithNoFreeTimeMeansNoCommonTime() {
        Seeker s = seeker(free(1, 8, 20), Set.of(CS112), Set.of());
        assertTrue(run(s, List.of(group(1, CS112, 5, free(1, 9, 15), WeeklySchedule.EMPTY)), List.of()).isEmpty());
    }

    @Test
    void blocksShorterThanASessionDoNotCount() {
        Seeker s = seeker(free(b(1, 9, 0, 9, 59), b(2, 9, 0, 10, 0)), Set.of(CS112), Set.of());
        var recs = run(s, List.of(), List.of(new PeerCandidate(5, Set.of(CS112), free(b(1, 8, 0, 12, 0), b(2, 8, 0, 12, 0)))));
        assertEquals(60, recs.get(0).usableMinutes());           // Tuesday's 60 counts, Monday's 59 does not
        assertEquals(List.of(new Block(2, hm(9, 0), hm(10, 0))), recs.get(0).overlap().blocks());
    }

    @Test
    void sharingACourseButNeverFreeTogetherIsNotAMatch() {
        Seeker s = seeker(free(1, 9, 12), Set.of(CS112), Set.of());
        assertTrue(run(s, List.of(), List.of(new PeerCandidate(5, Set.of(CS112), free(2, 9, 12)))).isEmpty());
    }

    // --- Ranking ------------------------------------------------------------------

    @Test
    void ranksByUsableMinutesFirst() {
        Seeker s = seeker(free(b(1, 8, 0, 20, 0), b(3, 8, 0, 20, 0)), Set.of(CS112), Set.of());
        var recs = run(s, List.of(group(1, CS112, 5, free(1, 9, 11)), group(2, CS112, 5, free(1, 9, 13))),
                List.of(new PeerCandidate(9, Set.of(CS112), free(3, 9, 12))));
        assertEquals(List.of(2L, 9L, 1L), recs.stream().map(Recommendation::id).toList());   // 240, 180, 120
    }

    @Test
    void tiesBrokenByLongestBlock() {
        Seeker s = seeker(free(b(1, 8, 0, 20, 0), b(2, 8, 0, 20, 0)), Set.of(CS112), Set.of());
        // Both give 120 minutes: one 2-hour block beats two 1-hour blocks.
        var recs = run(s, List.of(),
                List.of(new PeerCandidate(5, Set.of(CS112), free(b(1, 9, 0, 10, 0), b(2, 9, 0, 10, 0))),
                        new PeerCandidate(6, Set.of(CS112), free(1, 9, 11))));
        assertEquals(List.of(6L, 5L), recs.stream().map(Recommendation::id).toList());
    }

    @Test
    void thenBySharedCourseCount() {
        Seeker s = seeker(free(1, 8, 20), Set.of(CS112, MATH151), Set.of());
        var recs = run(s, List.of(),
                List.of(new PeerCandidate(5, Set.of(CS112), free(1, 9, 11)),
                        new PeerCandidate(6, Set.of(CS112, MATH151), free(1, 9, 11))));
        assertEquals(List.of(6L, 5L), recs.stream().map(Recommendation::id).toList());
        assertEquals(List.of(CS112, MATH151), recs.get(0).sharedCourseIds());
    }

    @Test
    void thenByDaysThenGroupsBeforePeers() {
        Seeker s = seeker(free(b(1, 8, 0, 20, 0), b(2, 8, 0, 20, 0)), Set.of(CS112), Set.of());
        // 120 min each, longest 60 each, 1 course each. Two days beats one day...
        var recs = run(s, List.of(group(1, CS112, 5, free(b(1, 9, 0, 10, 0), b(1, 12, 0, 13, 0)))),
                List.of(new PeerCandidate(5, Set.of(CS112), free(b(1, 9, 0, 10, 0), b(2, 9, 0, 10, 0)))));
        assertEquals(List.of(5L, 1L), recs.stream().map(Recommendation::id).toList());
        // ...and on a full tie the existing group comes first.
        var tie = run(s, List.of(group(1, CS112, 5, free(1, 9, 11))),
                List.of(new PeerCandidate(5, Set.of(CS112), free(1, 9, 11))));
        assertEquals(List.of(Kind.GROUP, Kind.PEER), tie.stream().map(Recommendation::kind).toList());
    }

    @Test
    void respectsLimit() {
        Seeker s = seeker(free(1, 8, 20), Set.of(CS112), Set.of());
        List<PeerCandidate> peers = new ArrayList<>();
        for (long id = 10; id < 30; id++) {
            peers.add(new PeerCandidate(id, Set.of(CS112), free(1, 9, 11)));
        }
        assertEquals(5, matcher.recommend(s, List.of(), peers, 5).size());
    }

    // --- Randomized comparison against a naive reference implementation ------------

    /**
     * Recomputes every recommendation with the minute grid and plain loops, then
     * sorts with an independently written comparison. Must agree exactly with
     * Matcher on random populations.
     */
    static List<long[]> reference(Seeker s, Map<Long, List<Block>> seekerBlocks,
                                  List<GroupCandidate> groups, Map<Long, List<List<Block>>> groupBlocks,
                                  List<PeerCandidate> peers, Map<Long, List<Block>> peerBlocks, int minSession) {
        List<long[]> rows = new ArrayList<>();   // {kindOrdinal, id, usable, longest, shared, days}
        MinuteGrid mine = MinuteGrid.of(seekerBlocks.get(ME));
        for (GroupCandidate g : groups) {
            if (!s.courseIds().contains(g.courseId()) || s.courseIdsWithGroup().contains(g.courseId())) continue;
            if (g.memberFreeTime().containsKey(ME) || g.memberFreeTime().size() >= g.capacity()) continue;
            MinuteGrid common = mine;
            for (List<Block> member : groupBlocks.get(g.groupId())) common = common.and(MinuteGrid.of(member));
            addRow(rows, 0, g.groupId(), 1, common.runsAtLeast(minSession));
        }
        for (PeerCandidate p : peers) {
            if (p.studentId() == ME) continue;
            int shared = 0;
            for (long c : p.courseIdsWithoutGroup()) {
                if (s.courseIds().contains(c) && !s.courseIdsWithGroup().contains(c)) shared++;
            }
            if (shared == 0) continue;
            addRow(rows, 1, p.studentId(), shared, mine.and(MinuteGrid.of(peerBlocks.get(p.studentId()))).runsAtLeast(minSession));
        }
        rows.sort((x, y) -> {
            for (int k : new int[] {2, 3, 4, 5}) if (x[k] != y[k]) return Long.compare(y[k], x[k]);
            if (x[0] != y[0]) return Long.compare(x[0], y[0]);
            return Long.compare(x[1], y[1]);
        });
        return rows;
    }

    static void addRow(List<long[]> rows, int kind, long id, int shared, List<Block> usable) {
        if (usable.isEmpty()) return;
        long total = 0, longest = 0;
        Set<Integer> days = new HashSet<>();
        for (Block u : usable) {
            total += u.end() - u.start();
            longest = Math.max(longest, u.end() - u.start());
            days.add(u.dayOfWeek());
        }
        rows.add(new long[] {kind, id, total, longest, shared, days.size()});
    }

    @Test
    void matchesNaiveReferenceOnRandomPopulations() {
        Random rnd = new Random(42);
        long[] courses = {CS112, MATH151, CS205, 400, 500};
        int compared = 0;
        for (int trial = 0; trial < 1_000; trial++) {
            int minSession = List.of(30, 60, 90).get(rnd.nextInt(3));
            Map<Long, List<Block>> seekerBlocks = Map.of(ME, WeeklyScheduleTest.randomBlocks(rnd, 14));
            Set<Long> myCourses = new HashSet<>(), withGroup = new HashSet<>();
            for (long c : courses) {
                if (rnd.nextInt(3) > 0) { myCourses.add(c); if (rnd.nextInt(4) == 0) withGroup.add(c); }
            }
            Seeker s = new Seeker(ME, myCourses, withGroup, WeeklySchedule.of(seekerBlocks.get(ME)));

            List<GroupCandidate> groups = new ArrayList<>();
            Map<Long, List<List<Block>>> groupBlocks = new HashMap<>();
            int groupCount = rnd.nextInt(9);
            for (long gid = 1; gid <= groupCount; gid++) {
                int size = 1 + rnd.nextInt(4);
                Map<Long, WeeklySchedule> members = new LinkedHashMap<>();
                List<List<Block>> raw = new ArrayList<>();
                for (int k = 0; k < size; k++) {
                    List<Block> bl = WeeklyScheduleTest.randomBlocks(rnd, 14);
                    raw.add(bl);
                    members.put(rnd.nextInt(10) == 0 && !members.containsKey(ME) ? ME : 10_000 * gid + k,
                            WeeklySchedule.of(bl));
                }
                if (members.size() < raw.size()) raw = raw.subList(0, members.size());
                groupBlocks.put(gid, raw);
                groups.add(new GroupCandidate(gid, courses[rnd.nextInt(courses.length)], 2 + rnd.nextInt(4), members));
            }

            List<PeerCandidate> peers = new ArrayList<>();
            Map<Long, List<Block>> peerBlocks = new HashMap<>();
            int peerCount = rnd.nextInt(13);
            for (long pid = 2; pid < 2 + peerCount; pid++) {
                Set<Long> theirs = new HashSet<>();
                for (long c : courses) if (rnd.nextInt(3) == 0) theirs.add(c);
                List<Block> bl = WeeklyScheduleTest.randomBlocks(rnd, 14);
                peerBlocks.put(pid, bl);
                peers.add(new PeerCandidate(pid, theirs, WeeklySchedule.of(bl)));
            }

            List<Recommendation> actual = new Matcher(minSession).recommend(s, groups, peers, 1_000);
            List<long[]> expected = reference(s, seekerBlocks, groups, groupBlocks, peers, peerBlocks, minSession);
            assertEquals(expected.size(), actual.size(), "count, trial " + trial);
            for (int i = 0; i < expected.size(); i++) {
                long[] e = expected.get(i);
                Recommendation a = actual.get(i);
                assertArrayEquals(e, new long[] {a.kind().ordinal(), a.id(), a.usableMinutes(),
                        a.longestBlockMinutes(), a.sharedCourseIds().size(), a.daysWithTime()},
                        "rank " + i + ", trial " + trial);
            }
            compared += expected.size();
        }
        System.out.println("MatcherTest: compared " + compared + " recommendations against the reference");
        assertTrue(compared > 1_000, "random scenarios should produce plenty of matches, got " + compared);
    }
}
