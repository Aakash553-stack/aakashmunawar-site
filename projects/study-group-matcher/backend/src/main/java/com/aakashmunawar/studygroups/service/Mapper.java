package com.aakashmunawar.studygroups.service;

import com.aakashmunawar.studygroups.api.Dtos.*;
import com.aakashmunawar.studygroups.domain.*;
import com.aakashmunawar.studygroups.matching.WeeklySchedule;
import java.util.Comparator;
import java.util.List;

/** Entity -> response conversions. */
final class Mapper {

    private Mapper() {
    }

    static CourseDto course(Course c) {
        return new CourseDto(c.getId(), c.getCode(), c.getTitle());
    }

    static List<BlockDto> blocks(WeeklySchedule s) {
        return s.blocks().stream().map(b -> new BlockDto(b.dayOfWeek(), b.start(), b.end())).toList();
    }

    static WeeklySchedule schedule(Student s) {
        return WeeklySchedule.of(s.getAvailability().stream()
                .map(a -> new WeeklySchedule.Block(a.getDayOfWeek(), a.getStartMinute(), a.getEndMinute()))
                .toList());
    }

    static ProfileDto profile(Student s) {
        List<CourseDto> courses = s.getCourses().stream()
                .sorted(Comparator.comparing(Course::getCode)).map(Mapper::course).toList();
        return new ProfileDto(s.getId(), s.getEmail(), s.getDisplayName(), s.isDemo(), courses, blocks(schedule(s)));
    }

    static GroupDto group(StudyGroup g, long viewerId) {
        return new GroupDto(g.getId(), g.getName(), g.getDescription(), course(g.getCourse()),
                g.getCapacity(), g.getMembers().size(), g.hasMember(viewerId),
                g.getOwner().getId().equals(viewerId));
    }

    static MemberDto member(Student s, StudyGroup g) {
        return new MemberDto(s.getId(), s.getDisplayName(), g != null && g.getOwner().getId().equals(s.getId()));
    }
}
