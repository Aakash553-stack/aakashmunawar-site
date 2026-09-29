package com.aakashmunawar.studygroups.repo;

import com.aakashmunawar.studygroups.domain.StudyGroup;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudyGroupRepository extends JpaRepository<StudyGroup, Long> {

    /** Groups for the given courses, with members loaded in the same query. */
    @Query("""
            select distinct g from StudyGroup g
            left join fetch g.members m left join fetch m.student
            where g.course.id in :courseIds
            order by g.id""")
    List<StudyGroup> findWithMembersByCourseIds(@Param("courseIds") Collection<Long> courseIds);

    @Query("""
            select distinct g from StudyGroup g join g.members m
            where m.student.id = :studentId order by g.id""")
    List<StudyGroup> findByMember(@Param("studentId") long studentId);

    /** Lock the group row so two students can't both take its last seat. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from StudyGroup g where g.id = :id")
    Optional<StudyGroup> findByIdForUpdate(@Param("id") long id);

    /** (studentId, courseId) for every membership in groups for the given courses. */
    @Query("""
            select m.student.id, g.course.id from GroupMembership m join m.group g
            where g.course.id in :courseIds""")
    List<Object[]> findMembershipsByCourseIds(@Param("courseIds") Collection<Long> courseIds);
}
