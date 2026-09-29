package com.aakashmunawar.studygroups.matching;

import com.aakashmunawar.studygroups.matching.Matcher.GroupCandidate;
import com.aakashmunawar.studygroups.matching.Matcher.PeerCandidate;
import com.aakashmunawar.studygroups.matching.Matcher.Seeker;
import com.aakashmunawar.studygroups.matching.WeeklySchedule.Block;
import java.util.*;

/**
 * Timing only; not a unit test. Builds a random population sized like one
 * semester of Rutgers CS + Math students (148 courses), then times the pure
 * matching step for many seekers. The random data exists only here.
 *
 *   ./mvnw -q test-compile && java -cp "target/classes:target/test-classes" \
 *       com.aakashmunawar.studygroups.matching.MatcherBenchmark
 */
public final class MatcherBenchmark {

    public static void main(String[] args) {
        int students = args.length > 0 ? Integer.parseInt(args[0]) : 5_000;
        Random rnd = new Random(1);
        int courseCount = 148;

        // Each student: 4-5 courses, weighted toward intro courses like a real catalog.
        List<Set<Long>> courses = new ArrayList<>();
        List<WeeklySchedule> free = new ArrayList<>();
        for (int s = 0; s < students; s++) {
            Set<Long> cs = new HashSet<>();
            while (cs.size() < 4 + rnd.nextInt(2)) {
                cs.add((long) Math.min(courseCount - 1, (int) Math.abs(rnd.nextGaussian() * 30)));
            }
            courses.add(cs);
            List<Block> blocks = new ArrayList<>();
            for (int k = 0; k < 6 + rnd.nextInt(8); k++) {             // 6-13 free blocks a week
                int day = 1 + rnd.nextInt(7);
                int start = 8 * 60 + 30 * rnd.nextInt(24);
                blocks.add(new Block(day, start, Math.min(1440, start + 60 + 30 * rnd.nextInt(5))));
            }
            free.add(WeeklySchedule.of(blocks));
        }

        // About 30% of students are in one group (groups of 2-5).
        Map<Long, List<Integer>> byCourse = new HashMap<>();
        for (int s = 0; s < students; s++) for (long c : courses.get(s)) byCourse.computeIfAbsent(c, k -> new ArrayList<>()).add(s);
        List<GroupCandidate> groups = new ArrayList<>();
        Map<Integer, Long> groupCourseOf = new HashMap<>();
        long gid = 0;
        for (var e : byCourse.entrySet()) {
            List<Integer> pool = new ArrayList<>(e.getValue());
            Collections.shuffle(pool, rnd);
            int i = 0;
            while (i < pool.size() * 0.3) {
                int size = 2 + rnd.nextInt(4);
                Map<Long, WeeklySchedule> members = new LinkedHashMap<>();
                for (int k = 0; k < size && i < pool.size(); k++, i++) {
                    int s = pool.get(i);
                    if (groupCourseOf.containsKey(s)) continue;
                    groupCourseOf.put(s, e.getKey());
                    members.put((long) s, free.get(s));
                }
                if (!members.isEmpty()) groups.add(new GroupCandidate(++gid, e.getKey(), 6, members));
            }
        }

        Matcher matcher = new Matcher();
        long[] nanos = new long[1_000];
        long candidatesSeen = 0, recs = 0;
        for (int round = -200; round < nanos.length; round++) {       // first 200 rounds warm up the JIT
            int me = rnd.nextInt(students);
            Set<Long> mine = courses.get(me);
            Set<Long> withGroup = groupCourseOf.containsKey(me) ? Set.of(groupCourseOf.get(me)) : Set.of();
            // What MatchService would load from the database for this seeker:
            List<GroupCandidate> gc = groups.stream().filter(g -> mine.contains(g.courseId())).toList();
            List<PeerCandidate> pc = new ArrayList<>();
            for (int s = 0; s < students; s++) {
                if (s == me) continue;
                Set<Long> shared = new HashSet<>(courses.get(s));
                shared.retainAll(mine);
                if (groupCourseOf.containsKey(s)) shared.remove(groupCourseOf.get(s));
                if (!shared.isEmpty()) pc.add(new PeerCandidate(s, shared, free.get(s)));
            }
            Seeker seeker = new Seeker(me, mine, withGroup, free.get(me));
            long t = System.nanoTime();
            List<Matcher.Recommendation> out = matcher.recommend(seeker, gc, pc, 20);
            long dt = System.nanoTime() - t;
            if (round >= 0) {
                nanos[round] = dt;
                candidatesSeen += gc.size() + pc.size();
                recs += out.size();
            }
        }
        Arrays.sort(nanos);
        System.out.printf("students=%d groups=%d | per seeker: avg %.0f candidates (groups + classmates), %.1f shown%n",
                students, groups.size(), candidatesSeen / (double) nanos.length, recs / (double) nanos.length);
        System.out.printf("matching time: median %.2f ms, p95 %.2f ms, max %.2f ms (1,000 seekers)%n",
                nanos[nanos.length / 2] / 1e6, nanos[(int) (nanos.length * 0.95)] / 1e6, nanos[nanos.length - 1] / 1e6);
    }
}
