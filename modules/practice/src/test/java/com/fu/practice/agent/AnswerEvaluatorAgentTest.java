package com.fu.practice.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.ai.PromptTemplateLoader;
import com.fu.practice.entity.PracticeItem;
import com.fu.practice.entity.PracticeItemType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit Test: AnswerEvaluatorAgent.evaluate()
 * Template: docs/EVALUATE_ANSWER_UNIT_TEST_TEMPLATE.md
 *
 * Tất cả rule-based paths (MC, TF, MATCHING, FILL_BLANK với integer ID,
 * SENTENCE_ORDER với integer array) không cần gọi LLM thật.
 * LLM mock (OpenAiChatModel) sẽ NPE khi được gọi → fallback graceful.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnswerEvaluatorAgent Unit Tests")
class AnswerEvaluatorAgentTest {

    @Mock private OpenAiChatModel openAiChatModel;
    @Mock private PromptTemplateLoader promptLoader;

    private AnswerEvaluatorAgent agent;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Fixed UUIDs ───────────────────────────────────────────────────────
    private static final UUID SESSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ITEM_ID    = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        agent = new AnswerEvaluatorAgent(openAiChatModel, objectMapper, promptLoader);
    }

    // ── UTCID01-05: MULTIPLE_CHOICE — rule-based ──────────────────────────

    @Nested
    @DisplayName("MULTIPLE_CHOICE — rule-based exact match")
    class MultipleChoiceTests {

        @Test
        @DisplayName("UTCID01 — đáp án đúng → isCorrect=true, score=100, correction=null")
        void shouldReturnCorrectResultWhenAnswerMatches() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.MULTIPLE_CHOICE, "2");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "2");

            // Assert
            assertThat(result.isCorrect()).isTrue();
            assertThat(result.score()).isEqualTo(100);
            assertThat(result.feedback()).isEqualTo("Chính xác!");
            assertThat(result.correction()).isNull();
        }

        @Test
        @DisplayName("UTCID02 — đáp án sai → isCorrect=false, score=0, correction=correctAnswer")
        void shouldReturnWrongResultWhenAnswerDoesNotMatch() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.MULTIPLE_CHOICE, "2");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "3");

            // Assert
            assertThat(result.isCorrect()).isFalse();
            assertThat(result.score()).isEqualTo(0);
            assertThat(result.correction()).isEqualTo("2");
        }

        @Test
        @DisplayName("UTCID03 — so sánh case-insensitive: 'TRUE' vs 'true' → đúng")
        void shouldCompareCaseInsensitive() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.MULTIPLE_CHOICE, "TRUE");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "true");

            // Assert
            assertThat(result.isCorrect()).isTrue();
        }

        @Test
        @DisplayName("UTCID04 — correctAnswer null → isCorrect=false (null guard)")
        void shouldReturnFalseWhenCorrectAnswerIsNull() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.MULTIPLE_CHOICE, null);

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "2");

            // Assert
            assertThat(result.isCorrect()).isFalse();
        }

        @Test
        @DisplayName("UTCID05 — userAnswer null → isCorrect=false (null guard)")
        void shouldReturnFalseWhenUserAnswerIsNull() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.MULTIPLE_CHOICE, "2");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, null);

            // Assert
            assertThat(result.isCorrect()).isFalse();
        }
    }

    // ── UTCID06: TRUE_FALSE ───────────────────────────────────────────────

    @Nested
    @DisplayName("TRUE_FALSE — same rule-based path as MC")
    class TrueFalseTests {

        @Test
        @DisplayName("UTCID06 — TF đúng → isCorrect=true, score=100")
        void shouldReturnCorrectForTrueFalseMatch() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.TRUE_FALSE, "1");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "1");

            // Assert
            assertThat(result.isCorrect()).isTrue();
            assertThat(result.score()).isEqualTo(100);
        }
    }

    // ── UTCID07-13: MATCHING — partial scoring ────────────────────────────

    @Nested
    @DisplayName("MATCHING — partial score N_correct / total × 100")
    class MatchingTests {

        @Test
        @DisplayName("UTCID07 — tất cả cặp đúng → isCorrect=true, score=100, correction=null")
        void shouldReturnFullScoreWhenAllPairsCorrect() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.MATCHING, "0:4,1:5,2:6");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "0:4,1:5,2:6");

            // Assert
            assertThat(result.isCorrect()).isTrue();
            assertThat(result.score()).isEqualTo(100);
            assertThat(result.correction()).isNull();
        }

        @Test
        @DisplayName("UTCID08 — 2/4 cặp đúng → score=50, isCorrect=false")
        void shouldReturnPartialScoreWhenSomePairsCorrect() {
            // Setup: user đúng 0:4 và 2:6, sai 1:6(đúng phải 1:5) và 3:8(đúng phải 3:7)
            PracticeItem item = buildItem(PracticeItemType.MATCHING, "0:4,1:5,2:6,3:7");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "0:4,1:6,2:6,3:8");

            // Assert
            assertThat(result.isCorrect()).isFalse();
            assertThat(result.score()).isEqualTo(50); // 2/4 = 50
        }

        @Test
        @DisplayName("UTCID09 — 0 cặp đúng → score=0, isCorrect=false")
        void shouldReturnZeroScoreWhenNoPairsCorrect() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.MATCHING, "0:4,1:5");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "0:9,1:8");

            // Assert
            assertThat(result.isCorrect()).isFalse();
            assertThat(result.score()).isEqualTo(0);
        }

        @Test
        @DisplayName("UTCID10 — userAnswer rỗng → score=0, feedback chứa 'chưa nối'")
        void shouldReturnZeroWhenUserAnswerEmpty() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.MATCHING, "0:4,1:5");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "");

            // Assert
            assertThat(result.isCorrect()).isFalse();
            assertThat(result.score()).isEqualTo(0);
            assertThat(result.feedback()).contains("chưa nối");
        }

        @Test
        @DisplayName("UTCID11 — userAnswer malformed → isCorrect=false, không ném exception")
        void shouldHandleMalformedAnswerGracefully() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.MATCHING, "0:4,1:5");

            // Execute & Assert: must not throw
            assertThatCode(() -> agent.evaluate(item, "!!!INVALID!!!"))
                    .doesNotThrowAnyException();
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "!!!INVALID!!!");
            assertThat(result.isCorrect()).isFalse();
        }

        @Test
        @DisplayName("UTCID12 — feedback chứa total khi đúng hết")
        void shouldIncludeTotalInFeedbackWhenAllCorrect() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.MATCHING, "0:4,1:5,2:6");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "0:4,1:5,2:6");

            // Assert: "3" (total pairs) in feedback
            assertThat(result.feedback()).contains("3");
        }

        @Test
        @DisplayName("UTCID13 — feedback chứa ratio X/Y khi partial")
        void shouldIncludeRatioInFeedbackWhenPartial() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.MATCHING, "0:4,1:5,2:6");

            // Execute: đúng 1/3
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "0:4,1:9,2:9");

            // Assert
            assertThat(result.feedback()).contains("1");
            assertThat(result.feedback()).contains("3");
        }
    }

    // ── UTCID14-18: FILL_BLANK — integer ID ──────────────────────────────

    @Nested
    @DisplayName("FILL_BLANK — so sánh integer option ID")
    class FillBlankTests {

        @Test
        @DisplayName("UTCID14 — ID khớp → isCorrect=true, score=100")
        void shouldReturnCorrectWhenIdsMatch() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.FILL_BLANK, "2");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "2");

            // Assert
            assertThat(result.isCorrect()).isTrue();
            assertThat(result.score()).isEqualTo(100);
        }

        @Test
        @DisplayName("UTCID15 — ID không khớp → isCorrect=false, score=0")
        void shouldReturnWrongWhenIdsDontMatch() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.FILL_BLANK, "2");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "3");

            // Assert
            assertThat(result.isCorrect()).isFalse();
            assertThat(result.score()).isEqualTo(0);
        }

        @Test
        @DisplayName("UTCID16 — whitespace được trim trước khi parse (' 2 ' == '  2  ')")
        void shouldTrimWhitespaceBeforeComparison() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.FILL_BLANK, " 2 ");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "  2  ");

            // Assert
            assertThat(result.isCorrect()).isTrue();
        }

        @Test
        @DisplayName("UTCID17 — userAnswer null → treated as -1 → false")
        void shouldTreatNullUserAnswerAsMinus1() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.FILL_BLANK, "2");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, null);

            // Assert
            assertThat(result.isCorrect()).isFalse();
        }

        @Test
        @DisplayName("UTCID18 — correctAnswer blank → LLM path → không ném exception (graceful)")
        void shouldFallbackGracefullyWhenCorrectAnswerBlank() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.FILL_BLANK, "");

            // Execute & Assert: LLM fails → fallbackExactMatch → no exception
            assertThatCode(() -> agent.evaluate(item, "some answer"))
                    .doesNotThrowAnyException();
        }
    }

    // ── UTCID19-24: SENTENCE_ORDER — integer array ────────────────────────

    @Nested
    @DisplayName("SENTENCE_ORDER — so sánh mảng integer token ID")
    class SentenceOrderTests {

        @Test
        @DisplayName("UTCID19 — thứ tự đúng → isCorrect=true, score=100")
        void shouldReturnCorrectWhenOrderMatches() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.SENTENCE_ORDER, "0,1,2,3");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "0,1,2,3");

            // Assert
            assertThat(result.isCorrect()).isTrue();
            assertThat(result.score()).isEqualTo(100);
        }

        @Test
        @DisplayName("UTCID20 — thứ tự sai → isCorrect=false, score=0")
        void shouldReturnWrongWhenOrderDiffers() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.SENTENCE_ORDER, "0,1,2,3");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "0,2,1,3");

            // Assert
            assertThat(result.isCorrect()).isFalse();
            assertThat(result.score()).isEqualTo(0);
        }

        @Test
        @DisplayName("UTCID21 — whitespace xung quanh comma được trim ('0, 1, 2' == '0,1,2')")
        void shouldTrimWhitespaceAroundCommas() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.SENTENCE_ORDER, "0, 1, 2");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "0,1,2");

            // Assert
            assertThat(result.isCorrect()).isTrue();
        }

        @Test
        @DisplayName("UTCID22 — userAnswer rỗng → isCorrect=false")
        void shouldReturnFalseWhenUserAnswerEmpty() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.SENTENCE_ORDER, "0,1,2");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "");

            // Assert
            assertThat(result.isCorrect()).isFalse();
        }

        @Test
        @DisplayName("UTCID23 — userAnswer null → isCorrect=false")
        void shouldReturnFalseWhenUserAnswerNull() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.SENTENCE_ORDER, "0,1,2");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, null);

            // Assert
            assertThat(result.isCorrect()).isFalse();
        }

        @Test
        @DisplayName("UTCID24 — correctAnswer blank → LLM fallback graceful, không ném exception")
        void shouldFallbackGracefullyWhenCorrectAnswerBlank() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.SENTENCE_ORDER, "");

            // Execute & Assert
            assertThatCode(() -> agent.evaluate(item, "0,1")).doesNotThrowAnyException();
        }
    }

    // ── UTCID25-26: EvaluationResult record ──────────────────────────────

    @Nested
    @DisplayName("EvaluationResult — correction field")
    class EvaluationResultTests {

        @Test
        @DisplayName("UTCID25 — correction = null khi đúng")
        void shouldSetCorrectionNullWhenAnswerIsCorrect() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.MULTIPLE_CHOICE, "1");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "1");

            // Assert
            assertThat(result.correction()).isNull();
        }

        @Test
        @DisplayName("UTCID26 — correction = correctAnswer khi sai")
        void shouldSetCorrectionToCorrectAnswerWhenWrong() {
            // Setup
            PracticeItem item = buildItem(PracticeItemType.MULTIPLE_CHOICE, "1");

            // Execute
            AnswerEvaluatorAgent.EvaluationResult result = agent.evaluate(item, "2");

            // Assert
            assertThat(result.correction()).isEqualTo("1");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private PracticeItem buildItem(PracticeItemType type, String correctAnswer) {
        PracticeItem item = PracticeItem.builder()
                .sessionId(SESSION_ID)
                .itemType(type)
                .question("テスト問題")
                .correctAnswer(correctAnswer)
                .itemIndex(0)
                .build();
        item.setId(ITEM_ID);
        return item;
    }
}
