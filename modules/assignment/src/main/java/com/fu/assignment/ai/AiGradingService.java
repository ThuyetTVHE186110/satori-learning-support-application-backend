package com.fu.assignment.ai;

import com.fu.assignment.ai.GoogleVisionOcrService;
import com.fu.assignment.dto.request.WritingEvaluationRequest;
import com.fu.assignment.dto.response.WritingEvaluationResponse;
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

    @Value("classpath:prompts/writing-evaluation-system.st")
    private Resource systemPromptResource;

    @Value("classpath:prompts/writing-evaluation-user.st")
    private Resource userPromptResource;

    /**
     * Evaluates a writing submission's images and prompt synchronously.
     * Returns AI feedback in a DTO.
     * Throws BusinessException if evaluation fails.
     */
    public WritingEvaluationResponse evaluateWriting(WritingEvaluationRequest request, MultipartFile[] images) {
        log.info("Starting AI evaluation for assignment {}", request.getAssignmentId());

        // 1. Fetch JLPT level automatically
        String jlptLevel = assignmentTargetRepository.findJlptLevelByAssignmentId(request.getAssignmentId());
        // 2. OCR text from images
        String extractedText = ocrService.extractTextFromFiles(List.of(images));
        if (extractedText.isBlank()) {
            throw new BusinessException("OCR_FAILED", "Không thể nhận diện được chữ viết từ hình ảnh của bạn. Vui lòng chụp rõ nét hơn.");
        }

        // 3. Prepare AI Prompt using PromptTemplate
        PromptTemplate systemTemplate = new PromptTemplate(systemPromptResource);
        SystemMessage systemMessage = new SystemMessage(systemTemplate.render(Map.of("lb", "{", "rb", "}")));

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
            // 4. Call LLM
            log.info("AI Request: [{}] [Content Length: {}]", jlptLevel, extractedText.length());

            String feedback = ChatClient.create(chatModel)
                    .prompt(new Prompt(List.of(systemMessage, userMessage)))
                    .call()
                    .content();

            return WritingEvaluationResponse.builder()
                    .feedback(feedback)
                    .build();

        } catch (Exception e) {
            log.error("Failed to evaluate writing for assignment {}", request.getAssignmentId(), e);
            throw new BusinessException("AI_EVALUATION_FAILED", "Có lỗi xảy ra trong quá trình AI nhận xét bài viết. Vui lòng thử lại sau.");
        }
    }
}
