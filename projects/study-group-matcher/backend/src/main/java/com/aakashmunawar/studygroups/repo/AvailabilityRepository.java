package com.aakashmunawar.studygroups.repo;

import com.aakashmunawar.studygroups.domain.AvailabilityBlock;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AvailabilityRepository extends JpaRepository<AvailabilityBlock, Long> {

    /** (studentId, day, start, end) for many students in one query. */
    @Query("""
            select a.student.id, a.dayOfWeek, a.startMinute, a.endMinute
            from AvailabilityBlock a where a.student.id in :studentIds""")
    List<Object[]> findBlocks(@Param("studentIds") Collection<Long> studentIds);
}
