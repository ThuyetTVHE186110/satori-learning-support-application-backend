package com.fu.assignment.ai;

import com.fu.assignment.dto.request.AiExplanationRequest;
import com.fu.assignment.dto.response.AiExplanationResponse;
import com.fu.assignment.entity.QuizQuestion;
import com.fu.assignment.repository.QuizQuestionRepository;
import com.fu.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("QuizExplanationService Unit Tests")
class QuizExplanationServiceTest {

    @Mock private OpenAiChatModel chatModel;
    @Mock private QuizQuestionRepository questionRepository;
    @Mock private BeanOutputConverter<AiExplanationResponse> outputConverter;

    private QuizExplanationService quizExplanationService;
    private ChatClient chatClient;

    @BeforeEach
    void setUp() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);

        try (var staticMock = mockStatic(ChatClient.class)) {
            staticMock.when(() -> ChatClient.builder(chatModel)).thenReturn(builder);
            given(builder.build()).willReturn(chatClient);

            quizExplanationService = new QuizExplanationService(chatModel, questionRepository);

            ReflectionTestUtils.setField(quizExplanationService, "chatClient", chatClient);
            ReflectionTestUtils.setField(quizExplanationService, "model", "gpt-4o-mini");
            ReflectionTestUtils.setField(quizExplanationService, "temperature", 0.7);
            ReflectionTestUtils.setField(quizExplanationService, "outputConverter", outputConverter);
            
            // Mock prompt resources - these are @Value injected Resources
            org.springframework.core.io.Resource systemResource = mock(org.springframework.core.io.Resource.class);
            org.springframework.core.io.Resource userResource = mock(org.springframework.core.io.Resource.class);
            try {
                lenient().when(systemResource.getInputStream()).thenReturn(
                        new java.io.ByteArrayInputStream("You are a helpful assistant.".getBytes()));
                lenient().when(userResource.getInputStream()).thenReturn(
                        new java.io.ByteArrayInputStream("Generate explanation for: {questionText}".getBytes()));
            } catch (Exception ignored) {}
            ReflectionTestUtils.setField(quizExplanationService, "systemPromptResource", systemResource);
            ReflectionTestUtils.setField(quizExplanationService, "userPromptResource", userResource);
        }

        lenient().when(outputConverter.getFormat()).thenReturn("json_format");
    }

    @Nested
    @DisplayName("GenerateExplanation Tests")
    class GenerateExplanationTests {

        @Test
        @DisplayName("Should generate explanation for existing question")
        void shouldGenerateExplanationForExistingQuestion() {
            UUID questionId = UUID.randomUUID();
            UUID teacherId = UUID.randomUUID();
            QuizQuestion question = QuizQuestion.builder()
                    .questionText("Q1")
                    .correctAnswer("A")
                    .build();
            ReflectionTestUtils.setField(question, "id", questionId);

            given(questionRepository.findById(questionId)).willReturn(Optional.of(question));
            given(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call().content())
                    .willReturn("{\"explanation\": \"[日本語]{にほんご} ++rất vui++.\"}");

            AiExplanationResponse response = AiExplanationResponse.builder()
                    .explanation("[日本語]{にほんご} ++rất vui++.")
                    .build();
            given(outputConverter.convert(anyString())).willReturn(response);
            given(questionRepository.save(any())).willAnswer(I -> I.getArgument(0));

            QuizQuestion result = quizExplanationService.generateExplanation(questionId, teacherId);

            assertThat(result.getExplanation()).isEqualTo("日本語 rất vui.");
            verify(questionRepository).save(question);
        }

        @Test
        @DisplayName("Should throw when question not found")
        void shouldThrowWhenNotFound() {
            UUID questionId = UUID.randomUUID();
            given(questionRepository.findById(questionId)).willReturn(Optional.empty());

            assertThatThrownBy(() -> quizExplanationService.generateExplanation(questionId, UUID.randomUUID()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("GenerateExplanationFromDraft Tests")
    class GenerateExplanationFromDraftTests {

        @Test
        @DisplayName("Should generate explanation from draft request")
        void shouldGenerateFromDraft() {
            AiExplanationRequest request = AiExplanationRequest.builder()
                    .questionText("Hôm nay là ____")
                    .correctAnswer("thứ bảy")
                    .build();

            given(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call().content())
                    .willReturn("{\"explanation\": \"[日本語]{にほんご} ++rất vui++.\"}");

            AiExplanationResponse response = AiExplanationResponse.builder()
                    .explanation("[日本語]{にほんご} ++rất vui++.")
                    .build();
            given(outputConverter.convert(anyString())).willReturn(response);

            AiExplanationResponse result = quizExplanationService.generateExplanationFromDraft(request);

            assertThat(result.getExplanation()).isEqualTo("日本語 rất vui.");
        }

        @Test
        @DisplayName("Should handle fallback for malformed JSON")
        void shouldHandleMalformedJsonGracefully() {
            AiExplanationRequest request = AiExplanationRequest.builder().questionText("Q").build();

            given(chatClient.prompt().system(anyString()).user(anyString()).options(any()).call().content())
                    .willReturn("{\"explanation\": \"Broken");

            // Mock converter failure to trigger fallback
            given(outputConverter.convert(anyString())).willThrow(new RuntimeException("JSON error"));

            AiExplanationResponse result = quizExplanationService.generateExplanationFromDraft(request);

            assertThat(result.getExplanation()).contains("Lỗi xử lý kết quả");
        }
    }
}
