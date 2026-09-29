package com.aakashmunawar.studygroups.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "students")
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** Part of the demo sandbox (see DemoSeeder); invisible to real students. */
    @Column(nullable = false)
    private boolean demo;

    @ManyToMany
    @JoinTable(name = "student_courses",
            joinColumns = @JoinColumn(name = "student_id"),
            inverseJoinColumns = @JoinColumn(name = "course_id"))
    private Set<Course> courses = new HashSet<>();

    @OneToMany(mappedBy = "student", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("dayOfWeek, startMinute")
    private List<AvailabilityBlock> availability = new ArrayList<>();

    protected Student() {
    }

    public Student(String email, String passwordHash, String displayName) {
        this(email, passwordHash, displayName, false);
    }

    public Student(String email, String passwordHash, String displayName, boolean demo) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.demo = demo;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isDemo() { return demo; }
    public Set<Course> getCourses() { return courses; }
    public List<AvailabilityBlock> getAvailability() { return availability; }
}
