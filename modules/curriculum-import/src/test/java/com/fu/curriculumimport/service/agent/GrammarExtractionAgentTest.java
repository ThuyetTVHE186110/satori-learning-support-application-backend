package com.fu.curriculumimport.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import com.fu.curriculumimport.config.AgenticRagProperties;
import com.fu.curriculumimport.service.vision.VisionPageExtractor;
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
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for GrammarExtractionAgent.
 *
 * Coverage:
 *  extractGrammar():
 *   - Happy path -> grammarPoints parsed, vocabulary empty, lessons empty
 *   - GRAMMAR-type RAG items in context -> ChatModel is called (context included in prompt)
 *   - VOCABULARY-type RAG items -> filtered out (ChatModel still called, vocab items excluded)
 *   - Malformed JSON -> returns empty result (confidence=0.0)
 *   - ChatModel throws exception -> returns empty result (confidence=0.0)
 *
 *  reExtractWithFeedback():
 *   - Happy path -> grammarPoints parsed correctly from re-extraction response
 *   - ChatModel throws exception -> returns empty result (confidence=0.0)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GrammarExtractionAgent")
class GrammarExtractionAgentTest {

    @Mock
    private OpenAiChatModel chatModel;

    @Mock
    private VisionPageExtractor visionPageExtractor;

    @Mock
    private AgenticRagProperties ragProperties;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    private GrammarExtractionAgent agent;

    @BeforeEach
    void setUp() {
        agent = new GrammarExtractionAgent(chatModel, objectMapper, visionPageExtractor, ragProperties);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void stubModelResponse(String content) {
        AssistantMessage msg = mock(AssistantMessage.class);
        given(msg.getText()).willReturn(content);

        Generation gen = mock(Generation.class);
        given(gen.getOutput()).willReturn(msg);

        ChatResponse chatResp = mock(ChatResponse.class);
        given(chatResp.getResult()).willReturn(gen);

        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.just(chatResp));
    }

    private DocumentChunk chunk(int chunkIndex, String content) {
        return DocumentChunk.builder()
                .chunkIndex(chunkIndex)
                .content(content)
                .pageStart(2)
                .pageEnd(4)
                .build();
    }

    private DocumentChunk chunk(String content) {
        return chunk(0, content);
    }

    private RagContext ragContextWithItems(List<RagContext.SimilarItem> items) {
        return RagContext.builder()
                .similarItems(items)
                .existingVocabCount(0)
                .existingGrammarCount(items.size())
                .build();
    }

    private RagContext.SimilarItem similarItem(String contentType, String textContent) {
        return RagContext.SimilarItem.builder()
                .contentId(UUID.randomUUID())
                .contentType(contentType)
                .textContent(textContent)
                .similarity(0.85)
                .build();
    }

    private String singleGrammarJson(String pattern, String meaningEn) {
        return String.format(
                "{\"grammarPoints\":[{\"pattern\":\"%s\",\"patternReading\":\"%s\","
                        + "\"meaningVi\":\"nghia tieng Viet\",\"meaningEn\":\"%s\","
                        + "\"shortExplanationVi\":\"short exp\",\"detailedExplanationVi\":\"detailed exp\","
                        + "\"jlptLevel\":\"N5\",\"category\":\"VERB_FORM\"}],"
                        + "\"confidence\":0.88}",
                pattern, pattern, meaningEn);
    }

    // =========================================================================
    // extractGrammar()
    // =========================================================================

    @Nested
    @DisplayName("extractGrammar()")
    class ExtractGrammarTests {

        @Test
        @DisplayName("Happy path -> grammarPoints parsed, vocabulary empty, lessons empty")
        void extractGrammar_validJson_parsesGrammarPointsOnly() {
            stubModelResponse(singleGrammarJson("～ます", "Polite verb ending"));

            ExtractionResult result = agent.extractGrammar(chunk("～ます grammar"), "N5", null);

            assertThat(result.getGrammarPoints()).hasSize(1);
            assertThat(result.getGrammarPoints().get(0).getPattern()).isEqualTo("～ます");
            assertThat(result.getGrammarPoints().get(0).getMeaningEn()).isEqualTo("Polite verb ending");
            assertThat(result.getGrammarPoints().get(0).getCategory()).isEqualTo("VERB_FORM");
            assertThat(result.getVocabulary()).isEmpty();
            assertThat(result.getLessons()).isEmpty();
            assertThat(result.getConfidence()).isEqualTo(0.88);
        }

