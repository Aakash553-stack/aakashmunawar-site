package com.aakashmunawar.studygroups.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "group_members")
public class GroupMembership {

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "group_id")
        private Long groupId;
        @Column(name = "student_id")
        private Long studentId;

        protected Key() {
        }

        Key(Long groupId, Long studentId) {
            this.groupId = groupId;
            this.studentId = studentId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(groupId, k.groupId) && Objects.equals(studentId, k.studentId);
        }

        @Override
        public int hashCode() { return Objects.hash(groupId, studentId); }
    }

    @EmbeddedId
    private Key id = new Key();

    @ManyToOne(optional = false)
    @MapsId("groupId")
    @JoinColumn(name = "group_id")
    private StudyGroup group;

    @ManyToOne(optional = false)
    @MapsId("studentId")
    @JoinColumn(name = "student_id")
    private Student student;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt = Instant.now();

    protected GroupMembership() {
    }

    public GroupMembership(StudyGroup group, Student student) {
        this.group = group;
        this.student = student;
    }

    public StudyGroup getGroup() { return group; }
    public Student getStudent() { return student; }
    public Instant getJoinedAt() { return joinedAt; }
}
