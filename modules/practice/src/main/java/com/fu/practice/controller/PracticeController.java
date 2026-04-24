package com.fu.practice.controller;

import com.fu.common.response.ApiResponse;
import com.fu.practice.dto.request.StartPracticeRequest;
import com.fu.practice.dto.request.SubmitAnswerRequest;
import com.fu.practice.dto.response.*;
import com.fu.practice.service.PracticeSessionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller cho tính năng luyện tập của học viên.
 * Base path: /api/v1/learner/practice
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/learner/practice")
@RequiredArgsConstructor
@PreAuthorize("hasRole('LEARNER')")
public class PracticeController {

    private final PracticeSessionService practiceSessionService;

    /**
     * Bắt đầu phiên luyện tập mới.
     * POST /api/v1/learner/practice/sessions
     */
    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<StartPracticeResponse>> startSession(
            @AuthenticationPrincipal(expression = "id") UUID userId,
            @Valid @RequestBody StartPracticeRequest request) {

        log.info("Học viên {} bắt đầu phiên luyện tập bài {}", userId, request.getLessonId());
        StartPracticeResponse response = practiceSessionService.startSession(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Phiên luyện tập đã được tạo.", response));
    }

    /**
     * Nộp câu trả lời cho một câu hỏi.
     * POST /api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer
     */
    @PostMapping("/sessions/{sessionId}/items/{itemId}/answer")
    public ResponseEntity<ApiResponse<AnswerFeedbackResponse>> submitAnswer(
            @AuthenticationPrincipal(expression = "id") UUID userId,
            @PathVariable UUID sessionId,
            @PathVariable UUID itemId,
            @Valid @RequestBody SubmitAnswerRequest request) {

        log.debug("Học viên {} nộp câu trả lời cho item {} trong session {}", userId, itemId, sessionId);
        AnswerFeedbackResponse response = practiceSessionService.submitAnswer(sessionId, itemId, userId, request);

        // AI feedback sinh async sau khi transaction commit — không block HTTP response.
        if (response.isSessionCompleted()) {
            practiceSessionService.generateAndSaveFeedbackAsync(sessionId);
        }

        return ResponseEntity.ok(ApiResponse.success("Đã nhận câu trả lời.", response));
    }

    /**
     * Xem tổng kết phiên luyện tập.
     * GET /api/v1/learner/practice/sessions/{sessionId}/summary
     */
    @GetMapping("/sessions/{sessionId}/summary")
    public ResponseEntity<ApiResponse<SessionSummaryResponse>> getSessionSummary(
            @AuthenticationPrincipal(expression = "id") UUID userId,
            @PathVariable UUID sessionId) {

        SessionSummaryResponse response = practiceSessionService.getSessionSummary(sessionId, userId);
        return ResponseEntity.ok(ApiResponse.success("Tổng kết phiên luyện tập.", response));
    }

    /**
     * Lấy danh sách vocab/grammar của bài học kèm mastery status.
     * Dùng để render item picker trước khi bắt đầu phiên luyện tập.
     * GET /api/v1/learner/practice/lessons/{lessonId}/items
     *
     * @param masteryLevel filter theo mức mastery (0=Chưa thành thạo, 1=Quen thuộc, 2=Nắm vững, 3=Thành thạo).
     *                     Null = trả về tất cả. Có thể truyền nhiều giá trị: ?masteryLevel=0&masteryLevel=1
     */
    @GetMapping("/lessons/{lessonId}/items")
    public ResponseEntity<ApiResponse<LessonItemsResponse>> getLessonItems(
            @AuthenticationPrincipal(expression = "id") UUID userId,
            @PathVariable UUID lessonId,
            @RequestParam(required = false) List<Integer> masteryLevel) {

        // Lọc bỏ các giá trị null/invalid trong list trước khi truyền vào service
        List<Integer> validFilter = (masteryLevel == null) ? null :
                masteryLevel.stream()
                        .filter(l -> l != null && l >= 0 && l <= 3)
                        .collect(java.util.stream.Collectors.toList());
        // List rỗng sau khi lọc = coi như không có filter
        if (validFilter != null && validFilter.isEmpty()) validFilter = null;

        LessonItemsResponse response = practiceSessionService.getLessonItems(userId, lessonId, validFilter);
        return ResponseEntity.ok(ApiResponse.success("Danh sách nội dung bài học.", response));
    }

    /**
     * Xem tiến độ luyện tập theo bài học.
     * GET /api/v1/learner/practice/lessons/{lessonId}/progress
     */
    @GetMapping("/lessons/{lessonId}/progress")
    public ResponseEntity<ApiResponse<LessonProgressResponse>> getLessonProgress(
            @AuthenticationPrincipal(expression = "id") UUID userId,
            @PathVariable UUID lessonId) {

        LessonProgressResponse response = practiceSessionService.getLessonProgress(userId, lessonId);
        return ResponseEntity.ok(ApiResponse.success("Tiến độ luyện tập bài học.", response));
    }

    /**
     * Xem tiến độ luyện tập theo khóa học.
     * GET /api/v1/learner/practice/courses/{courseId}/progress
     */
    @GetMapping("/courses/{courseId}/progress")
    public ResponseEntity<ApiResponse<List<LessonProgressResponse>>> getCourseProgress(
            @AuthenticationPrincipal(expression = "id") UUID userId,
            @PathVariable UUID courseId) {

        List<LessonProgressResponse> response = practiceSessionService.getCourseProgress(userId, courseId);
        return ResponseEntity.ok(ApiResponse.success("Tiến độ luyện tập khóa học.", response));
    }

    // ── Session History & Redo ─────────────────────────────────────────────

    /**
     * Danh sách phiên luyện tập (lịch sử), sắp xếp theo thời gian tạo giảm dần.
     * GET /api/v1/learner/practice/sessions?lessonId=...&page=0&size=10
     */
    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<Page<PracticeSessionSummaryResponse>>> listSessions(
            @AuthenticationPrincipal(expression = "id") UUID userId,
            @RequestParam(required = false) UUID lessonId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Page<PracticeSessionSummaryResponse> result = practiceSessionService.listSessions(
                userId, lessonId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(ApiResponse.success("Danh sách phiên luyện tập.", result));
    }

    /**
     * Chi tiết câu hỏi trong phiên luyện tập (bao gồm đáp án, kết quả, feedback).
     * GET /api/v1/learner/practice/sessions/{sessionId}/items
     */
    @GetMapping("/sessions/{sessionId}/items")
    public ResponseEntity<ApiResponse<List<PracticeItemDetailResponse>>> getSessionItems(
            @AuthenticationPrincipal(expression = "id") UUID userId,
            @PathVariable UUID sessionId) {

        List<PracticeItemDetailResponse> items = practiceSessionService.getSessionItems(sessionId, userId);
        return ResponseEntity.ok(ApiResponse.success("Chi tiết câu hỏi phiên luyện tập.", items));
    }

    /**
     * Làm lại phiên đã hoàn thành — tạo phiên mới với cùng cấu hình.
     * POST /api/v1/learner/practice/sessions/{sessionId}/redo
     */
    @PostMapping("/sessions/{sessionId}/redo")
    public ResponseEntity<ApiResponse<StartPracticeResponse>> redoSession(
            @AuthenticationPrincipal(expression = "id") UUID userId,
            @PathVariable UUID sessionId) {

        log.info("Học viên {} làm lại phiên luyện tập {}", userId, sessionId);
        StartPracticeResponse response = practiceSessionService.redoSession(sessionId, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Phiên luyện tập mới đã được tạo.", response));
    }

    /**
     * Tạo phiên ôn lại chỉ gồm các câu trả lời sai trong phiên đã hoàn thành.
     * POST /api/v1/learner/practice/sessions/{sessionId}/retry-wrong
     */
    @PostMapping("/sessions/{sessionId}/retry-wrong")
    public ResponseEntity<ApiResponse<StartPracticeResponse>> retryWrongItems(
            @AuthenticationPrincipal(expression = "id") UUID userId,
            @PathVariable UUID sessionId) {

        log.info("Học viên {} ôn lại câu sai của phiên {}", userId, sessionId);
        StartPracticeResponse response = practiceSessionService.retryWrongItems(sessionId, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Phiên ôn lại câu sai đã được tạo.", response));
    }
}
