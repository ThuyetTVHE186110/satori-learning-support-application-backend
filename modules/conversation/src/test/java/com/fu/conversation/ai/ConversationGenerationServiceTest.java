package com.fu.conversation.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.conversation.dto.response.GeneratedThemeSuggestionResponse;
import com.fu.course.entity.Course;
import com.fu.course.entity.GrammarPoint;
import com.fu.course.entity.Vocabulary;
import com.fu.course.repository.CourseRepository;
import com.fu.course.repository.GrammarPointRepository;
import com.fu.course.repository.VocabularyRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationGenerationService Tests")
class ConversationGenerationServiceTest {

    @Mock private OpenAiChatModel chatModel;
    @Mock private VocabularyRepository vocabularyRepository;
    @Mock private GrammarPointRepository grammarPointRepository;
    @Mock private CourseRepository courseRepository;
    @Mock private ChatClient chatClient;
    @Spy  private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ConversationGenerationService generationService;

    private UUID courseId;
    private Course course;
    private List<Vocabulary> vocabularyList;
    private List<GrammarPoint> grammarList;

    // ── valid JSON responses ─────────────────────────────────────────────────
    private static final String VALID_THEMES_JSON = """
            [
              {
                "title": "Daily Shopping",
                "titleJapanese": "日常のショッピング",
                "description": "Practice shopping vocabulary",
                "category": "shopping",
                "vocabularyHints": ["買い物", "値段"],
                "grammarPoints": ["〜てください"],
                "usefulExpressions": ["いくらですか"],
                "scenarioSuggestions": ["At the supermarket"]
              }
            ]
            """;

    private static final String VALID_CONTEXT_JSON = """
            {
              "vocabularyHints": ["ショッピング"],
              "grammarPoints": ["〜てください"],
              "usefulExpressions": ["いくらですか"],
              "practiceQuestions": ["何が欲しいですか"]
            }
            """;

    @BeforeEach
    void setUp() {
        // Inject the mock chatClient into the service (field set in constructor)
        ReflectionTestUtils.setField(generationService, "chatClient", chatClient);

        courseId = UUID.randomUUID();

        course = Course.builder().name("N5 Beginner").build();
        ReflectionTestUtils.setField(course, "id", courseId);

        Vocabulary vocab = Vocabulary.builder()
                .japaneseText("買い物")
                .meaningVi("Mua sắm")
                .partOfSpeech("noun")
                .jlptLevel(Course.JlptLevel.N5)
                .build();

        GrammarPoint grammar = GrammarPoint.builder()
                .pattern("〜てください")
                .meaningVi("Hãy làm...")
                .shortExplanation("Polite request form")
                .jlptLevel(Course.JlptLevel.N5)
                .build();

        vocabularyList = List.of(vocab);
        grammarList    = List.of(grammar);
    }

    // ── helper: stub chatClient call chain ──────────────────────────────────
    private void stubChatResponse(String returnedContent) {
        ChatClient.ChatClientRequestSpec promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(returnedContent);
    }

    private ArgumentCaptor<String> stubChatResponseAndCaptureUserPrompt(String returnedContent) {
        ChatClient.ChatClientRequestSpec promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);

        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(userCaptor.capture())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(returnedContent);

        return userCaptor;
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("generateThemes")
    class GenerateThemesTests {

        @Test
        @DisplayName("Should return parsed theme list when AI call succeeds")
        void shouldReturnParsedThemesOnSuccessfulAiCall() {
            // Given
            stubChatResponse(VALID_THEMES_JSON);

            // When
            List<GeneratedThemeSuggestionResponse> result =
                    generationService.generateThemes(Course.JlptLevel.N5, "shopping", null, 1);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("Daily Shopping");
            assertThat(result.get(0).getTitleJapanese()).isEqualTo("日常のショッピング");
            assertThat(result.get(0).getCategory()).isEqualTo("shopping");
            assertThat(result.get(0).getVocabularyHints()).contains("買い物", "値段");
        }

