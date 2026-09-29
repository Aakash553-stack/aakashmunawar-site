package com.aakashmunawar.studygroups.repo;

import com.aakashmunawar.studygroups.domain.Course;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseRepository extends JpaRepository<Course, Long> {

    @Query("""
            select c from Course c
            where lower(c.code) like lower(concat('%', :q, '%'))
               or lower(c.title) like lower(concat('%', :q, '%'))
            order by c.code""")
    List<Course> search(@Param("q") String q);

    List<Course> findAllByOrderByCodeAsc();
}
