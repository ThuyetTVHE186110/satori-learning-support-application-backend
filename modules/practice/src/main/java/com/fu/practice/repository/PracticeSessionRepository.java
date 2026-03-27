package com.fu.practice.repository;

import com.fu.practice.entity.PracticeSession;
import com.fu.practice.entity.PracticeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PracticeSessionRepository extends JpaRepository<PracticeSession, UUID> {

    /**
     * Lấy tất cả phiên của user, trừ trạng thái chỉ định (ví dụ: trừ ABANDONED).
     */
    Page<PracticeSession> findByUserIdAndStatusNot(UUID userId, PracticeStatus status, Pageable pageable);

    /**
     * Lấy phiên luyện tập của user theo bài học cụ thể.
     */
    Page<PracticeSession> findByUserIdAndLessonId(UUID userId, UUID lessonId, Pageable pageable);

    /**
     * Lấy tất cả phiên đã hoàn thành của user theo bài học, để tính mastery.
     */
    @Query("SELECT ps FROM PracticeSession ps WHERE ps.userId = :userId AND ps.lessonId = :lessonId AND ps.status = 'COMPLETED'")
    List<PracticeSession> findCompletedByUserIdAndLessonId(
            @Param("userId") UUID userId,
            @Param("lessonId") UUID lessonId);

    /**
     * Kiểm tra session có thuộc user không.
     */
    boolean existsByIdAndUserId(UUID id, UUID userId);
}