        @Test
        @DisplayName("GRAMMAR-type RAG items in context -> ChatModel is called with context included")
        void extractGrammar_withGrammarRagItems_callsChatModelWithContext() {
            RagContext ragContext = ragContextWithItems(List.of(
                    similarItem("GRAMMAR", "～ている - ongoing action - VERB_FORM")));
            stubModelResponse(singleGrammarJson("～てください", "Please do..."));

            ExtractionResult result = agent.extractGrammar(chunk("～てください usage"), "N5", ragContext);

            // Verify ChatModel was called (context was formatted into prompt)
            verify(chatModel, times(1)).stream(any(Prompt.class));
            assertThat(result.getGrammarPoints()).hasSize(1);
            assertThat(result.getGrammarPoints().get(0).getPattern()).isEqualTo("～てください");
        }

        @Test
        @DisplayName("VOCABULARY-type RAG items -> filtered out, ChatModel still called")
        void extractGrammar_vocabularyTypeRagItems_filteredOutButModelStillCalled() {
            // Only VOCABULARY items in RAG context; formatRagContext returns "" so no GRAMMAR section
            RagContext ragContext = ragContextWithItems(List.of(
                    similarItem("VOCABULARY", "食べる - to eat - verb")));
            stubModelResponse(singleGrammarJson("～ない", "Negative form"));

            ExtractionResult result = agent.extractGrammar(chunk("～ない grammar"), "N5", ragContext);

            // ChatModel should still be called even though vocabulary items are filtered out
            verify(chatModel, times(1)).stream(any(Prompt.class));
            assertThat(result.getGrammarPoints()).hasSize(1);
            assertThat(result.getGrammarPoints().get(0).getPattern()).isEqualTo("～ない");
        }

        @Test
        @DisplayName("Malformed JSON response -> returns empty result with confidence=0.0")
        void extractGrammar_malformedJson_returnsEmptyResult() {
            stubModelResponse("this is totally broken json {][");

            ExtractionResult result = agent.extractGrammar(chunk("some content"), "N5", RagContext.empty());

            assertThat(result.getConfidence()).isEqualTo(0.0);
            assertThat(result.getGrammarPoints()).isEmpty();
            assertThat(result.getVocabulary()).isEmpty();
            assertThat(result.getLessons()).isEmpty();
        }

        @Test
        @DisplayName("ChatModel throws exception -> returns empty result with confidence=0.0")
        void extractGrammar_chatModelThrows_returnsEmptyResult() {
            given(chatModel.stream(any(Prompt.class)))
                    .willReturn(Flux.error(new RuntimeException("Network error")));

            ExtractionResult result = agent.extractGrammar(chunk("some content"), "N5", RagContext.empty());

            assertThat(result.getConfidence()).isEqualTo(0.0);
            assertThat(result.getGrammarPoints()).isEmpty();
            assertThat(result.getVocabulary()).isEmpty();
            assertThat(result.getLessons()).isEmpty();
            assertThat(result.getTokensUsed()).isZero();
        }
    }

    // =========================================================================
    // reExtractWithFeedback()
    // =========================================================================

    @Nested
    @DisplayName("reExtractWithFeedback()")
    class ReExtractWithFeedbackTests {

        @Test
        @DisplayName("Happy path -> grammarPoints parsed correctly from re-extraction response")
        void reExtractWithFeedback_validResponse_parsesGrammarSuccessfully() {
            stubModelResponse(singleGrammarJson("～ている", "Ongoing action or resulting state"));

            ExtractionResult result = agent.reExtractWithFeedback(
                    chunk(4, "～ている grammar explanation"),
                    "N5",
                    RagContext.empty(),
                    "Previous extraction was missing the category field for all grammar points.");

            assertThat(result.getGrammarPoints()).hasSize(1);
            assertThat(result.getGrammarPoints().get(0).getPattern()).isEqualTo("～ている");
            assertThat(result.getGrammarPoints().get(0).getMeaningEn())
                    .isEqualTo("Ongoing action or resulting state");
            assertThat(result.getVocabulary()).isEmpty();
            assertThat(result.getLessons()).isEmpty();
            assertThat(result.getChunkIndex()).isEqualTo(4);
        }

        @Test
        @DisplayName("ChatModel throws exception during re-extraction -> returns empty result")
        void reExtractWithFeedback_chatModelThrows_returnsEmptyResult() {
            given(chatModel.stream(any(Prompt.class)))
                    .willReturn(Flux.error(new RuntimeException("Rate limit exceeded")));

            ExtractionResult result = agent.reExtractWithFeedback(
                    chunk(1, "some grammar content"),
                    "N5",
                    RagContext.empty(),
                    "Category field was null for all items.");

            assertThat(result.getConfidence()).isEqualTo(0.0);
            assertThat(result.getGrammarPoints()).isEmpty();
            assertThat(result.getVocabulary()).isEmpty();
            assertThat(result.getLessons()).isEmpty();
            assertThat(result.getTokensUsed()).isZero();
        }
    }
}
