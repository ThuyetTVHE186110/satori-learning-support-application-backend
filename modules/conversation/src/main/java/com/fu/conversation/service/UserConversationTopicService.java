package com.fu.conversation.service;

import com.fu.conversation.dto.request.CreateUserTopicRequest;
import com.fu.conversation.dto.request.UpdateUserTopicRequest;
import com.fu.conversation.dto.response.UserConversationTopicResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface UserConversationTopicService {

    UserConversationTopicResponse createTopic(CreateUserTopicRequest request, UUID userId);

    UserConversationTopicResponse updateTopic(UUID id, UpdateUserTopicRequest request, UUID userId);

    void deleteTopic(UUID id, UUID userId);

    UserConversationTopicResponse generateContext(UUID id, UUID userId);

    Page<UserConversationTopicResponse> getMyTopics(UUID userId, Pageable pageable);

    UserConversationTopicResponse getMyTopic(UUID id, UUID userId);
}
