package com.aakashmunawar.studygroups.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** Request and response bodies. */
public final class Dtos {

    private Dtos() {
    }

    // --- Requests -----------------------------------------------------------------

    public record SignupRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password,     // BCrypt uses at most 72 bytes
            @NotBlank @Size(max = 60) String displayName) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record CoursesRequest(@NotNull @Size(max = 10) List<@NotNull Long> courseIds) {
    }

    public record BlockDto(
            @Min(1) @Max(7) int day,                 // ISO: 1 = Monday ... 7 = Sunday
            @Min(0) @Max(1439) int start,            // minutes after midnight
            @Min(1) @Max(1440) int end) {
    }

    public record AvailabilityRequest(@NotNull @Size(max = 50) List<@Valid @NotNull BlockDto> blocks) {
    }

    public record CreateGroupRequest(
            @NotNull Long courseId,
            @NotBlank @Size(max = 80) String name,
            @Size(max = 500) String description,
            @Min(2) @Max(12) int capacity) {
    }

    // --- Responses ----------------------------------------------------------------

    public record AuthResponse(String token, ProfileDto student) {
    }

    public record CourseDto(long id, String code, String title) {
    }

    public record ProfileDto(long id, String email, String displayName,
                             List<CourseDto> courses, List<BlockDto> availability) {
    }

    public record MemberDto(long id, String displayName, boolean owner) {
    }

    public record GroupDto(long id, String name, String description, CourseDto course,
                           int capacity, int memberCount, boolean member, boolean owner) {
    }

    public record GroupDetailDto(GroupDto group, List<MemberDto> members, List<BlockDto> commonFreeTime) {
    }

    public record RecommendationDto(
            String kind,                         // "GROUP" (join it) or "PEER" (start one together)
            GroupDto group,                      // set when kind = GROUP
            MemberDto peer,                      // set when kind = PEER
            List<CourseDto> sharedCourses,
            int usableMinutes, int longestBlockMinutes, int daysWithTime,
            List<BlockDto> overlap) {
    }
}
