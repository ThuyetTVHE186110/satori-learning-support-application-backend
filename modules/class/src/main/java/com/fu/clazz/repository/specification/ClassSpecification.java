package com.fu.clazz.repository.specification;

import com.fu.clazz.entity.Clazz;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

public class ClassSpecification {

    public static Specification<Clazz> hasKeyword(String keyword) {
        return (root, query, cb) -> {
            if (keyword == null || keyword.isBlank()) {
                return null;
            }
            return cb.like(cb.lower(root.get("name")), "%" + keyword.toLowerCase().trim() + "%");
        };
    }

    public static Specification<Clazz> hasTeacherId(UUID teacherId) {
        return (root, query, cb) -> {
            if (teacherId == null) {
                return null;
            }
            return cb.equal(root.get("teacherId"), teacherId);
        };
    }

    public static Specification<Clazz> hasIsActive(Boolean isActive) {
        return (root, query, cb) -> {
            if (isActive == null) {
                return null;
            }
            return cb.equal(root.get("isActive"), isActive);
        };
    }

    public static Specification<Clazz> hasCourseId(UUID courseId) {
        return (root, query, cb) -> {
            if (courseId == null) {
                return cb.conjunction();
            }
            return cb.equal(root.get("courseId"), courseId);
        };
    }

    public static Specification<Clazz> isNotDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }
}
