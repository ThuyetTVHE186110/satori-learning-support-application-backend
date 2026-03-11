package com.fu.assignment.controller;

import com.fu.assignment.dto.request.SubmitAssignmentRequest;
import com.fu.assignment.dto.request.SubmitWritingRequest;
import com.fu.assignment.dto.response.*;
import com.fu.assignment.service.AssignmentService;
import com.fu.common.response.ApiResponse;
import com.fu.common.response.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/learner/assignments")
@RequiredArgsConstructor
@Tag(name = "Learner Assignment View", description = "Operations for learners to view and submit assignments")
@PreAuthorize("hasRole('LEARNER')") // Only learners can access these endpoints
public class LearnerAssignmentController {

    private final AssignmentService assignmentService;

    private UUID getUserId(UserDetails userDetails) {
        return UUID.fromString(userDetails.getUsername());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BROWSE ASSIGNMENTS
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping
    @Operation(summary = "List all published assignments for the learner's active classes")
    public PageResponse<AssignmentSummaryResponse> getAvailableAssignments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal UserDetails userDetails) {
        Page<AssignmentSummaryResponse> result = assignmentService.getLearnerAssignments(getUserId(userDetails), PageRequest.of(page - 1, size));
        return PageResponse.of(result.getContent(), result.getNumber() + 1, result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get assignment details and quiz questions (without correct answers)")
    public ApiResponse<LearnerAssignmentResponse> getAssignmentDetails(@PathVariable UUID id,
                                                                       @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.getAssignmentForLearner(id, getUserId(userDetails)));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TAKE ASSIGNMENT
    // ═══════════════════════════════════════════════════════════════════════════

    @PostMapping("/{id}/start")
    @Operation(summary = "Start an assignment attempt")
    public ApiResponse<SubmissionResponse> startAssignment(@PathVariable UUID id,
                                                           @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.startAssignment(id, getUserId(userDetails)));
    }

    @PostMapping("/submit")
    @Operation(summary = "Submit answers for a QUIZ assignment (auto-scored)")
    public ApiResponse<SubmissionResponse> submitQuizAssignment(
            @Valid @RequestBody SubmitAssignmentRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.submitQuiz(request, getUserId(userDetails)));
    }

    @PostMapping(value = "/{id}/submit-writing", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Submit answers for a WRITING or TRANSLATION assignment (supports image uploads)")
    public ApiResponse<SubmissionResponse> submitWritingAssignment(
            @PathVariable UUID id,
            @RequestParam(required = false) String writtenAnswer,
            @RequestParam(required = false) Integer timeSpentSeconds,
            @RequestPart(value = "images", required = false) MultipartFile[] images,
            @AuthenticationPrincipal UserDetails userDetails) {

        SubmitWritingRequest request = SubmitWritingRequest.builder()
                .assignmentId(id)
                .writtenAnswer(writtenAnswer)
                .timeSpentSeconds(timeSpentSeconds)
                .build();

        return ApiResponse.success(assignmentService.submitWriting(request, images, getUserId(userDetails)));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SUBMISSION HISTORY
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping("/submissions")
    @Operation(summary = "List all submissions made by the learner")
    public PageResponse<SubmissionSummaryResponse> getMySubmissions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal UserDetails userDetails) {
        Page<SubmissionSummaryResponse> result = assignmentService.getMySubmissions(getUserId(userDetails), PageRequest.of(page - 1, size));
        return PageResponse.of(result.getContent(), result.getNumber() + 1, result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping("/submissions/{id}")
    @Operation(summary = "Get details of a specific submission")
    public ApiResponse<SubmissionResponse> getMySubmissionDetails(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.getMySubmission(id, getUserId(userDetails)));
    }
}
