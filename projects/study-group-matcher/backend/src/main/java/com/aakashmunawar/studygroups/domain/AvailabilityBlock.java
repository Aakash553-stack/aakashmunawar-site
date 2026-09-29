package com.aakashmunawar.studygroups.domain;

import jakarta.persistence.*;

/** One weekly block of free time: [startMinute, endMinute) on an ISO day (1 = Mon). */
@Entity
@Table(name = "availability_blocks")
public class AvailabilityBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id")
    private Student student;

    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    @Column(name = "start_minute", nullable = false)
    private short startMinute;

    @Column(name = "end_minute", nullable = false)
    private short endMinute;

    protected AvailabilityBlock() {
    }

    public AvailabilityBlock(Student student, int dayOfWeek, int startMinute, int endMinute) {
        this.student = student;
        this.dayOfWeek = (short) dayOfWeek;
        this.startMinute = (short) startMinute;
        this.endMinute = (short) endMinute;
    }

    public Long getId() { return id; }
    public int getDayOfWeek() { return dayOfWeek; }
    public int getStartMinute() { return startMinute; }
    public int getEndMinute() { return endMinute; }
}
