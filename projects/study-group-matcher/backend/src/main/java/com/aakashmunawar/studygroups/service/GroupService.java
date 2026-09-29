package com.aakashmunawar.studygroups.service;

import com.aakashmunawar.studygroups.api.ApiException;
import com.aakashmunawar.studygroups.api.Dtos.*;
import com.aakashmunawar.studygroups.domain.*;
import com.aakashmunawar.studygroups.matching.WeeklySchedule;
import com.aakashmunawar.studygroups.repo.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Group rules: you must take a group's course to create or join it, you can be
 * in at most one group per course, and groups have a fixed capacity.
 */
@Service
@Transactional
public class GroupService {

    private final StudyGroupRepository groups;
    private final CourseRepository courses;
    private final StudentService studentService;

    public GroupService(StudyGroupRepository groups, CourseRepository courses, StudentService studentService) {
        this.groups = groups;
        this.courses = courses;
        this.studentService = studentService;
    }

    @Transactional(readOnly = true)
    public List<GroupDto> forCourse(long viewerId, long courseId) {
        return groups.findWithMembersByCourseIds(List.of(courseId)).stream()
                .map(g -> Mapper.group(g, viewerId)).toList();
    }

    @Transactional(readOnly = true)
    public List<GroupDto> mine(long viewerId) {
        return groups.findByMember(viewerId).stream().map(g -> Mapper.group(g, viewerId)).toList();
    }

    @Transactional(readOnly = true)
    public GroupDetailDto detail(long viewerId, long groupId) {
        StudyGroup g = groups.findById(groupId).orElseThrow(() -> ApiException.notFound("Group"));
        List<MemberDto> members = g.getMembers().stream().map(m -> Mapper.member(m.getStudent(), g)).toList();
        WeeklySchedule common = WeeklySchedule.intersectAll(
                g.getMembers().stream().map(m -> Mapper.schedule(m.getStudent())).toList());
        return new GroupDetailDto(Mapper.group(g, viewerId), members, Mapper.blocks(common));
    }

    public GroupDto create(long viewerId, CreateGroupRequest req) {
        Student me = studentService.load(viewerId);
        Course course = courses.findById(req.courseId()).orElseThrow(() -> ApiException.notFound("Course"));
        requireEligible(me, course);
        String description = req.description() == null || req.description().isBlank() ? null : req.description().trim();
        StudyGroup g = new StudyGroup(course, req.name().trim(), description, req.capacity(), me);
        g.getMembers().add(new GroupMembership(g, me));
        return Mapper.group(groups.save(g), viewerId);
    }

    public GroupDto join(long viewerId, long groupId) {
        // Row lock: concurrent joins for the last seat are serialized here.
        StudyGroup g = groups.findByIdForUpdate(groupId).orElseThrow(() -> ApiException.notFound("Group"));
        if (g.hasMember(viewerId)) {
            throw ApiException.conflict("You're already in this group");
        }
        Student me = studentService.load(viewerId);
        requireEligible(me, g.getCourse());
        if (g.isFull()) {
            throw ApiException.conflict("This group is full");
        }
        g.getMembers().add(new GroupMembership(g, me));
        return Mapper.group(g, viewerId);
    }

    public void leave(long viewerId, long groupId) {
        StudyGroup g = groups.findByIdForUpdate(groupId).orElseThrow(() -> ApiException.notFound("Group"));
        if (!g.getMembers().removeIf(m -> m.getStudent().getId().equals(viewerId))) {
            throw ApiException.conflict("You're not in this group");
        }
        if (g.getMembers().isEmpty()) {
            groups.delete(g);
        } else if (g.getOwner().getId().equals(viewerId)) {
            g.setOwner(g.getMembers().get(0).getStudent());   // longest-standing member
        }
    }

    private void requireEligible(Student me, Course course) {
        if (!StudentService.courseIds(me).contains(course.getId())) {
            throw ApiException.conflict("Add " + course.getCode() + " to your courses first");
        }
        boolean hasGroup = groups.findByMember(me.getId()).stream()
                .anyMatch(other -> other.getCourse().getId().equals(course.getId()));
        if (hasGroup) {
            throw ApiException.conflict("You're already in a " + course.getCode() + " group");
        }
    }
}
