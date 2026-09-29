package com.aakashmunawar.studygroups.api;

import static com.aakashmunawar.studygroups.security.TokenService.studentId;

import com.aakashmunawar.studygroups.api.Dtos.RecommendationDto;
import com.aakashmunawar.studygroups.service.MatchService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/matches")
@Validated
public class MatchController {

    private final MatchService matches;

    public MatchController(MatchService matches) {
        this.matches = matches;
    }

    /** Ranked recommendations: groups to join and classmates to start a group with. */
    @GetMapping
    List<RecommendationDto> recommend(@AuthenticationPrincipal Jwt jwt,
                                      @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return matches.recommend(studentId(jwt), limit);
    }
}
