package com.fu.curriculumimport.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for ContentExtractionAgent.
 *
 * Coverage:
 *  extractContent():
 *   - Happy path: vocabulary, grammar, lessons all parsed from a full JSON response
 *   - JSON wrapped in ```json``` code fences -> cleaned and parsed correctly
 *   - Missing vocabulary field -> vocabulary list is empty
 *   - Missing confidence field -> defaults to 0.5
 *   - Malformed JSON -> empty result (confidence=0.0, all lists empty)
 *   - ChatModel throws RuntimeException -> empty result (confidence=0.0)
 *   - chunkIndex is sourced from the DocumentChunk parameter
 *   - tokensUsed equals response.length / 4
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ContentExtractionAgent")
class ContentExtractionAgentTest {

    @Mock
    private OpenAiChatModel chatModel;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    private ContentExtractionAgent agent;

    @BeforeEach
    void setUp() {
        agent = new ContentExtractionAgent(chatModel, objectMapper);
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

        given(chatModel.call(any(Prompt.class))).willReturn(chatResp);
    }

    private DocumentChunk chunk(int chunkIndex, String content) {
        return DocumentChunk.builder()
                .chunkIndex(chunkIndex)
                .content(content)
                .pageStart(1)
                .pageEnd(3)
                .build();
    }

    private DocumentChunk chunk(String content) {
        return chunk(0, content);
    }

    // =========================================================================
    // extractContent()
    // =========================================================================

    @Nested
    @DisplayName("extractContent()")
    class ExtractContentTests {

        @Test
        @DisplayName("Happy path: full JSON with vocab, grammar, lesson -> all fields parsed correctly")
        void extractContent_validJson_parsesAllFields() {
            String json = """
                    {
                      "contentType": "MIXED",
                      "confidence": 0.8,
                      "lessons": [
                        {
                          "title": "Lesson 1",
                          "titleJapanese": "第一課",
                          "lessonType": "MIXED",
                          "pageStart": 1,
                          "pageEnd": 3,
                          "orderIndex": 0
                        }
                      ],
                      "vocabulary": [
                        {
                          "japaneseText": "食べる",
                          "reading": "たべる",
                          "romaji": "taberu",
                          "meaningVi": "ăn",
                          "meaningEn": "to eat",
                          "partOfSpeech": "verb",
                          "jlptLevel": "N5"
                        }
                      ],
                      "grammarPoints": [
                        {
                          "pattern": "～ます",
                          "meaningVi": "Thể lịch sự",
                          "meaningEn": "Polite form",
                          "jlptLevel": "N5",
                          "category": "VERB_FORM"
                        }
                      ]
                    }
                    """;
            stubModelResponse(json);

            ExtractionResult result = agent.extractContent(chunk("食べる N5"), "N5");

            assertThat(result.getConfidence()).isEqualTo(0.8);

            assertThat(result.getVocabulary()).hasSize(1);
            assertThat(result.getVocabulary().get(0).getJapaneseText()).isEqualTo("食べる");
            assertThat(result.getVocabulary().get(0).getReading()).isEqualTo("たべる");
            assertThat(result.getVocabulary().get(0).getMeaningEn()).isEqualTo("to eat");

            assertThat(result.getGrammarPoints()).hasSize(1);
            assertThat(result.getGrammarPoints().get(0).getPattern()).isEqualTo("～ます");
            assertThat(result.getGrammarPoints().get(0).getCategory()).isEqualTo("VERB_FORM");

            assertThat(result.getLessons()).hasSize(1);
            assertThat(result.getLessons().get(0).getTitle()).isEqualTo("Lesson 1");
        }

        @Test
        @DisplayName("JSON wrapped in ```json``` code fences -> cleaned and parsed correctly")
        void extractContent_markdownFenceWrapped_stripsAndParsesCorrectly() {
            String json = "```json\n"
                    + "{\"confidence\":0.75,"
                    + "\"vocabulary\":[{\"japaneseText\":\"行く\",\"reading\":\"いく\",\"romaji\":\"iku\","
                    + "\"meaningVi\":\"đi\",\"meaningEn\":\"to go\",\"partOfSpeech\":\"verb\",\"jlptLevel\":\"N5\"}],"
                    + "\"grammarPoints\":[],"
                    + "\"lessons\":[]}\n"
                    + "```";
            stubModelResponse(json);

            ExtractionResult result = agent.extractContent(chunk("行く N5"), "N5");

            assertThat(result.getConfidence()).isEqualTo(0.75);
            assertThat(result.getVocabulary()).hasSize(1);
            assertThat(result.getVocabulary().get(0).getJapaneseText()).isEqualTo("行く");
            assertThat(result.getGrammarPoints()).isEmpty();
            assertThat(result.getLessons()).isEmpty();
        }

