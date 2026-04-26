package com.fu.practice.entity;

import com.fu.infrastructure.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ItemMastery — theo dõi mức độ thuộc của từng item (Vocabulary / GrammarPoint)
 * theo từng học viên, dùng thuật toán WRCR + EMA.
 *
 * Maps to practice.item_mastery table.
 *
 * Công thức tính mastery score (0-100):
 *   accuracy = correctCount / attemptCount          (trọng số 30%)
 *   ema      = EMA với alpha=0.4                    (trọng số 50%)
 *   streak   = min(consecutiveCorrect, 3) / 3.0     (trọng số 20%)
 *   score    = (accuracy*0.30 + ema*0.50 + streak*0.20) * 100
 *
 * Mastery level:
 *   0 = Chưa thành thạo  (đã luyện nhưng score < 40)
 *   1 = Quen thuộc  (40 <= score < 70)
 *   2 = Nắm vững    (70 <= score < 90)
 *   3 = Thành thạo  (score >= 90)
 */
@Entity
@Table(
    name = "item_mastery",
    schema = "practice",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_item_mastery_user_entity",
        columnNames = {"user_id", "entity_id"}
    ),
    indexes = {
        @Index(name = "idx_item_mastery_user",        columnList = "user_id"),
        @Index(name = "idx_item_mastery_user_entity", columnList = "user_id, entity_id"),
        @Index(name = "idx_item_mastery_user_type_level",
               columnList = "user_id, entity_type, mastery_level")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ItemMastery extends BaseEntity {

    private static final double ALPHA = 0.4;
    private static final double INITIAL_EMA = 0.5;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** ID của Vocabulary hoặc GrammarPoint. */
    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    /** "VOCABULARY" hoặc "GRAMMAR". */
    @Column(name = "entity_type", length = 10, nullable = false)
    private String entityType;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "correct_count", nullable = false)
    @Builder.Default
    private int correctCount = 0;

    /**
     * Số lần trả lời đúng liên tiếp hiện tại.
     * Tăng khi đúng, giảm 1 (min 0) khi sai (soft decay — không hard reset).
     */
    @Column(name = "consecutive_correct", nullable = false)
    @Builder.Default
    private int consecutiveCorrect = 0;

    /**
     * Exponential Moving Average của wasCorrect (0 hoặc 1) với alpha=0.4.
     * Prior: 0.5 (uninformed — chưa biết học viên có thuộc không).
     * Range: [0.0, 1.0]
     */
    @Column(name = "ema_score", precision = 6, scale = 5, nullable = false)
    @Builder.Default
    private BigDecimal emaScore = BigDecimal.valueOf(INITIAL_EMA);

    /**
     * 0=Chưa thành thạo, 1=Quen thuộc, 2=Nắm vững, 3=Thành thạo.
     * Tính lại sau mỗi recordAnswer().
     */
    @Column(name = "mastery_level", nullable = false)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Builder.Default
    private int masteryLevel = 0;

    /**
     * QuestionDimension.name() của session gần nhất chứa câu hỏi về item này.
     * Dùng để tính mastery-weighted vote cho session tiếp theo.
     */
    @Column(name = "last_dimension", length = 20)
    private String lastDimension;

    @Column(name = "last_practiced_at")
    private LocalDateTime lastPracticedAt;

    // ── Business logic ──────────────────────────────────────────────────────

    /**
     * Cập nhật tất cả fields sau khi học viên trả lời 1 câu hỏi về item này.
     * Gọi từ ItemMasteryService.upsert() trong transaction của submitAnswer.
     */
    public void recordAnswer(boolean isCorrect) {
        attemptCount++;

        if (isCorrect) {
            correctCount++;
            consecutiveCorrect++;
        } else {
            consecutiveCorrect = Math.max(0, consecutiveCorrect - 1);
        }

        double wasCorrect = isCorrect ? 1.0 : 0.0;
        double oldEma = emaScore.doubleValue();
        double newEma = ALPHA * wasCorrect + (1 - ALPHA) * oldEma;
        emaScore = BigDecimal.valueOf(newEma).setScale(5, RoundingMode.HALF_UP);

        masteryLevel = computeMasteryLevel();
        lastPracticedAt = LocalDateTime.now();
    }

    /**
     * Tính mastery level từ 3 thành phần:
     *   accuracy (30%) + ema (50%) + streak (20%)
     */
    private int computeMasteryLevel() {
        double accuracy = attemptCount > 0 ? (double) correctCount / attemptCount : 0.0;
        double ema      = emaScore.doubleValue();
        double streak   = Math.min(consecutiveCorrect, 3) / 3.0;
        double raw      = accuracy * 0.30 + ema * 0.50 + streak * 0.20;

        if (raw < 0.40) return 0;
        if (raw < 0.70) return 1;
        if (raw < 0.90) return 2;
        return 3;
    }

    /** Tên hiển thị cho UI. */
    public String getMasteryLabel() {
        return switch (masteryLevel) {
            case 1  -> "Quen thuộc";
            case 2  -> "Nắm vững";
            case 3  -> "Thành thạo";
            default -> "Chưa thành thạo";
        };
    }
}
