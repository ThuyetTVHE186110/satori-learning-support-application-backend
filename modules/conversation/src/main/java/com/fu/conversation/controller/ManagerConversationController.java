package com.fu.conversation.controller;

import com.fu.common.response.ApiResponse;
import com.fu.common.response.PageResponse;
import com.fu.conversation.dto.request.CreateThemeRequest;
import com.fu.conversation.dto.request.CreateTopicRequest;
import com.fu.conversation.dto.request.GenerateThemeRequest;
import com.fu.conversation.dto.request.UpdateThemeRequest;
import com.fu.conversation.dto.request.UpdateTopicRequest;
import com.fu.conversation.dto.response.ConversationThemeResponse;
import com.fu.conversation.dto.response.ConversationThemeSummaryResponse;
import com.fu.conversation.dto.response.ConversationTopicResponse;
import com.fu.conversation.dto.response.ConversationTopicSummaryResponse;
import com.fu.conversation.dto.response.GeneratedThemeSuggestionResponse;
import com.fu.conversation.service.ConversationThemeService;
import com.fu.conversation.service.ConversationTopicService;
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
 * REST Controller for Manager: Conversation themes and sub-topics.
 * Base path: /api/v1/manager/conversation
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/manager/conversation")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MANAGER')")
public class ManagerConversationController {

    private final ConversationThemeService themeService;
    private final ConversationTopicService topicService;

    // ==================== THEME (Chủ đề lớn) ====================

    /**
     * AI-generate conversation theme suggestions from a course.
     * POST /api/v1/manager/conversation/themes/generate
     */
    @PostMapping("/themes/generate")
    public ResponseEntity<ApiResponse<List<GeneratedThemeSuggestionResponse>>> generateThemes(
            @Valid @RequestBody GenerateThemeRequest request) {

        log.info("Generating theme suggestions for course: {}", request.getCourseId());
        List<GeneratedThemeSuggestionResponse> suggestions = themeService.generateThemeSuggestions(request);
        return ResponseEntity.ok(ApiResponse.success(
                "Generated " + suggestions.size() + " theme suggestions", suggestions));
    }

    /**
     * Create a new conversation theme.
     * POST /api/v1/manager/conversation/themes
     */
    @PostMapping("/themes")
    public ResponseEntity<ApiResponse<ConversationThemeResponse>> createTheme(
            @Valid @RequestBody CreateThemeRequest request,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} creating theme: {}", managerId, request.getTitle());
        ConversationThemeResponse response = themeService.createTheme(request, managerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Conversation theme created successfully", response));
    }

