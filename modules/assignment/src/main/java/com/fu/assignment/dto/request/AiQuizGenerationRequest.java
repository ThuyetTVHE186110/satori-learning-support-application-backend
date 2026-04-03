package com.fu.assignment.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for AI-based quiz generation.
 * Teacher provides either an uploaded file (PDF/DOCX) OR raw text, plus a custom prompt.
 * One of file or rawText must be non-null.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiQuizGenerationRequest {

    /**
     * Teacher's custom instruction for AI.
     * Example: "Focus on て-form conjugation, use N4 vocabulary only"
     */
    @NotNull(message = "Yêu cầu giáo viên phải cung cấp yêu cầu cho AI để tạo câu hỏi.")
    @Size(min = 5, max = 2000, message = "Yêu cầu của giáo viên phải có độ dài từ 5 đến 2000 ký tự.")
    private String teacherPrompt;

    /**
     * The ID of the class context for this quiz generation.
     * Used to resolve course terminology (vocabulary, grammar) by lesson index.
     */
    @NotNull(message = "Class ID is required to fetch course context")
    private UUID classId;

    /**
     * Number of questions to generate.
     */
    @NotNull(message = "Question count is required")
    private Integer questionCount;

    /**
     * Types of questions to generate.
     * Supported: 'multiple_choice', 'fill_blank', 'true_false'
     * Defaults to ['multiple_choice'] if not provided.
     */
    @Builder.Default
    private List<String> questionTypes = List.of("multiple_choice");

    /**
     * JLPT level for generated questions (optional).
     * If provided, AI will try to match the level.
     */
    private com.fu.course.entity.Course.JlptLevel jlptLevel;

    /**
     * Specific lessons to generate questions from.
     * If provided, these lessons will be used for context (Vocabulary/Grammar).
     */
    @NotNull(message = "Danh sách bài học không được để trống")
    @NotEmpty(message = "Danh sách bài học không được để trống")
    private List<UUID> lessonIds;
}
