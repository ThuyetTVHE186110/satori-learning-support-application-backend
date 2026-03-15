package com.fu.assignment.controller;

import com.fu.assignment.dto.request.*;
import com.fu.assignment.dto.response.*;
import com.fu.assignment.entity.AssignmentType;
import com.fu.assignment.service.AssignmentService;
import com.fu.common.exception.BusinessException;
import com.fu.common.response.ApiResponse;
import com.fu.common.response.PageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/teacher/assignments")
@RequiredArgsConstructor
@Tag(name = "Teacher Assignment Management", description = "Operations for teachers to manage assignments, questions, and AI generation")
@PreAuthorize("hasRole('TEACHER')") // Only users with TEACHER role can access these endpoints
public class TeacherAssignmentController {

    private final AssignmentService assignmentService;
    private final ObjectMapper objectMapper;

    // Helper method to extract user ID from UserDetails (assuming username is the UUID string)
    private UUID getUserId(UserDetails userDetails) {
        return UUID.fromString(userDetails.getUsername());
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // AI QUESTION GENERATION
    // ═══════════════════════════════════════════════════════════════════════════


    /**
     * Use AI to generate quiz questions based on the teacher's prompt.
     * <p>
     * Returns a <strong>preview</strong> of generated questions — nothing is saved to the database.
     * The teacher reviews and edits on the UI, then confirms via the {@code saveFullQuiz} endpoint.
     *
     * @param request     AI generation parameters (teacherPrompt, questionCount, questionTypes, etc.)
     * @param userDetails the authenticated teacher
     * @return list of AI-generated quiz questions (preview only, not persisted)
     */
    @PostMapping("/questions/ai-generate")
    @Operation(summary = "Use AI to generate quiz questions from a prompt")
    public ApiResponse<List<QuizQuestionResponse>> aiGenerateQuestions(
            @Valid @RequestBody AiQuizGenerationRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.aiGenerateQuestions(request, getUserId(userDetails)));
    }

