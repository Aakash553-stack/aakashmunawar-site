package com.aakashmunawar.studygroups.domain;

import jakarta.persistence.*;

/** A real Rutgers course, seeded by Flyway (V2). Read-only from the app's side. */
@Entity
@Table(name = "courses")
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String title;

    protected Course() {
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getTitle() { return title; }
}
