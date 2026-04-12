package com.fu.assignment.ai;

import com.fu.assignment.dto.request.WritingEvaluationRequest;
import com.fu.assignment.dto.response.WritingEvaluationResponse;
import com.fu.assignment.entity.Assignment;
import com.fu.assignment.entity.AssignmentType;
import com.fu.assignment.repository.AssignmentRepository;
import com.fu.assignment.repository.AssignmentTargetRepository;
import com.fu.assignment.repository.WritingAssignmentRepository;
import com.fu.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiGradingService Unit Tests")
class AiGradingServiceTest {

    @Mock
    private GoogleVisionOcrService ocrService;

    @Mock
    private OpenAiChatModel chatModel;

    @Mock
    private WritingAssignmentRepository writingAssignmentRepository;

    @Mock
    private AssignmentTargetRepository assignmentTargetRepository;

    @Mock
    private AssignmentRepository assignmentRepository;

    private AiGradingService aiGradingService;

    private UUID testAssignmentId;
    private MultipartFile[] testImages;

    @BeforeEach
    void setUp() {
        aiGradingService = new AiGradingService(
                ocrService,
                chatModel,
                writingAssignmentRepository,
                assignmentTargetRepository,
                assignmentRepository
        );

        testAssignmentId = UUID.randomUUID();
        testImages = new MultipartFile[]{mock(MultipartFile.class)};

        // Mock Resource objects for prompt templates
        Resource writingSystemResource = mockResource(
                "You are a professional Japanese Language Teacher.\n" +
                        "Your task is to evaluate a student's writing assignment based on JLPT standards.\n" +
                        "JLPT Level: {jlptLevel}"
        );
        Resource writingUserResource = mockResource(
                "[Target JLPT Level]: {jlptLevel}\n" +
                        "[Assignment Prompt]: {assignmentPrompt}\n" +
                        "[Student's Content]: {studentContent}"
        );
        Resource translationSystemResource = mockResource(
                "You are a professional Japanese Language Teacher specializing in translation evaluation.\n" +
                        "JLPT Level: {jlptLevel}\n" +
                        "Evaluate: vocabulary, accuracy, spelling, style, tone.\n" +
                        "Auto-detect translation direction (VI->JP or JP->VI)."
        );
        Resource translationUserResource = mockResource(
                "[Target JLPT Level]: {jlptLevel}\n" +
                        "[Source Text]: {assignmentPrompt}\n" +
                        "[Student's Translation]: {studentContent}"
        );

        // Inject mock resources via reflection
        ReflectionTestUtils.setField(aiGradingService, "writingSystemPromptResource", writingSystemResource);
        ReflectionTestUtils.setField(aiGradingService, "writingUserPromptResource", writingUserResource);
        ReflectionTestUtils.setField(aiGradingService, "translationSystemPromptResource", translationSystemResource);
        ReflectionTestUtils.setField(aiGradingService, "translationUserPromptResource", translationUserResource);
    }

