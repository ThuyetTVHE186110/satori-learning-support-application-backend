package com.fu.conversation.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.conversation.dto.response.GeneratedConversationSuggestionResponse;
import com.fu.conversation.dto.response.GeneratedTopicSuggestionResponse;
import com.fu.conversation.entity.ConversationTopic;
import com.fu.conversation.repository.ConversationTopicRepository;
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
    @Mock private ConversationTopicRepository topicRepository;
    @Mock private ChatClient chatClient;
    @Spy  private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ConversationGenerationService generationService;

    private UUID courseId;
    private UUID topicId;
    private Course course;
    private ConversationTopic topic;
    private List<Vocabulary> vocabularyList;
    private List<GrammarPoint> grammarList;

    private static final String VALID_TOPICS_JSON = """
            [
              {
                "title": "Mua sắm hàng ngày",
                "titleJapanese": "日常のショッピング",
                "description": "Luyện các kỹ năng mua sắm tại siêu thị, cửa hàng tạp hóa hoặc thị trường",
                "descriptionVi": "Luyện từ vựng và diễn tả liên quan đến việc mua sắm, hỏi giá cả, chọn sản phẩm",
                "scenario": "Bạn vào siêu thị để mua các mặt hàng hàng ngày",
                "category": "shopping",
                "vocabularyHints": ["買い物", "値段", "スーパー", "商品"],
                "grammarPoints": ["〜てください", "〜はいくらですか", "〜をください"],
                "usefulExpressions": ["いくらですか", "これをください", "合わせていくらですか"],
                "missions": []
              }
            ]
            """;

    private static final String VALID_CONVERSATIONS_JSON = """
            [
              {
                "title": "Gọi đồ ăn tại nhà hàng",
                "titleJapanese": "食べ物を注文する",
                "description": "Luyện gọi món ở nhà hàng Nhật Bản",
                "descriptionVi": "Luyện các cách gọi đồ ăn, hỏi giá, và trao đổi với nhân viên phục vụ tại nhà hàng",
                "scenario": "Bạn là khách hàng vào nhà hàng Nhật Bản, nhân viên phục vụ sẽ chào đón và giới thiệu menu cho bạn",
                "category": "restaurant",
                "aiRole": "Nhân viên phục vụ nhà hàng",
                "learnerRole": "Khách hàng",
                "vocabularyHints": ["食べ物", "注文", "メニュー", "値段"],
                "grammarPoints": ["〜をください", "〜があります", "〜はどうですか"],
                "usefulExpressions": ["いらっしゃいませ", "これをください", "いくらですか"],
                "missions": [
                  {
                    "title": "Gọi một món ăn",
                    "titleJapanese": "一品注文する",
                    "titleVi": "Gọi một món ăn",
                    "description": "Bạn phải gọi ít nhất một món ăn từ menu, hỏi giá và nội dung của nó",
                    "descriptionVi": "Đọc menu, chọn một món ăn, hỏi giá tiền, và xác nhận đơn hàng",
                    "missionType": "TRANSACTIONAL",
                    "successCriteria": {
                      "requiredActions": ["gọi một món ăn", "hỏi giá tiền", "xác nhận đơn hàng"],
                      "requiredVocabulary": ["〜をください", "値段"],
                      "completionConditions": "Khách hàng gọi thành công một món ăn"
                    },
                    "requiredVocabulary": ["メニュー", "注文", "値段"],
                    "requiredGrammar": ["〜をください", "いくらですか"],
                    "orderIndex": 1
                  },
                  {
                    "title": "Thương lượng về danh sách ăn chay",
                    "titleJapanese": "ベジタリアン料理について聞く",
                    "titleVi": "Hỏi về món ăn chay",
                    "description": "Hỏi xem có món ăn chay nào không và danh sách các thành phần",
                    "descriptionVi": "Bạn là người ăn chay, hãy hỏi nhân viên về các lựa chọn ăn chay có sẵn",
                    "missionType": "INFORMATION_GATHERING",
                    "successCriteria": {
                      "requiredActions": ["hỏi về món ăn chay", "liệt kê các thành phần"],
                      "requiredVocabulary": ["野菜", "ベジタリアン"],
                      "completionConditions": "Nhân viên cung cấp thông tin về các lựa chọn ăn chay"
                    },
                    "requiredVocabulary": ["野菜", "材料"],
                    "requiredGrammar": ["〜はありますか", "〜について"],
                    "orderIndex": 2
                  }
                ]
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
        ReflectionTestUtils.setField(generationService, "chatClient", chatClient);

        courseId = UUID.randomUUID();
        topicId  = UUID.randomUUID();

        course = Course.builder().name("N5 Beginner").build();
        ReflectionTestUtils.setField(course, "id", courseId);

        topic = ConversationTopic.builder()
                .title("Daily Shopping")
                .titleJapanese("日常のショッピング")
                .description("Shopping scenarios")
                .category("shopping")
                .jlptLevel(Course.JlptLevel.N5)
                .build();
        ReflectionTestUtils.setField(topic, "id", topicId);

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
    @DisplayName("generateTopics")
    class GenerateTopicsTests {

        @Test
        @DisplayName("Should return parsed topic list when AI call succeeds")
        void shouldReturnParsedTopicsOnSuccessfulAiCall() {
            stubChatResponse(VALID_TOPICS_JSON);

            List<GeneratedTopicSuggestionResponse> result =
                    generationService.generateTopics(Course.JlptLevel.N5, "shopping", null, 1);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("Mua sắm hàng ngày");
            assertThat(result.get(0).getTitleJapanese()).isEqualTo("日常のショッピング");
            assertThat(result.get(0).getCategory()).isEqualTo("shopping");
        }

        @Test
        @DisplayName("Should strip markdown code fences from AI response before parsing")
        void shouldStripMarkdownCodeFences() {
            String wrappedJson = "```json\n" + VALID_TOPICS_JSON + "\n```";
            stubChatResponse(wrappedJson);

            List<GeneratedTopicSuggestionResponse> result =
                    generationService.generateTopics(null, null, null, 1);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("Mua sắm hàng ngày");
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

            List<GeneratedTopicSuggestionResponse> result =
                    generationService.generateTopics(null, null, null, 3);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return empty list when AI returns invalid JSON")
        void shouldReturnEmptyListOnInvalidJson() {
            stubChatResponse("not valid json at all {{{");

            List<GeneratedTopicSuggestionResponse> result =
                    generationService.generateTopics(null, null, null, 1);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should include JLPT level in prompt when provided")
        void shouldIncludeJlptLevelInPromptWhenProvided() {
            ArgumentCaptor<String> userCaptor = stubChatResponseAndCaptureUserPrompt(VALID_TOPICS_JSON);

            generationService.generateTopics(Course.JlptLevel.N5, null, null, 1);

            assertThat(userCaptor.getValue()).contains("N5");
        }

        @Test
        @DisplayName("Should include category in prompt when provided")
        void shouldIncludeCategoryInPromptWhenProvided() {
            ArgumentCaptor<String> userCaptor = stubChatResponseAndCaptureUserPrompt(VALID_TOPICS_JSON);

            generationService.generateTopics(null, "restaurant", null, 1);

            assertThat(userCaptor.getValue()).contains("restaurant");
        }

        @Test
        @DisplayName("Should include hint in prompt when provided")
        void shouldIncludeHintInPromptWhenProvided() {
            ArgumentCaptor<String> userCaptor = stubChatResponseAndCaptureUserPrompt(VALID_TOPICS_JSON);

            generationService.generateTopics(null, null, "focus on polite speech", 2);

            assertThat(userCaptor.getValue()).contains("focus on polite speech");
        }

        @Test
        @DisplayName("Should include count in prompt text")
        void shouldIncludeCountInPromptText() {
            ArgumentCaptor<String> userCaptor = stubChatResponseAndCaptureUserPrompt(VALID_TOPICS_JSON);

            generationService.generateTopics(null, null, null, 3);

            assertThat(userCaptor.getValue()).contains("3");
        }

        @Test
        @DisplayName("Should return empty list when AI returns null content")
        void shouldReturnEmptyListWhenAiReturnsNull() {
            stubChatResponse(null);

            List<GeneratedTopicSuggestionResponse> result =
                    generationService.generateTopics(null, null, null, 1);

            assertThat(result).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("generateConversationsForTopic")
    class GenerateConversationsForTopicTests {

        @Test
        @DisplayName("Should return parsed conversations when topic and course exist")
        void shouldReturnParsedConversationsWhenTopicAndCourseExist() {
            when(topicRepository.findById(topicId)).thenReturn(Optional.of(topic));
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            when(vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(vocabularyList));
            when(grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, 15)))
                    .thenReturn(new PageImpl<>(grammarList));
            stubChatResponse(VALID_CONVERSATIONS_JSON);

            List<GeneratedConversationSuggestionResponse> result =
                    generationService.generateConversationsForTopic(topicId, courseId, "Restaurant Ordering", 
                            "Practice ordering food at a restaurant", 2, Course.JlptLevel.N5);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("Gọi đồ ăn tại nhà hàng");
            assertThat(result.get(0).getAiRole()).isEqualTo("Nhân viên phục vụ nhà hàng");
            assertThat(result.get(0).getLearnerRole()).isEqualTo("Khách hàng");
            assertThat(result.get(0).getMissions()).hasSize(2);
        }

        @Test
        @DisplayName("Should return empty list when topic not found")
        void shouldReturnEmptyListWhenTopicNotFound() {
            when(topicRepository.findById(topicId)).thenReturn(Optional.empty());

            List<GeneratedConversationSuggestionResponse> result =
                    generationService.generateConversationsForTopic(topicId, courseId, "Title", "Description", 1, 
                            Course.JlptLevel.N5);
        }

        @Test
        @DisplayName("Should use provided jlptLevel from course in prompt")
        void shouldUseProvidedJlptLevelFromCourseInPrompt() {
            when(topicRepository.findById(topicId)).thenReturn(Optional.of(topic));
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            when(vocabularyRepository.findByCourseId(any(), any())).thenReturn(new PageImpl<>(List.of()));
            when(grammarPointRepository.findByCourseId(any(), any())).thenReturn(new PageImpl<>(List.of()));
            ArgumentCaptor<String> userCaptor = stubChatResponseAndCaptureUserPrompt(VALID_CONVERSATIONS_JSON);

            generationService.generateConversationsForTopic(topicId, courseId, "Title", "Description", 1, 
                    Course.JlptLevel.N3);

            assertThat(userCaptor.getValue()).contains("N3");
        }

        @Test
        @DisplayName("Should handle null jlptLevel gracefully")
        void shouldHandleNullJlptLevelGracefully() {
            when(topicRepository.findById(topicId)).thenReturn(Optional.of(topic));
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            when(vocabularyRepository.findByCourseId(any(), any())).thenReturn(new PageImpl<>(List.of()));
            when(grammarPointRepository.findByCourseId(any(), any())).thenReturn(new PageImpl<>(List.of()));
            ArgumentCaptor<String> userCaptor = stubChatResponseAndCaptureUserPrompt(VALID_CONVERSATIONS_JSON);

            generationService.generateConversationsForTopic(topicId, courseId, "Title", "Description", 1, null);

            String userPrompt = userCaptor.getValue();
            assertThat(userPrompt).doesNotContain("Target JLPT Level:");
        }

        @Test
        @DisplayName("Should return empty list when AI throws exception")
        void shouldReturnEmptyListWhenAiThrowsException() {
            when(topicRepository.findById(topicId)).thenReturn(Optional.of(topic));
            when(courseRepository.findById(courseId)).thenReturn(Optional.empty());
            when(vocabularyRepository.findByCourseId(any(), any())).thenReturn(new PageImpl<>(List.of()));
            when(grammarPointRepository.findByCourseId(any(), any())).thenReturn(new PageImpl<>(List.of()));

            ChatClient.ChatClientRequestSpec promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
            when(chatClient.prompt()).thenReturn(promptSpec);
            when(promptSpec.system(anyString())).thenReturn(promptSpec);
            when(promptSpec.user(anyString())).thenReturn(promptSpec);
            when(promptSpec.call()).thenReturn(callSpec);
            when(callSpec.content()).thenThrow(new RuntimeException("API error"));

            List<GeneratedConversationSuggestionResponse> result =
                    generationService.generateConversationsForTopic(topicId, courseId, "Title", "Description", 1, 
                            Course.JlptLevel.N5);

            assertThat(result).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("generateUserConversationContext")
    class GenerateUserConversationContextTests {

        @Test
        @DisplayName("Should return AI-generated JSON context for a scenario with course")
        void shouldReturnContextWithCourse() {
            String scenario = "Ordering food at a restaurant";
            when(vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(vocabularyList));
            when(grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, 10)))
                    .thenReturn(new PageImpl<>(grammarList));
            stubChatResponse(VALID_CONTEXT_JSON);

            String result = generationService.generateUserConversationContext(scenario, courseId);

            assertThat(result).isNotNull().contains("vocabularyHints");
            verify(vocabularyRepository).findByCourseId(courseId, PageRequest.of(0, 20));
            verify(grammarPointRepository).findByCourseId(courseId, PageRequest.of(0, 10));
        }

        @Test
        @DisplayName("Should skip course lookup when courseId is null")
        void shouldSkipCourseLookupWhenCourseIdNull() {
            String scenario = "Talking with a friend";
            stubChatResponse(VALID_CONTEXT_JSON);

            String result = generationService.generateUserConversationContext(scenario, null);

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

            String result = generationService.generateUserConversationContext(scenario, null);

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

            String result = generationService.generateUserConversationContext(scenario, null);

            assertThat(result).isEqualTo("{}");
        }

        @Test
        @DisplayName("Should return '{}' fallback when AI returns invalid JSON")
        void shouldReturnEmptyJsonOnInvalidJsonResponse() {
            String scenario = "Job interview practice";
            stubChatResponse("{invalid json{{");

            String result = generationService.generateUserConversationContext(scenario, null);

            assertThat(result).isEqualTo("{}");
        }

        @Test
        @DisplayName("Should include scenario text in prompt sent to AI")
        void shouldIncludeScenarioInUserPrompt() {
            String scenario = "Asking for directions in Tokyo";
            ArgumentCaptor<String> userPromptCaptor = stubChatResponseAndCaptureUserPrompt(VALID_CONTEXT_JSON);

            generationService.generateUserConversationContext(scenario, null);

            assertThat(userPromptCaptor.getValue()).contains(scenario);
        }
    }
}
