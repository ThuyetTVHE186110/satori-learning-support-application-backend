package com.fu.course.repository;

import com.fu.course.entity.Course;
import com.fu.course.entity.Course.CourseStatus;
import com.fu.course.entity.Course.JlptLevel;
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
 * Repository for Course entity
 */
@Repository
public interface CourseRepository extends JpaRepository<Course, UUID> {

    /**
     * Find all non-deleted courses (list)
     */
    List<Course> findByDeletedAtIsNull();

    /**
     * Find all non-deleted courses (paginated)
     */
    Page<Course> findByDeletedAtIsNull(Pageable pageable);

    /**
     * Find course by ID (non-deleted)
     */
    @Query("SELECT c FROM Course c WHERE c.id = :id AND c.deletedAt IS NULL")
    Optional<Course> findByIdAndNotDeleted(@Param("id") UUID id);

    /**
     * Find all courses by status
     */
    Page<Course> findByStatusAndDeletedAtIsNull(CourseStatus status, Pageable pageable);

    /**
     * Find all courses by JLPT level
     */
    Page<Course> findByJlptLevelAndDeletedAtIsNull(JlptLevel jlptLevel, Pageable pageable);

    /**
     * Find all courses by JLPT level and status
     */
    Page<Course> findByJlptLevelAndStatusAndDeletedAtIsNull(
        JlptLevel jlptLevel, 
        CourseStatus status, 
        Pageable pageable
    );

    /**
     * Search courses by name (case-insensitive)
     */
    @Query("SELECT c FROM Course c WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "AND c.deletedAt IS NULL")
    Page<Course> searchByName(@Param("keyword") String keyword, Pageable pageable);

    /**
     * Find published courses only
     */
    @Query("SELECT c FROM Course c WHERE c.status = 'ACTIVE' AND c.deletedAt IS NULL " +
           "ORDER BY c.orderIndex ASC, c.createdAt DESC")
    List<Course> findActiveCourses();

    /**
     * Find published courses by JLPT level
     */
    @Query("SELECT c FROM Course c WHERE c.jlptLevel = :level AND c.status = 'ACTIVE' " +
           "AND c.deletedAt IS NULL ORDER BY c.orderIndex ASC")
    List<Course> findActiveCoursesByLevel(@Param("level") JlptLevel level);

    /**
     * Check if course name already exists (case-insensitive, excluding deleted)
     */
    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM Course c " +
           "WHERE LOWER(c.name) = LOWER(:name) AND c.deletedAt IS NULL")
    boolean existsByNameIgnoreCase(@Param("name") String name);

    /**
     * Check if course name exists excluding specific course ID
     */
    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM Course c " +
           "WHERE LOWER(c.name) = LOWER(:name) AND c.id <> :excludeId AND c.deletedAt IS NULL")
    boolean existsByNameIgnoreCaseAndIdNot(@Param("name") String name, @Param("excludeId") UUID excludeId);

    /**
     * Count courses by status
     */
    long countByStatusAndDeletedAtIsNull(CourseStatus status);

    /**
     * Count courses by JLPT level
     */
    long countByJlptLevelAndDeletedAtIsNull(JlptLevel jlptLevel);
}
