package com.fu.curriculumimport.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.curriculumimport.config.AgenticRagProperties;
import com.fu.curriculumimport.service.agent.ReflectionResult.ItemReflection;
import com.fu.curriculumimport.service.agent.ReflectionResult.ReflectionVerdict;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SelfReflectionAgent — Self-RAG (Asai et al., 2023) reflection step.
 *
 * Coverage:
 *  reflect():
 *   - Empty vocab AND grammar → ReflectionResult.passed() returned, NO ChatModel call
 *   - Vocab only (no grammar) → ChatModel IS called once
 *   - Grammar only (no vocab) → ChatModel IS called once
 *   - Valid JSON response → all top-level fields parsed correctly
 *   - Verdict "REJECTED" parsed correctly
 *   - Invalid verdict string → defaults to MOSTLY_APPROVED
 *   - recommendsReExtraction=true in JSON → needsReExtraction=true
 *   - itemReflections array in response → per-item fields parsed correctly
 *   - Malformed JSON response → fallback result (MOSTLY_APPROVED, 0.7, needsReExtraction=false)
 *   - ChatModel throws RuntimeException → fallback result, no exception propagated
 *   - Response with ```json fences → cleaned and parsed successfully
 *   - Content length > selfReflectionMaxSourceChars → truncated, call still succeeds
 *   - recommendsReExtraction absent from JSON → defaults to false
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SelfReflectionAgent")
class SelfReflectionAgentTest {

    @Mock
    private OpenAiChatModel chatModel;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AgenticRagProperties ragProperties;

    @InjectMocks
    private SelfReflectionAgent agent;

    // =========================================================================
    // Helpers
    // =========================================================================

    private DocumentChunk chunk(String content) {
        return DocumentChunk.builder()
                .chunkIndex(0)
                .content(content)
                .pageStart(1)
                .pageEnd(2)
                .chunkType("TEXT")
                .estimatedTokenCount(100)
                .build();
    }

    private ExtractionResult.ExtractedVocabulary vocab(String japaneseText) {
        return ExtractionResult.ExtractedVocabulary.builder()
                .japaneseText(japaneseText)
                .reading("たべる")
                .romaji("taberu")
                .meaningVi("ăn")
                .meaningEn("to eat")
                .partOfSpeech("verb")
                .jlptLevel("N5")
                .build();
    }

    private ExtractionResult.ExtractedGrammar grammar(String pattern) {
        return ExtractionResult.ExtractedGrammar.builder()
                .pattern(pattern)
                .patternReading("ています")
                .meaningVi("đang làm")
                .meaningEn("ongoing action")
                .shortExplanation("Used for progressive actions")
                .jlptLevel("N5")
                .build();
    }

    private ExtractionResult resultWith(
            List<ExtractionResult.ExtractedVocabulary> vocab,
            List<ExtractionResult.ExtractedGrammar> grammar) {
        return ExtractionResult.builder()
                .chunkIndex(0)
                .confidence(0.9)
                .tokensUsed(150)
                .vocabulary(vocab)
                .grammarPoints(grammar)
                .lessons(List.of())
                .build();
    }

    /**
     * Configure chatModel mock to return the given JSON string when called with any Prompt.
     */
    private void mockChatModelResponse(String jsonContent) {
        ChatResponse chatResponse = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage msg = mock(AssistantMessage.class);
        given(msg.getContent()).willReturn(jsonContent);
        given(generation.getOutput()).willReturn(msg);
        given(chatResponse.getResult()).willReturn(generation);
        given(chatModel.call(any(Prompt.class))).willReturn(chatResponse);
    }

    private static final String VALID_APPROVED_JSON = """
            {
              "overallVerdict": "APPROVED",
              "overallScore": 0.92,
              "overallReasoning": "All items are well-supported by the source",
              "recommendsReExtraction": false,
              "reExtractionSuggestion": "None needed",
              "itemReflections": []
            }
            """;

    // =========================================================================
    // Empty extraction (skip path)
    // =========================================================================

    @Nested
    @DisplayName("reflect() — empty extraction skips LLM call")
    class EmptyExtractionTests {

        @Test
        @DisplayName("Empty vocab AND grammar → ReflectionResult.passed() returned")
        void reflect_emptyVocabAndGrammar_returnsPassed() {
            ExtractionResult result = resultWith(List.of(), List.of());

            ReflectionResult reflection = agent.reflect(chunk("Some source text"), result, "N5");

            assertThat(reflection.getReflectionVerdict()).isEqualTo(ReflectionVerdict.APPROVED);
            assertThat(reflection.getReflectionScore()).isEqualTo(1.0);
            assertThat(reflection.isNeedsReExtraction()).isFalse();
        }

        @Test
        @DisplayName("Empty vocab AND grammar → NO call to ChatModel")
        void reflect_emptyVocabAndGrammar_chatModelNotCalled() {
            ExtractionResult result = resultWith(List.of(), List.of());

            agent.reflect(chunk("Some source text"), result, "N5");

            verify(chatModel, never()).call(any(Prompt.class));
        }

        @Test
        @DisplayName("Empty extraction → combinedVerdict is EXCELLENT (from passed())")
        void reflect_emptyExtraction_combinedVerdictExcellent() {
            ExtractionResult result = resultWith(List.of(), List.of());

            ReflectionResult reflection = agent.reflect(chunk("source"), result, "N5");

            assertThat(reflection.getCombinedVerdict())
                    .isEqualTo(ReflectionResult.CombinedVerdict.EXCELLENT);
        }
    }

    // =========================================================================
    // ChatModel invocation — vocab-only and grammar-only
    // =========================================================================

    @Nested
    @DisplayName("reflect() — ChatModel invocation conditions")
    class ChatModelInvocationTests {

        @BeforeEach
        void setup() {
            given(ragProperties.getSelfReflectionMaxSourceChars()).willReturn(4000);
            mockChatModelResponse(VALID_APPROVED_JSON);
        }

        @Test
        @DisplayName("Vocab only (empty grammar) → ChatModel IS called exactly once")
        void reflect_vocabOnly_chatModelCalledOnce() {
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            agent.reflect(chunk("食べる たべる to eat"), result, "N5");

            verify(chatModel, times(1)).call(any(Prompt.class));
        }

        @Test
        @DisplayName("Grammar only (empty vocab) → ChatModel IS called exactly once")
        void reflect_grammarOnly_chatModelCalledOnce() {
            ExtractionResult result = resultWith(List.of(), List.of(grammar("〜ている")));

            agent.reflect(chunk("〜ている ongoing action"), result, "N5");

            verify(chatModel, times(1)).call(any(Prompt.class));
        }

        @Test
        @DisplayName("Both vocab and grammar present → ChatModel IS called exactly once")
        void reflect_vocabAndGrammar_chatModelCalledOnce() {
            ExtractionResult result = resultWith(
                    List.of(vocab("食べる")),
                    List.of(grammar("〜ている")));

            agent.reflect(chunk("食べる 〜ている lesson"), result, "N5");

            verify(chatModel, times(1)).call(any(Prompt.class));
        }
    }

    // =========================================================================
    // JSON parsing — top-level fields
    // =========================================================================

    @Nested
    @DisplayName("reflect() — JSON response parsing")
    class JsonParsingTests {

        @BeforeEach
        void setup() {
            given(ragProperties.getSelfReflectionMaxSourceChars()).willReturn(4000);
        }

        @Test
        @DisplayName("Valid JSON → reflectionVerdict parsed as APPROVED")
        void reflect_validJson_verdictParsedCorrectly() {
            mockChatModelResponse(VALID_APPROVED_JSON);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.getReflectionVerdict()).isEqualTo(ReflectionVerdict.APPROVED);
        }

        @Test
        @DisplayName("Valid JSON → reflectionScore parsed correctly")
        void reflect_validJson_reflectionScoreParsed() {
            mockChatModelResponse(VALID_APPROVED_JSON);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.getReflectionScore()).isEqualTo(0.92);
        }

        @Test
        @DisplayName("Valid JSON → reflectionReasoning parsed correctly")
        void reflect_validJson_reflectionReasoningParsed() {
            mockChatModelResponse(VALID_APPROVED_JSON);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.getReflectionReasoning())
                    .isEqualTo("All items are well-supported by the source");
        }

        @Test
        @DisplayName("Valid JSON → llmRecommendsReExtraction parsed correctly as false")
        void reflect_validJson_llmRecommendsReExtractionFalse() {
            mockChatModelResponse(VALID_APPROVED_JSON);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.isLlmRecommendsReExtraction()).isFalse();
        }

        @Test
        @DisplayName("Valid JSON → llmReExtractionSuggestion parsed correctly")
        void reflect_validJson_llmReExtractionSuggestionParsed() {
            mockChatModelResponse(VALID_APPROVED_JSON);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.getLlmReExtractionSuggestion()).isEqualTo("None needed");
        }

        @Test
        @DisplayName("Verdict 'REJECTED' in JSON → reflectionVerdict = REJECTED")
        void reflect_rejectedVerdict_parsedCorrectly() {
            String json = """
                    {
                      "overallVerdict": "REJECTED",
                      "overallScore": 0.15,
                      "overallReasoning": "Extraction is largely incorrect",
                      "recommendsReExtraction": true,
                      "reExtractionSuggestion": "Re-extract with corrected context",
                      "itemReflections": []
                    }
                    """;
            mockChatModelResponse(json);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.getReflectionVerdict()).isEqualTo(ReflectionVerdict.REJECTED);
            assertThat(reflection.getReflectionScore()).isEqualTo(0.15);
        }

        @Test
        @DisplayName("Verdict 'NEEDS_IMPROVEMENT' in JSON → reflectionVerdict = NEEDS_IMPROVEMENT")
        void reflect_needsImprovementVerdict_parsedCorrectly() {
            String json = """
                    {
                      "overallVerdict": "NEEDS_IMPROVEMENT",
                      "overallScore": 0.55,
                      "overallReasoning": "Several items are questionable",
                      "recommendsReExtraction": false,
                      "reExtractionSuggestion": "",
                      "itemReflections": []
                    }
                    """;
            mockChatModelResponse(json);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.getReflectionVerdict())
                    .isEqualTo(ReflectionVerdict.NEEDS_IMPROVEMENT);
        }

        @Test
        @DisplayName("Invalid verdict string → defaults to MOSTLY_APPROVED")
        void reflect_invalidVerdictString_defaultsMostlyApproved() {
            String json = """
                    {
                      "overallVerdict": "COMPLETELY_WRONG_VALUE",
                      "overallScore": 0.6,
                      "overallReasoning": "Some reasoning",
                      "recommendsReExtraction": false,
                      "reExtractionSuggestion": "",
                      "itemReflections": []
                    }
                    """;
            mockChatModelResponse(json);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.getReflectionVerdict()).isEqualTo(ReflectionVerdict.MOSTLY_APPROVED);
        }

        @Test
        @DisplayName("recommendsReExtraction=true in JSON → needsReExtraction=true")
        void reflect_recommendsReExtractionTrue_needsReExtractionTrue() {
            String json = """
                    {
                      "overallVerdict": "NEEDS_IMPROVEMENT",
                      "overallScore": 0.45,
                      "overallReasoning": "Issues detected",
                      "recommendsReExtraction": true,
                      "reExtractionSuggestion": "Fix the readings",
                      "itemReflections": []
                    }
                    """;
            mockChatModelResponse(json);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.isNeedsReExtraction()).isTrue();
            assertThat(reflection.isLlmRecommendsReExtraction()).isTrue();
            assertThat(reflection.getLlmReExtractionSuggestion()).isEqualTo("Fix the readings");
        }

        @Test
        @DisplayName("recommendsReExtraction absent from JSON → needsReExtraction defaults to false")
        void reflect_missingRecommendsReExtraction_defaultsFalse() {
            String json = """
                    {
                      "overallVerdict": "APPROVED",
                      "overallScore": 0.88,
                      "overallReasoning": "Good quality",
                      "reExtractionSuggestion": ""
                    }
                    """;
            mockChatModelResponse(json);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.isNeedsReExtraction()).isFalse();
            assertThat(reflection.isLlmRecommendsReExtraction()).isFalse();
        }

        @Test
        @DisplayName("Response with ```json fences → cleaned and parsed successfully")
        void reflect_jsonFencedResponse_cleanedAndParsed() {
            String fencedJson = "```json\n" +
                    "{\n" +
                    "  \"overallVerdict\": \"APPROVED\",\n" +
                    "  \"overallScore\": 0.95,\n" +
                    "  \"overallReasoning\": \"Fenced JSON parsed correctly\",\n" +
                    "  \"recommendsReExtraction\": false,\n" +
                    "  \"reExtractionSuggestion\": \"\",\n" +
                    "  \"itemReflections\": []\n" +
                    "}\n" +
                    "```";
            mockChatModelResponse(fencedJson);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.getReflectionVerdict()).isEqualTo(ReflectionVerdict.APPROVED);
            assertThat(reflection.getReflectionScore()).isEqualTo(0.95);
            assertThat(reflection.getReflectionReasoning()).isEqualTo("Fenced JSON parsed correctly");
        }

        @Test
        @DisplayName("Response with ``` (no json label) fences → cleaned and parsed successfully")
        void reflect_plainFencedResponse_cleanedAndParsed() {
            String fencedJson = "```\n" +
                    "{\n" +
                    "  \"overallVerdict\": \"MOSTLY_APPROVED\",\n" +
                    "  \"overallScore\": 0.8,\n" +
                    "  \"overallReasoning\": \"Plain fence cleaned\",\n" +
                    "  \"recommendsReExtraction\": false,\n" +
                    "  \"reExtractionSuggestion\": \"\",\n" +
                    "  \"itemReflections\": []\n" +
                    "}\n" +
                    "```";
            mockChatModelResponse(fencedJson);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.getReflectionVerdict()).isEqualTo(ReflectionVerdict.MOSTLY_APPROVED);
        }
    }

    // =========================================================================
    // itemReflections parsing
    // =========================================================================

    @Nested
    @DisplayName("reflect() — itemReflections array parsing")
    class ItemReflectionsParsingTests {

        @BeforeEach
        void setup() {
            given(ragProperties.getSelfReflectionMaxSourceChars()).willReturn(4000);
        }

        @Test
        @DisplayName("itemReflections array with one entry → item parsed with all fields")
        void reflect_singleItemReflection_allFieldsParsed() {
            String json = """
                    {
                      "overallVerdict": "MOSTLY_APPROVED",
                      "overallScore": 0.85,
                      "overallReasoning": "Mostly good quality",
                      "recommendsReExtraction": false,
                      "reExtractionSuggestion": "",
                      "itemReflections": [
                        {
                          "itemIdentifier": "食べる",
                          "contentType": "VOCABULARY",
                          "isRelevant": true,
                          "isSupported": true,
                          "isUseful": true,
                          "score": 0.95,
                          "reasoning": "Well-supported vocabulary item"
                        }
                      ]
                    }
                    """;
            mockChatModelResponse(json);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる たべる to eat"), result, "N5");

            assertThat(reflection.getItemReflections()).hasSize(1);
            ItemReflection item = reflection.getItemReflections().get(0);
            assertThat(item.getItemIdentifier()).isEqualTo("食べる");
            assertThat(item.getContentType()).isEqualTo("VOCABULARY");
            assertThat(item.isRelevant()).isTrue();
            assertThat(item.isSupported()).isTrue();
            assertThat(item.isUseful()).isTrue();
            assertThat(item.getScore()).isEqualTo(0.95);
            assertThat(item.getReasoning()).isEqualTo("Well-supported vocabulary item");
        }

        @Test
        @DisplayName("itemReflections with isRelevant=false and isSupported=false → parsed correctly")
        void reflect_itemWithNegativeFlags_parsedCorrectly() {
            String json = """
                    {
                      "overallVerdict": "NEEDS_IMPROVEMENT",
                      "overallScore": 0.4,
                      "overallReasoning": "Some items are not supported",
                      "recommendsReExtraction": false,
                      "reExtractionSuggestion": "",
                      "itemReflections": [
                        {
                          "itemIdentifier": "〜ている",
                          "contentType": "GRAMMAR",
                          "isRelevant": false,
                          "isSupported": false,
                          "isUseful": true,
                          "score": 0.3,
                          "reasoning": "Pattern not found in source text"
                        }
                      ]
                    }
                    """;
            mockChatModelResponse(json);
            ExtractionResult result = resultWith(List.of(), List.of(grammar("〜ている")));

            ReflectionResult reflection = agent.reflect(chunk("〜ている progressive form"), result, "N5");

            assertThat(reflection.getItemReflections()).hasSize(1);
            ItemReflection item = reflection.getItemReflections().get(0);
            assertThat(item.getItemIdentifier()).isEqualTo("〜ている");
            assertThat(item.getContentType()).isEqualTo("GRAMMAR");
            assertThat(item.isRelevant()).isFalse();
            assertThat(item.isSupported()).isFalse();
            assertThat(item.isUseful()).isTrue();
            assertThat(item.getScore()).isEqualTo(0.3);
            assertThat(item.getReasoning()).isEqualTo("Pattern not found in source text");
        }

        @Test
        @DisplayName("itemReflections array with multiple entries → all items parsed")
        void reflect_multipleItemReflections_allParsed() {
            String json = """
                    {
                      "overallVerdict": "MOSTLY_APPROVED",
                      "overallScore": 0.82,
                      "overallReasoning": "Two items evaluated",
                      "recommendsReExtraction": false,
                      "reExtractionSuggestion": "",
                      "itemReflections": [
                        {
                          "itemIdentifier": "食べる",
                          "contentType": "VOCABULARY",
                          "isRelevant": true,
                          "isSupported": true,
                          "isUseful": true,
                          "score": 0.9,
                          "reasoning": "Well-extracted"
                        },
                        {
                          "itemIdentifier": "〜ている",
                          "contentType": "GRAMMAR",
                          "isRelevant": true,
                          "isSupported": false,
                          "isUseful": true,
                          "score": 0.6,
                          "reasoning": "Reading uncertain"
                        }
                      ]
                    }
                    """;
            mockChatModelResponse(json);
            ExtractionResult result = resultWith(
                    List.of(vocab("食べる")),
                    List.of(grammar("〜ている")));

            ReflectionResult reflection = agent.reflect(chunk("食べる 〜ている lesson"), result, "N5");

            assertThat(reflection.getItemReflections()).hasSize(2);
            assertThat(reflection.getItemReflections())
                    .extracting(ItemReflection::getItemIdentifier)
                    .containsExactly("食べる", "〜ている");
        }

        @Test
        @DisplayName("Empty itemReflections array → itemReflections list is empty")
        void reflect_emptyItemReflectionsArray_emptyList() {
            mockChatModelResponse(VALID_APPROVED_JSON);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.getItemReflections()).isEmpty();
        }
    }

    // =========================================================================
    // Fallback behavior
    // =========================================================================

    @Nested
    @DisplayName("reflect() — fallback on error")
    class FallbackBehaviorTests {

        @BeforeEach
        void setup() {
            given(ragProperties.getSelfReflectionMaxSourceChars()).willReturn(4000);
        }

        @Test
        @DisplayName("Malformed JSON response → fallback: verdict=MOSTLY_APPROVED, score=0.7")
        void reflect_malformedJson_fallbackVerdict() {
            mockChatModelResponse("{ this is NOT valid json }{{{");
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.getReflectionVerdict()).isEqualTo(ReflectionVerdict.MOSTLY_APPROVED);
            assertThat(reflection.getReflectionScore()).isEqualTo(0.7);
        }

        @Test
        @DisplayName("Malformed JSON response → fallback: needsReExtraction=false")
        void reflect_malformedJson_fallbackNeedsReExtractionFalse() {
            mockChatModelResponse("not json at all");
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.isNeedsReExtraction()).isFalse();
        }

        @Test
        @DisplayName("Malformed JSON response → fallback: combinedScore=0.7")
        void reflect_malformedJson_fallbackCombinedScore() {
            mockChatModelResponse("{invalid}");
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.getCombinedScore()).isEqualTo(0.7);
        }

        @Test
        @DisplayName("ChatModel throws RuntimeException → fallback result returned, no exception propagated")
        void reflect_chatModelThrows_fallbackReturned() {
            given(chatModel.call(any(Prompt.class)))
                    .willThrow(new RuntimeException("OpenAI API unavailable"));
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection).isNotNull();
            assertThat(reflection.getReflectionVerdict()).isEqualTo(ReflectionVerdict.MOSTLY_APPROVED);
            assertThat(reflection.getReflectionScore()).isEqualTo(0.7);
            assertThat(reflection.isNeedsReExtraction()).isFalse();
        }

        @Test
        @DisplayName("ChatModel throws RuntimeException → exception is NOT propagated to caller")
        void reflect_chatModelThrows_noExceptionPropagated() {
            given(chatModel.call(any(Prompt.class)))
                    .willThrow(new RuntimeException("Network timeout"));
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            // Must not throw
            org.assertj.core.api.Assertions.assertThatCode(
                    () -> agent.reflect(chunk("食べる to eat"), result, "N5"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Fallback result → itemReflections is empty list")
        void reflect_fallback_itemReflectionsEmpty() {
            mockChatModelResponse("totally-not-json");
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.getItemReflections()).isNotNull();
            assertThat(reflection.getItemReflections()).isEmpty();
        }

        @Test
        @DisplayName("Fallback result → groundingScore=0.0, groundedCount=0, hallucinatedCount=0")
        void reflect_fallback_groundingFieldsAreZero() {
            given(chatModel.call(any(Prompt.class)))
                    .willThrow(new RuntimeException("Service down"));
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.getGroundingScore()).isEqualTo(0.0);
            assertThat(reflection.getGroundedCount()).isZero();
            assertThat(reflection.getHallucinatedCount()).isZero();
        }
    }

    // =========================================================================
    // Truncation behavior
    // =========================================================================

    @Nested
    @DisplayName("reflect() — content truncation")
    class TruncationTests {

        @Test
        @DisplayName("Content length > selfReflectionMaxSourceChars → call still succeeds (truncated text used)")
        void reflect_contentExceedsMaxChars_callSucceeds() {
            // Set a very small max to force truncation
            given(ragProperties.getSelfReflectionMaxSourceChars()).willReturn(10);
            mockChatModelResponse(VALID_APPROVED_JSON);

            // Build a content string much longer than the limit
            String longContent = "食べる ".repeat(500); // ~2000 chars
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk(longContent), result, "N5");

            // Verify the agent did not crash and returned a valid result
            assertThat(reflection).isNotNull();
            assertThat(reflection.getReflectionVerdict()).isEqualTo(ReflectionVerdict.APPROVED);
        }

        @Test
        @DisplayName("Content length > selfReflectionMaxSourceChars → ChatModel still called once")
        void reflect_contentExceedsMaxChars_chatModelCalledOnce() {
            given(ragProperties.getSelfReflectionMaxSourceChars()).willReturn(20);
            mockChatModelResponse(VALID_APPROVED_JSON);

            String longContent = "A".repeat(500);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            agent.reflect(chunk(longContent), result, "N5");

            verify(chatModel, times(1)).call(any(Prompt.class));
        }

        @Test
        @DisplayName("Content length <= selfReflectionMaxSourceChars → content passed through unchanged, call succeeds")
        void reflect_contentWithinLimit_callSucceeds() {
            given(ragProperties.getSelfReflectionMaxSourceChars()).willReturn(4000);
            mockChatModelResponse(VALID_APPROVED_JSON);

            String shortContent = "食べる たべる to eat N5 verb";
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk(shortContent), result, "N5");

            assertThat(reflection.getReflectionVerdict()).isEqualTo(ReflectionVerdict.APPROVED);
            verify(chatModel, times(1)).call(any(Prompt.class));
        }
    }

    // =========================================================================
    // Result structure from LLM response
    // =========================================================================

    @Nested
    @DisplayName("reflect() — result structure completeness")
    class ResultStructureTests {

        @BeforeEach
        void setup() {
            given(ragProperties.getSelfReflectionMaxSourceChars()).willReturn(4000);
        }

        @Test
        @DisplayName("Successful reflection → groundingScore initialised to 0.0 (filled by GroundingVerifier later)")
        void reflect_success_groundingScoreIsZero() {
            mockChatModelResponse(VALID_APPROVED_JSON);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.getGroundingScore()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Successful reflection → groundedCount=0 (filled by GroundingVerifier later)")
        void reflect_success_groundedCountIsZero() {
            mockChatModelResponse(VALID_APPROVED_JSON);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.getGroundedCount()).isZero();
        }

        @Test
        @DisplayName("Successful reflection → combinedVerdict=GOOD (initial placeholder before GroundingVerifier)")
        void reflect_success_combinedVerdictGood() {
            mockChatModelResponse(VALID_APPROVED_JSON);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.getCombinedVerdict())
                    .isEqualTo(ReflectionResult.CombinedVerdict.GOOD);
        }

        @Test
        @DisplayName("Successful reflection → combinedScore equals reflectionScore before grounding update")
        void reflect_success_combinedScoreEqualsReflectionScore() {
            mockChatModelResponse(VALID_APPROVED_JSON);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            // combinedScore is set to overallScore (0.92) and will be recalculated by GroundingVerifier
            assertThat(reflection.getCombinedScore()).isEqualTo(reflection.getReflectionScore());
        }

        @Test
        @DisplayName("Successful reflection → hallucinatedItems list is empty (pre-grounding)")
        void reflect_success_hallucinatedItemsEmpty() {
            mockChatModelResponse(VALID_APPROVED_JSON);
            ExtractionResult result = resultWith(List.of(vocab("食べる")), List.of());

            ReflectionResult reflection = agent.reflect(chunk("食べる to eat"), result, "N5");

            assertThat(reflection.getHallucinatedItems()).isEmpty();
        }
    }
}
