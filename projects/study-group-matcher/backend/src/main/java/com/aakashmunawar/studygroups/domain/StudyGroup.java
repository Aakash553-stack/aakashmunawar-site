package com.aakashmunawar.studygroups.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "study_groups")
public class StudyGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "course_id")
    private Course course;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private short capacity;

    @ManyToOne(optional = false)
    @JoinColumn(name = "owner_id")
    private Student owner;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("joinedAt")
    private List<GroupMembership> members = new ArrayList<>();

    protected StudyGroup() {
    }

    public StudyGroup(Course course, String name, String description, int capacity, Student owner) {
        this.course = course;
        this.name = name;
        this.description = description;
        this.capacity = (short) capacity;
        this.owner = owner;
    }

    public boolean isFull() { return members.size() >= capacity; }

    public boolean hasMember(Long studentId) {
        return members.stream().anyMatch(m -> m.getStudent().getId().equals(studentId));
    }

    public Long getId() { return id; }
    public Course getCourse() { return course; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getCapacity() { return capacity; }
    public Student getOwner() { return owner; }
    public void setOwner(Student owner) { this.owner = owner; }
    public Instant getCreatedAt() { return createdAt; }
    public List<GroupMembership> getMembers() { return members; }
}
