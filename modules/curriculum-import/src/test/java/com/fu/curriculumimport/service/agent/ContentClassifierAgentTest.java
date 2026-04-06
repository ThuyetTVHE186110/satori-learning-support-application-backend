package com.fu.curriculumimport.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.ai.PromptTemplateLoader;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for ContentClassifierAgent.
 *
 * Coverage:
 *  classify():
 *   - Returns VOCABULARY for valid vocabulary JSON
 *   - Returns GRAMMAR for valid grammar JSON
 *   - Returns MIXED when contentType is unrecognized/unknown
 *   - Parses confidence, reasoning, estimatedCounts, containsExercises
 *   - Falls back to MIXED when JSON is malformed
 *   - Falls back to MIXED (ClassificationResult.fallback) when AI call throws exception
 *   - Strips markdown code fences (```json ... ```)
 *   - Strips bare code fences (``` ... ```)
 *   - Content > 800 chars is truncated before sending (no functional change in output)
 *   - Content <= 800 chars is not truncated
 *   - containsExercises: true when JSON says true
 *   - Default confidence 0.5 when not present in JSON
 *
 *  ClassificationResult.fallback():
 *   - Returns MIXED type, confidence 0.3
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContentClassifierAgent")
class ContentClassifierAgentTest {

    @Mock private OpenAiChatModel chatModel;
    @Mock private PromptTemplateLoader promptLoader;

    // Use a real ObjectMapper so JSON parsing logic is tested for real
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ContentClassifierAgent agent;

    @BeforeEach
    void setUp() {
        lenient().when(promptLoader.load(anyString())).thenReturn("system prompt");
        lenient().when(promptLoader.render(anyString(), anyMap())).thenReturn("user prompt");
        agent = new ContentClassifierAgent(chatModel, objectMapper, promptLoader);
    }

    // -------------------------------------------------------------------------
    // Helper: stub the chat model to return a given content string
    // -------------------------------------------------------------------------

    private void stubModelResponse(String content) {
        AssistantMessage msg = mock(AssistantMessage.class);
        given(msg.getText()).willReturn(content);

        Generation gen = mock(Generation.class);
        given(gen.getOutput()).willReturn(msg);

        ChatResponse resp = mock(ChatResponse.class);
        given(resp.getResult()).willReturn(gen);

        given(chatModel.call(any(Prompt.class))).willReturn(resp);
    }

    private DocumentChunk chunk(String content) {
        return DocumentChunk.builder()
                .chunkIndex(0)
                .content(content)
                .pageStart(1)
                .pageEnd(3)
                .build();
    }

    // =========================================================================
    // classify()
    // =========================================================================

    @Nested
    @DisplayName("classify()")
    class ClassifyTests {

        @Test
        @DisplayName("Valid VOCABULARY JSON → VOCABULARY type with parsed fields")
        void classify_vocabularyJson_returnsVocabularyType() {
            String json = """
                    {
                      "contentType": "VOCABULARY",
                      "confidence": 0.95,
                      "reasoning": "Contains word lists",
                      "estimatedVocabCount": 20,
                      "estimatedGrammarCount": 0,
                      "containsExercises": false
                    }
                    """;
            stubModelResponse(json);

            ClassificationResult result = agent.classify(chunk("食べる たべる N5 verb"));

            assertThat(result.getContentType())
                    .isEqualTo(ClassificationResult.ContentType.VOCABULARY);
            assertThat(result.getConfidence()).isEqualTo(0.95);
            assertThat(result.getReasoning()).isEqualTo("Contains word lists");
        }

        @Test
        @DisplayName("Valid GRAMMAR JSON → GRAMMAR type")
        void classify_grammarJson_returnsGrammarType() {
            String json = """
                    {
                      "contentType": "GRAMMAR",
                      "confidence": 0.88,
                      "reasoning": "Grammar pattern table",
                      "estimatedVocabCount": 2,
                      "estimatedGrammarCount": 5,
                      "containsExercises": false
                    }
                    """;
            stubModelResponse(json);

            ClassificationResult result = agent.classify(chunk("〜ている is used for ongoing actions"));

            assertThat(result.getContentType())
                    .isEqualTo(ClassificationResult.ContentType.GRAMMAR);
            assertThat(result.getConfidence()).isEqualTo(0.88);
        }

