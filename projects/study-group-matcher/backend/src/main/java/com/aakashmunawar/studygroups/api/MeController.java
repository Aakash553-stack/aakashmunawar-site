package com.aakashmunawar.studygroups.api;

import static com.aakashmunawar.studygroups.security.TokenService.studentId;

import com.aakashmunawar.studygroups.api.Dtos.*;
import com.aakashmunawar.studygroups.service.StudentService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** The signed-in student's profile: courses and weekly free time. */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final StudentService students;

    public MeController(StudentService students) {
        this.students = students;
    }

    @GetMapping
    ProfileDto me(@AuthenticationPrincipal Jwt jwt) {
        return students.profile(studentId(jwt));
    }

    @PutMapping("/courses")
    ProfileDto setCourses(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CoursesRequest req) {
        return students.setCourses(studentId(jwt), req);
    }

    @PutMapping("/availability")
    ProfileDto setAvailability(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AvailabilityRequest req) {
        return students.setAvailability(studentId(jwt), req);
    }
}
