package com.fu.conversation.service;

import com.fu.conversation.dto.request.CreateConversationRequest;
import com.fu.conversation.dto.request.GenerateConversationRequest;
import com.fu.conversation.dto.request.UpdateConversationRequest;
import com.fu.conversation.dto.response.ConversationResponse;
import com.fu.conversation.dto.response.ConversationSummaryResponse;
import com.fu.conversation.dto.response.GeneratedConversationSuggestionResponse;
import com.fu.course.entity.Course;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface ConversationService {

    ConversationResponse createConversation(CreateConversationRequest request, UUID managerId);

    ConversationResponse updateConversation(UUID id, UpdateConversationRequest request, UUID managerId);

    void deleteConversation(UUID id, UUID managerId);

    ConversationResponse updateStatus(UUID id, Course.ContentStatus status, UUID managerId);

    ConversationResponse getConversation(UUID id);

    Page<ConversationSummaryResponse> getConversationsByTopic(UUID topicId, Pageable pageable);

    Page<ConversationSummaryResponse> getConversationsByTopic(
            UUID topicId,
            Course.JlptLevel jlptLevel,
            Course.ContentStatus status,
            Pageable pageable);

    /**
     * Returns published conversations for a topic, enriched with the learner's practice history.
     *
     * @param topicId the topic to fetch conversations for
     * @param userId  the learner's user ID; may be null (manager view)
     */
    List<ConversationSummaryResponse> getPublishedConversationsByTopic(UUID topicId, UUID userId);

    /**
     * Returns published conversations for a topic filtered by the user's enrolled course's JLPT level.
     * Returns empty list if user is not enrolled in any class.
     *
     * @param topicId the topic to fetch conversations for
     * @param userId  the learner's user ID
     * @return list of conversations matching the user's JLPT level, enriched with practice history
     */
    List<ConversationSummaryResponse> getPublishedConversationsByTopicAndUserJlptLevel(UUID topicId, UUID userId);

    /** Returns an active, published conversation — throws 404 for drafts/archived/deleted. */
    ConversationResponse getPublishedConversation(UUID id);

    Page<ConversationSummaryResponse> getAllConversations(Pageable pageable);

    Page<ConversationSummaryResponse> getAllConversations(String keyword, Course.JlptLevel jlptLevel, Course.ContentStatus status, Pageable pageable);

    /**
     * Uses AI to generate conversation suggestions for a topic.
     * Suggestions are not persisted — the manager reviews and saves them manually.
     */
    List<GeneratedConversationSuggestionResponse> generateConversationSuggestions(GenerateConversationRequest request);
}
