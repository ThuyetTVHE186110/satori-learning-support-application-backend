package com.fu.course.repository;

import com.fu.course.entity.Lesson;
import com.fu.course.entity.Lesson.ContentStatus;
import com.fu.course.entity.Lesson.LessonType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Lesson entity.
 */
@Repository
public interface LessonRepository extends JpaRepository<Lesson, UUID> {

    // ===== Find by ID (non-deleted) =====

    @Query("SELECT l FROM Lesson l WHERE l.id = :id AND l.deletedAt IS NULL")
    Optional<Lesson> findByIdAndNotDeleted(@Param("id") UUID id);

    // ===== Find by course =====

    @Query("SELECT l FROM Lesson l WHERE l.course.id = :courseId AND l.deletedAt IS NULL ORDER BY l.orderIndex ASC")
    List<Lesson> findByCourseIdAndNotDeleted(@Param("courseId") UUID courseId);

    @Query("SELECT l FROM Lesson l WHERE l.course.id = :courseId AND l.deletedAt IS NULL")
    Page<Lesson> findByCourseIdAndNotDeleted(@Param("courseId") UUID courseId, Pageable pageable);

    // ===== Find published lessons for a course (student access) =====

    @Query("SELECT l FROM Lesson l WHERE l.course.id = :courseId AND l.status = 'PUBLISHED' AND l.deletedAt IS NULL ORDER BY l.orderIndex ASC")
    List<Lesson> findPublishedByCourseId(@Param("courseId") UUID courseId);

    // ===== Filter by status =====

    Page<Lesson> findByStatusAndDeletedAtIsNull(ContentStatus status, Pageable pageable);

    @Query("SELECT l FROM Lesson l WHERE l.course.id = :courseId AND l.status = :status AND l.deletedAt IS NULL")
    Page<Lesson> findByCourseIdAndStatusAndNotDeleted(@Param("courseId") UUID courseId,
                                                       @Param("status") ContentStatus status,
                                                       Pageable pageable);

    // ===== Filter by lesson type =====

    Page<Lesson> findByLessonTypeAndDeletedAtIsNull(LessonType lessonType, Pageable pageable);

    // ===== Search =====

    @Query("SELECT l FROM Lesson l WHERE l.deletedAt IS NULL AND " +
           "(LOWER(l.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(l.titleJapanese) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Lesson> searchByTitle(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT l FROM Lesson l WHERE l.course.id = :courseId AND l.deletedAt IS NULL AND " +
           "(LOWER(l.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(l.titleJapanese) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Lesson> searchByTitleInCourse(@Param("courseId") UUID courseId,
                                        @Param("keyword") String keyword,
                                        Pageable pageable);

    // ===== Counting =====

    long countByCourseIdAndDeletedAtIsNull(UUID courseId);

    long countByStatusAndDeletedAtIsNull(ContentStatus status);

    long countByCourseIdAndStatusAndDeletedAtIsNull(UUID courseId, ContentStatus status);

    // ===== Find by import =====

    @Query("SELECT l FROM Lesson l WHERE l.importId = :importId AND l.deletedAt IS NULL ORDER BY l.orderIndex ASC")
    List<Lesson> findByImportIdAndNotDeleted(@Param("importId") UUID importId);

    @Query("SELECT l FROM Lesson l WHERE l.importId = :importId AND LOWER(l.title) = LOWER(:title) AND l.deletedAt IS NULL")
    Optional<Lesson> findByImportIdAndTitleIgnoreCase(@Param("importId") UUID importId, @Param("title") String title);

    @Query("SELECT l FROM Lesson l WHERE l.course.id = :courseId AND l.orderIndex = :orderIndex AND l.deletedAt IS NULL ORDER BY l.createdAt DESC")
    List<Lesson> findByCourseIdAndOrderIndexAndNotDeleted(@Param("courseId") UUID courseId, @Param("orderIndex") int orderIndex);

    // ===== Exists checks =====

    boolean existsByTitleIgnoreCaseAndCourseId(String title, UUID courseId);

    @Query("SELECT CASE WHEN COUNT(l) > 0 THEN true ELSE false END FROM Lesson l " +
           "WHERE LOWER(l.title) = LOWER(:title) AND l.course.id = :courseId AND l.id <> :lessonId AND l.deletedAt IS NULL")
    boolean existsByTitleIgnoreCaseAndCourseIdAndIdNot(@Param("title") String title,
                                                        @Param("courseId") UUID courseId,
                                                        @Param("lessonId") UUID lessonId);
}
