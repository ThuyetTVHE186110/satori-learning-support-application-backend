package com.fu.conversation.entity;

import com.fu.course.entity.Course;
import com.fu.infrastructure.persistence.SoftDeletableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnTransformer;

import java.util.UUID;

/**
 * ConversationTopic - Chủ đề hội thoại nhỏ.
 * Belongs to a ConversationTheme (chủ đề lớn) and optionally linked to a Course
 * for vocabulary/grammar context.
 * Maps to speaking.conversation_topics table (extended with theme_id, course_id).
 */
@Entity
@Table(name = "conversation_topics", schema = "speaking",
        indexes = {
                @Index(name = "idx_conv_topics_theme", columnList = "theme_id"),
                @Index(name = "idx_conv_topics_course", columnList = "course_id"),
                @Index(name = "idx_conv_topics_level", columnList = "jlpt_level")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class ConversationTopic extends SoftDeletableEntity {

    /**
     * Parent theme (chủ đề lớn).
     */
    @Column(name = "theme_id")
    private UUID themeId;

    /**
     * Course linked for vocabulary/grammar context.
     */
    @Column(name = "course_id")
    private UUID courseId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "title_japanese", length = 255)
    private String titleJapanese;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "description_vi", columnDefinition = "TEXT")
    private String descriptionVi;

    @Column(name = "scenario", columnDefinition = "TEXT")
    private String scenario;

    @Column(name = "vocabulary_hints", columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    @Builder.Default
    private String vocabularyHints = "[]";

    @Column(name = "grammar_points", columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    @Builder.Default
    private String grammarPoints = "[]";

    @Column(name = "useful_expressions", columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    @Builder.Default
    private String usefulExpressions = "[]";

    @Column(name = "category", length = 100)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "jlpt_level", length = 5)
    @ColumnTransformer(write = "CAST(? AS jlpt_level)")
    private Course.JlptLevel jlptLevel;

    @Column(name = "difficulty_score")
    @Builder.Default
    private Integer difficultyScore = 1;

    @Column(name = "is_roleplay")
    @Builder.Default
    private Boolean isRoleplay = false;

    @Column(name = "roleplay_context", columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    private String roleplayContext;

    @Column(name = "thumbnail_url", columnDefinition = "TEXT")
    private String thumbnailUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "content_status")
    @ColumnTransformer(write = "CAST(? AS content_status)")
    @Builder.Default
    private Course.ContentStatus status = Course.ContentStatus.DRAFT;

    @Column(name = "order_index")
    @Builder.Default
    private Integer orderIndex = 0;
}
