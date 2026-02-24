package com.fu.course.entity;

import com.fu.infrastructure.persistence.SoftDeletableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnTransformer;

/**
 * Course entity - Japanese courses (N5, N4, N3, N2, N1)
 * Maps to learning_content.courses table
 * 
 * Business: MANAGER creates and manages courses
 */
@Entity
@Table(name = "courses", schema = "learning_content", indexes = {
    @Index(name = "idx_courses_name", columnList = "name"),
    @Index(name = "idx_courses_jlpt_level", columnList = "jlpt_level"),
    @Index(name = "idx_courses_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class Course extends SoftDeletableEntity {

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "jlpt_level", nullable = false, length = 5)
    @ColumnTransformer(write = "CAST(? AS jlpt_level)")
    private JlptLevel jlptLevel;

    @Column(name = "thumbnail_url", columnDefinition = "TEXT")
    private String thumbnailUrl;

    @Column(name = "order_index")
    @Builder.Default
    private Integer orderIndex = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @ColumnTransformer(write = "CAST(? AS content_status)")
    @Builder.Default
    private ContentStatus status = ContentStatus.DRAFT;

    /**
     * JLPT Level enum
     */
    public enum JlptLevel {
        N5, N4, N3, N2, N1
    }

    /**
     * Content Status enum
     */
    public enum ContentStatus {
        DRAFT,      // Being prepared
        PUBLISHED,  // Available to students
        ARCHIVED    // Hidden from students
    }
}
