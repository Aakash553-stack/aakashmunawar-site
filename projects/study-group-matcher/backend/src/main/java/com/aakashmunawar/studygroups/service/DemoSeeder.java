package com.aakashmunawar.studygroups.service;

import com.aakashmunawar.studygroups.domain.*;
import com.aakashmunawar.studygroups.repo.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the demo sandbox at startup, so visitors can try the app with one
 * click (recruiter@example.com / demo1234).
 *
 * <ul>
 *   <li>Every demo account is flagged {@code demo} and named "... (demo)". The
 *       API keeps the two worlds apart: real students never see demo accounts
 *       or their groups, and the demo account never sees real students.</li>
 *   <li>Only the recruiter account can log in; the other demo accounts have no
 *       usable password.</li>
 *   <li>Existing demo data is deleted and recreated on each startup, so changes
 *       made by visitors last until the next restart (on Render's free tier the
 *       server restarts after 15 idle minutes). Real accounts are never touched.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true", matchIfMissing = true)
public class DemoSeeder implements ApplicationRunner {

    public static final String DEMO_EMAIL_DOMAIN = "example.com";
    public static final String DEMO_EMAIL = "recruiter@" + DEMO_EMAIL_DOMAIN;
    public static final String DEMO_PASSWORD = "demo1234";
    /** Not a BCrypt hash, so no password ever matches it. */
    private static final String NO_LOGIN = "!demo-account-without-login";

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);
    private static final int MON = 1, TUE = 2, WED = 3, THU = 4, FRI = 5;

    private final StudentRepository students;
    private final StudyGroupRepository groups;
    private final CourseRepository courses;
    private final PasswordEncoder passwords;

    public DemoSeeder(StudentRepository students, StudyGroupRepository groups,
                      CourseRepository courses, PasswordEncoder passwords) {
        this.students = students;
        this.groups = groups;
        this.courses = courses;
        this.passwords = passwords;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        reset();
        log.info("Demo sandbox ready: log in as {} / {}", DEMO_EMAIL, DEMO_PASSWORD);
    }

    @Transactional
    public void reset() {
        // Remove the previous demo sandbox. Demo groups only ever contain demo
        // students, so deleting them never affects a real account.
        groups.deleteAll(groups.findByOwnerDemoTrue());
        groups.flush();
        students.deleteAll(students.findByDemoTrue());
        students.flush();

        Map<String, Course> c = courses.findAll().stream()
                .collect(Collectors.toMap(Course::getCode, x -> x));

        // The account visitors log in as.
        Student recruiter = demo(DEMO_EMAIL, "Recruiter (demo)", passwords.encode(DEMO_PASSWORD),
                List.of(c.get("198:112"), c.get("198:205"), c.get("640:250")),
                new int[][] {{MON, 10, 0, 13, 0}, {TUE, 14, 0, 17, 0}, {WED, 10, 0, 12, 0},
                             {THU, 14, 0, 17, 0}, {FRI, 13, 0, 15, 0}});

        // Classmates. Times were chosen so the recruiter sees a spread of results:
        // two groups to join, two classmates to start a group with (Priya is in a
        // Data Structures group but has none for Linear Algebra), and one group
        // they're already in. DemoTest checks the exact ranking.
        Student priya = demo("priya.demo@" + DEMO_EMAIL_DOMAIN, "Priya (demo)", NO_LOGIN,
                List.of(c.get("198:112"), c.get("640:250")),
                new int[][] {{MON, 11, 0, 14, 0}, {THU, 15, 0, 18, 0}, {FRI, 12, 0, 15, 0}});
        Student marcus = demo("marcus.demo@" + DEMO_EMAIL_DOMAIN, "Marcus (demo)", NO_LOGIN,
                List.of(c.get("198:112"), c.get("198:211")),
                new int[][] {{MON, 10, 0, 13, 0}, {THU, 14, 0, 17, 0}});
        Student jordan = demo("jordan.demo@" + DEMO_EMAIL_DOMAIN, "Jordan (demo)", NO_LOGIN,
                List.of(c.get("640:250")),
                new int[][] {{TUE, 14, 0, 16, 30}, {WED, 9, 0, 12, 0}});
        Student sam = demo("sam.demo@" + DEMO_EMAIL_DOMAIN, "Sam (demo)", NO_LOGIN,
                List.of(c.get("640:250")),
                new int[][] {{TUE, 13, 30, 17, 0}, {WED, 10, 0, 12, 0}});
        Student elena = demo("elena.demo@" + DEMO_EMAIL_DOMAIN, "Elena (demo)", NO_LOGIN,
                List.of(c.get("198:205")),
                new int[][] {{WED, 10, 0, 12, 0}, {FRI, 13, 0, 15, 0}});
        demo("noah.demo@" + DEMO_EMAIL_DOMAIN, "Noah (demo)", NO_LOGIN,
                List.of(c.get("198:112"), c.get("640:250")),
                new int[][] {{MON, 10, 0, 12, 0}, {TUE, 15, 0, 17, 0}});

        group(c.get("198:112"), "Data Structures review (demo)",
                "Going over each week's DS assignment before it's due.", 4, priya, marcus);
        group(c.get("640:250"), "Linear algebra problem sets (demo)",
                "Working through the problem sets together.", 5, jordan, sam);
        group(c.get("198:205"), "Discrete math study hall (demo)",
                "Proof practice and exam review.", 4, elena, recruiter);
    }

    private Student demo(String email, String name, String passwordHash, List<Course> courseList, int[][] free) {
        Student s = new Student(email, passwordHash, name, true);
        s.getCourses().addAll(courseList);
        for (int[] f : free) {
            s.getAvailability().add(new AvailabilityBlock(s, f[0], f[1] * 60 + f[2], f[3] * 60 + f[4]));
        }
        return students.save(s);
    }

    private void group(Course course, String name, String description, int capacity,
                       Student owner, Student... others) {
        StudyGroup g = new StudyGroup(course, name, description, capacity, owner);
        g.getMembers().add(new GroupMembership(g, owner));
        for (Student s : others) {
            g.getMembers().add(new GroupMembership(g, s));
        }
        groups.save(g);
    }
}
