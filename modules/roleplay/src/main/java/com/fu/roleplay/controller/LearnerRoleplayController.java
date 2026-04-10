package com.fu.roleplay.controller;

import com.fu.common.response.ApiResponse;
import com.fu.common.response.PageResponse;
import com.fu.roleplay.dto.request.SendMessageRequest;
import com.fu.roleplay.dto.request.StartFreeTalkRequest;
import com.fu.roleplay.dto.request.StartSessionRequest;
import com.fu.roleplay.dto.response.SendMessageResponse;
import com.fu.roleplay.dto.response.SessionEvaluationResponse;
import com.fu.roleplay.dto.response.SessionResponse;
import com.fu.roleplay.dto.response.SessionSummaryResponse;
import com.fu.roleplay.service.RoleplaySessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/learner/roleplay")
@RequiredArgsConstructor
@PreAuthorize("hasRole('LEARNER')")
public class LearnerRoleplayController {

    private final RoleplaySessionService sessionService;

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<SessionResponse>> startSession(
            @Valid @RequestBody StartSessionRequest request,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        log.info("User {} starting roleplay session for conversation {}", userId, request.getConversationId());
        SessionResponse response = sessionService.startSession(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Roleplay session started", response));
    }

    @PostMapping("/free-talk/sessions")
    public ResponseEntity<ApiResponse<SessionResponse>> startFreeTalk(
            @Valid @RequestBody StartFreeTalkRequest request,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        log.info("User {} starting free-talk session at JLPT {}", userId, request.getJlptLevel());
        SessionResponse response = sessionService.startFreeTalk(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Free-talk session started", response));
    }

    @PostMapping(value = "/sessions/{sessionId}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<SendMessageResponse>> sendMessage(
            @PathVariable UUID sessionId,
            @Valid @RequestPart("message") SendMessageRequest request,
            @RequestPart(value = "audio", required = false) MultipartFile audio,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        log.info("User {} sending message to session {}", userId, sessionId);
        SendMessageResponse response = sessionService.sendMessage(sessionId, request, audio, userId);
        return ResponseEntity.ok(ApiResponse.success("Message sent", response));
    }

    @PostMapping("/sessions/{sessionId}/complete")
    public ResponseEntity<ApiResponse<SessionEvaluationResponse>> completeSession(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        log.info("User {} completing session {}", userId, sessionId);
        SessionEvaluationResponse response = sessionService.completeSession(sessionId, userId);
        return ResponseEntity.ok(ApiResponse.success("Session completed with evaluation", response));
    }

    @PostMapping("/sessions/{sessionId}/abandon")
    public ResponseEntity<ApiResponse<Void>> abandonSession(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        log.info("User {} abandoning session {}", userId, sessionId);
        sessionService.abandonSession(sessionId, userId);
        return ResponseEntity.ok(ApiResponse.success("Session abandoned", null));
    }

    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<PageResponse<SessionSummaryResponse>>> getSessionHistory(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) int size,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        // Keep Pageable unsorted because repository native SQL already orders by created_at DESC.
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<SessionSummaryResponse> result = sessionService.getSessionHistory(userId, pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(
                result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages())));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<ApiResponse<SessionResponse>> getSession(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        SessionResponse response = sessionService.getSession(sessionId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get the feedback from the latest completed session for a conversation.
     * Used when a learner clicks on a COMPLETED conversation to view their previous results.
     * Returns 404 if no completed session exists for this conversation.
     * GET /api/v1/learner/roleplay/conversations/{conversationId}/latest-feedback
     */
    @GetMapping("/conversations/{conversationId}/latest-feedback")
    public ResponseEntity<ApiResponse<SessionEvaluationResponse>> getConversationFeedback(
            @PathVariable UUID conversationId,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        SessionEvaluationResponse response = sessionService.getConversationFeedback(conversationId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
