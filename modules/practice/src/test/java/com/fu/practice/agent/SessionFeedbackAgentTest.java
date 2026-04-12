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
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit Test: SessionFeedbackAgent.generateFeedback()
 * Template: docs/GENERATE_SESSION_FEEDBACK_UNIT_TEST_TEMPLATE.md
 *
 * LLM luôn fail (mock OpenAiChatModel) → kích hoạt buildSimpleFeedback fallback.
 * 3 nhánh theo score: ≥80 "Xuất sắc" | ≥60 "Khá tốt" | <60 generic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionFeedbackAgent Unit Tests")
class SessionFeedbackAgentTest {

    @Mock private OpenAiChatModel openAiChatModel;
    @Mock private PromptTemplateLoader promptLoader;

    private SessionFeedbackAgent agent;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Fixed UUIDs ───────────────────────────────────────────────────────
    private static final UUID SESSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        agent = new SessionFeedbackAgent(openAiChatModel, objectMapper, promptLoader);
    }

    // ── UTCID01-05: buildSimpleFeedback — 3 nhánh score ──────────────────

    @Nested
    @DisplayName("buildSimpleFeedback — 3 score branches (LLM luôn fail → fallback)")
    class SimpleFeedbackScoreBranches {

        @Test
        @DisplayName("UTCID01 — score=85.0 (≥80) → summary chứa 'Xuất sắc' và '85'")
        void shouldReturnExcellentFeedbackWhenScoreAbove80() {
            // Execute
            String json = agent.generateFeedback(SESSION_ID, 85.0, List.of(), "MIXED_LESSON");

            // Assert
            assertThat(json).contains("Xuất sắc");
            assertThat(json).contains("85");
        }

        @Test
        @DisplayName("UTCID02 — score=70.0 (≥60 và <80) → summary chứa 'Khá tốt' và '70'")
        void shouldReturnGoodFeedbackWhenScoreBetween60And80() {
            // Execute
            String json = agent.generateFeedback(SESSION_ID, 70.0, List.of(), "VOCAB_DRILL");

            // Assert
            assertThat(json).contains("Khá tốt");
            assertThat(json).contains("70");
        }

        @Test
        @DisplayName("UTCID03 — score=45.0 (<60) → KHÔNG chứa 'Xuất sắc' hoặc 'Khá tốt'")
        void shouldReturnGenericFeedbackWhenScoreBelow60() {
            // Execute
            String json = agent.generateFeedback(SESSION_ID, 45.0, List.of(), "GRAMMAR_DRILL");

            // Assert
            assertThat(json).doesNotContain("Xuất sắc");
            assertThat(json).doesNotContain("Khá tốt");
            assertThat(json).contains("45");
        }

        @Test
        @DisplayName("UTCID04 — score=80.0 (boundary =80) → 'Xuất sắc' (nhánh ≥80)")
        void shouldReturnExcellentAtBoundary80() {
            // Execute
            String json = agent.generateFeedback(SESSION_ID, 80.0, List.of(), "MIXED_LESSON");

            // Assert
            assertThat(json).contains("Xuất sắc");
        }

        @Test
        @DisplayName("UTCID05 — score=60.0 (boundary =60) → 'Khá tốt' (nhánh ≥60)")
        void shouldReturnGoodAtBoundary60() {
            // Execute
            String json = agent.generateFeedback(SESSION_ID, 60.0, List.of(), "VOCAB_DRILL");

            // Assert
            assertThat(json).contains("Khá tốt");
        }
    }

    // ── UTCID06-07: JSON structure validation ─────────────────────────────

    @Nested
    @DisplayName("Fallback JSON structure — parseable và có đủ fields")
    class FallbackJsonStructure {

        @Test
        @DisplayName("UTCID06 — fallback JSON parseable bởi ObjectMapper")
        void shouldReturnValidJsonString() {
            // Execute
            String json = agent.generateFeedback(SESSION_ID, 75.0, List.of(), "MIXED_LESSON");

            // Assert
            assertThatCode(() -> objectMapper.readTree(json)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("UTCID07 — fallback JSON có 'summary' và 'weaknesses' fields")
        void shouldHaveRequiredFieldsInFallbackJson() throws Exception {
            // Execute
            String json = agent.generateFeedback(SESSION_ID, 50.0, List.of(), "GRAMMAR_DRILL");

            // Assert
            var node = objectMapper.readTree(json);
            assertThat(node.has("summary")).isTrue();
            assertThat(node.get("summary").asText()).isNotBlank();
            assertThat(node.has("weaknesses")).isTrue();
        }
    }

    // ── UTCID08-11: wrong items filtering và edge cases ───────────────────

    @Nested
    @DisplayName("Wrong items filtering và edge cases")
    class WrongItemsFilteringTests {

        @Test
        @DisplayName("UTCID08 — items có isCorrect=false được nhận diện là sai, không ném exception")
        void shouldHandleMixedItemsWithoutException() {
            // Setup
            PracticeItem wrong   = buildItem(false, "問題1");
            PracticeItem correct = buildItem(true, "問題2");

            // Execute & Assert
            assertThatCode(() ->
                    agent.generateFeedback(SESSION_ID, 50.0, List.of(wrong, correct), "MIXED_LESSON"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("UTCID09 — sessionType=null → default 'MIXED_LESSON', không ném exception")
        void shouldHandleNullSessionTypeGracefully() {
            // Execute & Assert
            assertThatCode(() ->
                    agent.generateFeedback(SESSION_ID, 70.0, List.of(), null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("UTCID10 — tất cả đúng (0 wrong items) → không ném exception")
        void shouldHandleAllCorrectItemsGracefully() {
            // Setup
            PracticeItem c1 = buildItem(true, "問題1");
            PracticeItem c2 = buildItem(true, "問題2");

            // Execute & Assert
            assertThatCode(() ->
                    agent.generateFeedback(SESSION_ID, 100.0, List.of(c1, c2), "VOCAB_DRILL"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("UTCID11 — empty items + null sessionType → JSON hợp lệ trả về")
        void shouldReturnValidJsonForEmptyItemsAndNullType() {
            // Execute
            String json = agent.generateFeedback(SESSION_ID, 65.0, List.of(), null);

            // Assert: parseable JSON
            assertThatCode(() -> objectMapper.readTree(json)).doesNotThrowAnyException();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private PracticeItem buildItem(boolean isCorrect, String question) {
        PracticeItem item = PracticeItem.builder()
                .sessionId(SESSION_ID)
                .itemType(PracticeItemType.MULTIPLE_CHOICE)
                .question(question)
                .correctAnswer("1")
                .userAnswer(isCorrect ? "1" : "2")
                .itemIndex(0)
                .build();
        item.setIsCorrect(isCorrect);
        item.setId(UUID.randomUUID());
        return item;
    }
}