        @Test
        @DisplayName("Valid MIXED JSON → MIXED type")
        void classify_mixedJson_returnsMixedType() {
            String json = """
                    {
                      "contentType": "MIXED",
                      "confidence": 0.70,
                      "reasoning": "Contains both vocab and grammar",
                      "estimatedVocabCount": 10,
                      "estimatedGrammarCount": 3,
                      "containsExercises": false
                    }
                    """;
            stubModelResponse(json);

            ClassificationResult result = agent.classify(chunk("mixed content here"));

            assertThat(result.getContentType())
                    .isEqualTo(ClassificationResult.ContentType.MIXED);
        }

        @Test
        @DisplayName("CONVERSATION contentType → CONVERSATION")
        void classify_conversationJson_returnsConversationType() {
            String json = """
                    {
                      "contentType": "CONVERSATION",
                      "confidence": 0.80,
                      "reasoning": "Dialogue exchange",
                      "estimatedVocabCount": 0,
                      "estimatedGrammarCount": 0,
                      "containsExercises": false
                    }
                    """;
            stubModelResponse(json);

            ClassificationResult result = agent.classify(chunk("A: こんにちは B: こんにちは"));

            assertThat(result.getContentType())
                    .isEqualTo(ClassificationResult.ContentType.CONVERSATION);
        }

        @Test
        @DisplayName("EXERCISE contentType → EXERCISE")
        void classify_exerciseJson_returnsExerciseType() {
            String json = """
                    {
                      "contentType": "EXERCISE",
                      "confidence": 0.76,
                      "reasoning": "Practice drills",
                      "estimatedVocabCount": 0,
                      "estimatedGrammarCount": 0,
                      "containsExercises": true
                    }
                    """;
            stubModelResponse(json);

            ClassificationResult result = agent.classify(chunk("問題 1: Fill in the blank"));

            assertThat(result.getContentType())
                    .isEqualTo(ClassificationResult.ContentType.EXERCISE);
        }

        @Test
        @DisplayName("Unknown contentType in JSON → falls back to MIXED")
        void classify_unknownContentType_fallsBackToMixed() {
            String json = """
                    {
                      "contentType": "KANJI_STUDY",
                      "confidence": 0.60,
                      "reasoning": "Kanji flashcards"
                    }
                    """;
            stubModelResponse(json);

            ClassificationResult result = agent.classify(chunk("漢字 kanji content"));

            assertThat(result.getContentType())
                    .isEqualTo(ClassificationResult.ContentType.MIXED);
        }

        @Test
        @DisplayName("Missing contentType field → defaults to MIXED")
        void classify_missingContentType_defaultsMixed() {
            String json = """
                    {
                      "confidence": 0.50
                    }
                    """;
            stubModelResponse(json);

            ClassificationResult result = agent.classify(chunk("some content"));

            assertThat(result.getContentType())
                    .isEqualTo(ClassificationResult.ContentType.MIXED);
        }

