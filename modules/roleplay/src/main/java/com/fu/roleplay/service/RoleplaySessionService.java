package com.fu.roleplay.service;

import com.fu.roleplay.dto.request.SendMessageRequest;
import com.fu.roleplay.dto.request.StartFreeTalkRequest;
import com.fu.roleplay.dto.request.StartSessionRequest;
import com.fu.roleplay.dto.response.SendMessageResponse;
import com.fu.roleplay.dto.response.SessionEvaluationResponse;
import com.fu.roleplay.dto.response.SessionResponse;
import com.fu.roleplay.dto.response.SessionSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface RoleplaySessionService {

    SessionResponse startSession(StartSessionRequest request, UUID userId);

    SessionResponse startFreeTalk(StartFreeTalkRequest request, UUID userId);

    SendMessageResponse sendMessage(UUID sessionId, SendMessageRequest request, MultipartFile audio, UUID userId);

    SessionEvaluationResponse completeSession(UUID sessionId, UUID userId);

    void abandonSession(UUID sessionId, UUID userId);

    SessionResponse getSession(UUID sessionId, UUID userId);

    Page<SessionSummaryResponse> getSessionHistory(UUID userId, Pageable pageable);

    SessionEvaluationResponse getConversationFeedback(UUID conversationId, UUID userId);
}
