package com.aakashmunawar.studygroups.api;

import com.aakashmunawar.studygroups.api.Dtos.*;
import com.aakashmunawar.studygroups.service.StudentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final StudentService students;

    public AuthController(StudentService students) {
        this.students = students;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    AuthResponse signup(@Valid @RequestBody SignupRequest req) {
        return students.signup(req);
    }

    @PostMapping("/login")
    AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return students.login(req);
    }
}
