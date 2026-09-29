package com.aakashmunawar.studygroups.service;

import com.aakashmunawar.studygroups.api.Dtos.*;
import com.aakashmunawar.studygroups.domain.*;
import com.aakashmunawar.studygroups.matching.Matcher;
import com.aakashmunawar.studygroups.matching.Matcher.Recommendation;
import com.aakashmunawar.studygroups.matching.WeeklySchedule;
import com.aakashmunawar.studygroups.repo.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads what the matcher needs in a fixed number of queries (no per-student
 * round trips), runs {@link Matcher}, and turns the results into responses.
 */
@Service
@Transactional(readOnly = true)
public class MatchService {

    private final Matcher matcher = new Matcher();
    private final StudentService studentService;
    private final StudentRepository students;
    private final StudyGroupRepository groups;
    private final AvailabilityRepository availability;
    private final CourseRepository courses;

    public MatchService(StudentService studentService, StudentRepository students, StudyGroupRepository groups,
                        AvailabilityRepository availability, CourseRepository courses) {
        this.studentService = studentService;
        this.students = students;
        this.groups = groups;
        this.availability = availability;
        this.courses = courses;
    }

    public List<RecommendationDto> recommend(long me, int limit) {
        Student seeker = studentService.load(me);
        Set<Long> myCourses = StudentService.courseIds(seeker);
        if (myCourses.isEmpty()) {
            return List.of();
        }

        // 1. Who is already in a group, per course (for my courses only).
        Map<Long, Set<Long>> groupCoursesByStudent = new HashMap<>();
        for (Object[] row : groups.findMembershipsByCourseIds(myCourses)) {
            groupCoursesByStudent.computeIfAbsent((Long) row[0], k -> new HashSet<>()).add((Long) row[1]);
        }

        // 2. Existing groups for my courses, with members.
        List<StudyGroup> candidateGroups = groups.findWithMembersByCourseIds(myCourses);

        // 3. Classmates, with the shared courses in which they have no group.
        Map<Long, Set<Long>> peerCourses = new HashMap<>();
        for (Object[] row : students.findEnrollments(myCourses, me)) {
            long student = (Long) row[0];
            long course = (Long) row[1];
            if (!groupCoursesByStudent.getOrDefault(student, Set.of()).contains(course)) {
                peerCourses.computeIfAbsent(student, k -> new HashSet<>()).add(course);
            }
        }

        // 4. Free time for everyone involved, in one query.
        Set<Long> ids = new HashSet<>(peerCourses.keySet());
        ids.add(me);
        candidateGroups.forEach(g -> g.getMembers().forEach(m -> ids.add(m.getStudent().getId())));
        Map<Long, WeeklySchedule> free = schedules(ids);

        Matcher.Seeker s = new Matcher.Seeker(me, myCourses,
                groupCoursesByStudent.getOrDefault(me, Set.of()), free.get(me));
        List<Matcher.GroupCandidate> groupCandidates = candidateGroups.stream()
                .map(g -> new Matcher.GroupCandidate(g.getId(), g.getCourse().getId(), g.getCapacity(),
                        g.getMembers().stream().collect(Collectors.toMap(
                                m -> m.getStudent().getId(), m -> free.get(m.getStudent().getId()),
                                (a, b) -> a, LinkedHashMap::new))))
                .toList();
        List<Matcher.PeerCandidate> peerCandidates = peerCourses.entrySet().stream()
                .map(e -> new Matcher.PeerCandidate(e.getKey(), e.getValue(), free.get(e.getKey())))
                .toList();

        List<Recommendation> ranked = matcher.recommend(s, groupCandidates, peerCandidates, limit);
        return toDtos(ranked, candidateGroups, me);
    }

    private Map<Long, WeeklySchedule> schedules(Set<Long> studentIds) {
        Map<Long, List<WeeklySchedule.Block>> blocks = new HashMap<>();
        for (Object[] row : availability.findBlocks(studentIds)) {
            blocks.computeIfAbsent((Long) row[0], k -> new ArrayList<>()).add(new WeeklySchedule.Block(
                    ((Number) row[1]).intValue(), ((Number) row[2]).intValue(), ((Number) row[3]).intValue()));
        }
        Map<Long, WeeklySchedule> out = new HashMap<>();
        for (long id : studentIds) {
            out.put(id, WeeklySchedule.of(blocks.getOrDefault(id, List.of())));
        }
        return out;
    }

    private List<RecommendationDto> toDtos(List<Recommendation> ranked, List<StudyGroup> candidateGroups, long me) {
        Map<Long, StudyGroup> groupById = candidateGroups.stream()
                .collect(Collectors.toMap(StudyGroup::getId, Function.identity()));
        Set<Long> peerIds = ranked.stream().filter(r -> r.kind() == Matcher.Kind.PEER)
                .map(Recommendation::id).collect(Collectors.toSet());
        Map<Long, Student> peers = students.findByIdIn(peerIds).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        Set<Long> courseIds = ranked.stream().flatMap(r -> r.sharedCourseIds().stream()).collect(Collectors.toSet());
        Map<Long, CourseDto> courseById = courses.findAllById(courseIds).stream()
                .collect(Collectors.toMap(Course::getId, Mapper::course));

        return ranked.stream().map(r -> new RecommendationDto(
                r.kind().name(),
                r.kind() == Matcher.Kind.GROUP ? Mapper.group(groupById.get(r.id()), me) : null,
                r.kind() == Matcher.Kind.PEER ? Mapper.member(peers.get(r.id()), null) : null,
                r.sharedCourseIds().stream().map(courseById::get).toList(),
                r.usableMinutes(), r.longestBlockMinutes(), r.daysWithTime(),
                Mapper.blocks(r.overlap()))).toList();
    }
}
