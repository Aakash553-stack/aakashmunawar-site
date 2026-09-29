package com.aakashmunawar.studygroups.api;

import com.aakashmunawar.studygroups.api.Dtos.CourseDto;
import com.aakashmunawar.studygroups.domain.Course;
import com.aakashmunawar.studygroups.repo.CourseRepository;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** The course catalog (public). */
@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseRepository courses;

    public CourseController(CourseRepository courses) {
        this.courses = courses;
    }

    @GetMapping
    List<CourseDto> list(@RequestParam(required = false) String q) {
        List<Course> found = q == null || q.isBlank() ? courses.findAllByOrderByCodeAsc() : courses.search(q.trim());
        return found.stream().map(c -> new CourseDto(c.getId(), c.getCode(), c.getTitle())).toList();
    }
}
