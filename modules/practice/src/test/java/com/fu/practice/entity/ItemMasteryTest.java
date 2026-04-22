package com.fu.practice.entity;

import com.fu.practice.service.ItemMasteryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit Test: ItemMastery.recordAnswer()
 * Template: docs/RECORD_ANSWER_UNIT_TEST_TEMPLATE.md
 *
 * EMA formula : newEma = 0.4 * wasCorrect + 0.6 * oldEma   (alpha=0.4, prior=0.5)
 * mastery score: accuracy*0.30 + ema*0.50 + streak*0.20     (streak capped at 3)
 * Level thresholds: 0(<0.40) | 1(0.40-0.70) | 2(0.70-0.90) | 3(≥0.90)
 */
@DisplayName("ItemMastery — recordAnswer business logic")
class ItemMasteryTest {

    private ItemMastery mastery;

    @BeforeEach
    void setUp() {
        mastery = ItemMastery.builder()
                .userId(UUID.randomUUID())
                .entityId(UUID.randomUUID())
                .entityType("VOCABULARY")
                .build();
    }

    // ── UTC-U01/02: attemptCount & correctCount ──────────────────────────

    @Nested
    @DisplayName("Counters — attemptCount and correctCount")
    class Counters {

        @Test
        @DisplayName("UTCID01 — attemptCount tăng mỗi lần gọi (đúng lẫn sai)")
        void shouldIncrementAttemptCountOnEveryCall() {
            // Execute
            mastery.recordAnswer(true);
            mastery.recordAnswer(false);

            // Assert
            assertThat(mastery.getAttemptCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("UTCID02 — correctCount chỉ tăng khi isCorrect=true")
        void shouldIncrementCorrectCountOnlyWhenCorrect() {
            // Execute
            mastery.recordAnswer(true);
            mastery.recordAnswer(false);
            mastery.recordAnswer(true);

            // Assert
            assertThat(mastery.getCorrectCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("UTCID15 — lastPracticedAt được set sau mỗi lần gọi")
        void shouldSetLastPracticedAtAfterEachCall() {
            // Setup
            assertThat(mastery.getLastPracticedAt()).isNull();

            // Execute
            mastery.recordAnswer(true);

            // Assert
            assertThat(mastery.getLastPracticedAt()).isNotNull();
        }
    }

    // ── UTCID04-06: consecutiveCorrect soft-decay ─────────────────────────

    @Nested
    @DisplayName("ConsecutiveCorrect — soft-decay khi sai")
    class ConsecutiveCorrect {

        @Test
        @DisplayName("UTCID03 — tăng liên tiếp khi đúng liên tục")
        void shouldIncrementStreakOnConsecutiveCorrect() {
            // Execute
            mastery.recordAnswer(true);
            mastery.recordAnswer(true);
            mastery.recordAnswer(true);

            // Assert
            assertThat(mastery.getConsecutiveCorrect()).isEqualTo(3);
        }

        @Test
        @DisplayName("UTCID05 — soft-decay: streak giảm 1 khi sai (không reset về 0)")
        void shouldSoftDecayStreakOnWrongAnswer() {
            // Setup: streak=3
            mastery.recordAnswer(true);
            mastery.recordAnswer(true);
            mastery.recordAnswer(true);

            // Execute
            mastery.recordAnswer(false);

            // Assert: 3 → 2 (không phải 0)
            assertThat(mastery.getConsecutiveCorrect()).isEqualTo(2);
        }

        @Test
        @DisplayName("UTCID06 — streak ở 0 khi sai → giữ nguyên 0 (không âm)")
        void shouldNotGoBelowZeroOnWrongAnswer() {
            // Execute
            mastery.recordAnswer(false);
            mastery.recordAnswer(false);

            // Assert
            assertThat(mastery.getConsecutiveCorrect()).isEqualTo(0);
        }

        @Test
        @DisplayName("UTCID07 — streak tăng vượt cap=3 khi đúng nhiều lần")
        void shouldAllowStreakBeyondCapValue() {
            // Execute
            for (int i = 0; i < 5; i++) mastery.recordAnswer(true);

            // Assert: consecutive=5 (stored raw), but scoring uses min(5,3)
            assertThat(mastery.getConsecutiveCorrect()).isEqualTo(5);
        }
    }

    // ── UTCID01/02/09/10: EMA calculation ────────────────────────────────

    @Nested
    @DisplayName("EMA — Exponential Moving Average formula")
    class EmaCalculation {

        @Test
        @DisplayName("UTCID01 — first correct: ema = 0.4×1.0 + 0.6×0.5 = 0.70000")
        void shouldComputeCorrectEmaOnFirstCorrectAnswer() {
            // Execute
            mastery.recordAnswer(true);

            // Assert
            assertThat(mastery.getEmaScore().doubleValue()).isCloseTo(0.70, within(0.00001));
        }

        @Test
        @DisplayName("UTCID02 — first wrong: ema = 0.4×0.0 + 0.6×0.5 = 0.30000")
        void shouldComputeCorrectEmaOnFirstWrongAnswer() {
            // Execute
            mastery.recordAnswer(false);

            // Assert
            assertThat(mastery.getEmaScore().doubleValue()).isCloseTo(0.30, within(0.00001));
        }

        @Test
        @DisplayName("UTCID09 — ema hội tụ > 0.97 sau 10 câu đúng liên tiếp")
        void shouldConvergeHighAfterManyCorrectAnswers() {
            // Execute
            for (int i = 0; i < 10; i++) mastery.recordAnswer(true);

            // Assert
            assertThat(mastery.getEmaScore().doubleValue()).isGreaterThan(0.97);
        }

        @Test
        @DisplayName("UTCID10 — ema hội tụ < 0.03 sau 10 câu sai liên tiếp")
        void shouldConvergeLowAfterManyWrongAnswers() {
            // Execute
            for (int i = 0; i < 10; i++) mastery.recordAnswer(false);

            // Assert
            assertThat(mastery.getEmaScore().doubleValue()).isLessThan(0.03);
        }

        @Test
        @DisplayName("UTCID01 — ema.scale() == 5 (HALF_UP rounding)")
        void shouldHaveScale5OnEmaScore() {
            // Execute
            mastery.recordAnswer(true);

            // Assert
            assertThat(mastery.getEmaScore().scale()).isEqualTo(5);
        }
    }

    // ── Mastery level thresholds ──────────────────────────────────────────

    @Nested
    @DisplayName("MasteryLevel — ngưỡng 0.40 / 0.70 / 0.90")
    class MasteryLevelThresholds {

        @Test
        @DisplayName("Fresh entity mặc định level=0")
        void shouldBeLevel0WhenFresh() {
            assertThat(mastery.getMasteryLevel()).isEqualTo(0);
        }

        @Test
        @DisplayName("UTCID02 — first wrong: score≈0.15 < 0.40 → level=0")
        void shouldStayLevel0AfterFirstWrongAnswer() {
            // Execute
            mastery.recordAnswer(false);

            // Assert: accuracy=0, ema=0.30, streak=0 → score=0*0.30+0.30*0.50+0*0.20=0.15
            assertThat(mastery.getMasteryLevel()).isEqualTo(0);
        }

        @Test
        @DisplayName("UTCID11 — level=3 sau 5 câu đúng liên tiếp (score≥0.90)")
        void shouldReachLevel3AfterPerfectStreak() {
            // Execute
            for (int i = 0; i < 5; i++) mastery.recordAnswer(true);

            // Assert
            assertThat(mastery.getMasteryLevel()).isEqualTo(3);
        }

        @Test
        @DisplayName("Level invariant: luôn nằm trong [0, 3]")
        void shouldAlwaysBeInValidRange() {
            // Execute: 20 câu sai rồi 20 câu đúng
            for (int i = 0; i < 20; i++) mastery.recordAnswer(false);
            assertThat(mastery.getMasteryLevel()).isGreaterThanOrEqualTo(0);

            for (int i = 0; i < 20; i++) mastery.recordAnswer(true);
            assertThat(mastery.getMasteryLevel()).isLessThanOrEqualTo(3);
        }
    }

    // ── getMasteryLabel switch coverage ──────────────────────────────────

    @Nested
    @DisplayName("getMasteryLabel — switch 4 cases")
    class MasteryLabel {

        @Test
        @DisplayName("Level 0 → 'Chưa thành thạo'")
        void shouldReturnChuaThanhThaoForLevel0() {
            assertThat(mastery.getMasteryLabel()).isEqualTo("Chưa thành thạo");
        }

        @Test
        @DisplayName("Level 1 → 'Quen thuộc'")
        void shouldReturnQuenThuocForLevel1() {
            // Setup
            mastery.setMasteryLevel(1);

            // Assert
            assertThat(mastery.getMasteryLabel()).isEqualTo("Quen thuộc");
        }

        @Test
        @DisplayName("Level 2 → 'Nắm vững'")
        void shouldReturnNamVungForLevel2() {
            // Setup
            mastery.setMasteryLevel(2);

            // Assert
            assertThat(mastery.getMasteryLabel()).isEqualTo("Nắm vững");
        }

        @Test
        @DisplayName("Level 3 → 'Thuộc'")
        void shouldReturnThuocForLevel3() {
            // Setup
            mastery.setMasteryLevel(3);

            // Assert
            assertThat(mastery.getMasteryLabel()).isEqualTo("Thuộc");
        }
    }

    // ── MasteryUpdateResult record ────────────────────────────────────────

    @Nested
    @DisplayName("MasteryUpdateResult — changed() và newlyMastered()")
    class MasteryUpdateResultTest {

        @Test
        @DisplayName("changed() = false khi level không đổi")
        void shouldReturnFalseWhenLevelUnchanged() {
            // Setup
            var result = new ItemMasteryService.MasteryUpdateResult(
                    UUID.randomUUID(), "VOCABULARY", 2, 2);

            // Assert
            assertThat(result.changed()).isFalse();
        }

        @Test
        @DisplayName("changed() = true khi level thay đổi")
        void shouldReturnTrueWhenLevelChanged() {
            // Setup
            var result = new ItemMasteryService.MasteryUpdateResult(
                    UUID.randomUUID(), "VOCABULARY", 1, 2);

            // Assert
            assertThat(result.changed()).isTrue();
        }

        @Test
        @DisplayName("newlyMastered() = true khi levelAfter=3 và levelBefore<3")
        void shouldReturnTrueWhenNewlyMastered() {
            // Setup
            var result = new ItemMasteryService.MasteryUpdateResult(
                    UUID.randomUUID(), "GRAMMAR", 2, 3);

            // Assert
            assertThat(result.newlyMastered()).isTrue();
        }

        @Test
        @DisplayName("newlyMastered() = false khi levelBefore đã là 3")
        void shouldReturnFalseWhenAlreadyMastered() {
            // Setup
            var result = new ItemMasteryService.MasteryUpdateResult(
                    UUID.randomUUID(), "GRAMMAR", 3, 3);

            // Assert
            assertThat(result.newlyMastered()).isFalse();
        }

        @Test
        @DisplayName("newlyMastered() = false khi levelAfter < 3")
        void shouldReturnFalseWhenNotAtMaxLevel() {
            // Setup
            var result = new ItemMasteryService.MasteryUpdateResult(
                    UUID.randomUUID(), "VOCABULARY", 1, 2);

            // Assert
            assertThat(result.newlyMastered()).isFalse();
        }
    }
}
