package com.fu.clazz.entity;

import com.fu.infrastructure.persistence.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "class_teacher_history", schema = "user_management",
        indexes = {
                @Index(name = "idx_cth_teacher_id", columnList = "teacherId"),
                @Index(name = "idx_cth_class_id", columnList = "classId")
        }
)
@FieldDefaults(level = AccessLevel.PRIVATE)
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ClassTeacherHistory extends AuditableEntity {
    @Column(name = "teacher_id", nullable = false)
    UUID teacherId;
    @Column(name = "class_id", nullable = false)
    UUID classId;
    @Column(name = "assigned_at", nullable = false)
    @Builder.Default
    LocalDate assignedAt = LocalDate.now();
    @Column(name = "released_at")
    LocalDate releasedAt;
}
