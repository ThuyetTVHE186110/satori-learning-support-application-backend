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
@DisplayName("QuizAiGenerationService Unit Tests")
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

        try (var staticMock = mockStatic(ChatClient.class)) {
            staticMock.when(() -> ChatClient.builder(chatModel)).thenReturn(builder);
            given(builder.build()).willReturn(chatClient);

            quizAiGenerationService = new QuizAiGenerationService(
                    objectMapper, chatModel, assignmentMapper, classRepository,
                    lessonRepository, vocabularyRepository, grammarPointRepository,
                    embeddingService
            );


            ReflectionTestUtils.setField(quizAiGenerationService, "chatClient", chatClient);
            ReflectionTestUtils.setField(quizAiGenerationService, "model", "gpt-4o-mini");
            ReflectionTestUtils.setField(quizAiGenerationService, "outputConverter", outputConverter);
            
            // Mock prompt resources - these are @Value injected Resources
            // Use thenAnswer to return a fresh stream each time (streams are consumed after reading)
            org.springframework.core.io.Resource systemResource = mock(org.springframework.core.io.Resource.class);
            org.springframework.core.io.Resource userResource = mock(org.springframework.core.io.Resource.class);
            org.springframework.core.io.Resource simpleUserResource = mock(org.springframework.core.io.Resource.class);
            try {
                // System template uses {lb} and {rb} only
                lenient().when(systemResource.getInputStream()).thenAnswer(inv ->
                        new java.io.ByteArrayInputStream("You are a helpful assistant. {lb}{rb}".getBytes()));
                // User template uses: context, teacherPrompt, questionCount, questionTypes, jlptLevel, formatInstruction, lb, rb
                String userTemplateText = "Context: {context}\nPrompt: {teacherPrompt}\nCount: {questionCount}\nTypes: {questionTypes}\nLevel: {jlptLevel}\nFormat: {formatInstruction} {lb}{rb}";
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
        @DisplayName("Should generate questions successfully")
        void shouldGenerateQuestionsSuccessfully() throws Exception {
            AiQuizGenerationRequest request = AiQuizGenerationRequest.builder()
                    .teacherPrompt("Bài 1")
                    .lessonIds(List.of(UUID.randomUUID()))
                    .classId(UUID.randomUUID())
                    .questionCount(1)
                    .questionTypes(List.of("multiple_choice"))
                    .build();


            String rawJson = "{\"questions\": [{\"questionText\": \"[日本]{にほん} ++đẹp++\", \"questionType\": \"multiple_choice\", \"explanation\": \"Ex1\"}]}";

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

            AiQuizQuestionDto dto = new AiQuizQuestionDto("[日本]{にほん} ++đẹp++", "multiple_choice", null, "A", null, "Ex1");
            AiQuizResponseWrapper wrapper = new AiQuizResponseWrapper(List.of(dto));

            given(outputConverter.convert(anyString())).willReturn(wrapper);
            given(assignmentMapper.toResponse(any(QuizQuestion.class))).willReturn(new QuizQuestionResponse());

            Clazz clazz = mock(Clazz.class);
            given(clazz.getCourseId()).willReturn(UUID.randomUUID());
            given(classRepository.findByIdAndNotDeleted(any())).willReturn(Optional.of(clazz));
            given(lessonRepository.findAllById(any())).willReturn(List.of(mock(Lesson.class)));
            given(embeddingService.retrieveContext(anyString(), any())).willReturn(RagContext.empty());
            
            // Mock vocabulary/grammar so lesson is not empty
            Vocabulary mockVocab = mock(Vocabulary.class);
            given(mockVocab.getJapaneseText()).willReturn("日本語");
            given(mockVocab.getMeaningVi()).willReturn("tiếng Nhật");
            given(vocabularyRepository.findByLessonId(any())).willReturn(List.of(mockVocab));
            given(grammarPointRepository.findByLessonId(any())).willReturn(List.of());


            List<QuizQuestionResponse> result = quizAiGenerationService.generateQuestions(request);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("Should salvage truncated JSON response")
        void shouldSalvageTruncatedResponse() throws Exception {
            AiQuizGenerationRequest request = AiQuizGenerationRequest.builder()
                    .teacherPrompt("Bài 1")
                    .lessonIds(List.of(UUID.randomUUID()))
                    .classId(UUID.randomUUID())
                    .questionCount(2)
                    .build();


            String truncatedJson = "{\"questions\": [{\"questionText\": \"Q1\", \"explanation\": \"E1\"}, {\"questionText\": \"Q2\"";

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

            AiQuizQuestionDto q1 = new AiQuizQuestionDto("Q1", "multiple_choice", null, "A", null, "E1");
            AiQuizResponseWrapper wrapper = new AiQuizResponseWrapper(List.of(q1));
            given(objectMapper.readValue(anyString(), eq(AiQuizResponseWrapper.class))).willReturn(wrapper);

            Clazz clazz = mock(Clazz.class);
            given(clazz.getCourseId()).willReturn(UUID.randomUUID());
            given(classRepository.findByIdAndNotDeleted(any())).willReturn(Optional.of(clazz));
            given(lessonRepository.findAllById(any())).willReturn(List.of(mock(Lesson.class)));
            given(embeddingService.retrieveContext(anyString(), any())).willReturn(RagContext.empty());

            // Mock vocabulary/grammar so lesson is not empty
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
    @DisplayName("Logic Tests")
    class LogicTests {

        @Test
        @DisplayName("Should throw BusinessException when lessonIds is empty")
        void shouldThrowExceptionWhenLessonIdsIsEmpty() {
            AiQuizGenerationRequest request = AiQuizGenerationRequest.builder()
                    .lessonIds(List.of())
                    .build();

            org.junit.jupiter.api.Assertions.assertThrows(com.fu.common.exception.BusinessException.class,
                    () -> quizAiGenerationService.generateQuestions(request));
        }

        @Test
        @DisplayName("Should use course-level RAG for multiple lessons")
        void shouldUseCourseLevelRagForMultipleLessons() throws Exception {
            UUID classId = UUID.randomUUID();
            UUID courseId = UUID.randomUUID();
            List<UUID> lessonIds = List.of(UUID.randomUUID(), UUID.randomUUID());

            AiQuizGenerationRequest request = AiQuizGenerationRequest.builder()
                    .classId(classId)
                    .lessonIds(lessonIds)
                    .questionCount(1)
                    .teacherPrompt("Test")
                    .build();

            Clazz clazz = mock(Clazz.class);
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(clazz));
            given(clazz.getCourseId()).willReturn(courseId);

            Lesson l1 = mock(Lesson.class);
            given(lessonRepository.findAllById(lessonIds)).willReturn(List.of(l1, mock(Lesson.class)));

            // Mock vocabulary/grammar so lesson is not empty
            Vocabulary mockVocab = mock(Vocabulary.class);
            given(mockVocab.getJapaneseText()).willReturn("日本語");
            given(mockVocab.getMeaningVi()).willReturn("tiếng Nhật");
            given(vocabularyRepository.findByLessonId(any())).willReturn(List.of(mockVocab));
            given(grammarPointRepository.findByLessonId(any())).willReturn(List.of());

            // Mock successful AI call for first attempt
            ChatResponse chatResponse = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
            Generation gen = mock(Generation.class, RETURNS_DEEP_STUBS);
            given(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call().chatResponse())
                    .willReturn(chatResponse);
            given(chatResponse.getResults()).willReturn(List.of(gen));
            given(chatResponse.getResult()).willReturn(gen);
            given(gen.getMetadata().getFinishReason()).willReturn("stop");
            given(gen.getOutput().getText()).willReturn("{\"questions\": []}");
            given(outputConverter.convert(anyString())).willReturn(new AiQuizResponseWrapper(List.of(mock(AiQuizQuestionDto.class))));

            given(embeddingService.retrieveContext(anyString(), any())).willReturn(RagContext.empty());


            quizAiGenerationService.generateQuestions(request);

            ArgumentCaptor<ChatbotFilter> filterCaptor = ArgumentCaptor.forClass(ChatbotFilter.class);
            verify(embeddingService).retrieveContext(anyString(), filterCaptor.capture());

            // Key assertion: lessonId should be null for multi-lesson search
            assertThat(filterCaptor.getValue().getLessonId()).isNull();
            assertThat(filterCaptor.getValue().getCourseId()).isEqualTo(courseId);
        }

        @Test
        @DisplayName("Should retry with simple prompt when first attempt fails")
        void shouldRetryWithSimplePromptOnFailure() throws Exception {
            UUID classId = UUID.randomUUID();
            AiQuizGenerationRequest request = AiQuizGenerationRequest.builder()
                    .classId(classId)
                    .lessonIds(List.of(UUID.randomUUID()))
                    .questionCount(1)
                    .teacherPrompt("Test")
                    .build();

            Clazz clazz = mock(Clazz.class);
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(clazz));
            given(clazz.getCourseId()).willReturn(UUID.randomUUID());
            given(lessonRepository.findAllById(any())).willReturn(List.of(mock(Lesson.class)));

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
            given(successGen.getOutput().getText()).willReturn("{\"questions\": []}");

            // Mock the chatClient.prompt()... call flow
            // Note: Since chatClient is deep mocked, we can stub sequentially
            given(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call().chatResponse())
                    .willReturn(failResponse)    // First call
                    .willReturn(successResponse); // Second call (retry)

            given(outputConverter.convert(anyString())).willReturn(new AiQuizResponseWrapper(List.of(mock(AiQuizQuestionDto.class))));
            given(embeddingService.retrieveContext(anyString(), any())).willReturn(RagContext.empty());


            quizAiGenerationService.generateQuestions(request);

            // Verify retry happened: first attempt + second attempt = at least 2 calls
            verify(chatClient, atLeast(2)).prompt();
        }
    }
}