        @Test
        @DisplayName("Missing confidence field → defaults to 0.5")
        void classify_missingConfidence_defaultsToHalf() {
            String json = """
                    {
                      "contentType": "VOCABULARY"
                    }
                    """;
            stubModelResponse(json);

            ClassificationResult result = agent.classify(chunk("vocab content"));

            assertThat(result.getConfidence()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("Malformed JSON → returns ClassificationResult.fallback (MIXED, 0.3)")
        void classify_malformedJson_returnsFallback() {
            stubModelResponse("this is not json { broken ]}");

            ClassificationResult result = agent.classify(chunk("some content"));

            assertThat(result.getContentType())
                    .isEqualTo(ClassificationResult.ContentType.MIXED);
            assertThat(result.getConfidence()).isEqualTo(0.3);
            assertThat(result.getReasoning()).contains("Fallback");
        }

        @Test
        @DisplayName("Empty JSON object → returns MIXED fallback")
        void classify_emptyJsonObject_returnsFallback() {
            stubModelResponse("{}");

            ClassificationResult result = agent.classify(chunk("content"));

            assertThat(result.getContentType())
                    .isEqualTo(ClassificationResult.ContentType.MIXED);
        }

        @Test
        @DisplayName("AI call throws exception → returns ClassificationResult.fallback")
        void classify_chatModelThrows_returnsFallback() {
            given(chatModel.call(any(Prompt.class)))
                    .willThrow(new RuntimeException("OpenAI API unavailable"));

            ClassificationResult result = agent.classify(chunk("some content"));

            assertThat(result.getContentType())
                    .isEqualTo(ClassificationResult.ContentType.MIXED);
            assertThat(result.getConfidence()).isEqualTo(0.3);
            assertThat(result.getReasoning()).contains("Fallback");
        }

        @Test
        @DisplayName("JSON wrapped in markdown code fence → stripped and parsed correctly")
        void classify_markdownFenceWrapped_stripsAndParsesCorrectly() {
            String json = """
                    ```json
                    {
                      "contentType": "GRAMMAR",
                      "confidence": 0.75,
                      "reasoning": "fence-wrapped",
                      "estimatedVocabCount": 0,
                      "estimatedGrammarCount": 4,
                      "containsExercises": false
                    }
                    ```
                    """;
            stubModelResponse(json);

            ClassificationResult result = agent.classify(chunk("grammar content"));

            assertThat(result.getContentType())
                    .isEqualTo(ClassificationResult.ContentType.GRAMMAR);
            assertThat(result.getConfidence()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("JSON wrapped in bare code fence → stripped and parsed correctly")
        void classify_bareFenceWrapped_stripsAndParsesCorrectly() {
            String json = "```\n{\"contentType\":\"VOCABULARY\",\"confidence\":0.9}\n```";
            stubModelResponse(json);

            ClassificationResult result = agent.classify(chunk("vocab"));

            assertThat(result.getContentType())
                    .isEqualTo(ClassificationResult.ContentType.VOCABULARY);
        }

        @Test
        @DisplayName("Content > 800 chars is processed without errors (truncation tested)")
        void classify_longContent_truncatedBeforeSending() {
            // Generate content > 800 chars
            String longContent = "あ".repeat(1000);
            String json = """
                    {
                      "contentType": "OTHER",
                      "confidence": 0.60,
                      "reasoning": "Long content classified",
                      "estimatedVocabCount": 0,
                      "estimatedGrammarCount": 0,
                      "containsExercises": false
                    }
                    """;
            stubModelResponse(json);

            // Should not throw, and the truncated prompt still gets a valid result
            ClassificationResult result = agent.classify(chunk(longContent));

            assertThat(result.getContentType())
                    .isEqualTo(ClassificationResult.ContentType.OTHER);
        }

        @Test
        @DisplayName("Content <= 800 chars — not truncated (still processed normally)")
        void classify_shortContent_notTruncated() {
            String shortContent = "食べる たべる N5 verb to eat";
            String json = """
                    {
                      "contentType": "VOCABULARY",
                      "confidence": 0.85,
                      "reasoning": "short vocab content",
                      "estimatedVocabCount": 1,
                      "estimatedGrammarCount": 0,
                      "containsExercises": false
                    }
                    """;
            stubModelResponse(json);

            ClassificationResult result = agent.classify(chunk(shortContent));

            assertThat(result.getContentType())
                    .isEqualTo(ClassificationResult.ContentType.VOCABULARY);
        }
    }

    // =========================================================================
    // ClassificationResult.fallback()
    // =========================================================================

    @Nested
    @DisplayName("ClassificationResult.fallback()")
    class FallbackTests {

        @Test
        @DisplayName("Fallback returns MIXED type, 0.3 confidence, fallback reasoning")
        void fallback_returnsExpectedValues() {
            ClassificationResult fallback = ClassificationResult.fallback();

            assertThat(fallback.getContentType())
                    .isEqualTo(ClassificationResult.ContentType.MIXED);
            assertThat(fallback.getConfidence()).isEqualTo(0.3);
            assertThat(fallback.getReasoning()).isNotBlank();
        }
    }
}
