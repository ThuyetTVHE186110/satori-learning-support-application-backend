package com.fu.curriculumimport.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for VocabularyExtractionAgent.
 *
 * Coverage:
 *  extractVocabulary():
 *   - Happy path (null RAG context) -> vocabulary parsed, grammarPoints empty, lessons empty
 *   - VOCABULARY-type RAG items in context -> ChatModel is called (context included in prompt)
 *   - Non-VOCABULARY RAG items -> filtered out (ChatModel still called, but grammar items excluded)
 *   - Malformed JSON -> returns empty result (confidence=0.0)
 *   - ChatModel throws exception -> returns empty result (confidence=0.0)
 *   - Missing vocabulary field in JSON -> vocabulary list is empty
 *
 *  reExtractWithFeedback():
 *   - Happy path -> vocabulary parsed from re-extraction response
 *   - ChatModel throws exception -> returns empty result (confidence=0.0)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VocabularyExtractionAgent")
class VocabularyExtractionAgentTest {

    @Mock
    private OpenAiChatModel chatModel;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    private VocabularyExtractionAgent agent;

    @BeforeEach
    void setUp() {
        agent = new VocabularyExtractionAgent(chatModel, objectMapper);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void stubModelResponse(String content) {
        AssistantMessage msg = mock(AssistantMessage.class);
        given(msg.getContent()).willReturn(content);

        Generation gen = mock(Generation.class);
        given(gen.getOutput()).willReturn(msg);

        ChatResponse chatResp = mock(ChatResponse.class);
        given(chatResp.getResult()).willReturn(gen);

        given(chatModel.call(any(Prompt.class))).willReturn(chatResp);
    }

    private DocumentChunk chunk(int chunkIndex, String content) {
        return DocumentChunk.builder()
                .chunkIndex(chunkIndex)
                .content(content)
                .pageStart(1)
                .pageEnd(5)
                .build();
    }

    private DocumentChunk chunk(String content) {
        return chunk(0, content);
    }

    private RagContext ragContextWithItems(List<RagContext.SimilarItem> items) {
        return RagContext.builder()
                .similarItems(items)
                .existingVocabCount(items.size())
                .existingGrammarCount(0)
                .formattedContext("")
                .build();
    }

    private RagContext.SimilarItem similarItem(String contentType, String textContent) {
        return RagContext.SimilarItem.builder()
                .contentId(UUID.randomUUID())
                .contentType(contentType)
                .textContent(textContent)
                .similarity(0.9)
                .build();
    }

    private String singleVocabJson(String japaneseText, String meaningEn) {
        return String.format(
                "{\"vocabulary\":[{\"japaneseText\":\"%s\",\"reading\":\"reading\",\"romaji\":\"romaji\","
                        + "\"meaningVi\":\"nghia\",\"meaningEn\":\"%s\",\"partOfSpeech\":\"noun\","
                        + "\"jlptLevel\":\"N5\"}],\"confidence\":0.87}",
                japaneseText, meaningEn);
    }

    // =========================================================================
    // extractVocabulary()
    // =========================================================================

    @Nested
    @DisplayName("extractVocabulary()")
    class ExtractVocabularyTests {

        @Test
        @DisplayName("Happy path with null RAG context -> vocabulary parsed, grammarPoints and lessons empty")
        void extractVocabulary_nullRagContext_parsesVocabularyOnly() {
            stubModelResponse(singleVocabJson("食べる", "to eat"));

            ExtractionResult result = agent.extractVocabulary(chunk("食べる N5"), "N5", null);

            assertThat(result.getVocabulary()).hasSize(1);
            assertThat(result.getVocabulary().get(0).getJapaneseText()).isEqualTo("食べる");
            assertThat(result.getVocabulary().get(0).getMeaningEn()).isEqualTo("to eat");
            assertThat(result.getGrammarPoints()).isEmpty();
            assertThat(result.getLessons()).isEmpty();
            assertThat(result.getConfidence()).isEqualTo(0.87);
        }

        @Test
        @DisplayName("VOCABULARY-type RAG items in context -> ChatModel is called with context included")
        void extractVocabulary_withVocabularyRagItems_callsChatModelWithContext() {
            RagContext ragContext = ragContextWithItems(List.of(
                    similarItem("VOCABULARY", "食べる - to eat - verb")));
            stubModelResponse(singleVocabJson("飲む", "to drink"));

            ExtractionResult result = agent.extractVocabulary(chunk("飲む N5"), "N5", ragContext);

            // Verify ChatModel was called (context was formatted into prompt)
            verify(chatModel, times(1)).call(any(Prompt.class));
            assertThat(result.getVocabulary()).hasSize(1);
            assertThat(result.getVocabulary().get(0).getJapaneseText()).isEqualTo("飲む");
        }

        @Test
        @DisplayName("RAG items of non-VOCABULARY type -> GRAMMAR items filtered out, ChatModel still called")
        void extractVocabulary_grammarTypeRagItems_filteredOutButModelStillCalled() {
            // Only GRAMMAR items in RAG context; formatRagContext returns "" so no VOCABULARY section
            RagContext ragContext = ragContextWithItems(List.of(
                    similarItem("GRAMMAR", "～ている - ongoing action")));
            stubModelResponse(singleVocabJson("書く", "to write"));

            ExtractionResult result = agent.extractVocabulary(chunk("書く N5"), "N5", ragContext);

            // ChatModel should still be called even if grammar items are filtered
            verify(chatModel, times(1)).call(any(Prompt.class));
            assertThat(result.getVocabulary()).hasSize(1);
            assertThat(result.getVocabulary().get(0).getJapaneseText()).isEqualTo("書く");
        }

        @Test
        @DisplayName("Malformed JSON response -> returns empty result with confidence=0.0")
        void extractVocabulary_malformedJson_returnsEmptyResult() {
            stubModelResponse("not valid json at all {][");

            ExtractionResult result = agent.extractVocabulary(chunk("some content"), "N5", RagContext.empty());

            assertThat(result.getConfidence()).isEqualTo(0.0);
            assertThat(result.getVocabulary()).isEmpty();
            assertThat(result.getGrammarPoints()).isEmpty();
            assertThat(result.getLessons()).isEmpty();
        }

        @Test
        @DisplayName("ChatModel throws exception -> returns empty result with confidence=0.0")
        void extractVocabulary_chatModelThrows_returnsEmptyResult() {
            given(chatModel.call(any(Prompt.class)))
                    .willThrow(new RuntimeException("API timeout"));

            ExtractionResult result = agent.extractVocabulary(chunk("some content"), "N5", RagContext.empty());

            assertThat(result.getConfidence()).isEqualTo(0.0);
            assertThat(result.getVocabulary()).isEmpty();
            assertThat(result.getGrammarPoints()).isEmpty();
            assertThat(result.getLessons()).isEmpty();
            assertThat(result.getTokensUsed()).isZero();
        }

        @Test
        @DisplayName("Missing vocabulary field in JSON -> vocabulary list is empty")
        void extractVocabulary_missingVocabularyField_returnsEmptyVocabularyList() {
            // JSON has confidence but no vocabulary key
            stubModelResponse("{\"confidence\":0.6}");

            ExtractionResult result = agent.extractVocabulary(chunk("some content"), "N5", RagContext.empty());

            assertThat(result.getVocabulary()).isEmpty();
            assertThat(result.getConfidence()).isEqualTo(0.6);
        }
    }

    // =========================================================================
    // reExtractWithFeedback()
    // =========================================================================

    @Nested
    @DisplayName("reExtractWithFeedback()")
    class ReExtractWithFeedbackTests {

        @Test
        @DisplayName("Happy path -> vocabulary parsed correctly from re-extraction response")
        void reExtractWithFeedback_validResponse_parsesVocabularySuccessfully() {
            stubModelResponse(singleVocabJson("見る", "to see"));

            ExtractionResult result = agent.reExtractWithFeedback(
                    chunk(3, "見る N4"),
                    "N4",
                    RagContext.empty(),
                    "Previous extraction missed readings for all entries.");

            assertThat(result.getVocabulary()).hasSize(1);
            assertThat(result.getVocabulary().get(0).getJapaneseText()).isEqualTo("見る");
            assertThat(result.getVocabulary().get(0).getMeaningEn()).isEqualTo("to see");
            assertThat(result.getGrammarPoints()).isEmpty();
            assertThat(result.getLessons()).isEmpty();
            assertThat(result.getChunkIndex()).isEqualTo(3);
        }

        @Test
        @DisplayName("ChatModel throws exception during re-extraction -> returns empty result")
        void reExtractWithFeedback_chatModelThrows_returnsEmptyResult() {
            given(chatModel.call(any(Prompt.class)))
                    .willThrow(new RuntimeException("Rate limit exceeded"));

            ExtractionResult result = agent.reExtractWithFeedback(
                    chunk(2, "some content"),
                    "N5",
                    RagContext.empty(),
                    "Missing romaji for all words.");

            assertThat(result.getConfidence()).isEqualTo(0.0);
            assertThat(result.getVocabulary()).isEmpty();
            assertThat(result.getGrammarPoints()).isEmpty();
            assertThat(result.getLessons()).isEmpty();
            assertThat(result.getTokensUsed()).isZero();
        }
    }
}
