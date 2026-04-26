package com.fu.conversation.controller;

import com.fu.common.response.ApiResponse;
import com.fu.common.response.PageResponse;
import com.fu.conversation.dto.request.CreateUserConversationRequest;
import com.fu.conversation.dto.request.UpdateUserConversationRequest;
import com.fu.conversation.dto.response.ConversationResponse;
import com.fu.conversation.dto.response.ConversationSummaryResponse;
import com.fu.conversation.dto.response.ConversationTopicSummaryResponse;
import com.fu.conversation.dto.response.UserConversationResponse;
import com.fu.conversation.service.ConversationService;
import com.fu.conversation.service.ConversationTopicService;
import com.fu.conversation.service.UserConversationService;
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
 * REST Controller for Learner: View published topics/conversations and manage personal conversations.
 * Base path: /api/v1/learner/conversation
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/learner/conversation")
@RequiredArgsConstructor
@PreAuthorize("hasRole('LEARNER')")
public class LearnerConversationController {

    private final ConversationTopicService topicService;
    private final ConversationService conversationService;
    private final UserConversationService userConversationService;

    // ==================== Browse Published Topics ====================

    /**
     * Get all published topics (optionally filtered by JLPT level). Supports keyword search.
     * GET /api/v1/learner/conversation/topics?level=N5&keyword=xxx
     */
    @GetMapping("/topics")
    public ResponseEntity<ApiResponse<PageResponse<ConversationTopicSummaryResponse>>> getPublishedTopics(
            @RequestParam(required = false) Course.JlptLevel level,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 50),
                Sort.by("order_index").ascending());

        Page<ConversationTopicSummaryResponse> result = topicService.searchPublishedTopics(
                keyword, level, pageable);

        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(
                result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages())));
    }

    // ==================== Browse Published Conversations ====================

    /**
     * Get published conversations for a topic (enriched with learner's practice history),
     * filtered by the learner's enrolled course's JLPT level.
     * GET /api/v1/learner/conversation/topics/{topicId}/conversations
     */
    @GetMapping("/topics/{topicId}/conversations")
    public ResponseEntity<ApiResponse<List<ConversationSummaryResponse>>> getConversationsByTopic(
            @PathVariable UUID topicId,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        List<ConversationSummaryResponse> conversations =
                conversationService.getPublishedConversationsByTopicAndUserJlptLevel(topicId, userId);
        return ResponseEntity.ok(ApiResponse.success(conversations));
    }

    /**
     * Get detail of a single published conversation.
     * GET /api/v1/learner/conversation/conversations/{conversationId}
     */
    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<ApiResponse<ConversationResponse>> getPublishedConversation(
            @PathVariable UUID conversationId) {

        return ResponseEntity.ok(ApiResponse.success(
                conversationService.getPublishedConversation(conversationId)));
    }

    // ==================== My Personal Conversations (Hội thoại tự tạo) ====================

    /**
     * Create a personal conversation.
     * POST /api/v1/learner/conversation/my-conversations
     */
    @PostMapping("/my-conversations")
    public ResponseEntity<ApiResponse<UserConversationResponse>> createMyConversation(
            @Valid @RequestBody CreateUserConversationRequest request,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        log.info("Learner {} creating personal conversation: {}", userId, request.getTitle());
        UserConversationResponse response = userConversationService.createConversation(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Personal conversation created successfully", response));
    }

    /**
     * Get all personal conversations (paginated).
     * GET /api/v1/learner/conversation/my-conversations
     */
    @GetMapping("/my-conversations")
    public ResponseEntity<ApiResponse<PageResponse<UserConversationResponse>>> getMyConversations(
            @AuthenticationPrincipal(expression = "id") UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 50),
                Sort.by("createdAt").descending());
        Page<UserConversationResponse> result = userConversationService.getMyConversations(userId, pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(
                result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages())));
    }

    /**
     * Get a specific personal conversation by ID.
     * GET /api/v1/learner/conversation/my-conversations/{conversationId}
     */
    @GetMapping("/my-conversations/{conversationId}")
    public ResponseEntity<ApiResponse<UserConversationResponse>> getMyConversation(
            @PathVariable UUID conversationId,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        return ResponseEntity.ok(ApiResponse.success(
                userConversationService.getMyConversation(conversationId, userId)));
    }

    /**
     * Update a personal conversation.
     * PUT /api/v1/learner/conversation/my-conversations/{conversationId}
     */
    @PutMapping("/my-conversations/{conversationId}")
    public ResponseEntity<ApiResponse<UserConversationResponse>> updateMyConversation(
            @PathVariable UUID conversationId,
            @Valid @RequestBody UpdateUserConversationRequest request,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        UserConversationResponse response = userConversationService.updateConversation(conversationId, request, userId);
        return ResponseEntity.ok(ApiResponse.success("Conversation updated successfully", response));
    }

    /**
     * Delete a personal conversation (hard delete - it's private data).
     * DELETE /api/v1/learner/conversation/my-conversations/{conversationId}
     */
    @DeleteMapping("/my-conversations/{conversationId}")
    public ResponseEntity<ApiResponse<Void>> deleteMyConversation(
            @PathVariable UUID conversationId,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        userConversationService.deleteConversation(conversationId, userId);
        return ResponseEntity.ok(ApiResponse.success("Conversation deleted successfully", null));
    }

    /**
     * Generate AI context for a personal conversation (vocabulary hints, grammar, expressions).
     * POST /api/v1/learner/conversation/my-conversations/{conversationId}/generate-context
     */
    @PostMapping("/my-conversations/{conversationId}/generate-context")
    public ResponseEntity<ApiResponse<UserConversationResponse>> generateContext(
            @PathVariable UUID conversationId,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        log.info("Learner {} generating context for conversation: {}", userId, conversationId);
        UserConversationResponse response = userConversationService.generateContext(conversationId, userId);
        return ResponseEntity.ok(ApiResponse.success("Context generated successfully", response));
    }
}
