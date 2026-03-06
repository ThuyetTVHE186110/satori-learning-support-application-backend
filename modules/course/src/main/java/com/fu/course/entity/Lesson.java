package com.fu.course.entity;

import com.fu.infrastructure.persistence.SoftDeletableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lesson entity - individual lessons within a Course.
 * Maps to learning_content.lessons table.
 *
 * Business: MANAGER creates and manages lessons for each course.
 * Supports content file upload (PDF/Doc), AI-based processing,
 * and JSONB flexible content storage.
 */
@Entity
@Table(name = "lessons", schema = "learning_content", indexes = {
    @Index(name = "idx_lessons_course_id", columnList = "course_id"),
    @Index(name = "idx_lessons_status", columnList = "status"),
    @Index(name = "idx_lessons_lesson_type", columnList = "lesson_type"),
    @Index(name = "idx_lessons_order_index", columnList = "order_index"),
    @Index(name = "idx_lessons_import_id", columnList = "import_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class Lesson extends SoftDeletableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    @ToString.Exclude
    private Course course;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "title_japanese", length = 255)
    private String titleJapanese;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "lesson_type", nullable = false, length = 20)
    @ColumnTransformer(write = "CAST(? AS lesson_type)")
    private LessonType lessonType;

    /**
     * Flexible content structure stored as JSONB in PostgreSQL.
     * Structure varies by lesson type.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", columnDefinition = "jsonb")
    private String content;

    /**
     * Learning objectives stored as PostgreSQL TEXT[] array.
     * Stored as comma-separated string; converted in service layer.
     */
    @Column(name = "objectives", columnDefinition = "TEXT[]")
    private String[] objectives;

    // --- Curriculum Import (PDF/Doc upload) ---

    /** Tracks which curriculum import created this lesson, if any. */
    @Column(name = "import_id")
    private UUID importId;

    @Column(name = "content_file_url", columnDefinition = "TEXT")
    private String contentFileUrl;

    @Column(name = "content_file_name", length = 255)
    private String contentFileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", length = 20)
    @ColumnTransformer(write = "CAST(? AS processing_status)")
    @Builder.Default
    private ProcessingStatus processingStatus = ProcessingStatus.COMPLETED;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "processing_error", columnDefinition = "TEXT")
    private String processingError;

    // --- Page info (for imported curriculum) ---

    @Column(name = "page_start")
    private Integer pageStart;

    @Column(name = "page_end")
    private Integer pageEnd;

    // --- Ordering & Duration ---

    @Column(name = "order_index")
    @Builder.Default
    private Integer orderIndex = 0;

    @Column(name = "estimated_duration_minutes")
    @Builder.Default
    private Integer estimatedDurationMinutes = 30;

    // --- Status ---

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @ColumnTransformer(write = "CAST(? AS content_status)")
    @Builder.Default
    private ContentStatus status = ContentStatus.DRAFT;

    /**
     * Lesson Type enum - categorizes lesson content.
     */
    public enum LessonType {
        VOCABULARY,
        GRAMMAR,
        KANJI,
        LISTENING,
        READING,
        MIXED
    }

    /**
     * Processing Status enum - tracks AI/import pipeline progress.
     */
    public enum ProcessingStatus {
        QUEUED,
        EXTRACTING,
        CHUNKING,
        AI_PROCESSING,
        EMBEDDING,
        COMPLETED,
        FAILED
    }

    /**
     * Re-use Course's ContentStatus for consistency.
     */
    public enum ContentStatus {
        DRAFT,
        PUBLISHED,
        ARCHIVED
    }
}
