package com.fu.conversation.controller;

import com.fu.common.response.ApiResponse;
import com.fu.common.response.PageResponse;
import com.fu.conversation.dto.request.CreateUserTopicRequest;
import com.fu.conversation.dto.request.UpdateUserTopicRequest;
import com.fu.conversation.dto.response.ConversationThemeSummaryResponse;
import com.fu.conversation.dto.response.ConversationTopicResponse;
import com.fu.conversation.dto.response.ConversationTopicSummaryResponse;
import com.fu.conversation.dto.response.UserConversationTopicResponse;
import com.fu.conversation.service.ConversationThemeService;
import com.fu.conversation.service.ConversationTopicService;
import com.fu.conversation.service.UserConversationTopicService;
import com.fu.course.entity.Course;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller for Learner: View published topics and manage personal topics.
 * Base path: /api/v1/learner/conversation
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/learner/conversation")
@RequiredArgsConstructor
@PreAuthorize("hasRole('LEARNER')")
public class LearnerConversationController {

    private final ConversationThemeService themeService;
    private final ConversationTopicService topicService;
    private final UserConversationTopicService userTopicService;

    // ==================== Browse Published Themes/Topics ====================

    /**
     * Get all published themes (optionally filtered by JLPT level). Supports keyword search.
     * GET /api/v1/learner/conversation/themes?level=N5&keyword=xxx
     */
    @GetMapping("/themes")
    public ResponseEntity<ApiResponse<PageResponse<ConversationThemeSummaryResponse>>> getPublishedThemes(
            @RequestParam(required = false) Course.JlptLevel level,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 50),
                Sort.by("orderIndex").ascending());

        Page<ConversationThemeSummaryResponse> result;
        if (keyword != null && !keyword.isBlank()) {
            result = themeService.searchPublishedThemes(keyword.trim(), pageable);
        } else {
            result = themeService.getPublishedThemes(level, pageable);
        }

        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(
                result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages())));
    }

    /**
     * Get published topics for a theme.
     * GET /api/v1/learner/conversation/themes/{themeId}/topics
     */
    @GetMapping("/themes/{themeId}/topics")
    public ResponseEntity<ApiResponse<List<ConversationTopicSummaryResponse>>> getTopicsByTheme(
            @PathVariable UUID themeId,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        List<ConversationTopicSummaryResponse> topics =
                topicService.getPublishedTopicsByTheme(themeId, userId);
        return ResponseEntity.ok(ApiResponse.success(topics));
    }

    /**
     * Get detail of a single published topic.
     * GET /api/v1/learner/conversation/topics/{topicId}
     */
    @GetMapping("/topics/{topicId}")
    public ResponseEntity<ApiResponse<ConversationTopicResponse>> getPublishedTopic(
            @PathVariable UUID topicId) {

        return ResponseEntity.ok(ApiResponse.success(
                topicService.getPublishedTopic(topicId)));
    }

    // ==================== My Personal Topics (Chủ đề tự tạo) ====================

    /**
     * Create a personal conversation topic.
     * POST /api/v1/learner/conversation/my-topics
     */
    @PostMapping("/my-topics")
    public ResponseEntity<ApiResponse<UserConversationTopicResponse>> createMyTopic(
            @Valid @RequestBody CreateUserTopicRequest request,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        log.info("Learner {} creating personal topic: {}", userId, request.getTitle());
        UserConversationTopicResponse response = userTopicService.createTopic(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Personal topic created successfully", response));
    }

    /**
     * Get all personal topics (paginated).
     * GET /api/v1/learner/conversation/my-topics
     */
    @GetMapping("/my-topics")
    public ResponseEntity<ApiResponse<PageResponse<UserConversationTopicResponse>>> getMyTopics(
            @AuthenticationPrincipal(expression = "id") UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 50),
                Sort.by("createdAt").descending());
        Page<UserConversationTopicResponse> result = userTopicService.getMyTopics(userId, pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(
                result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages())));
    }

    /**
     * Get a specific personal topic by ID.
     * GET /api/v1/learner/conversation/my-topics/{topicId}
     */
    @GetMapping("/my-topics/{topicId}")
    public ResponseEntity<ApiResponse<UserConversationTopicResponse>> getMyTopic(
            @PathVariable UUID topicId,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        return ResponseEntity.ok(ApiResponse.success(
                userTopicService.getMyTopic(topicId, userId)));
    }

    /**
     * Update a personal topic.
     * PUT /api/v1/learner/conversation/my-topics/{topicId}
     */
    @PutMapping("/my-topics/{topicId}")
    public ResponseEntity<ApiResponse<UserConversationTopicResponse>> updateMyTopic(
            @PathVariable UUID topicId,
            @Valid @RequestBody UpdateUserTopicRequest request,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        UserConversationTopicResponse response = userTopicService.updateTopic(topicId, request, userId);
        return ResponseEntity.ok(ApiResponse.success("Topic updated successfully", response));
    }

    /**
     * Delete a personal topic (hard delete - it's private data).
     * DELETE /api/v1/learner/conversation/my-topics/{topicId}
     */
    @DeleteMapping("/my-topics/{topicId}")
    public ResponseEntity<ApiResponse<Void>> deleteMyTopic(
            @PathVariable UUID topicId,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        userTopicService.deleteTopic(topicId, userId);
        return ResponseEntity.ok(ApiResponse.success("Topic deleted successfully", null));
    }

    /**
     * Generate AI context for a personal topic (vocabulary hints, grammar, expressions).
     * POST /api/v1/learner/conversation/my-topics/{topicId}/generate-context
     */
    @PostMapping("/my-topics/{topicId}/generate-context")
    public ResponseEntity<ApiResponse<UserConversationTopicResponse>> generateContext(
            @PathVariable UUID topicId,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        log.info("Learner {} generating context for topic: {}", userId, topicId);
        UserConversationTopicResponse response = userTopicService.generateContext(topicId, userId);
        return ResponseEntity.ok(ApiResponse.success("Context generated successfully", response));
    }
}
