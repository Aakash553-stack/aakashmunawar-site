package com.aakashmunawar.studygroups.matching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recommends study groups to join, and classmates to start a group with.
 *
 * <p>Pure logic: callers pass in plain snapshots of the seeker, the existing
 * groups and the other students, so the algorithm can be tested without a
 * database. See the README for a worked explanation.
 *
 * <h2>Rules</h2>
 * <ol>
 *   <li><b>Shared course is required.</b> A group is a candidate only if it is
 *       for a course the seeker takes; a classmate only if they share a course.</li>
 *   <li><b>Only courses where it makes sense.</b> Courses in which the seeker is
 *       already in a group are skipped. Groups that are full or that the seeker
 *       already belongs to are skipped. A classmate counts only for courses in
 *       which neither of them has a group yet.</li>
 *   <li><b>Overlapping free time.</b> For a group, the relevant free time is the
 *       time when <i>every</i> member is free (intersection over members); for a
 *       classmate, it is their own free time. That is intersected with the
 *       seeker's, and only blocks of at least {@code minSessionMinutes}
 *       contiguous minutes count: 20 spare minutes between classes is not a
 *       study session.</li>
 *   <li><b>Ranking</b>, best first, compared field by field:
 *       <ol>
 *         <li>usable overlapping minutes per week (more is better)</li>
 *         <li>longest single usable block (a 2-hour window beats two 1-hour ones)</li>
 *         <li>number of shared courses (a classmate in two of your courses first)</li>
 *         <li>number of days with a usable block (more options for scheduling)</li>
 *         <li>existing groups before classmates, then by id, for a stable order</li>
 *       </ol>
 *   </li>
 * </ol>
 */
public final class Matcher {

    public static final int DEFAULT_MIN_SESSION_MINUTES = 60;

    private final int minSessionMinutes;

    public Matcher() {
        this(DEFAULT_MIN_SESSION_MINUTES);
    }

    public Matcher(int minSessionMinutes) {
        if (minSessionMinutes < 1) {
            throw new IllegalArgumentException("minSessionMinutes must be positive");
        }
        this.minSessionMinutes = minSessionMinutes;
    }

    /** The student asking for recommendations. */
    public record Seeker(long studentId, Set<Long> courseIds, Set<Long> courseIdsWithGroup,
                         WeeklySchedule freeTime) {
    }

    /** An existing group, with each member's free time. */
    public record GroupCandidate(long groupId, long courseId, int capacity,
                                 Map<Long, WeeklySchedule> memberFreeTime) {
    }

    /** Another student, with the courses in which they have no group yet. */
    public record PeerCandidate(long studentId, Set<Long> courseIdsWithoutGroup,
                                WeeklySchedule freeTime) {
    }

    public enum Kind { GROUP, PEER }

    /**
     * One recommendation. {@code overlap} holds only the usable blocks
     * (each at least minSessionMinutes long).
     */
    public record Recommendation(Kind kind, long id, List<Long> sharedCourseIds,
                                 WeeklySchedule overlap, int usableMinutes,
                                 int longestBlockMinutes, int daysWithTime) {
    }

    public static final Comparator<Recommendation> RANKING =
            Comparator.comparingInt(Recommendation::usableMinutes).reversed()
                    .thenComparing(Comparator.comparingInt(Recommendation::longestBlockMinutes).reversed())
                    .thenComparing(Comparator.comparingInt((Recommendation r) -> r.sharedCourseIds().size()).reversed())
                    .thenComparing(Comparator.comparingInt(Recommendation::daysWithTime).reversed())
                    .thenComparing(Recommendation::kind)          // GROUP before PEER
                    .thenComparingLong(Recommendation::id);

    public List<Recommendation> recommend(Seeker seeker, List<GroupCandidate> groups,
                                          List<PeerCandidate> peers, int limit) {
        List<Recommendation> out = new ArrayList<>();

        for (GroupCandidate g : groups) {
            boolean relevantCourse = seeker.courseIds().contains(g.courseId())
                    && !seeker.courseIdsWithGroup().contains(g.courseId());
            boolean joinable = !g.memberFreeTime().containsKey(seeker.studentId())
                    && g.memberFreeTime().size() < g.capacity();
            if (!relevantCourse || !joinable) {
                continue;
            }
            WeeklySchedule groupTime = WeeklySchedule.intersectAll(List.copyOf(g.memberFreeTime().values()));
            score(Kind.GROUP, g.groupId(), List.of(g.courseId()), seeker.freeTime(), groupTime, out);
        }

        for (PeerCandidate p : peers) {
            if (p.studentId() == seeker.studentId()) {
                continue;
            }
            List<Long> shared = p.courseIdsWithoutGroup().stream()
                    .filter(c -> seeker.courseIds().contains(c) && !seeker.courseIdsWithGroup().contains(c))
                    .sorted()
                    .toList();
            if (!shared.isEmpty()) {
                score(Kind.PEER, p.studentId(), shared, seeker.freeTime(), p.freeTime(), out);
            }
        }

        out.sort(RANKING);
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : List.copyOf(out);
    }

    private void score(Kind kind, long id, List<Long> sharedCourses, WeeklySchedule seekerTime,
                       WeeklySchedule theirTime, List<Recommendation> out) {
        WeeklySchedule usable = seekerTime.intersect(theirTime).atLeast(minSessionMinutes);
        if (usable.isEmpty()) {
            return;   // shares a course but never free together for a real session
        }
        out.add(new Recommendation(kind, id, sharedCourses, usable, usable.totalMinutes(),
                usable.longestBlock(), usable.daysWithTime()));
    }
}
