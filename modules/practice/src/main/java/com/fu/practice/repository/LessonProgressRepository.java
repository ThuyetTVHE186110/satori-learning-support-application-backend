package com.fu.practice.repository;

import com.fu.practice.entity.LessonProgress;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LessonProgressRepository extends JpaRepository<LessonProgress, UUID> {

    /**
     * Tìm tiến độ của user cho một bài học cụ thể.
     */
    Optional<LessonProgress> findByUserIdAndLessonId(UUID userId, UUID lessonId);

    /**
     * Lấy tất cả tiến độ của user (có phân trang).
     */
    Page<LessonProgress> findByUserId(UUID userId, Pageable pageable);

    /**
     * Lấy tiến độ của user cho một khóa học cụ thể.
     * Join qua lessonId để lọc theo courseId (cần query native hoặc qua Lesson entity).
     */
    @Query(value = """
            SELECT lp.* FROM practice.lesson_progress lp
            JOIN learning_content.lessons l ON l.id = lp.lesson_id
            WHERE lp.user_id = :userId AND l.course_id = :courseId AND l.deleted_at IS NULL
            """, nativeQuery = true)
    List<LessonProgress> findByUserIdAndCourseId(
            @Param("userId") UUID userId,
            @Param("courseId") UUID courseId);

    /**
     * Bài học được luyện gần nhất của user — dùng cho chatbot trả lời
     * "em đang học đến bài nào?". NULL lastPracticedAt xếp cuối.
     */
    Optional<LessonProgress> findTopByUserIdOrderByLastPracticedAtDesc(UUID userId);

    /**
     * Bài học được luyện gần nhất của user trong 1 khóa cụ thể.
     * Native query vì LessonProgress không có trường courseId trực tiếp.
     */
    @Query(value = """
            SELECT lp.* FROM practice.lesson_progress lp
            JOIN learning_content.lessons l ON l.id = lp.lesson_id
            WHERE lp.user_id = :userId
              AND l.course_id = :courseId
              AND l.deleted_at IS NULL
            ORDER BY lp.last_practiced_at DESC NULLS LAST
            LIMIT 1
            """, nativeQuery = true)
    Optional<LessonProgress> findTopByUserIdAndCourseIdOrderByLastPracticedAtDesc(
            @Param("userId") UUID userId,
            @Param("courseId") UUID courseId);
}
