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

    // ── helper: build full ChatClient mock chain ─────────────────────────────
    private ChatClient.ChatClientRequestSpec mockChatClientChain(String returnedContent) {
        ChatClient.Builder           builder      = mock(ChatClient.Builder.class);
        ChatClient                   client       = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec promptSpec   = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec  callSpec     = mock(ChatClient.CallResponseSpec.class);

        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(returnedContent);

        return promptSpec;
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("generateThemesForCourse")
    class GenerateThemesForCourseTests {

        @Test
        @DisplayName("Should return empty list immediately when course has no vocabulary and no grammar")
        void shouldReturnEmptyWhenNoCourseContent() {
            // Given
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            when(vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, 30)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));
            when(grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // When
            List<GeneratedThemeSuggestionResponse> result =
                    generationService.generateThemesForCourse(courseId, 3);

            // Then — AI is never called
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should use 'Unknown Course' when courseId does not match any course")
        void shouldUseUnknownCourseWhenNotFound() {
            // Given
            when(courseRepository.findById(courseId)).thenReturn(Optional.empty());
            when(vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, 30)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));
            when(grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // When — empty vocab/grammar triggers early return regardless
            List<GeneratedThemeSuggestionResponse> result =
                    generationService.generateThemesForCourse(courseId, 3);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return parsed theme list when AI call succeeds")
        void shouldReturnParsedThemesOnSuccessfulAiCall() {
            // Given
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            when(vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, 30)))
                    .thenReturn(new PageImpl<>(vocabularyList));
            when(grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(grammarList));

            try (MockedStatic<ChatClient> mockedStatic = mockStatic(ChatClient.class)) {
                ChatClient.Builder builder = mock(ChatClient.Builder.class);
                ChatClient client = mock(ChatClient.class);
                ChatClient.ChatClientRequestSpec promptSpec =
                        mock(ChatClient.ChatClientRequestSpec.class);
                ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

                mockedStatic.when(() -> ChatClient.builder(chatModel)).thenReturn(builder);
                when(builder.build()).thenReturn(client);
                when(client.prompt()).thenReturn(promptSpec);
                when(promptSpec.system(anyString())).thenReturn(promptSpec);
                when(promptSpec.user(anyString())).thenReturn(promptSpec);
                when(promptSpec.call()).thenReturn(callSpec);
                when(callSpec.content()).thenReturn(VALID_THEMES_JSON);

                // When
                List<GeneratedThemeSuggestionResponse> result =
                        generationService.generateThemesForCourse(courseId, 1);

                // Then
                assertThat(result).hasSize(1);
                assertThat(result.get(0).getTitle()).isEqualTo("Daily Shopping");
                assertThat(result.get(0).getTitleJapanese()).isEqualTo("日常のショッピング");
                assertThat(result.get(0).getCategory()).isEqualTo("shopping");
                assertThat(result.get(0).getVocabularyHints()).contains("買い物", "値段");
            }
        }

        @Test
        @DisplayName("Should strip markdown code fences from AI response before parsing")
        void shouldStripMarkdownCodeFences() {
            String wrappedJson = "```json\n" + VALID_THEMES_JSON + "\n```";

            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            when(vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, 30)))
                    .thenReturn(new PageImpl<>(vocabularyList));
            when(grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(grammarList));

            try (MockedStatic<ChatClient> mockedStatic = mockStatic(ChatClient.class)) {
                ChatClient.Builder builder = mock(ChatClient.Builder.class);
                ChatClient client = mock(ChatClient.class);
                ChatClient.ChatClientRequestSpec promptSpec =
                        mock(ChatClient.ChatClientRequestSpec.class);
                ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

                mockedStatic.when(() -> ChatClient.builder(chatModel)).thenReturn(builder);
                when(builder.build()).thenReturn(client);
                when(client.prompt()).thenReturn(promptSpec);
                when(promptSpec.system(anyString())).thenReturn(promptSpec);
                when(promptSpec.user(anyString())).thenReturn(promptSpec);
                when(promptSpec.call()).thenReturn(callSpec);
                when(callSpec.content()).thenReturn(wrappedJson);

                List<GeneratedThemeSuggestionResponse> result =
                        generationService.generateThemesForCourse(courseId, 1);

                assertThat(result).hasSize(1);
                assertThat(result.get(0).getTitle()).isEqualTo("Daily Shopping");
            }
        }

        @Test
        @DisplayName("Should return empty list when AI throws an exception")
        void shouldReturnEmptyListOnAiException() {
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            when(vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, 30)))
                    .thenReturn(new PageImpl<>(vocabularyList));
            when(grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(grammarList));

            try (MockedStatic<ChatClient> mockedStatic = mockStatic(ChatClient.class)) {
                ChatClient.Builder builder = mock(ChatClient.Builder.class);
                ChatClient client = mock(ChatClient.class);
                ChatClient.ChatClientRequestSpec promptSpec =
                        mock(ChatClient.ChatClientRequestSpec.class);
                ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

                mockedStatic.when(() -> ChatClient.builder(chatModel)).thenReturn(builder);
                when(builder.build()).thenReturn(client);
                when(client.prompt()).thenReturn(promptSpec);
                when(promptSpec.system(anyString())).thenReturn(promptSpec);
                when(promptSpec.user(anyString())).thenReturn(promptSpec);
                when(promptSpec.call()).thenReturn(callSpec);
                when(callSpec.content()).thenThrow(new RuntimeException("OpenAI API error"));

                List<GeneratedThemeSuggestionResponse> result =
                        generationService.generateThemesForCourse(courseId, 3);

                // Then — graceful fallback, not rethrown
                assertThat(result).isEmpty();
            }
        }

        @Test
        @DisplayName("Should return empty list when AI returns invalid JSON")
        void shouldReturnEmptyListOnInvalidJson() {
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            when(vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, 30)))
                    .thenReturn(new PageImpl<>(vocabularyList));
            when(grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(grammarList));

            try (MockedStatic<ChatClient> mockedStatic = mockStatic(ChatClient.class)) {
                ChatClient.Builder builder = mock(ChatClient.Builder.class);
                ChatClient client = mock(ChatClient.class);
                ChatClient.ChatClientRequestSpec promptSpec =
                        mock(ChatClient.ChatClientRequestSpec.class);
                ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

                mockedStatic.when(() -> ChatClient.builder(chatModel)).thenReturn(builder);
                when(builder.build()).thenReturn(client);
                when(client.prompt()).thenReturn(promptSpec);
                when(promptSpec.system(anyString())).thenReturn(promptSpec);
                when(promptSpec.user(anyString())).thenReturn(promptSpec);
                when(promptSpec.call()).thenReturn(callSpec);
                when(callSpec.content()).thenReturn("not valid json at all {{{}");

                List<GeneratedThemeSuggestionResponse> result =
                        generationService.generateThemesForCourse(courseId, 1);

                assertThat(result).isEmpty();
            }
        }

        @Test
        @DisplayName("Should request vocabulary and grammar with correct page sizes")
        void shouldFetchVocabularyAndGrammarWithCorrectPageSizes() {
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            when(vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, 30)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));
            when(grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            generationService.generateThemesForCourse(courseId, 3);

            verify(vocabularyRepository).findByCourseId(courseId, PageRequest.of(0, 30));
            verify(grammarPointRepository).findByCourseId(courseId, PageRequest.of(0, 20));
        }

        @Test
        @DisplayName("Should call AI when vocab is non-empty but grammar list is empty")
        void shouldCallAiWhenOnlyVocabularyPresent() {
            // Branch: vocabulary.isEmpty() == false, grammar.isEmpty() == true → NOT early return
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            when(vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, 30)))
                    .thenReturn(new PageImpl<>(vocabularyList));   // non-empty
            when(grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(Collections.emptyList())); // empty

            try (MockedStatic<ChatClient> mockedStatic = mockStatic(ChatClient.class)) {
                ChatClient.Builder builder = mock(ChatClient.Builder.class);
                ChatClient client = mock(ChatClient.class);
                ChatClient.ChatClientRequestSpec promptSpec =
                        mock(ChatClient.ChatClientRequestSpec.class);
                ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

                mockedStatic.when(() -> ChatClient.builder(chatModel)).thenReturn(builder);
                when(builder.build()).thenReturn(client);
                when(client.prompt()).thenReturn(promptSpec);
                when(promptSpec.system(anyString())).thenReturn(promptSpec);
                when(promptSpec.user(anyString())).thenReturn(promptSpec);
                when(promptSpec.call()).thenReturn(callSpec);
                when(callSpec.content()).thenReturn(VALID_THEMES_JSON);

                List<GeneratedThemeSuggestionResponse> result =
                        generationService.generateThemesForCourse(courseId, 1);

                assertThat(result).isNotEmpty(); // AI was called and returned results
            }
        }

        @Test
        @DisplayName("Should call AI when grammar is non-empty but vocabulary list is empty")
        void shouldCallAiWhenOnlyGrammarPresent() {
            // Branch: vocabulary.isEmpty() == true, grammar.isEmpty() == false → NOT early return
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            when(vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, 30)))
                    .thenReturn(new PageImpl<>(Collections.emptyList())); // empty
            when(grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(grammarList));   // non-empty

            try (MockedStatic<ChatClient> mockedStatic = mockStatic(ChatClient.class)) {
                ChatClient.Builder builder = mock(ChatClient.Builder.class);
                ChatClient client = mock(ChatClient.class);
                ChatClient.ChatClientRequestSpec promptSpec =
                        mock(ChatClient.ChatClientRequestSpec.class);
                ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

                mockedStatic.when(() -> ChatClient.builder(chatModel)).thenReturn(builder);
                when(builder.build()).thenReturn(client);
                when(client.prompt()).thenReturn(promptSpec);
                when(promptSpec.system(anyString())).thenReturn(promptSpec);
                when(promptSpec.user(anyString())).thenReturn(promptSpec);
                when(promptSpec.call()).thenReturn(callSpec);
                when(callSpec.content()).thenReturn(VALID_THEMES_JSON);

                List<GeneratedThemeSuggestionResponse> result =
                        generationService.generateThemesForCourse(courseId, 1);

                assertThat(result).isNotEmpty();
            }
        }

        @Test
        @DisplayName("Should fall back to meaningEn in prompt when vocabulary meaningVi is null")
        void shouldFallbackToMeaningEnWhenMeaningViIsNull() {
            Vocabulary vocabNoVi = Vocabulary.builder()
                    .japaneseText("食べる")
                    .meaningVi(null)
                    .meaningEn("to eat")
                    .partOfSpeech("verb")
                    .build();

            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            when(vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, 30)))
                    .thenReturn(new PageImpl<>(List.of(vocabNoVi)));
            when(grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            try (MockedStatic<ChatClient> mockedStatic = mockStatic(ChatClient.class)) {
                ChatClient.Builder builder = mock(ChatClient.Builder.class);
                ChatClient client = mock(ChatClient.class);
                ChatClient.ChatClientRequestSpec promptSpec =
                        mock(ChatClient.ChatClientRequestSpec.class);
                ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

                mockedStatic.when(() -> ChatClient.builder(chatModel)).thenReturn(builder);
                when(builder.build()).thenReturn(client);
                when(client.prompt()).thenReturn(promptSpec);
                when(promptSpec.system(anyString())).thenReturn(promptSpec);
                ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
                when(promptSpec.user(userCaptor.capture())).thenReturn(promptSpec);
                when(promptSpec.call()).thenReturn(callSpec);
                when(callSpec.content()).thenReturn(VALID_THEMES_JSON);

                generationService.generateThemesForCourse(courseId, 1);

                assertThat(userCaptor.getValue()).contains("to eat"); // English fallback in prompt
            }
        }

        @Test
        @DisplayName("Should use 'N/A' in prompt when vocabulary partOfSpeech is null")
        void shouldUseNAWhenPartOfSpeechIsNull() {
            Vocabulary vocabNoPos = Vocabulary.builder()
                    .japaneseText("食べる")
                    .meaningVi("ăn")
                    .partOfSpeech(null)  // null partOfSpeech
                    .build();

            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            when(vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, 30)))
                    .thenReturn(new PageImpl<>(List.of(vocabNoPos)));
            when(grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            try (MockedStatic<ChatClient> mockedStatic = mockStatic(ChatClient.class)) {
                ChatClient.Builder builder = mock(ChatClient.Builder.class);
                ChatClient client = mock(ChatClient.class);
                ChatClient.ChatClientRequestSpec promptSpec =
                        mock(ChatClient.ChatClientRequestSpec.class);
                ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

                mockedStatic.when(() -> ChatClient.builder(chatModel)).thenReturn(builder);
                when(builder.build()).thenReturn(client);
                when(client.prompt()).thenReturn(promptSpec);
                when(promptSpec.system(anyString())).thenReturn(promptSpec);
                ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
                when(promptSpec.user(userCaptor.capture())).thenReturn(promptSpec);
                when(promptSpec.call()).thenReturn(callSpec);
                when(callSpec.content()).thenReturn(VALID_THEMES_JSON);

                generationService.generateThemesForCourse(courseId, 1);

                assertThat(userCaptor.getValue()).contains("[N/A]");
            }
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

            try (MockedStatic<ChatClient> mockedStatic = mockStatic(ChatClient.class)) {
                ChatClient.Builder builder = mock(ChatClient.Builder.class);
                ChatClient client = mock(ChatClient.class);
                ChatClient.ChatClientRequestSpec promptSpec =
                        mock(ChatClient.ChatClientRequestSpec.class);
                ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

                mockedStatic.when(() -> ChatClient.builder(chatModel)).thenReturn(builder);
                when(builder.build()).thenReturn(client);
                when(client.prompt()).thenReturn(promptSpec);
                when(promptSpec.system(anyString())).thenReturn(promptSpec);
                when(promptSpec.user(anyString())).thenReturn(promptSpec);
                when(promptSpec.call()).thenReturn(callSpec);
                when(callSpec.content()).thenReturn(VALID_CONTEXT_JSON);

                // When
                String result = generationService.generateUserTopicContext(scenario, courseId);

                // Then
                assertThat(result).isNotNull().contains("vocabularyHints");
                verify(vocabularyRepository).findByCourseId(courseId, PageRequest.of(0, 20));
                verify(grammarPointRepository).findByCourseId(courseId, PageRequest.of(0, 10));
            }
        }

        @Test
        @DisplayName("Should skip course lookup when courseId is null")
        void shouldSkipCourseLookupWhenCourseIdNull() {
            String scenario = "Talking with a friend";

            try (MockedStatic<ChatClient> mockedStatic = mockStatic(ChatClient.class)) {
                ChatClient.Builder builder = mock(ChatClient.Builder.class);
                ChatClient client = mock(ChatClient.class);
                ChatClient.ChatClientRequestSpec promptSpec =
                        mock(ChatClient.ChatClientRequestSpec.class);
                ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

                mockedStatic.when(() -> ChatClient.builder(chatModel)).thenReturn(builder);
                when(builder.build()).thenReturn(client);
                when(client.prompt()).thenReturn(promptSpec);
                when(promptSpec.system(anyString())).thenReturn(promptSpec);
                when(promptSpec.user(anyString())).thenReturn(promptSpec);
                when(promptSpec.call()).thenReturn(callSpec);
                when(callSpec.content()).thenReturn(VALID_CONTEXT_JSON);

                String result = generationService.generateUserTopicContext(scenario, null);

                assertThat(result).isNotNull();
                // Repositories for vocab/grammar must NOT be queried when courseId is null
                verify(vocabularyRepository, never()).findByCourseId(any(), any());
                verify(grammarPointRepository, never()).findByCourseId(any(), any());
            }
        }

        @Test
        @DisplayName("Should strip markdown code fences from context response")
        void shouldStripMarkdownCodeFencesFromContext() {
            String scenario = "Shopping at a mall";
            String wrappedJson = "```json\n" + VALID_CONTEXT_JSON + "\n```";

            try (MockedStatic<ChatClient> mockedStatic = mockStatic(ChatClient.class)) {
                ChatClient.Builder builder = mock(ChatClient.Builder.class);
                ChatClient client = mock(ChatClient.class);
                ChatClient.ChatClientRequestSpec promptSpec =
                        mock(ChatClient.ChatClientRequestSpec.class);
                ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

                mockedStatic.when(() -> ChatClient.builder(chatModel)).thenReturn(builder);
                when(builder.build()).thenReturn(client);
                when(client.prompt()).thenReturn(promptSpec);
                when(promptSpec.system(anyString())).thenReturn(promptSpec);
                when(promptSpec.user(anyString())).thenReturn(promptSpec);
                when(promptSpec.call()).thenReturn(callSpec);
                when(callSpec.content()).thenReturn(wrappedJson);

                String result = generationService.generateUserTopicContext(scenario, null);

                // Result must be valid JSON (no backticks)
                assertThatCode(() -> new ObjectMapper().readTree(result))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("Should return '{}' fallback when AI throws exception")
        void shouldReturnEmptyJsonOnAiException() {
            String scenario = "Meeting a new friend";

            try (MockedStatic<ChatClient> mockedStatic = mockStatic(ChatClient.class)) {
                ChatClient.Builder builder = mock(ChatClient.Builder.class);
                ChatClient client = mock(ChatClient.class);
                ChatClient.ChatClientRequestSpec promptSpec =
                        mock(ChatClient.ChatClientRequestSpec.class);
                ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

                mockedStatic.when(() -> ChatClient.builder(chatModel)).thenReturn(builder);
                when(builder.build()).thenReturn(client);
                when(client.prompt()).thenReturn(promptSpec);
                when(promptSpec.system(anyString())).thenReturn(promptSpec);
                when(promptSpec.user(anyString())).thenReturn(promptSpec);
                when(promptSpec.call()).thenReturn(callSpec);
                when(callSpec.content()).thenThrow(new RuntimeException("API quota exceeded"));

                String result = generationService.generateUserTopicContext(scenario, null);

                assertThat(result).isEqualTo("{}");
            }
        }

        @Test
        @DisplayName("Should return '{}' fallback when AI returns invalid JSON")
        void shouldReturnEmptyJsonOnInvalidJsonResponse() {
            String scenario = "Job interview practice";

            try (MockedStatic<ChatClient> mockedStatic = mockStatic(ChatClient.class)) {
                ChatClient.Builder builder = mock(ChatClient.Builder.class);
                ChatClient client = mock(ChatClient.class);
                ChatClient.ChatClientRequestSpec promptSpec =
                        mock(ChatClient.ChatClientRequestSpec.class);
                ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

                mockedStatic.when(() -> ChatClient.builder(chatModel)).thenReturn(builder);
                when(builder.build()).thenReturn(client);
                when(client.prompt()).thenReturn(promptSpec);
                when(promptSpec.system(anyString())).thenReturn(promptSpec);
                when(promptSpec.user(anyString())).thenReturn(promptSpec);
                when(promptSpec.call()).thenReturn(callSpec);
                when(callSpec.content()).thenReturn("{invalid json{{");

                String result = generationService.generateUserTopicContext(scenario, null);

                assertThat(result).isEqualTo("{}");
            }
        }

        @Test
        @DisplayName("Should include scenario text in prompt sent to AI")
        void shouldIncludeScenarioInUserPrompt() {
            String scenario = "Asking for directions in Tokyo";

            try (MockedStatic<ChatClient> mockedStatic = mockStatic(ChatClient.class)) {
                ChatClient.Builder builder = mock(ChatClient.Builder.class);
                ChatClient client = mock(ChatClient.class);
                ChatClient.ChatClientRequestSpec promptSpec =
                        mock(ChatClient.ChatClientRequestSpec.class);
                ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

                mockedStatic.when(() -> ChatClient.builder(chatModel)).thenReturn(builder);
                when(builder.build()).thenReturn(client);
                when(client.prompt()).thenReturn(promptSpec);
                when(promptSpec.system(anyString())).thenReturn(promptSpec);
                ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
                when(promptSpec.user(userPromptCaptor.capture())).thenReturn(promptSpec);
                when(promptSpec.call()).thenReturn(callSpec);
                when(callSpec.content()).thenReturn(VALID_CONTEXT_JSON);

                generationService.generateUserTopicContext(scenario, null);

                // Scenario text must appear in what's sent to the AI
                assertThat(userPromptCaptor.getValue()).contains(scenario);
            }
        }

        @Test
        @DisplayName("Should still call AI when courseId is present but vocab and grammar lists are empty")
        void shouldCallAiWhenCourseIdPresentButContentEmpty() {
            // generateUserTopicContext has NO early-return guard on empty vocab/grammar —
            // it always calls the AI (unlike generateThemesForCourse which guards).
            String scenario = "Visiting a shrine";
            when(vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));
            when(grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, 10)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            try (MockedStatic<ChatClient> mockedStatic = mockStatic(ChatClient.class)) {
                ChatClient.Builder builder = mock(ChatClient.Builder.class);
                ChatClient client = mock(ChatClient.class);
                ChatClient.ChatClientRequestSpec promptSpec =
                        mock(ChatClient.ChatClientRequestSpec.class);
                ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

                mockedStatic.when(() -> ChatClient.builder(chatModel)).thenReturn(builder);
                when(builder.build()).thenReturn(client);
                when(client.prompt()).thenReturn(promptSpec);
                when(promptSpec.system(anyString())).thenReturn(promptSpec);
                when(promptSpec.user(anyString())).thenReturn(promptSpec);
                when(promptSpec.call()).thenReturn(callSpec);
                when(callSpec.content()).thenReturn(VALID_CONTEXT_JSON);

                String result = generationService.generateUserTopicContext(scenario, courseId);

                // AI was called despite empty content — result is valid JSON
                assertThat(result).isNotNull().contains("vocabularyHints");
            }
        }

        @Test
        @DisplayName("Should include vocabulary in user-context prompt when course has vocab")
        void shouldIncludeVocabularyInContextPromptWhenPresent() {
            String scenario = "Ordering ramen at a restaurant";
            when(vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(vocabularyList));   // non-empty
            when(grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, 10)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            try (MockedStatic<ChatClient> mockedStatic = mockStatic(ChatClient.class)) {
                ChatClient.Builder builder = mock(ChatClient.Builder.class);
                ChatClient client = mock(ChatClient.class);
                ChatClient.ChatClientRequestSpec promptSpec =
                        mock(ChatClient.ChatClientRequestSpec.class);
                ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

                mockedStatic.when(() -> ChatClient.builder(chatModel)).thenReturn(builder);
                when(builder.build()).thenReturn(client);
                when(client.prompt()).thenReturn(promptSpec);
                when(promptSpec.system(anyString())).thenReturn(promptSpec);
                ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
                when(promptSpec.user(userCaptor.capture())).thenReturn(promptSpec);
                when(promptSpec.call()).thenReturn(callSpec);
                when(callSpec.content()).thenReturn(VALID_CONTEXT_JSON);

                generationService.generateUserTopicContext(scenario, courseId);

                // The vocabulary word "買い物" from vocabularyList should appear in the prompt
                assertThat(userCaptor.getValue()).contains("買い物");
            }
        }
    }
}
