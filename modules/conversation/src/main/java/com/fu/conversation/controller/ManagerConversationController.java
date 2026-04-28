package com.fu.conversation.controller;

import com.fu.common.exception.BusinessException;
import com.fu.common.response.ApiResponse;
import com.fu.common.response.PageResponse;
import com.fu.conversation.dto.request.CreateConversationRequest;
import com.fu.conversation.dto.request.CreateTopicRequest;
import com.fu.conversation.dto.request.GenerateConversationRequest;
import com.fu.conversation.dto.request.GenerateTopicRequest;
import com.fu.conversation.dto.request.UpdateConversationRequest;
import com.fu.conversation.dto.request.UpdateTopicRequest;
import com.fu.conversation.dto.response.ConversationResponse;
import com.fu.conversation.dto.response.ConversationSummaryResponse;
import com.fu.conversation.dto.response.ConversationTopicResponse;
import com.fu.conversation.dto.response.ConversationTopicSummaryResponse;
import com.fu.conversation.dto.response.GeneratedConversationSuggestionResponse;
import com.fu.conversation.dto.response.GeneratedTopicSuggestionResponse;
import com.fu.conversation.service.ConversationService;
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
 * REST Controller for Manager: Conversation topics and conversations.
 * Base path: /api/v1/manager/conversation
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/manager/conversation")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CONTENT_MANAGER')")
public class ManagerConversationController {

    private final ConversationTopicService topicService;
    private final ConversationService conversationService;

    // ==================== TOPIC (Chủ đề lớn) ====================

    /**
     * AI-generate conversation topic suggestions.
     * POST /api/v1/manager/conversation/topics/generate
     */
    @PostMapping("/topics/generate")
    public ResponseEntity<ApiResponse<List<GeneratedTopicSuggestionResponse>>> generateTopics(
            @Valid @RequestBody GenerateTopicRequest request) {

        log.info("Generating topic suggestions (jlpt={}, category={})", request.getJlptLevel(), request.getCategory());
        List<GeneratedTopicSuggestionResponse> suggestions = topicService.generateTopicSuggestions(request);
        return ResponseEntity.ok(ApiResponse.success(
                "Generated " + suggestions.size() + " topic suggestions", suggestions));
    }

    /**
     * Create a new conversation topic.
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
     * Get all topics (paginated). Supports filtering by keyword and/or status.
     * GET /api/v1/manager/conversation/topics?page=0&size=10&keyword=xxx&status=DRAFT
     */
    @GetMapping("/topics")
    public ResponseEntity<ApiResponse<PageResponse<ConversationTopicSummaryResponse>>> getAllTopics(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Course.ContentStatus status) {

        if(keyword != null && keyword.trim().length() > 100){
            throw new BusinessException("INVALID_KEYWORD_LENGTH", "Từ khóa tìm kiếm không được quá 100 ký tự!");
        }

        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
                Sort.by("order_index").ascending());

        Page<ConversationTopicSummaryResponse> result;
        if ((keyword != null && !keyword.isBlank()) || status != null) {
            // Either keyword or status (or both) provided
            result = topicService.searchTopics(keyword, status, pageable);
        } else {
            // No filters provided - show all
            result = topicService.getAllTopics(pageable);
        }

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
     * Publish a topic (status -> PUBLISHED).
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
     * Archive a topic.
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

    // ==================== CONVERSATION (Hội thoại cụ thể) ====================

    /**
     * AI-generate conversation suggestions for a topic.
     * POST /api/v1/manager/conversation/conversations/generate
     */
    @PostMapping("/conversations/generate")
    public ResponseEntity<ApiResponse<List<GeneratedConversationSuggestionResponse>>> generateConversations(
            @Valid @RequestBody GenerateConversationRequest request) {

        log.info("Generating conversation suggestions for topic: {}", request.getTopicId());
        List<GeneratedConversationSuggestionResponse> suggestions =
                conversationService.generateConversationSuggestions(request);
        return ResponseEntity.ok(ApiResponse.success(
                "Generated " + suggestions.size() + " conversation suggestions", suggestions));
    }

