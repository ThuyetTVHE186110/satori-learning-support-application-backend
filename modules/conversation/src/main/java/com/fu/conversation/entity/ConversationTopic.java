package com.fu.conversation.entity;

import com.fu.course.entity.Course;
import com.fu.infrastructure.persistence.SoftDeletableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnTransformer;

import java.util.UUID;

/**
 * ConversationTopic - Chủ đề hội thoại lớn.
 * Created by MANAGER, optionally with AI context generated from a course.
 * Maps to speaking.conversation_topics table.
 */
@Entity
@Table(name = "conversation_topics", schema = "speaking",
        indexes = {
                @Index(name = "idx_conv_topics_course", columnList = "course_id"),
                @Index(name = "idx_conv_topics_jlpt", columnList = "jlpt_level"),
                @Index(name = "idx_conv_topics_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class ConversationTopic extends SoftDeletableEntity {

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "title_japanese", length = 255)
    private String titleJapanese;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "description_vi", columnDefinition = "TEXT")
    private String descriptionVi;

    @Column(name = "category", length = 100)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "jlpt_level", length = 5)
    @ColumnTransformer(write = "CAST(? AS jlpt_level)")
    private Course.JlptLevel jlptLevel;

    /**
     * Reference to the course used as AI generation context.
     */
    @Column(name = "course_id")
    private UUID courseId;

    /**
     * AI-generated context: vocabulary hints, grammar points, scenario suggestions.
     */
    @Column(name = "ai_generated_context", columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    @Builder.Default
    private String aiGeneratedContext = "{}";

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
