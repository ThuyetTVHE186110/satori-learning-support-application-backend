package com.fu.assignment.controller;

import com.cloudinary.Api;
import com.fu.assignment.dto.request.SubmitAssignmentRequest;
import com.fu.assignment.dto.request.SubmitWritingRequest;
import com.fu.assignment.dto.response.*;
import com.fu.assignment.service.AssignmentService;
import com.fu.common.exception.BusinessException;
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
    @Operation(summary = "List all published and closed assignments for the learner's active classes")
    public ApiResponse<PageResponse<LearnerAssignmentSummaryResponse>> getAvailableAssignments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) com.fu.assignment.entity.AssignmentType type,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (size <= 0) {
            throw new BusinessException("INVALID_PAGE_SIZE", "Kích thước trang phải lớn hơn 0.");
        }
        if (page <= 0) {
            throw new BusinessException("INVALID_PAGE_NUMBER", "Số trang phải lớn hơn hoặc bằng 1.");
        }
        Page<LearnerAssignmentSummaryResponse> result = assignmentService.getLearnerAssignments(getUserId(userDetails), title, type, PageRequest.of(page - 1, size));
        return ApiResponse.success(PageResponse.of(result.getContent(), result.getNumber() + 1, result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // TAKE ASSIGNMENT
    // ═══════════════════════════════════════════════════════════════════════════

    @PostMapping("/{assignmentId}/start")
    @Operation(summary = "Start or resume an assignment attempt (returns current session/submission)")
    public ApiResponse<LearnerAssignmentResponse> startAssignment(@PathVariable UUID assignmentId,
                                                                  @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.startAssignment(assignmentId, getUserId(userDetails)));
    }

    @PostMapping("/{assignmentId}/submit-quiz")
    @Operation(summary = "Submit answers for a QUIZ assignment (auto-scored)")
    public ApiResponse<SubmissionResponse> submitQuizAssignment(
            @PathVariable UUID assignmentId,
            @Valid @RequestBody SubmitAssignmentRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.submitQuiz(assignmentId, request, getUserId(userDetails)));
    }

    @PostMapping(value = "/{assignmentId}/submit-writing", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Submit answers for a WRITING or TRANSLATION assignment (supports image uploads)")
    public ApiResponse<SubmissionResponse> submitWritingAssignment(
            @PathVariable UUID assignmentId,
            @RequestPart(value = "data") SubmitWritingRequest request,
            @RequestPart(value = "images", required = false) MultipartFile[] images,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.submitWriting(assignmentId, request, images, getUserId(userDetails)));
    }

    @PostMapping("/{assignmentId}/cancel")
    @Operation(summary = "Cancel a SUBMITTED assignment to return it to IN_PROGRESS for editing")
    public ApiResponse<SubmissionResponse> cancelSubmission(
            @PathVariable UUID assignmentId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.cancelSubmission(assignmentId, getUserId(userDetails)));
    }
    // ═══════════════════════════════════════════════════════════════════════════
    // SUBMISSION HISTORY
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping("/submissions")
    @Operation(summary = "List all submissions made by the learner")
    public ApiResponse<PageResponse<SubmissionSummaryResponse>> getMySubmissions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (size <= 0) {
            throw new BusinessException("INVALID_PAGE_SIZE", "Kích thước trang phải lớn hơn 0.");
        }
        if (page <= 0) {
            throw new BusinessException("INVALID_PAGE_NUMBER", "Số trang phải lớn hơn hoặc bằng 1.");
        }
        Page<SubmissionSummaryResponse> result = assignmentService.getMySubmissions(getUserId(userDetails), PageRequest.of(page - 1, size));
        return ApiResponse.success(PageResponse.of(result.getContent(), result.getNumber() + 1, result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    @GetMapping("/submissions/{id}")
    @Operation(summary = "Get details of a specific submission")
    public ApiResponse<SubmissionResponse> getMySubmissionDetails(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(assignmentService.getMySubmission(id, getUserId(userDetails)));
    }
}
