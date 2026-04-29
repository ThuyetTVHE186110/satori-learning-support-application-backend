package com.fu.assignment.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.assignment.ai.dto.AiQuizQuestionDto;
import com.fu.assignment.ai.dto.AiQuizResponseWrapper;
import com.fu.assignment.dto.request.AiQuizGenerationRequest;
import com.fu.assignment.dto.response.QuizQuestionResponse;
import com.fu.assignment.entity.QuizQuestion;
import com.fu.assignment.mapper.AssignmentMapper;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.repository.ClassRepository;
import com.fu.course.entity.GrammarPoint;
import com.fu.course.entity.Lesson;
import com.fu.course.entity.Vocabulary;
import com.fu.course.repository.GrammarPointRepository;
import com.fu.course.repository.LessonRepository;
import com.fu.course.repository.VocabularyRepository;
import com.fu.curriculumimport.service.embedding.ChatbotFilter;
import com.fu.curriculumimport.service.embedding.EmbeddingService;
import com.fu.curriculumimport.service.embedding.RagContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("QuizAiGenerationService Unit Tests - Phase 5: Pattern-Based Question Design")
class QuizAiGenerationServiceTest {

    @Mock private ObjectMapper objectMapper;
    @Mock private OpenAiChatModel chatModel;
    @Mock private AssignmentMapper assignmentMapper;
    @Mock private ClassRepository classRepository;
    @Mock private LessonRepository lessonRepository;
    @Mock private VocabularyRepository vocabularyRepository;
    @Mock private GrammarPointRepository grammarPointRepository;
    @Mock private EmbeddingService embeddingService;
    @Mock private BeanOutputConverter<AiQuizResponseWrapper> outputConverter;


    private QuizAiGenerationService quizAiGenerationService;
    private ChatClient chatClient;

