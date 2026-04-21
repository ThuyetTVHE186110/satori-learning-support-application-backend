package com.fu.clazz.entity;

import com.fu.infrastructure.persistence.SoftDeletableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Class entity - organizes learners and teachers for courses.
 * Maps to user_management.classes table.
 * <p>
 * Note: Named "Clazz" to avoid conflict with Java keyword "class".
 * <p>
 * Business: MANAGER creates and manages classes.
 */
@Entity
@Table(name = "classes", schema = "user_management", indexes = {
        @Index(name = "idx_classes_course_id", columnList = "course_id"),
        @Index(name = "idx_classes_jlpt_level", columnList = "jlpt_level")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class Clazz extends SoftDeletableEntity {

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Column(name = "max_students")
    @Builder.Default
    private Integer maxStudents = 8;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;
    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "status")
    @Builder.Default
    private ClassStatus status = ClassStatus.NOT_STARTED;

    @Column(name = "expected_end_date")
    private LocalDate expectedEndDate;
}
