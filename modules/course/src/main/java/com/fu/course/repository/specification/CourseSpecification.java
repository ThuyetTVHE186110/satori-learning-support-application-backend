package com.fu.course.repository.specification;

import com.fu.course.entity.Course;
import com.fu.course.entity.Course.CourseStatus;
import com.fu.course.entity.Course.JlptLevel;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA Specifications for Course entity.
 * Supports multi-criteria filtering with dynamic query building.
 */
public final class CourseSpecification {

    private CourseSpecification() {
        // Utility class
    }

    /**
     * Build a combined Specification that filters by status, JLPT level, keyword,
     * and always excludes soft-deleted courses (deletedAt IS NULL)
     * and courses with status DELETED.
     *
     * @param status   optional status filter
     * @param level    optional JLPT level filter
     * @param keyword  optional keyword for name search (case-insensitive)
     * @return combined Specification
     */
    public static Specification<Course> withFilters(CourseStatus status, JlptLevel level, String keyword) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Always exclude soft-deleted courses
            predicates.add(cb.isNull(root.get("deletedAt")));

            // Always exclude courses with DELETED status
            predicates.add(cb.notEqual(root.get("status"), CourseStatus.DELETED));

            // Filter by status if provided
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            // Filter by JLPT level if provided
            if (level != null) {
                predicates.add(cb.equal(root.get("jlptLevel"), level));
            }

            // Filter by keyword (name search, case-insensitive) if provided
            if (keyword != null && !keyword.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("name")),
                        "%" + keyword.trim().toLowerCase() + "%"));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}