    /**
     * Use AI to generate an explanation for an existing quiz question's correct answer.
     * The explanation is saved directly to the question entity.
     *
     * @param id          the quiz question UUID
     * @param userDetails the authenticated teacher
     * @return the updated quiz question with AI-generated explanation
     */
    @PostMapping("/questions/{id}/ai-explain")
    @Operation(summary = "Use AI to generate an explanation for an existing quiz question")
    public ApiResponse<QuizQuestionResponse> aiGenerateExplanation(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.aiGenerateExplanation(id, getUserId(userDetails)));
    }

    /**
     * Use AI to generate an explanation from draft question data on the frontend form.
     * This does NOT save anything to the database — it only returns the generated explanation
     * for the teacher to preview before saving.
     *
     * @param request draft question data (questionText, options, correctAnswer, etc.)
     * @return AI-generated explanation text
     */
    @PostMapping("/questions/ai-explain-draft")
    @Operation(summary = "Use AI to generate an explanation from a draft question on the form")
    public ApiResponse<AiExplanationResponse> aiGenerateExplanationFromDraft(
            @Valid @RequestBody AiExplanationRequest request) {
        return ApiResponse.success(assignmentService.aiGenerateExplanationFromDraft(request));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ASSIGNMENT CRUD
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Create a new full quiz assignment with all questions in a single request.
     * This is an UPSERT operation — creates the assignment, its questions, and the mappings all at once.
     *
     * @param classId     the class UUID to associate the assignment with
     * @param request     full quiz data (title, description, dueDate, questions, etc.)
     * @param userDetails the authenticated teacher
     * @return the created assignment with all linked questions
     */
    @PostMapping(value = "/class/{classId}/quiz", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.APPLICATION_JSON_VALUE})
    @Operation(summary = "UPSERT: Create a new full quiz assignment with all questions")
    public ApiResponse<AssignmentResponse> createFullQuiz(
            @PathVariable UUID classId,
            @RequestPart("data") @Valid SaveFullQuizRequest request,
            @RequestPart(value = "audioFile", required = false) MultipartFile audioFile,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.saveFullQuiz(null, classId, request, audioFile, getUserId(userDetails)));
    }

    /**
     * Update an existing full quiz assignment and sync its questions.
     * This is an UPSERT operation — updates the assignment metadata, adds/updates questions,
     * and removes orphaned question links.
     *
     * @param id          the existing assignment UUID
     * @param request     updated quiz data (title, description, dueDate, questions, etc.)
     * @param userDetails the authenticated teacher (ownership verification)
     * @return the updated assignment with synced questions
     */
    @PutMapping(value = "/{id}/quiz", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.APPLICATION_JSON_VALUE})
    @Operation(summary = "UPSERT: Update an existing full quiz assignment and sync questions")
    public ApiResponse<AssignmentResponse> updateFullQuiz(
            @PathVariable UUID id,
            @RequestPart("data") @Valid SaveFullQuizRequest request,
            @RequestPart(value = "audioFile", required = false) MultipartFile audioFile,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.saveFullQuiz(id, null, request, audioFile, getUserId(userDetails)));
    }

    /**
     * Create a new WRITING assignment with writing content in a single request.
     *
     * @param classId     the class UUID to associate the assignment with
     * @param request     writing assignment data (title, dueDate, prompt, sampleAnswer, etc.)
     * @param userDetails the authenticated teacher
     * @return the created assignment
     */
    @PostMapping("/class/{classId}/writing")
    @Operation(summary = "UPSERT: Create a new full WRITING assignment with content")
    public ApiResponse<AssignmentResponse> createFullWriting(
            @PathVariable UUID classId,
            @Valid @RequestBody SaveFullWritingRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.saveFullWriting(
                null, classId, request, AssignmentType.WRITING, getUserId(userDetails)));
    }

    /**
     * Update an existing WRITING assignment and its writing content.
     *
     * @param id          the existing assignment UUID
     * @param request     updated writing assignment data
     * @param userDetails the authenticated teacher (ownership verification)
     * @return the updated assignment
     */
    @PutMapping("/{id}/writing")
    @Operation(summary = "UPSERT: Update an existing full WRITING assignment and sync content")
    public ApiResponse<AssignmentResponse> updateFullWriting(
            @PathVariable UUID id,
            @Valid @RequestBody SaveFullWritingRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.saveFullWriting(
                id, null, request, AssignmentType.WRITING, getUserId(userDetails)));
    }

    /**
     * Create a new TRANSLATION assignment with translation content in a single request.
     *
     * @param classId     the class UUID to associate the assignment with
     * @param request     translation assignment data (title, dueDate, prompt, sourceText, translationDirection, etc.)
     * @param userDetails the authenticated teacher
     * @return the created assignment
     */
    @PostMapping("/class/{classId}/translation")
    @Operation(summary = "UPSERT: Create a new full TRANSLATION assignment with content")
    public ApiResponse<AssignmentResponse> createFullTranslation(
            @PathVariable UUID classId,
            @Valid @RequestBody SaveFullWritingRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.saveFullWriting(
                null, classId, request, AssignmentType.TRANSLATION, getUserId(userDetails)));
    }

    /**
     * Update an existing TRANSLATION assignment and its content.
     *
     * @param id          the existing assignment UUID
     * @param request     updated translation assignment data
     * @param userDetails the authenticated teacher (ownership verification)
     * @return the updated assignment
     */
    @PutMapping("/{id}/translation")
    @Operation(summary = "UPSERT: Update an existing full TRANSLATION assignment and sync content")
    public ApiResponse<AssignmentResponse> updateFullTranslation(
            @PathVariable UUID id,
            @Valid @RequestBody SaveFullWritingRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.saveFullWriting(
                id, null, request, AssignmentType.TRANSLATION, getUserId(userDetails)));
    }

    /**
     * Get the full details of an assignment, including linked questions and submission count.
     *
     * @param id the assignment UUID
     * @return the assignment details with questions
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get assignment details")
    public ApiResponse<AssignmentResponse> getAssignment(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.getAssignment(id, getUserId(userDetails)));
    }

    /**
     * Soft-delete an assignment. Only DRAFT assignments can be deleted.
     *
     * @param id          the assignment UUID
     * @param userDetails the authenticated teacher (ownership verification)
     * @return empty success response
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an assignment (soft delete, DRAFT only)")
    public ApiResponse<Void> deleteAssignment(@PathVariable UUID id,
                                              @AuthenticationPrincipal UserDetails userDetails) {
        assignmentService.deleteAssignment(id, getUserId(userDetails));
        return ApiResponse.success(null);
    }

    /**
     * List all assignments created by the currently authenticated teacher.
     *
     * @param page        page number (1-based, default: 1)
     * @param size        page size (default: 10)
     * @param userDetails the authenticated teacher
     * @return paginated list of assignment summaries
     */
    @GetMapping
    @Operation(summary = "List all assignments created by current teacher")
    public ApiResponse<PageResponse<AssignmentSummaryResponse>> getMyAssignments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) com.fu.assignment.entity.AssignmentStatus status,
            @RequestParam(required = false) AssignmentType type,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (size <= 0) {
            throw new BusinessException("INVALID_PAGE_SIZE", "Kích thước trang phải lớn hơn 0.");
        }
        if (page <= 0) {
            throw new BusinessException("INVALID_PAGE_NUMBER", "Số trang phải lớn hơn hoặc bằng 1.");
        }
        Page<AssignmentSummaryResponse> result = assignmentService.getMyAssignments(getUserId(userDetails), title, status, type, PageRequest.of(page - 1, size));
        return ApiResponse.success(PageResponse.of(result.getContent(), result.getNumber() + 1, result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    /**
     * List all assignments belonging to a specific class.
     *
     * @param classId the class UUID
     * @param page    page number (1-based, default: 1)
     * @param size    page size (default: 10)
     * @return paginated list of assignment summaries for the class
     */
    @GetMapping("/class/{classId}")
    @Operation(summary = "List all assignments for a specific class")
    public ApiResponse<PageResponse<AssignmentSummaryResponse>> getClassAssignments(
            @PathVariable UUID classId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) com.fu.assignment.entity.AssignmentStatus status,
            @RequestParam(required = false) AssignmentType type,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (size <= 0) {
            throw new BusinessException("INVALID_PAGE_SIZE", "Kích thước trang phải lớn hơn 0.");
        }
        if (page <= 0) {
            throw new BusinessException("INVALID_PAGE_NUMBER", "Số trang phải lớn hơn hoặc bằng 1.");
        }
        Page<AssignmentSummaryResponse> result = assignmentService.getAssignmentsByClass(classId, title, status, type, PageRequest.of(page - 1, size), getUserId(userDetails));
        return ApiResponse.success(PageResponse.of(result.getContent(), result.getNumber() + 1, result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    @GetMapping("/class/{classId}/statistics")
    @Operation(summary = "Get assignment and submission statistics for a specific class")
    public ApiResponse<ClassAssignmentStatisticsResponse> getClassStatistics(
            @PathVariable UUID classId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.getClassAssignmentStatistics(classId, getUserId(userDetails)));
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // CLONE
    // ═══════════════════════════════════════════════════════════════════════════


    /**
     * Clone/duplicate an existing assignment to another class.
     * Deep-copies all content:
     * <ul>
     *   <li>QUIZ: duplicates all questions (new UUIDs) and assignment-question links</li>
     *   <li>WRITING/TRANSLATION: duplicates writing content (prompt, sampleAnswer, etc.)</li>
     * </ul>
     * The cloned assignment is created as DRAFT with no submissions.
     *
     * @param id          the source assignment UUID to clone from
     * @param request     clone parameters (targetClassId, newDueDate, optional newTitle)
     * @param userDetails the authenticated teacher (must own the source assignment)
     * @return the newly created cloned assignment
     */
    @PostMapping("/{id}/clone")
    @Operation(summary = "Clone/duplicate an assignment to another class")
    public ApiResponse<AssignmentResponse> cloneAssignment(
            @PathVariable UUID id,
            @Valid @RequestBody CloneAssignmentRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.cloneAssignment(id, request, getUserId(userDetails)));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SUBMISSIONS & GRADING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * List all submissions for a specific assignment (teacher view).
     *
     * @param id   the assignment UUID
     * @param page page number (1-based, default: 1)
     * @param size page size (default: 10)
     * @return paginated list of submission summaries
     */
    @GetMapping("/{id}/submissions")
    @Operation(summary = "List all submissions for an assignment")
    public ApiResponse<PageResponse<SubmissionSummaryResponse>> getSubmissions(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (size <= 0) {
            throw new BusinessException("INVALID_PAGE_SIZE", "Kích thước trang phải lớn hơn 0.");
        }
        if (page <= 0) {
            throw new BusinessException("INVALID_PAGE_NUMBER", "Số trang phải lớn hơn hoặc bằng 1.");
        }
        Page<SubmissionSummaryResponse> result = assignmentService.getSubmissionsByAssignment(id, PageRequest.of(page - 1, size), getUserId(userDetails));
        return ApiResponse.success(PageResponse.of(result.getContent(), result.getNumber() + 1, result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    /**
     * Get the full details of a submission, including answers, score, and grading info.
     *
     * @param id the submission UUID
     * @return the submission details
     */
    @GetMapping("/submissions/{id}")
    @Operation(summary = "Get submission details (answers, score)")
    public ApiResponse<SubmissionResponse> getSubmission(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.getSubmission(id, getUserId(userDetails)));
    }

    /**
     * Grade a WRITING or TRANSLATION submission.
     * The teacher provides a score and feedback. The submission status changes to GRADED.
     * Not applicable for QUIZ submissions (which are auto-graded).
     *
     * @param id          the submission UUID
     * @param request     grading data (teacherScore, teacherComment, feedback)
     * @param userDetails the authenticated teacher
     * @return the graded submission
     */
    @PostMapping("/submissions/{id}/grade-writing")
    @Operation(summary = "Grade a WRITING or TRANSLATION submission")
    public ApiResponse<SubmissionResponse> gradeWritingSubmission(
            @PathVariable UUID id,
            @Valid @RequestBody GradeWritingRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.gradeWritingSubmission(id, request, getUserId(userDetails)));
    }
}
