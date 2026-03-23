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
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiGradingService {

    private final GoogleVisionOcrService ocrService;
    private final OpenAiChatModel chatModel;
    private final WritingAssignmentRepository writingAssignmentRepository;
    private final AssignmentTargetRepository assignmentTargetRepository;

    private static final String SYSTEM_PROMPT = """
            You are a professional Japanese Language Teacher.
            Your task is to evaluate a student's writing assignment based on JLPT standards.
            
            PRONOUNS:
            - Use "tôi" (I) and "bạn" (you) in all Vietnamese feedback. 
            - DO NOT use "em" or "thầy/cô" to address the student.
            
            INSTRUCTIONS:
            - Keep your evaluation and feedback CONCISE and DIRECT. Focus only on the most important points.
            - Analyze grammar, vocabulary, and Kanji accuracy according to the specified JLPT level.
            - Identify errors and provide corrections clearly.
            - All feedback MUST be written in Vietnamese.
            
            FORMATTING REQUIREMENTS:
            - Output MUST be in PLAIN TEXT only.
            - Do NOT use any Markdown formatting (no #, ##, ***, **, or ```).
            - STRUCTURE:
              1. General evaluation of the student's work (in Vietnamese).
              2. Detailed grammar and vocabulary analysis, pointing out specific errors and explaining them (in Vietnamese).
              3. Kanji corrections, if any (in Vietnamese).
              4. Advice and encouragement for the student's improvement (in Vietnamese).
            """;

    /**
     * Evaluates a writing submission's images and prompt synchronously.
     * Returns AI feedback in a DTO.
     * Throws BusinessException if evaluation fails.
     */
    public WritingEvaluationResponse evaluateWriting(WritingEvaluationRequest request, MultipartFile[] images) {
        log.info("Starting AI evaluation for assignment {}", request.getAssignmentId());

        // 1. Fetch JLPT level automatically
        String jlptLevel = assignmentTargetRepository.findJlptLevelByAssignmentId(request.getAssignmentId());
        if (jlptLevel == null) {
            jlptLevel = "N3"; // Fallback to intermediate
        }

        // 2. OCR text from images
        String extractedText = ocrService.extractTextFromFiles(List.of(images));
        if (extractedText.isBlank()) {
            throw new BusinessException("OCR_FAILED", "Không thể nhận diện được chữ viết từ hình ảnh của bạn. Vui lòng chụp rõ nét hơn.");
        }

        // 3. Prepare AI Prompt
        String userPrompt = String.format("""
                [Target JLPT Level]: %s
                
                [Assignment Prompt (Đề bài)]:
                %s
                
                [Student's Content (Bài làm trích xuất từ ảnh)]:
                ---
                %s
                ---
                Please evaluate the student's work in detailed Vietnamese as specified in the system prompt.
                """, jlptLevel, request.getPrompt(), extractedText);
        SystemMessage systemMessage = new SystemMessage(SYSTEM_PROMPT);
        UserMessage userMessage = new UserMessage(userPrompt);

        try {
            // 4. Call LLM
            log.info("User Prompt :", userMessage.toString());

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
