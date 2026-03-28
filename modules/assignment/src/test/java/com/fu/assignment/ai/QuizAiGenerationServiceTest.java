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
import com.fu.course.entity.Lesson;
import com.fu.course.repository.GrammarPointRepository;
import com.fu.course.repository.LessonRepository;
import com.fu.course.repository.VocabularyRepository;
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
                    lessonRepository, vocabularyRepository, grammarPointRepository
            );

            ReflectionTestUtils.setField(quizAiGenerationService, "chatClient", chatClient);
            ReflectionTestUtils.setField(quizAiGenerationService, "model", "gpt-4o-mini");
            ReflectionTestUtils.setField(quizAiGenerationService, "outputConverter", outputConverter);
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

            List<QuizQuestionResponse> result = quizAiGenerationService.generateQuestions(request);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("Should salvage truncated JSON response")
        void shouldSalvageTruncatedResponse() throws Exception {
            AiQuizGenerationRequest request = AiQuizGenerationRequest.builder()
                    .teacherPrompt("Bài 1")
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

            List<QuizQuestionResponse> result = quizAiGenerationService.generateQuestions(request);

            assertThat(result).hasSize(1);
            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(objectMapper).readValue(jsonCaptor.capture(), eq(AiQuizResponseWrapper.class));
            assertThat(jsonCaptor.getValue()).endsWith("]}");
        }
    }

    @Nested
    @DisplayName("Context Extraction Tests")
    class ContextExtractionTests {

        @Test
        @DisplayName("Should extract lesson context from prompt")
        void shouldExtractLessonContext() throws Exception {
            UUID classId = UUID.randomUUID();
            UUID courseId = UUID.randomUUID();
            AiQuizGenerationRequest request = AiQuizGenerationRequest.builder()
                    .teacherPrompt("Bài 1")
                    .classId(classId)
                    .questionCount(1)
                    .build();

            given(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call().content())
                    .willReturn("1");

            Clazz testClazz = mock(Clazz.class);
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(testClazz.getCourseId()).willReturn(courseId);

            Lesson lesson1 = mock(Lesson.class);
            given(lesson1.getOrderIndex()).willReturn(1);
            given(lesson1.getTitle()).willReturn("Lesson 1");
            given(lesson1.getId()).willReturn(UUID.randomUUID());
            given(lessonRepository.findByCourseIdAndNotDeleted(courseId)).willReturn(List.of(lesson1));

            ChatResponse chatResponse = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
            given(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call().chatResponse())
                    .willReturn(chatResponse);

            AssistantMessage assistantMessage = mock(AssistantMessage.class);
            Generation generation = mock(Generation.class, RETURNS_DEEP_STUBS);
            given(chatResponse.getResults()).willReturn(List.of(generation));
            given(chatResponse.getResult()).willReturn(generation);
            given(generation.getMetadata().getFinishReason()).willReturn("stop");
            given(generation.getOutput()).willReturn(assistantMessage);
            given(assistantMessage.getText()).willReturn("{\"questions\": []}");

            AiQuizQuestionDto mockDto = new AiQuizQuestionDto("Q1", "multiple_choice", null, "A", null, "Ex");
            AiQuizResponseWrapper mockWrapper = new AiQuizResponseWrapper(List.of(mockDto));
            given(outputConverter.convert(anyString())).willReturn(mockWrapper);
            given(assignmentMapper.toResponse(any(QuizQuestion.class))).willReturn(new QuizQuestionResponse());

            quizAiGenerationService.generateQuestions(request);

            verify(chatClient, atLeastOnce()).prompt();
        }
    }
}
