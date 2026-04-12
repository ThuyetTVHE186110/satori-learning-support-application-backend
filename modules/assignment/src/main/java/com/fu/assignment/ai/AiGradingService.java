package com.fu.assignment.ai;

import com.fu.assignment.ai.GoogleVisionOcrService;
import com.fu.assignment.dto.request.WritingEvaluationRequest;
import com.fu.assignment.dto.response.WritingEvaluationResponse;
import com.fu.assignment.entity.Assignment;
import com.fu.assignment.entity.AssignmentType;
import com.fu.assignment.repository.AssignmentRepository;
import com.fu.assignment.repository.AssignmentTargetRepository;
import com.fu.assignment.repository.WritingAssignmentRepository;
import com.fu.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiGradingService {

    private final GoogleVisionOcrService ocrService;
    private final OpenAiChatModel chatModel;
    private final WritingAssignmentRepository writingAssignmentRepository;
    private final AssignmentTargetRepository assignmentTargetRepository;
    private final AssignmentRepository assignmentRepository;

    @Value("classpath:prompts/writing-evaluation-system.st")
    private Resource writingSystemPromptResource;

    @Value("classpath:prompts/writing-evaluation-user.st")
    private Resource writingUserPromptResource;

    @Value("classpath:prompts/translation-evaluation-system.st")
    private Resource translationSystemPromptResource;

    @Value("classpath:prompts/translation-evaluation-user.st")
    private Resource translationUserPromptResource;

    /**
     * Evaluates a writing or translation submission's images and prompt synchronously.
     * Automatically detects assignment type (WRITING or TRANSLATION) and routes to appropriate evaluation.
     * Returns AI feedback in a DTO.
     * Throws BusinessException if evaluation fails.
     */
    public WritingEvaluationResponse evaluateWriting(WritingEvaluationRequest request, MultipartFile[] images) {
        log.info("Starting AI evaluation for assignment {}", request.getAssignmentId());
        

        // 1. Fetch JLPT level automatically
        String jlptLevel = assignmentTargetRepository.findJlptLevelByAssignmentId(request.getAssignmentId());

        // 2. Fetch assignment to determine type
        Assignment assignment = assignmentRepository.findById(request.getAssignmentId())
                .orElseThrow(() -> new BusinessException("ASSIGNMENT_NOT_FOUND", 
                    "Không tìm thấy bài tập với ID: " + request.getAssignmentId()));

        // 3. Extract text from images via OCR
        String extractedText = ocrService.extractTextFromFiles(List.of(images));
        
        // 4. Validate extracted content for freshness
        validateExtractedContent(extractedText);

        // 5. Select prompts based on assignment type
        Resource systemPromptResource;
        Resource userPromptResource;

        if (assignment.getAssignmentType() == AssignmentType.TRANSLATION) {
            systemPromptResource = translationSystemPromptResource;
            userPromptResource = translationUserPromptResource;
            log.info("Using TRANSLATION evaluation prompts for assignment {}", request.getAssignmentId());
        } else if (assignment.getAssignmentType() == AssignmentType.WRITING) {
            systemPromptResource = writingSystemPromptResource;
            userPromptResource = writingUserPromptResource;
            log.info("Using WRITING evaluation prompts for assignment {}", request.getAssignmentId());
        } else {
            throw new BusinessException("INVALID_ASSIGNMENT_TYPE",
                "Bài tập phải có kiểu WRITING hoặc TRANSLATION để đánh giá.");
        }

        // 6. Prepare AI Prompt using PromptTemplate with extraction timestamp
        PromptTemplate systemTemplate = new PromptTemplate(systemPromptResource);
        SystemMessage systemMessage = new SystemMessage(systemTemplate.render(Map.of(
            "jlptLevel", jlptLevel,
            "lb", "{",
            "rb", "}"
        )));

        PromptTemplate userTemplate = new PromptTemplate(userPromptResource);
        String userPrompt = userTemplate.render(Map.of(
            "jlptLevel", jlptLevel,
            "assignmentPrompt", request.getPrompt(),
            "studentContent", extractedText,
            "lb", "{",
            "rb", "}"
        ));
        UserMessage userMessage = new UserMessage(userPrompt);

        try {
            // 7. Call LLM

            String feedback = ChatClient.create(chatModel)
                    .prompt(new Prompt(List.of(systemMessage, userMessage)))
                    .call()
                    .content();

            return WritingEvaluationResponse.builder()
                    .feedback(feedback)
                    .build();

        } catch (Exception e) {
            log.error("Failed to evaluate {} assignment {}",
                assignment.getAssignmentType(), request.getAssignmentId(), e);
            throw new BusinessException("AI_EVALUATION_FAILED", 
                "Có lỗi xảy ra trong quá trình AI nhận xét bài tập. Vui lòng thử lại sau.");
        }
    }
    
    /**
     * Validates that extracted content meets minimum requirements for evaluation.
     * Ensures the content is not empty and has reasonable length.
     */
    private void validateExtractedContent(String extractedText) {
        if (extractedText.isBlank()) {
            throw new BusinessException("OCR_FAILED", 
                "Không thể nhận diện được chữ viết từ hình ảnh của bạn. Vui lòng chụp rõ nét hơn.");
        }
    }

}
