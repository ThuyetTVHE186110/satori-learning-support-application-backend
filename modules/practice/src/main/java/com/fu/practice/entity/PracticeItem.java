package com.fu.practice.entity;

import com.fu.infrastructure.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PracticeItem — một câu hỏi trong phiên luyện tập.
 * Maps to practice.practice_items table.
 *
 * Không dùng @ManyToOne với PracticeSession để tránh N+1.
 * Tham chiếu bằng UUID trực tiếp.
 */
@Entity
@Table(name = "practice_items", schema = "practice", indexes = {
        @Index(name = "idx_practice_items_session_id", columnList = "session_id"),
        @Index(name = "idx_practice_items_ref_content", columnList = "ref_content_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class PracticeItem extends BaseEntity {

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "item_index", nullable = false)
    private int itemIndex;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "item_type", columnDefinition = "practice_item_type", nullable = false)
    private PracticeItemType itemType;

    @Column(name = "question", nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(name = "question_japanese", columnDefinition = "TEXT")
    private String questionJapanese;

    @Column(name = "hint", columnDefinition = "TEXT")
    private String hint;

    /**
     * JSON array: [{\"text\":\"...\",\"isCorrect\":false}, ...]
     * Rỗng ([]) với FILL_BLANK và TRANSLATION.
     */
    @Column(name = "options", columnDefinition = "jsonb")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    @Builder.Default
    private String options = "[]";

    @Column(name = "correct_answer", nullable = false, columnDefinition = "TEXT")
    private String correctAnswer;

    @Column(name = "user_answer", columnDefinition = "TEXT")
    private String userAnswer;

    @Column(name = "is_correct")
    private Boolean isCorrect;

    @Column(name = "score")
    @Builder.Default
    private int score = 0;

    /**
     * Giải thích tại sao đáp án đúng — sinh bởi ExerciseGeneratorAgent khi tạo câu hỏi.
     * Hiển thị cho learner ngay sau khi nộp bài, không cần gọi thêm LLM.
     */
    @Column(name = "explanation", columnDefinition = "TEXT")
    private String explanation;

    /** Nhận xét của AnswerEvaluatorAgent sau khi học viên nộp bài. */
    @Column(name = "ai_feedback", columnDefinition = "TEXT")
    private String aiFeedback;

    /**
     * ID của Vocabulary hoặc GrammarPoint dùng để tạo câu hỏi.
     */
    @Column(name = "ref_content_id")
    private UUID refContentId;

    /**
     * "VOCABULARY" hoặc "GRAMMAR".
     */
    @Column(name = "ref_content_type", length = 20)
    private String refContentType;

    @Column(name = "answered_at")
    private LocalDateTime answeredAt;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;
}