    /**
     * Get all themes (paginated, includes all statuses).
     * GET /api/v1/manager/conversation/themes
     */
    @GetMapping("/themes")
    public ResponseEntity<ApiResponse<PageResponse<ConversationThemeSummaryResponse>>> getAllThemes(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
                Sort.by("orderIndex").ascending());
        Page<ConversationThemeSummaryResponse> result = themeService.getAllThemes(pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(
                result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages())));
    }

    /**
     * Get a theme by ID.
     * GET /api/v1/manager/conversation/themes/{themeId}
     */
    @GetMapping("/themes/{themeId}")
    public ResponseEntity<ApiResponse<ConversationThemeResponse>> getTheme(
            @PathVariable UUID themeId) {

        return ResponseEntity.ok(ApiResponse.success(themeService.getTheme(themeId)));
    }

    /**
     * Update a theme.
     * PUT /api/v1/manager/conversation/themes/{themeId}
     */
    @PutMapping("/themes/{themeId}")
    public ResponseEntity<ApiResponse<ConversationThemeResponse>> updateTheme(
            @PathVariable UUID themeId,
            @Valid @RequestBody UpdateThemeRequest request,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        ConversationThemeResponse response = themeService.updateTheme(themeId, request, managerId);
        return ResponseEntity.ok(ApiResponse.success("Theme updated successfully", response));
    }

    /**
     * Publish a theme (status -> PUBLISHED).
     * PATCH /api/v1/manager/conversation/themes/{themeId}/publish
     */
    @PatchMapping("/themes/{themeId}/publish")
    public ResponseEntity<ApiResponse<ConversationThemeResponse>> publishTheme(
            @PathVariable UUID themeId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        ConversationThemeResponse response = themeService.updateStatus(
                themeId, Course.ContentStatus.PUBLISHED, managerId);
        return ResponseEntity.ok(ApiResponse.success("Theme published successfully", response));
    }

    /**
     * Archive a theme.
     * PATCH /api/v1/manager/conversation/themes/{themeId}/archive
     */
    @PatchMapping("/themes/{themeId}/archive")
    public ResponseEntity<ApiResponse<ConversationThemeResponse>> archiveTheme(
            @PathVariable UUID themeId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        ConversationThemeResponse response = themeService.updateStatus(
                themeId, Course.ContentStatus.ARCHIVED, managerId);
        return ResponseEntity.ok(ApiResponse.success("Theme archived successfully", response));
    }

    /**
     * Soft-delete a theme.
     * DELETE /api/v1/manager/conversation/themes/{themeId}
     */
    @DeleteMapping("/themes/{themeId}")
    public ResponseEntity<ApiResponse<Void>> deleteTheme(
            @PathVariable UUID themeId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        themeService.deleteTheme(themeId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Theme deleted successfully", null));
    }

    // ==================== TOPIC (Chủ đề nhỏ) ====================

    /**
     * Create a sub-topic under a theme.
     * POST /api/v1/manager/conversation/topics
     */
    @PostMapping("/topics")
    public ResponseEntity<ApiResponse<ConversationTopicResponse>> createTopic(
            @Valid @RequestBody CreateTopicRequest request,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} creating topic: {}", managerId, request.getTitle());
        ConversationTopicResponse response = topicService.createTopic(request, managerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Conversation topic created successfully", response));
    }

    /**
     * Get all topics with pagination.
     * GET /api/v1/manager/conversation/topics
     */
    @GetMapping("/topics")
    public ResponseEntity<ApiResponse<PageResponse<ConversationTopicSummaryResponse>>> getAllTopics(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
                Sort.by("orderIndex").ascending());
        Page<ConversationTopicSummaryResponse> result = topicService.getAllTopics(pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(
                result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages())));
    }

    /**
     * Get topics by theme.
     * GET /api/v1/manager/conversation/themes/{themeId}/topics
     */
    @GetMapping("/themes/{themeId}/topics")
    public ResponseEntity<ApiResponse<PageResponse<ConversationTopicSummaryResponse>>> getTopicsByTheme(
            @PathVariable UUID themeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
                Sort.by("orderIndex").ascending());
        Page<ConversationTopicSummaryResponse> result = topicService.getTopicsByTheme(themeId, pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(
                result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages())));
    }

    /**
     * Get a topic by ID.
     * GET /api/v1/manager/conversation/topics/{topicId}
     */
    @GetMapping("/topics/{topicId}")
    public ResponseEntity<ApiResponse<ConversationTopicResponse>> getTopic(
            @PathVariable UUID topicId) {

        return ResponseEntity.ok(ApiResponse.success(topicService.getTopic(topicId)));
    }

    /**
     * Update a topic.
     * PUT /api/v1/manager/conversation/topics/{topicId}
     */
    @PutMapping("/topics/{topicId}")
    public ResponseEntity<ApiResponse<ConversationTopicResponse>> updateTopic(
            @PathVariable UUID topicId,
            @Valid @RequestBody UpdateTopicRequest request,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        ConversationTopicResponse response = topicService.updateTopic(topicId, request, managerId);
        return ResponseEntity.ok(ApiResponse.success("Topic updated successfully", response));
    }

    /**
     * Publish a topic.
     * PATCH /api/v1/manager/conversation/topics/{topicId}/publish
     */
    @PatchMapping("/topics/{topicId}/publish")
    public ResponseEntity<ApiResponse<ConversationTopicResponse>> publishTopic(
            @PathVariable UUID topicId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        ConversationTopicResponse response = topicService.updateStatus(
                topicId, Course.ContentStatus.PUBLISHED, managerId);
        return ResponseEntity.ok(ApiResponse.success("Topic published successfully", response));
    }

    /**
     * Archive a topic (status -> ARCHIVED).
     * PATCH /api/v1/manager/conversation/topics/{topicId}/archive
     */
    @PatchMapping("/topics/{topicId}/archive")
    public ResponseEntity<ApiResponse<ConversationTopicResponse>> archiveTopic(
            @PathVariable UUID topicId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        ConversationTopicResponse response = topicService.updateStatus(
                topicId, Course.ContentStatus.ARCHIVED, managerId);
        return ResponseEntity.ok(ApiResponse.success("Topic archived successfully", response));
    }

    /**
     * Soft-delete a topic.
     * DELETE /api/v1/manager/conversation/topics/{topicId}
     */
    @DeleteMapping("/topics/{topicId}")
    public ResponseEntity<ApiResponse<Void>> deleteTopic(
            @PathVariable UUID topicId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        topicService.deleteTopic(topicId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Topic deleted successfully", null));
    }
}
