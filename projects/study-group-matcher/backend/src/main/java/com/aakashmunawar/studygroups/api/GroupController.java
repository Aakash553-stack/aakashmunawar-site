package com.aakashmunawar.studygroups.api;

import static com.aakashmunawar.studygroups.security.TokenService.studentId;

import com.aakashmunawar.studygroups.api.Dtos.*;
import com.aakashmunawar.studygroups.service.GroupService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupService groups;

    public GroupController(GroupService groups) {
        this.groups = groups;
    }

    @GetMapping
    List<GroupDto> forCourse(@AuthenticationPrincipal Jwt jwt, @RequestParam long courseId) {
        return groups.forCourse(studentId(jwt), courseId);
    }

    @GetMapping("/mine")
    List<GroupDto> mine(@AuthenticationPrincipal Jwt jwt) {
        return groups.mine(studentId(jwt));
    }

    @GetMapping("/{id}")
    GroupDetailDto detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return groups.detail(studentId(jwt), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    GroupDto create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateGroupRequest req) {
        return groups.create(studentId(jwt), req);
    }

    @PostMapping("/{id}/join")
    GroupDto join(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return groups.join(studentId(jwt), id);
    }

    @PostMapping("/{id}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void leave(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        groups.leave(studentId(jwt), id);
    }
}