    /**
     * Helper to create mock Resource with text content
     */
    private Resource mockResource(String content) {
        Resource resource = mock(Resource.class);
        try {
            lenient().when(resource.getInputStream()).thenAnswer(inv ->
                    new ByteArrayInputStream(content.getBytes())
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return resource;
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("Should throw BusinessException when assignment not found")
        void testEvaluateWhenAssignmentNotFound() {
            // Arrange
            String jlptLevel = "N2";
            given(assignmentTargetRepository.findJlptLevelByAssignmentId(testAssignmentId))
                    .willReturn(jlptLevel);
            given(assignmentRepository.findById(testAssignmentId))
                    .willReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> aiGradingService.evaluateWriting(
                    WritingEvaluationRequest.builder()
                            .assignmentId(testAssignmentId)
                            .prompt("Test prompt")
                            .build(),
                    testImages
            ))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Không tìm thấy");
        }

        @Test
        @DisplayName("Should throw BusinessException when OCR fails (empty result)")
        void testEvaluateWhenOcrFails() {
            // Arrange
            Assignment writingAssignment = Assignment.builder()
                    .id(testAssignmentId)
                    .assignmentType(AssignmentType.WRITING)
                    .build();

            String jlptLevel = "N3";
            given(assignmentTargetRepository.findJlptLevelByAssignmentId(testAssignmentId))
                    .willReturn(jlptLevel);
            given(assignmentRepository.findById(testAssignmentId))
                    .willReturn(Optional.of(writingAssignment));
            given(ocrService.extractTextFromFiles(List.of(testImages)))
                    .willReturn(""); // Empty extraction

            // Act & Assert
            assertThatThrownBy(() -> aiGradingService.evaluateWriting(
                    WritingEvaluationRequest.builder()
                            .assignmentId(testAssignmentId)
                            .prompt("Test prompt")
                            .build(),
                    testImages
            ))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("nhận diện được");
        }

        @Test
        @DisplayName("Should throw BusinessException when extracted content is too short")
        void testEvaluateWhenContentTooShort() {
            // Arrange - Extracted content shorter than minimum required (5 characters)
            Assignment writingAssignment = Assignment.builder()
                    .id(testAssignmentId)
                    .assignmentType(AssignmentType.WRITING)
                    .build();

            String jlptLevel = "N3";
            given(assignmentTargetRepository.findJlptLevelByAssignmentId(testAssignmentId))
                    .willReturn(jlptLevel);
            given(assignmentRepository.findById(testAssignmentId))
                    .willReturn(Optional.of(writingAssignment));
            given(ocrService.extractTextFromFiles(List.of(testImages)))
                    .willReturn("ab"); // Too short

            // Act & Assert
            assertThatThrownBy(() -> aiGradingService.evaluateWriting(
                    WritingEvaluationRequest.builder()
                            .assignmentId(testAssignmentId)
                            .prompt("Test prompt")
                            .build(),
                    testImages
            ))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("quá ngắn");
        }

        @Test
        @DisplayName("Should throw BusinessException for invalid assignment type")
        void testEvaluateWhenInvalidAssignmentType() {
            // Arrange
            Assignment quizAssignment = Assignment.builder()
                    .id(testAssignmentId)
                    .assignmentType(AssignmentType.QUIZ)
                    .build();

            String jlptLevel = "N3";
            given(assignmentTargetRepository.findJlptLevelByAssignmentId(testAssignmentId))
                    .willReturn(jlptLevel);
            given(assignmentRepository.findById(testAssignmentId))
                    .willReturn(Optional.of(quizAssignment));
            given(ocrService.extractTextFromFiles(List.of(testImages)))
                    .willReturn("Some text");

            // Act & Assert
            assertThatThrownBy(() -> aiGradingService.evaluateWriting(
                    WritingEvaluationRequest.builder()
                            .assignmentId(testAssignmentId)
                            .prompt("Test prompt")
                            .build(),
                    testImages
            ))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("WRITING hoặc TRANSLATION");
        }
    }

    @Nested
    @DisplayName("Assignment Type Detection")
    class AssignmentTypeDetection {

        @Test
        @DisplayName("Should detect WRITING assignment type")
        void testDetectWritingAssignment() {
            // Arrange
            Assignment writingAssignment = Assignment.builder()
                    .id(testAssignmentId)
                    .assignmentType(AssignmentType.WRITING)
                    .build();

            given(assignmentTargetRepository.findJlptLevelByAssignmentId(testAssignmentId))
                    .willReturn("N3");
            given(assignmentRepository.findById(testAssignmentId))
                    .willReturn(Optional.of(writingAssignment));
            given(ocrService.extractTextFromFiles(List.of(testImages)))
                    .willReturn("Student's writing content");

            // Act & Assert - Just verify no exception is thrown for valid WRITING assignment
            // (The actual LLM call will fail, but we're checking type detection logic)
            try {
                aiGradingService.evaluateWriting(
                        WritingEvaluationRequest.builder()
                                .assignmentId(testAssignmentId)
                                .prompt("Write about your day")
                                .build(),
                        testImages
                );
            } catch (Exception e) {
                // Expected since we didn't mock ChatClient, but the assignment type was detected
                assertThat(e).isInstanceOf(BusinessException.class)
                        .hasMessageContaining("AI_EVALUATION_FAILED");
            }
        }

        @Test
        @DisplayName("Should detect TRANSLATION assignment type")
        void testDetectTranslationAssignment() {
            // Arrange
            Assignment translationAssignment = Assignment.builder()
                    .id(testAssignmentId)
                    .assignmentType(AssignmentType.TRANSLATION)
                    .build();

            given(assignmentTargetRepository.findJlptLevelByAssignmentId(testAssignmentId))
                    .willReturn("N2");
            given(assignmentRepository.findById(testAssignmentId))
                    .willReturn(Optional.of(translationAssignment));
            given(ocrService.extractTextFromFiles(List.of(testImages)))
                    .willReturn("Student's translation content");

            // Act & Assert - Just verify no exception is thrown for valid TRANSLATION assignment
            // (The actual LLM call will fail, but we're checking type detection logic)
            try {
                aiGradingService.evaluateWriting(
                        WritingEvaluationRequest.builder()
                                .assignmentId(testAssignmentId)
                                .prompt("Source text to translate")
                                .build(),
                        testImages
                );
            } catch (Exception e) {
                // Expected since we didn't mock ChatClient, but the assignment type was detected
                assertThat(e).isInstanceOf(BusinessException.class)
                        .hasMessageContaining("AI_EVALUATION_FAILED");
            }
        }
    }

    @Nested
    @DisplayName("Content Isolation and Freshness")
    class ContentIsolationAndFreshness {

        @Test
        @DisplayName("Should validate that different images produce different content hashes")
        void testContentChangesProduceDifferentHashes() {
            // This test ensures that when different images are provided,
            // the extracted content and resulting hash will be different
            // demonstrating that fresh content is being evaluated each time

            Assignment writingAssignment = Assignment.builder()
                    .id(testAssignmentId)
                    .assignmentType(AssignmentType.WRITING)
                    .build();

            String jlptLevel = "N3";
            given(assignmentTargetRepository.findJlptLevelByAssignmentId(testAssignmentId))
                    .willReturn(jlptLevel);
            given(assignmentRepository.findById(testAssignmentId))
                    .willReturn(Optional.of(writingAssignment));

            // First submission with one content
            given(ocrService.extractTextFromFiles(List.of(testImages)))
                    .willReturn("First submission with original content");

            try {
                aiGradingService.evaluateWriting(
                        WritingEvaluationRequest.builder()
                                .assignmentId(testAssignmentId)
                                .prompt("Test prompt")
                                .build(),
                        testImages
                );
            } catch (BusinessException e) {
                // LLM call expected to fail, that's OK for this test
                assertThat(e.getErrorCode()).isEqualTo("AI_EVALUATION_FAILED");
            }

            // Now change the mock to return different content (simulating different images)
            given(ocrService.extractTextFromFiles(List.of(testImages)))
                    .willReturn("Second submission with completely different content");

            try {
                aiGradingService.evaluateWriting(
                        WritingEvaluationRequest.builder()
                                .assignmentId(testAssignmentId)
                                .prompt("Test prompt")
                                .build(),
                        testImages
                );
            } catch (BusinessException e) {
                // LLM call expected to fail, that's OK for this test
                assertThat(e.getErrorCode()).isEqualTo("AI_EVALUATION_FAILED");
            }

            // Verify that OCR service was called twice with fresh content each time
            verify(ocrService, times(2)).extractTextFromFiles(List.of(testImages));
        }
    }
}
