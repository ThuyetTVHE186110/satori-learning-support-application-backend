package com.fu.conversation.service;

import com.fu.conversation.dto.request.CreateUserConversationRequest;
import com.fu.conversation.dto.request.UpdateUserConversationRequest;
import com.fu.conversation.dto.response.UserConversationResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface UserConversationService {

    UserConversationResponse createConversation(CreateUserConversationRequest request, UUID userId);

    UserConversationResponse updateConversation(UUID id, UpdateUserConversationRequest request, UUID userId);

    void deleteConversation(UUID id, UUID userId);

    UserConversationResponse generateContext(UUID id, UUID userId);

    Page<UserConversationResponse> getMyConversations(UUID userId, Pageable pageable);

    UserConversationResponse getMyConversation(UUID id, UUID userId);
}
