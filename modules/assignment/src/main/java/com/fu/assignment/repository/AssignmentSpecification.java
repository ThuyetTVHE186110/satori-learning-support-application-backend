package com.fu.assignment.repository;

import com.fu.assignment.entity.Assignment;
import com.fu.assignment.entity.AssignmentStatus;
import com.fu.assignment.entity.AssignmentType;
import com.fu.assignment.entity.AssignmentTarget;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.UUID;

public class AssignmentSpecification {

    public static Specification<Assignment> withTitle(String title) {
        return (root, query, cb) -> {
            if (title == null || title.isBlank()) return null;
            return cb.like(cb.lower(root.get("title")), "%" + title.toLowerCase() + "%");
        };
    }

    public static Specification<Assignment> withStatus(AssignmentStatus status) {
        return (root, query, cb) -> {
            if (status == null) return null;
            return cb.equal(root.get("status"), status);
        };
    }

    public static Specification<Assignment> withType(AssignmentType type) {
        return (root, query, cb) -> {
            if (type == null) return null;
            return cb.equal(root.get("assignmentType"), type);
        };
    }

    public static Specification<Assignment> withCreatedBy(UUID createdBy) {
        return (root, query, cb) -> {
            if (createdBy == null) return null;
            return cb.equal(root.get("createdBy"), createdBy);
        };
    }

    public static Specification<Assignment> withClassId(UUID classId) {
        return (root, query, cb) -> {
            if (classId == null) return null;
            return cb.equal(root.get("classId"), classId);
        };
    }

    public static Specification<Assignment> withClassIds(List<UUID> classIds) {
        return (root, query, cb) -> {
            if (classIds == null || classIds.isEmpty()) return cb.disjunction();
            return root.get("classId").in(classIds);
        };
    }

    public static Specification<Assignment> withLessonId(UUID lessonId) {
        return (root, query, cb) -> {
            if (lessonId == null) return null;
            return cb.equal(root.get("lessonId"), lessonId);
        };
    }

    public static Specification<Assignment> isNotDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<Assignment> isPublishedOrClosed() {
        return (root, query, cb) -> root.get("status").in(AssignmentStatus.PUBLISHED, AssignmentStatus.CLOSED);
    }

    public static Specification<Assignment> isAvailableForLearner(UUID userId, List<UUID> classIds) {
        return (root, query, cb) -> {
            Subquery<UUID> subquery = query.subquery(UUID.class);
            Root<AssignmentTarget> target = subquery.from(AssignmentTarget.class);
            subquery.select(target.get("assignmentId"));

            Predicate userMatch = cb.equal(target.get("userId"), userId);
            Predicate classMatch = (classIds == null || classIds.isEmpty()) ? cb.disjunction() : target.get("classId").in(classIds);

            subquery.where(cb.or(userMatch, classMatch));

            return root.get("id").in(subquery);
        };
    }
}
