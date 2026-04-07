package com.fu.clazz.repository.specification;

import com.fu.clazz.entity.ClassStatus;
import com.fu.clazz.entity.Clazz;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
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
            // Use subquery because Clazz doesn't have a direct @OneToMany relationship to ClassTeacherHistory
            var subquery = query.subquery(UUID.class);
            var historyRoot = subquery.from(com.fu.clazz.entity.ClassTeacherHistory.class);
            subquery.select(historyRoot.get("classId"))
                    .where(cb.and(
                            cb.equal(historyRoot.get("teacherId"), teacherId),
                            cb.isNull(historyRoot.get("releasedAt"))
                    ));
            return root.get("id").in(subquery);
        };
    }

    public static Specification<Clazz> hasStatus(ClassStatus status) {
        return (root, query, cb) -> {
            if (status == null) {
                return null;
            }
            return cb.equal(root.get("status"), status);
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
