package com.aakashmunawar.studygroups.repo;

import com.aakashmunawar.studygroups.domain.Student;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudentRepository extends JpaRepository<Student, Long> {

    Optional<Student> findByEmail(String email);

    boolean existsByEmail(String email);

    /** (studentId, courseId) for every other student taking any of the given courses. */
    @Query("""
            select s.id, c.id from Student s join s.courses c
            where c.id in :courseIds and s.id <> :excludeId""")
    List<Object[]> findEnrollments(@Param("courseIds") Collection<Long> courseIds,
                                   @Param("excludeId") long excludeId);

    List<Student> findByIdIn(Collection<Long> ids);
}
