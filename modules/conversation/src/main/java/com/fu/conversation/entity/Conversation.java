package com.fu.conversation.entity;

import com.fu.course.entity.Course;
import com.fu.infrastructure.persistence.SoftDeletableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnTransformer;

import java.util.UUID;

/**
 * Conversation - Chủ đề hội thoại cụ thể (roleplay scenario).
 * Belongs to a ConversationTopic (chủ đề lớn) and optionally linked to a Course
 * for vocabulary/grammar context.
 * Maps to speaking.conversations table.
 */
@Entity
@Table(name = "conversations", schema = "speaking",
        indexes = {
                @Index(name = "idx_conversations_topic", columnList = "topic_id"),
                @Index(name = "idx_conversations_course", columnList = "course_id"),
                @Index(name = "idx_conversations_level", columnList = "jlpt_level")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class Conversation extends SoftDeletableEntity {

    /**
     * Parent topic (chủ đề lớn).
     */
    @Column(name = "topic_id")
    private UUID topicId;

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

    /**
     * Role of the AI character in this roleplay scenario.
     */
    @Column(name = "ai_role", columnDefinition = "TEXT")
    private String aiRole;

    /**
     * Role of the learner in this roleplay scenario.
     */
    @Column(name = "learner_role", columnDefinition = "TEXT")
    private String learnerRole;

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
