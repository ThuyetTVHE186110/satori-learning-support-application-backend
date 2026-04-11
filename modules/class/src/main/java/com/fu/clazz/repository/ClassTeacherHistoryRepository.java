package com.fu.clazz.repository;

import com.fu.clazz.entity.ClassTeacherHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ClassTeacherHistoryRepository extends JpaRepository<ClassTeacherHistory, UUID> {
    @Query("SELECT cth.teacherId FROM ClassTeacherHistory cth " +
            "WHERE cth.classId = :classId AND cth.releasedAt IS NULL")
    Optional<UUID> findCurrentTeacherIdByClassId(UUID classId);

    @Query("SELECT cth FROM ClassTeacherHistory cth " +
            "WHERE cth.classId = :classId AND cth.teacherId = :teacherId AND cth.releasedAt IS NULL")
    Optional<ClassTeacherHistory> findActiveByClassIdAndTeacherId(UUID classId, UUID teacherId);

    @Query("SELECT cth FROM ClassTeacherHistory cth " +
            "WHERE cth.teacherId = :teacherId " +
            "AND cth.assignedAt = (SELECT MAX(cth2.assignedAt) FROM ClassTeacherHistory cth2 " +
            "  WHERE cth2.teacherId = cth.teacherId AND cth2.classId = cth.classId) " +
            "ORDER BY cth.assignedAt DESC")
    Page<ClassTeacherHistory> findTeacherClassHistory(UUID teacherId, Pageable pageable);
}
