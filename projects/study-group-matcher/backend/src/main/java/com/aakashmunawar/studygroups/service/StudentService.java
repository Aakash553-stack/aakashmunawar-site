package com.aakashmunawar.studygroups.service;

import com.aakashmunawar.studygroups.api.ApiException;
import com.aakashmunawar.studygroups.api.Dtos.*;
import com.aakashmunawar.studygroups.domain.*;
import com.aakashmunawar.studygroups.matching.WeeklySchedule;
import com.aakashmunawar.studygroups.repo.*;
import com.aakashmunawar.studygroups.security.TokenService;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class StudentService {

    private final StudentRepository students;
    private final CourseRepository courses;
    private final StudyGroupRepository groups;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    // Compared against when an email is unknown, so a login takes about as long
    // whether or not the account exists.
    private final String dummyHash;

    public StudentService(StudentRepository students, CourseRepository courses, StudyGroupRepository groups,
                          PasswordEncoder passwords, TokenService tokens) {
        this.students = students;
        this.courses = courses;
        this.groups = groups;
        this.passwords = passwords;
        this.tokens = tokens;
        this.dummyHash = passwords.encode("timing-equalizer");
    }

    public AuthResponse signup(SignupRequest req) {
        String email = normalizeEmail(req.email());
        if (email.endsWith("@" + DemoSeeder.DEMO_EMAIL_DOMAIN)) {
            throw ApiException.badRequest("Addresses at " + DemoSeeder.DEMO_EMAIL_DOMAIN + " are reserved for demo accounts");
        }
        if (students.existsByEmail(email)) {
            throw ApiException.conflict("An account with that email already exists");
        }
        Student s = students.save(new Student(email, passwords.encode(req.password()), req.displayName().trim()));
        return new AuthResponse(tokens.issue(s), Mapper.profile(s));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        Optional<Student> found = students.findByEmail(normalizeEmail(req.email()));
        String hash = found.map(Student::getPasswordHash).orElse(dummyHash);
        if (!passwords.matches(req.password(), hash) || found.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Wrong email or password");
        }
        return new AuthResponse(tokens.issue(found.get()), Mapper.profile(found.get()));
    }

    @Transactional(readOnly = true)
    public ProfileDto profile(long studentId) {
        return Mapper.profile(load(studentId));
    }

    public ProfileDto setCourses(long studentId, CoursesRequest req) {
        Student s = load(studentId);
        Set<Long> ids = new LinkedHashSet<>(req.courseIds());
        List<Course> found = courses.findAllById(ids);
        if (found.size() != ids.size()) {
            throw ApiException.badRequest("Unknown course id in " + ids);
        }
        // Dropping a course you have a group for would leave you in a group for
        // a course you don't take: require leaving the group first.
        for (StudyGroup g : groups.findByMember(studentId)) {
            if (!ids.contains(g.getCourse().getId())) {
                throw ApiException.conflict("Leave your " + g.getCourse().getCode()
                        + " group \"" + g.getName() + "\" before removing that course");
            }
        }
        s.getCourses().clear();
        s.getCourses().addAll(found);
        return Mapper.profile(s);
    }

    public ProfileDto setAvailability(long studentId, AvailabilityRequest req) {
        Student s = load(studentId);
        for (BlockDto b : req.blocks()) {
            if (b.start() >= b.end()) {
                throw ApiException.badRequest("Each block must end after it starts: " + b);
            }
        }
        // Store the normalized form: sorted, with overlapping blocks merged.
        WeeklySchedule normalized = WeeklySchedule.of(req.blocks().stream()
                .map(b -> new WeeklySchedule.Block(b.day(), b.start(), b.end())).toList());
        s.getAvailability().clear();
        students.flush();   // delete old rows before inserting replacements
        for (WeeklySchedule.Block b : normalized.blocks()) {
            s.getAvailability().add(new AvailabilityBlock(s, b.dayOfWeek(), b.start(), b.end()));
        }
        return Mapper.profile(s);
    }

    Student load(long studentId) {
        return students.findById(studentId).orElseThrow(() -> ApiException.notFound("Student"));
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    static Set<Long> courseIds(Student s) {
        return s.getCourses().stream().map(Course::getId).collect(Collectors.toSet());
    }
}
