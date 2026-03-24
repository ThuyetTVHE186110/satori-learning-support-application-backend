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
    @Future(message = "Hạn nộp phải là một thời điểm trong tương lai (sau thời điểm hiện tại).")
    private LocalDateTime dueDate;

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