    /**
     * Create a conversation under a topic.
     * POST /api/v1/manager/conversation/conversations
     */
    @PostMapping("/conversations")
    public ResponseEntity<ApiResponse<ConversationResponse>> createConversation(
            @Valid @RequestBody CreateConversationRequest request,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} creating conversation: {}", managerId, request.getTitle());
        ConversationResponse response = conversationService.createConversation(request, managerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Conversation created successfully", response));
    }

    /**
     * Get all conversations (paginated).
     * GET /api/v1/manager/conversation/conversations
     */
    @GetMapping("/conversations")
    public ResponseEntity<ApiResponse<PageResponse<ConversationSummaryResponse>>> getAllConversations(
            @RequestParam(required = false) Course.JlptLevel level,
            @RequestParam(required = false) Course.ContentStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        if(keyword != null && keyword.trim().length() > 100){
            throw new BusinessException("INVALID_KEYWORD_LENGTH", "Từ khóa tìm kiếm không được quá 100 ký tự!");
        }
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<ConversationSummaryResponse> result = conversationService.getAllConversations(
                keyword, level, status, pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(
                result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages())));
    }

    /**
     * Get conversations by topic.
     * GET /api/v1/manager/conversation/topics/{topicId}/conversations
     */
    @GetMapping("/topics/{topicId}/conversations")
    public ResponseEntity<ApiResponse<PageResponse<ConversationSummaryResponse>>> getConversationsByTopic(
            @PathVariable UUID topicId,
            @RequestParam(required = false) Course.JlptLevel level,
            @RequestParam(required = false) Course.ContentStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        if(keyword != null && keyword.trim().length() > 100){
            throw new BusinessException("INVALID_KEYWORD_LENGTH", "Từ khóa tìm kiếm không được quá 100 ký tự!");
        }
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<ConversationSummaryResponse> result =
                conversationService.getConversationsByTopic(topicId, keyword, level, status, pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(
                result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages())));
    }

    /**
     * Get a conversation by ID.
     * GET /api/v1/manager/conversation/conversations/{conversationId}
     */
    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<ApiResponse<ConversationResponse>> getConversation(
            @PathVariable UUID conversationId) {

        return ResponseEntity.ok(ApiResponse.success(conversationService.getConversation(conversationId)));
    }

    /**
     * Update a conversation.
     * PUT /api/v1/manager/conversation/conversations/{conversationId}
     */
    @PutMapping("/conversations/{conversationId}")
    public ResponseEntity<ApiResponse<ConversationResponse>> updateConversation(
            @PathVariable UUID conversationId,
            @Valid @RequestBody UpdateConversationRequest request,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        ConversationResponse response = conversationService.updateConversation(conversationId, request, managerId);
        return ResponseEntity.ok(ApiResponse.success("Conversation updated successfully", response));
    }

    /**
     * Publish a conversation.
     * PATCH /api/v1/manager/conversation/conversations/{conversationId}/publish
     */
    @PatchMapping("/conversations/{conversationId}/publish")
    public ResponseEntity<ApiResponse<ConversationResponse>> publishConversation(
            @PathVariable UUID conversationId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        ConversationResponse response = conversationService.updateStatus(
                conversationId, Course.ContentStatus.PUBLISHED, managerId);
        return ResponseEntity.ok(ApiResponse.success("Conversation published successfully", response));
    }

    /**
     * Archive a conversation.
     * PATCH /api/v1/manager/conversation/conversations/{conversationId}/archive
     */
    @PatchMapping("/conversations/{conversationId}/archive")
    public ResponseEntity<ApiResponse<ConversationResponse>> archiveConversation(
            @PathVariable UUID conversationId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        ConversationResponse response = conversationService.updateStatus(
                conversationId, Course.ContentStatus.ARCHIVED, managerId);
        return ResponseEntity.ok(ApiResponse.success("Conversation archived successfully", response));
    }

    /**
     * Soft-delete a conversation.
     * DELETE /api/v1/manager/conversation/conversations/{conversationId}
     */
    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<ApiResponse<Void>> deleteConversation(
            @PathVariable UUID conversationId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        conversationService.deleteConversation(conversationId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Conversation deleted successfully", null));
    }

}