        @Test
        @DisplayName("Should strip markdown code fences from AI response before parsing")
        void shouldStripMarkdownCodeFences() {
            String wrappedJson = "```json\n" + VALID_THEMES_JSON + "\n```";
            stubChatResponse(wrappedJson);

            List<GeneratedThemeSuggestionResponse> result =
                    generationService.generateThemes(null, null, null, 1);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("Daily Shopping");
        }

        @Test
        @DisplayName("Should return empty list when AI throws an exception")
        void shouldReturnEmptyListOnAiException() {
            ChatClient.ChatClientRequestSpec promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

            when(chatClient.prompt()).thenReturn(promptSpec);
            when(promptSpec.system(anyString())).thenReturn(promptSpec);
            when(promptSpec.user(anyString())).thenReturn(promptSpec);
            when(promptSpec.call()).thenReturn(callSpec);
            when(callSpec.content()).thenThrow(new RuntimeException("OpenAI API error"));

            List<GeneratedThemeSuggestionResponse> result =
                    generationService.generateThemes(null, null, null, 3);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return empty list when AI returns invalid JSON")
        void shouldReturnEmptyListOnInvalidJson() {
            stubChatResponse("not valid json at all {{{");

            List<GeneratedThemeSuggestionResponse> result =
                    generationService.generateThemes(null, null, null, 1);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should include JLPT level in prompt when provided")
        void shouldIncludeJlptLevelInPromptWhenProvided() {
            ArgumentCaptor<String> userCaptor = stubChatResponseAndCaptureUserPrompt(VALID_THEMES_JSON);

            generationService.generateThemes(Course.JlptLevel.N5, null, null, 1);

            assertThat(userCaptor.getValue()).contains("N5");
        }

        @Test
        @DisplayName("Should include category in prompt when provided")
        void shouldIncludeCategoryInPromptWhenProvided() {
            ArgumentCaptor<String> userCaptor = stubChatResponseAndCaptureUserPrompt(VALID_THEMES_JSON);

            generationService.generateThemes(null, "restaurant", null, 1);

            assertThat(userCaptor.getValue()).contains("restaurant");
        }

        @Test
        @DisplayName("Should include hint in prompt when provided")
        void shouldIncludeHintInPromptWhenProvided() {
            ArgumentCaptor<String> userCaptor = stubChatResponseAndCaptureUserPrompt(VALID_THEMES_JSON);

            generationService.generateThemes(null, null, "focus on polite speech", 2);

            assertThat(userCaptor.getValue()).contains("focus on polite speech");
        }

        @Test
        @DisplayName("Should include count in prompt text")
        void shouldIncludeCountInPromptText() {
            ArgumentCaptor<String> userCaptor = stubChatResponseAndCaptureUserPrompt(VALID_THEMES_JSON);

            generationService.generateThemes(null, null, null, 3);

            assertThat(userCaptor.getValue()).contains("3");
        }

        @Test
        @DisplayName("Should omit optional fields from prompt when all are null")
        void shouldOmitOptionalFieldsWhenNull() {
            ArgumentCaptor<String> userCaptor = stubChatResponseAndCaptureUserPrompt(VALID_THEMES_JSON);

            generationService.generateThemes(null, null, null, 1);

            // No JLPT or category lines in prompt
            assertThat(userCaptor.getValue()).doesNotContain("Target JLPT Level");
            assertThat(userCaptor.getValue()).doesNotContain("Preferred category");
            assertThat(userCaptor.getValue()).doesNotContain("Additional guidance");
        }

        @Test
        @DisplayName("Should return empty list when AI returns null content")
        void shouldReturnEmptyListWhenAiReturnsNull() {
            stubChatResponse(null);

            List<GeneratedThemeSuggestionResponse> result =
                    generationService.generateThemes(null, null, null, 1);

            assertThat(result).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("generateUserTopicContext")
    class GenerateUserTopicContextTests {

        @Test
        @DisplayName("Should return AI-generated JSON context for a scenario with course")
        void shouldReturnContextWithCourse() {
            // Given
            String scenario = "Ordering food at a restaurant";
            when(vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(vocabularyList));
            when(grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, 10)))
                    .thenReturn(new PageImpl<>(grammarList));
            stubChatResponse(VALID_CONTEXT_JSON);

            // When
            String result = generationService.generateUserTopicContext(scenario, courseId);

            // Then
            assertThat(result).isNotNull().contains("vocabularyHints");
            verify(vocabularyRepository).findByCourseId(courseId, PageRequest.of(0, 20));
            verify(grammarPointRepository).findByCourseId(courseId, PageRequest.of(0, 10));
        }

        @Test
        @DisplayName("Should skip course lookup when courseId is null")
        void shouldSkipCourseLookupWhenCourseIdNull() {
            String scenario = "Talking with a friend";
            stubChatResponse(VALID_CONTEXT_JSON);

            String result = generationService.generateUserTopicContext(scenario, null);

            assertThat(result).isNotNull();
            verify(vocabularyRepository, never()).findByCourseId(any(), any());
            verify(grammarPointRepository, never()).findByCourseId(any(), any());
        }

        @Test
        @DisplayName("Should strip markdown code fences from context response")
        void shouldStripMarkdownCodeFencesFromContext() {
            String scenario = "Shopping at a mall";
            String wrappedJson = "```json\n" + VALID_CONTEXT_JSON + "\n```";
            stubChatResponse(wrappedJson);

            String result = generationService.generateUserTopicContext(scenario, null);

            assertThatCode(() -> new ObjectMapper().readTree(result))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should return '{}' fallback when AI throws exception")
        void shouldReturnEmptyJsonOnAiException() {
            String scenario = "Meeting a new friend";
            ChatClient.ChatClientRequestSpec promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

            when(chatClient.prompt()).thenReturn(promptSpec);
            when(promptSpec.system(anyString())).thenReturn(promptSpec);
            when(promptSpec.user(anyString())).thenReturn(promptSpec);
            when(promptSpec.call()).thenReturn(callSpec);
            when(callSpec.content()).thenThrow(new RuntimeException("API quota exceeded"));

            String result = generationService.generateUserTopicContext(scenario, null);

            assertThat(result).isEqualTo("{}");
        }

        @Test
        @DisplayName("Should return '{}' fallback when AI returns invalid JSON")
        void shouldReturnEmptyJsonOnInvalidJsonResponse() {
            String scenario = "Job interview practice";
            stubChatResponse("{invalid json{{");

            String result = generationService.generateUserTopicContext(scenario, null);

            assertThat(result).isEqualTo("{}");
        }

        @Test
        @DisplayName("Should include scenario text in prompt sent to AI")
        void shouldIncludeScenarioInUserPrompt() {
            String scenario = "Asking for directions in Tokyo";
            ArgumentCaptor<String> userPromptCaptor = stubChatResponseAndCaptureUserPrompt(VALID_CONTEXT_JSON);

            generationService.generateUserTopicContext(scenario, null);

            assertThat(userPromptCaptor.getValue()).contains(scenario);
        }

        @Test
        @DisplayName("Should still call AI when courseId is present but vocab and grammar lists are empty")
        void shouldCallAiWhenCourseIdPresentButContentEmpty() {
            String scenario = "Visiting a shrine";
            when(vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));
            when(grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, 10)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));
            stubChatResponse(VALID_CONTEXT_JSON);

            String result = generationService.generateUserTopicContext(scenario, courseId);

            assertThat(result).isNotNull().contains("vocabularyHints");
        }

        @Test
        @DisplayName("Should include vocabulary in user-context prompt when course has vocab")
        void shouldIncludeVocabularyInContextPromptWhenPresent() {
            String scenario = "Ordering ramen at a restaurant";
            when(vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(vocabularyList));
            when(grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, 10)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));
            ArgumentCaptor<String> userCaptor = stubChatResponseAndCaptureUserPrompt(VALID_CONTEXT_JSON);

            generationService.generateUserTopicContext(scenario, courseId);

            assertThat(userCaptor.getValue()).contains("買い物");
        }
    }
}