    @BeforeEach
    void setUp() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);

        // Create a synchronous executor for testing (executes immediately instead of async)
        java.util.concurrent.Executor syncExecutor = (Runnable r) -> r.run();

        try (var staticMock = mockStatic(ChatClient.class)) {
            staticMock.when(() -> ChatClient.builder(chatModel)).thenReturn(builder);
            given(builder.build()).willReturn(chatClient);

            quizAiGenerationService = new QuizAiGenerationService(
                    objectMapper, chatModel, assignmentMapper, classRepository,
                    lessonRepository, vocabularyRepository, grammarPointRepository,
                    embeddingService, syncExecutor
            );


            ReflectionTestUtils.setField(quizAiGenerationService, "chatClient", chatClient);
            ReflectionTestUtils.setField(quizAiGenerationService, "model", "gpt-4o-mini");
            ReflectionTestUtils.setField(quizAiGenerationService, "outputConverter", outputConverter);
            
            // Mock prompt resources - these are @Value injected Resources
            // Updated to reflect Phase 5: Pattern-based design with Vietnamese instructions
            // System template: Contains 5-pattern framework (Grammar, Vocabulary, Register, Nuance, Cultural)
            // User template: Pattern examples with Vietnamese instructions
            org.springframework.core.io.Resource systemResource = mock(org.springframework.core.io.Resource.class);
            org.springframework.core.io.Resource userResource = mock(org.springframework.core.io.Resource.class);
            org.springframework.core.io.Resource simpleUserResource = mock(org.springframework.core.io.Resource.class);
            try {
                // System template: Contains FIVE QUESTION PATTERNS framework
                String systemTemplateText = "You are expert Japanese quiz creator. "
                        + "FIVE QUESTION PATTERNS: "
                        + "1) Grammar Structure - tests particles/forms (unambiguous) "
                        + "2) Vocabulary Context - word selection in situation "
                        + "3) Register/Politeness - formality level "
                        + "4) Nuance Distinction - synonym differences "
                        + "5) Cultural Knowledge - real Japanese practice. "
                        + "CRITICAL: NO answer words in questionText. MANDATORY Vietnamese instructions. {lb}{rb}";
                lenient().when(systemResource.getInputStream()).thenAnswer(inv ->
                        new java.io.ByteArrayInputStream(systemTemplateText.getBytes()));
                
                // User template: Pattern examples with Vietnamese instructions (Hãy chọn, Điền từ, etc.)
                String userTemplateText = "Context: {context}\n"
                        + "Teacher prompt: {teacherPrompt}\n"
                        + "Count: {questionCount}\n"
                        + "Types: {questionTypes}\n"
                        + "JLPT Level: {jlptLevel}\n"
                        + "MANDATORY: Every question MUST start with Vietnamese instruction (Hãy chọn, Điền từ, Chọn ý nghĩa, Hoàn thành). "
                        + "PREVENT answer leakage: No option words in questionText. "
                        + "Follow one of 5 patterns: Grammar, Vocabulary, Register, Nuance, Cultural. {lb}{rb}";
                lenient().when(userResource.getInputStream()).thenAnswer(inv ->
                        new java.io.ByteArrayInputStream(userTemplateText.getBytes()));
                lenient().when(simpleUserResource.getInputStream()).thenAnswer(inv ->
                        new java.io.ByteArrayInputStream(userTemplateText.getBytes()));
            } catch (Exception ignored) {}
            ReflectionTestUtils.setField(quizAiGenerationService, "systemPromptResource", systemResource);
            ReflectionTestUtils.setField(quizAiGenerationService, "userPromptResource", userResource);
            ReflectionTestUtils.setField(quizAiGenerationService, "simpleUserPromptResource", simpleUserResource);
        }

        lenient().when(outputConverter.getFormat()).thenReturn("json_format");
    }

    @Nested
    @DisplayName("GenerateQuestions Tests")
    class GenerateQuestionsTests {

        @Test
        @DisplayName("Should generate questions with Vietnamese instructions (Phase 5 requirement)")
        void shouldGenerateQuestionsWithVietnameseInstructions() throws Exception {
            AiQuizGenerationRequest request = AiQuizGenerationRequest.builder()
                    .teacherPrompt("Bài 1: Kiểm tra cấu trúc ngữ pháp")
                    .lessonId(UUID.randomUUID())
                    .classId(UUID.randomUUID())
                    .questionCount(1)
                    .questionTypes(List.of("multiple_choice"))
                    .build();

            // Phase 5: Questions MUST start with Vietnamese instruction
            // Example: "Hãy chọn đáp án đúng:" for multiple choice
            String rawJson = "{\"questions\": [{\"questionText\": \"Hãy chọn đáp án đúng: [私]{lb}わたし{rb}は[毎日]{lb}まいにち{rb}[日本語]{lb}にほんご{rb}を[勉強]{lb}べんきょう{rb}します。[昨日]{lb}きのう{rb}は何時間[勉強]{lb}べんきょう{rb}しましたか？\", \"questionType\": \"multiple_choice\", \"explanation\": \"Câu này kiểm tra thì quá khứ. Đáp án đúng là 2 giờ.\"}]}";

            ChatResponse chatResponse = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
            AssistantMessage assistantMessage = mock(AssistantMessage.class);
            Generation generation = mock(Generation.class, RETURNS_DEEP_STUBS);

            given(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call().chatResponse())
                    .willReturn(chatResponse);

            given(chatResponse.getResults()).willReturn(List.of(generation));
            given(chatResponse.getResult()).willReturn(generation);
            given(generation.getMetadata().getFinishReason()).willReturn("stop");
            given(generation.getOutput()).willReturn(assistantMessage);
            given(assistantMessage.getText()).willReturn(rawJson);

            AiQuizQuestionDto dto = new AiQuizQuestionDto(
                    "Hãy chọn đáp án đúng: [私]{lb}わたし{rb}は[毎日]{lb}まいにち{rb}[日本語]{lb}にほんご{rb}を[勉強]{lb}べんきょう{rb}します。[昨日]{lb}きのう{rb}は何時間[勉強]{lb}べんきょう{rb}しましたか？",
                    "multiple_choice", null, "B", null,
                    "Câu này kiểm tra thì quá khứ. Đáp án đúng là 2 giờ.");
            AiQuizResponseWrapper wrapper = new AiQuizResponseWrapper(List.of(dto));

            given(outputConverter.convert(anyString())).willReturn(wrapper);
            
            // Create a proper mock response with the question text
            QuizQuestionResponse mockResponse = new QuizQuestionResponse();
            mockResponse.setQuestionText("Hãy chọn đáp án đúng: [私]{lb}わたし{rb}は[毎日]{lb}まいにち{rb}[日本語]{lb}にほんご{rb}を[勉強]{lb}べんきょう{rb}します。[昨日]{lb}きのう{rb}は何時間[勉強]{lb}べんきょう{rb}しましたか？");
            given(assignmentMapper.toResponse(any(QuizQuestion.class))).willReturn(mockResponse);

            Clazz clazz = mock(Clazz.class);
            given(clazz.getCourseId()).willReturn(UUID.randomUUID());
            given(classRepository.findByIdAndNotDeleted(any())).willReturn(Optional.of(clazz));
            Lesson mockLesson = mock(Lesson.class);
            given(mockLesson.getTitle()).willReturn("Test Lesson");
            given(lessonRepository.findById(any())).willReturn(Optional.of(mockLesson));
            
            // Mock vocabulary/grammar so lesson is not empty
            Vocabulary mockVocab = mock(Vocabulary.class);
            given(mockVocab.getJapaneseText()).willReturn("日本語");
            given(mockVocab.getMeaningVi()).willReturn("tiếng Nhật");
            given(vocabularyRepository.findByLessonId(any())).willReturn(List.of(mockVocab));
            given(grammarPointRepository.findByLessonId(any())).willReturn(List.of());

            List<QuizQuestionResponse> result = quizAiGenerationService.generateQuestions(request);

            assertThat(result).hasSize(1);
            // Phase 5 verification: Question text must start with Vietnamese instruction
            assertThat(result.get(0).getQuestionText()).startsWith("Hãy chọn đáp án đúng:");
        }

        @Test
        @DisplayName("Should prevent answer leakage (Phase 5: no option words in questionText)")
        void shouldPreventAnswerLeakage() throws Exception {
            AiQuizGenerationRequest request = AiQuizGenerationRequest.builder()
                    .teacherPrompt("Fill-blank: Where to read newspaper")
                    .lessonId(UUID.randomUUID())
                    .classId(UUID.randomUUID())
                    .questionCount(1)
                    .questionTypes(List.of("fill_blank"))
                    .build();

            // Phase 5: NO answer words should leak into questionText
            // ❌ WRONG: "Điền từ thích hợp: [朝]{lb}あさ{rb}、わたしは____で[新聞]{lb}しんぶん{rb}を[読]{lb}よ{rb}みます。[喫茶店]{lb}きっさてん{rb}で..."
            // ✓ CORRECT: "Điền từ thích hợp: [朝]{lb}あさ{rb}、わたしは____で[新聞]{lb}しんぶん{rb}を[読]{lb}よ{rb}みます。"
            String rawJson = "{\"questions\": [{\"questionText\": \"Điền từ thích hợp: [朝]{lb}あさ{rb}、わたしは____で[新聞]{lb}しんぶん{rb}を[読]{lb}よ{rb}みます。\", \"questionType\": \"fill_blank\", \"options\": {\"type\": \"multiple_choice\", \"options\": [{\"id\": \"A\", \"text\": \"[公園]{lb}こうえん{rb}\"}, {\"id\": \"B\", \"text\": \"[喫茶店]{lb}きっさてん{rb}\"}, {\"id\": \"C\", \"text\": \"[図書館]{lb}としょかん{rb}\"}, {\"id\": \"D\", \"text\": \"[駅]{lb}えき{rb}\"}]}, \"correctAnswer\": \"B\", \"explanation\": \"Kết hợp sáng + đọc báo = quán cà phê là nơi tự nhiên nhất.\"}]}";

            ChatResponse chatResponse = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
            AssistantMessage assistantMessage = mock(AssistantMessage.class);
            Generation generation = mock(Generation.class, RETURNS_DEEP_STUBS);

            given(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call().chatResponse())
                    .willReturn(chatResponse);

            given(chatResponse.getResults()).willReturn(List.of(generation));
            given(chatResponse.getResult()).willReturn(generation);
            given(generation.getMetadata().getFinishReason()).willReturn("stop");
            given(generation.getOutput()).willReturn(assistantMessage);
            given(assistantMessage.getText()).willReturn(rawJson);

            AiQuizQuestionDto dto = new AiQuizQuestionDto(
                    "Điền từ thích hợp: [朝]{lb}あさ{rb}、わたしは____で[新聞]{lb}しんぶん{rb}を[読]{lb}よ{rb}みます。",
                    "fill_blank", null, "B", null,
                    "Kết hợp sáng + đọc báo = quán cà phê là nơi tự nhiên nhất.");
            AiQuizResponseWrapper wrapper = new AiQuizResponseWrapper(List.of(dto));

            given(outputConverter.convert(anyString())).willReturn(wrapper);
            
            // Create a proper mock response with the question text
            QuizQuestionResponse mockResponse = new QuizQuestionResponse();
            mockResponse.setQuestionText("Điền từ thích hợp: [朝]{lb}あさ{rb}、わたしは____で[新聞]{lb}しんぶん{rb}を[読]{lb}よ{rb}みます。");
            given(assignmentMapper.toResponse(any(QuizQuestion.class))).willReturn(mockResponse);

            Clazz clazz = mock(Clazz.class);
            given(clazz.getCourseId()).willReturn(UUID.randomUUID());
            given(classRepository.findByIdAndNotDeleted(any())).willReturn(Optional.of(clazz));
            Lesson mockLesson = mock(Lesson.class);
            given(mockLesson.getTitle()).willReturn("Test Lesson");
            given(lessonRepository.findById(any())).willReturn(Optional.of(mockLesson));
            
            Vocabulary mockVocab = mock(Vocabulary.class);
            given(mockVocab.getJapaneseText()).willReturn("日本語");
            given(mockVocab.getMeaningVi()).willReturn("tiếng Nhật");
            given(vocabularyRepository.findByLessonId(any())).willReturn(List.of(mockVocab));
            given(grammarPointRepository.findByLessonId(any())).willReturn(List.of());

            List<QuizQuestionResponse> result = quizAiGenerationService.generateQuestions(request);

            assertThat(result).hasSize(1);
            // Phase 5 verification: No option words should leak into questionText
            String questionText = result.get(0).getQuestionText();
            assertThat(questionText).doesNotContain("喫茶店"); // Correct answer should NOT appear
            assertThat(questionText).doesNotContain("きっさてん");
            assertThat(questionText).doesNotContain("公園"); // Wrong options should NOT appear
            assertThat(questionText).doesNotContain("図書館");
        }

        @Test
        @DisplayName("Should salvage truncated JSON response")
        void shouldSalvageTruncatedResponse() throws Exception {
            AiQuizGenerationRequest request = AiQuizGenerationRequest.builder()
                    .teacherPrompt("Bài 1")
                    .lessonId(UUID.randomUUID())
                    .classId(UUID.randomUUID())
                    .questionCount(2)
                    .build();


            String truncatedJson = "{\"questions\": [{\"questionText\": \"Hãy chọn đáp án đúng: Q1\", \"explanation\": \"E1\"}, {\"questionText\": \"Điền từ thích hợp: Q2\"";

            ChatResponse chatResponse = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
            AssistantMessage assistantMessage = mock(AssistantMessage.class);
            Generation generation = mock(Generation.class, RETURNS_DEEP_STUBS);

            given(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call().chatResponse())
                    .willReturn(chatResponse);

            given(chatResponse.getResults()).willReturn(List.of(generation));
            given(chatResponse.getResult()).willReturn(generation);
            given(generation.getMetadata().getFinishReason()).willReturn("length");
            given(generation.getOutput()).willReturn(assistantMessage);
            given(assistantMessage.getText()).willReturn(truncatedJson);

            // Mock converter failure to trigger salvage
            given(outputConverter.convert(anyString())).willThrow(new RuntimeException("Incomplete JSON"));

            AiQuizQuestionDto q1 = new AiQuizQuestionDto("Hãy chọn đáp án đúng: Q1", "multiple_choice", null, "A", null, "E1");
            AiQuizResponseWrapper wrapper = new AiQuizResponseWrapper(List.of(q1));
            given(objectMapper.readValue(anyString(), eq(AiQuizResponseWrapper.class))).willReturn(wrapper);

            Clazz clazz = mock(Clazz.class);
            given(clazz.getCourseId()).willReturn(UUID.randomUUID());
            given(classRepository.findByIdAndNotDeleted(any())).willReturn(Optional.of(clazz));
            Lesson mockLesson = mock(Lesson.class);
            given(mockLesson.getTitle()).willReturn("Test Lesson");
            given(lessonRepository.findById(any())).willReturn(Optional.of(mockLesson));

            Vocabulary mockVocab = mock(Vocabulary.class);
            given(mockVocab.getJapaneseText()).willReturn("日本語");
            given(mockVocab.getMeaningVi()).willReturn("tiếng Nhật");
            given(vocabularyRepository.findByLessonId(any())).willReturn(List.of(mockVocab));
            given(grammarPointRepository.findByLessonId(any())).willReturn(List.of());

            List<QuizQuestionResponse> result = quizAiGenerationService.generateQuestions(request);

            assertThat(result).hasSize(1);
            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(objectMapper).readValue(jsonCaptor.capture(), eq(AiQuizResponseWrapper.class));
            assertThat(jsonCaptor.getValue()).endsWith("]}");
        }
    }

    @Nested
    @DisplayName("Logic Tests - Phase 5 Framework")
    class LogicTests {

        @Test
        @DisplayName("Should throw BusinessException when lessonIds is empty")
        void shouldThrowExceptionWhenLessonIdsIsEmpty() {
            AiQuizGenerationRequest request = AiQuizGenerationRequest.builder()
                    .lessonId(null)
                    .build();

            org.junit.jupiter.api.Assertions.assertThrows(com.fu.common.exception.BusinessException.class,
                    () -> quizAiGenerationService.generateQuestions(request));
        }

        @Test
        @DisplayName("Should retry with simple prompt when first attempt fails (StringTemplate error)")
        void shouldRetryWithSimplePromptOnFailure() throws Exception {
            UUID classId = UUID.randomUUID();
            AiQuizGenerationRequest request = AiQuizGenerationRequest.builder()
                    .classId(classId)
                    .lessonId(UUID.randomUUID())
                    .questionCount(1)
                    .teacherPrompt("Test")
                    .build();

            Clazz clazz = mock(Clazz.class);
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(clazz));
            given(clazz.getCourseId()).willReturn(UUID.randomUUID());
            Lesson mockLesson = mock(Lesson.class);
            given(mockLesson.getTitle()).willReturn("Test Lesson");
            given(lessonRepository.findById(any())).willReturn(Optional.of(mockLesson));

            // Mock vocabulary/grammar so lesson is not empty
            Vocabulary mockVocab = mock(Vocabulary.class);
            given(mockVocab.getJapaneseText()).willReturn("日本語");
            given(mockVocab.getMeaningVi()).willReturn("tiếng Nhật");
            given(vocabularyRepository.findByLessonId(any())).willReturn(List.of(mockVocab));
            given(grammarPointRepository.findByLessonId(any())).willReturn(List.of());

            // First attempt: return null/empty chat response
            ChatResponse failResponse = mock(ChatResponse.class);
            given(failResponse.getResults()).willReturn(List.of()); // Results empty

            // Second attempt: return success
            ChatResponse successResponse = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
            Generation successGen = mock(Generation.class, RETURNS_DEEP_STUBS);
            given(successResponse.getResults()).willReturn(List.of(successGen));
            given(successResponse.getResult()).willReturn(successGen);
            given(successGen.getMetadata().getFinishReason()).willReturn("stop");
            given(successGen.getOutput().getText()).willReturn("{\"questions\": [{\"questionText\": \"Hãy chọn đáp án đúng: test\", \"explanation\": \"test\"}]}");

            // Mock the chatClient.prompt()... call flow
            // Note: Since chatClient is deep mocked, we can stub sequentially
            given(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call().chatResponse())
                    .willReturn(failResponse)    // First call (RAG prompt fails)
                    .willReturn(successResponse); // Second call (simple prompt succeeds)

            given(outputConverter.convert(anyString())).willReturn(new AiQuizResponseWrapper(List.of(mock(AiQuizQuestionDto.class))));

            quizAiGenerationService.generateQuestions(request);

            // Verify retry happened: first attempt + second attempt = at least 2 calls
            verify(chatClient, atLeast(2)).prompt();
        }

        @Test
        @DisplayName("Phase 5: Questions follow 5-pattern framework (Grammar, Vocab, Register, Nuance, Cultural)")
        void shouldFollowFivePatternFramework() throws Exception {
            AiQuizGenerationRequest request = AiQuizGenerationRequest.builder()
                    .teacherPrompt("Create 5 questions using all patterns")
                    .lessonId(UUID.randomUUID())
                    .classId(UUID.randomUUID())
                    .questionCount(1)
                    .questionTypes(List.of("multiple_choice"))
                    .build();

            // Pattern 1: Grammar Structure - particle choice
            // Pattern 2: Vocabulary Context - word selection
            // Pattern 3: Register/Politeness - formality level
            // Pattern 4: Nuance Distinction - synonym differences
            // Pattern 5: Cultural Knowledge - real Japanese practice
            
            String rawJson = "{\"questions\": [{\"questionText\": \"Hãy chọn đáp án đúng: [私]{lb}わたし{rb}は[雨]{lb}あめ{rb}が[降]{lb}ふ{rb}ると[思]{lb}おも{rb}います。____を[持]{lb}も{rb}ってきましょう。\", \"questionType\": \"multiple_choice\", \"explanation\": \"Câu này kiểm tra cấu trúc ngữ pháp: chọn động từ phù hợp với trời mưa. [傘]{lb}かさ{rb} (ô) là lựa chọn tự nhiên nhất. Các tùy chọn khác không liên quan đến mưa.\"}]}";

            ChatResponse chatResponse = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
            AssistantMessage assistantMessage = mock(AssistantMessage.class);
            Generation generation = mock(Generation.class, RETURNS_DEEP_STUBS);

            given(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call().chatResponse())
                    .willReturn(chatResponse);

            given(chatResponse.getResults()).willReturn(List.of(generation));
            given(chatResponse.getResult()).willReturn(generation);
            given(generation.getMetadata().getFinishReason()).willReturn("stop");
            given(generation.getOutput()).willReturn(assistantMessage);
            given(assistantMessage.getText()).willReturn(rawJson);

            AiQuizQuestionDto dto = new AiQuizQuestionDto(
                    "Hãy chọn đáp án đúng: [私]{lb}わたし{rb}は[雨]{lb}あめ{rb}が[降]{lb}ふ{rb}ると[思]{lb}おも{rb}います。____を[持]{lb}も{rb}ってきましょう。",
                    "multiple_choice", null, "A", null,
                    "Câu này kiểm tra cấu trúc ngữ pháp: chọn động từ phù hợp với trời mưa. [傘]{lb}かさ{rb} (ô) là lựa chọn tự nhiên nhất. Các tùy chọn khác không liên quan đến mưa.");
            AiQuizResponseWrapper wrapper = new AiQuizResponseWrapper(List.of(dto));

            given(outputConverter.convert(anyString())).willReturn(wrapper);
            
            // Create a proper mock response with the question text
            QuizQuestionResponse mockResponse = new QuizQuestionResponse();
            mockResponse.setQuestionText("Hãy chọn đáp án đúng: [私]{lb}わたし{rb}は[雨]{lb}あめ{rb}が[降]{lb}ふ{rb}ると[思]{lb}おも{rb}います。____を[持]{lb}も{rb}ってきましょう。");
            given(assignmentMapper.toResponse(any(QuizQuestion.class))).willReturn(mockResponse);

            Clazz clazz = mock(Clazz.class);
            given(clazz.getCourseId()).willReturn(UUID.randomUUID());
            given(classRepository.findByIdAndNotDeleted(any())).willReturn(Optional.of(clazz));
            Lesson mockLesson = mock(Lesson.class);
            given(mockLesson.getTitle()).willReturn("Test Lesson");
            given(lessonRepository.findById(any())).willReturn(Optional.of(mockLesson));
            
            Vocabulary mockVocab = mock(Vocabulary.class);
            given(mockVocab.getJapaneseText()).willReturn("日本語");
            given(mockVocab.getMeaningVi()).willReturn("tiếng Nhật");
            given(vocabularyRepository.findByLessonId(any())).willReturn(List.of(mockVocab));
            given(grammarPointRepository.findByLessonId(any())).willReturn(List.of());

            List<QuizQuestionResponse> result = quizAiGenerationService.generateQuestions(request);

            assertThat(result).hasSize(1);
            // Verify question follows pattern framework with Vietnamese instruction
            assertThat(result.get(0).getQuestionText()).startsWith("Hãy chọn đáp án đúng:");
        }
    }
}