        @Test
        @DisplayName("Missing vocabulary field in JSON -> vocabulary list is empty")
        void extractContent_missingVocabularyField_returnsEmptyVocabularyList() {
            String json = """
                    {
                      "confidence": 0.7,
                      "grammarPoints": [
                        {
                          "pattern": "～ている",
                          "meaningVi": "Đang làm",
                          "meaningEn": "Ongoing action",
                          "jlptLevel": "N5",
                          "category": "VERB_FORM"
                        }
                      ],
                      "lessons": []
                    }
                    """;
            stubModelResponse(json);

            ExtractionResult result = agent.extractContent(chunk("grammar only"), "N5");

            assertThat(result.getVocabulary()).isEmpty();
            assertThat(result.getGrammarPoints()).hasSize(1);
        }

        @Test
        @DisplayName("Missing confidence field in JSON -> defaults to 0.5")
        void extractContent_missingConfidenceField_defaultsToHalf() {
            String json = """
                    {
                      "vocabulary": [],
                      "grammarPoints": [],
                      "lessons": []
                    }
                    """;
            stubModelResponse(json);

            ExtractionResult result = agent.extractContent(chunk("some content"), "N5");

            assertThat(result.getConfidence()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("Malformed JSON -> returns empty result with confidence=0.0 and all lists empty")
        void extractContent_malformedJson_returnsEmptyResult() {
            stubModelResponse("this is { definitely not valid } json ][");

            ExtractionResult result = agent.extractContent(chunk("some content"), "N5");

            assertThat(result.getConfidence()).isEqualTo(0.0);
            assertThat(result.getVocabulary()).isEmpty();
            assertThat(result.getGrammarPoints()).isEmpty();
            assertThat(result.getLessons()).isEmpty();
            assertThat(result.getTokensUsed()).isZero();
        }

        @Test
        @DisplayName("ChatModel throws RuntimeException -> returns empty result with confidence=0.0")
        void extractContent_chatModelThrows_returnsEmptyResult() {
            given(chatModel.call(any(Prompt.class)))
                    .willThrow(new RuntimeException("OpenAI API unavailable"));

            ExtractionResult result = agent.extractContent(chunk("some content"), "N5");

            assertThat(result.getConfidence()).isEqualTo(0.0);
            assertThat(result.getVocabulary()).isEmpty();
            assertThat(result.getGrammarPoints()).isEmpty();
            assertThat(result.getLessons()).isEmpty();
            assertThat(result.getTokensUsed()).isZero();
        }

        @Test
        @DisplayName("chunkIndex in result is set from the DocumentChunk parameter")
        void extractContent_chunkIndex_setFromDocumentChunk() {
            int expectedChunkIndex = 7;
            String json = """
                    {
                      "confidence": 0.9,
                      "vocabulary": [],
                      "grammarPoints": [],
                      "lessons": []
                    }
                    """;
            stubModelResponse(json);

            DocumentChunk chunkWithIndex = chunk(expectedChunkIndex, "content for chunk 7");

            ExtractionResult result = agent.extractContent(chunkWithIndex, "N4");

            assertThat(result.getChunkIndex()).isEqualTo(expectedChunkIndex);
        }

        @Test
        @DisplayName("tokensUsed equals response.length / 4")
        void extractContent_tokensUsed_isApproximatelyResponseLengthDivFour() {
            // Use a compact JSON string so we can calculate an exact expected token count
            String json = "{\"confidence\":0.85,\"vocabulary\":[],\"grammarPoints\":[],\"lessons\":[]}";
            stubModelResponse(json);

            ExtractionResult result = agent.extractContent(chunk("some textbook content"), "N5");

            int expectedTokens = json.length() / 4;
            assertThat(result.getTokensUsed()).isEqualTo(expectedTokens);
        }
    }
}
