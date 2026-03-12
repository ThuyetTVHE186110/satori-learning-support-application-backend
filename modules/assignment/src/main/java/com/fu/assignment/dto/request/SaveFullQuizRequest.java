package com.fu.assignment.dto.request;

import com.fu.assignment.entity.AssignmentStatus;
import com.fu.course.entity.Course;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Unified request DTO for creating or updating a full QUIZ assignment.
 * Contains both assignment metadata and all of its questions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaveFullQuizRequest implements AssignmentMetadataRequest {

    @NotBlank(message = "Title is required")
    private String title;

    private String description;
    private String instructions;

    /** Existing audio URL (if already uploaded). Cleared if a new audio file is provided. */
    private String audioUrl;

    @NotNull(message = "Due date is required")
    @Future(message = "Due date must be in the future")
    private LocalDateTime dueDate;

    private Integer timeLimitMinutes;

    @Builder.Default
    private Boolean lateSubmissionAllowed = false;

    @Builder.Default
    private Integer maxScore = 100;

    @Builder.Default
    private Integer passingScore = 60;

    @Builder.Default
    private Boolean shuffleQuestions = false;

    @Builder.Default
    private Boolean shuffleOptions = false;

    @Builder.Default
    private Boolean showCorrectAnswers = true;

    @Builder.Default
    private Boolean allowRetry = false;

    @Builder.Default
    private Integer maxRetries = 1;

    /** 
     * Target status for the assignment.
     * Use DRAFT for "Lưu bản nháp", PUBLISHED for "Xác nhận giao bài"
     */
    @Builder.Default
    @NotNull(message = "Status is required (DRAFT or PUBLISHED)")
    private AssignmentStatus status = AssignmentStatus.DRAFT;

    /** List of all questions in this quiz */
    @Valid
    private List<QuizQuestionDraftDto> questions;
}
