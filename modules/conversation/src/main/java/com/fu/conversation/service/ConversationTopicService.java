package com.fu.conversation.service;

import com.fu.conversation.dto.request.CreateTopicRequest;
import com.fu.conversation.dto.request.GenerateTopicRequest;
import com.fu.conversation.dto.request.UpdateTopicRequest;
import com.fu.conversation.dto.response.ConversationTopicResponse;
import com.fu.conversation.dto.response.ConversationTopicSummaryResponse;
import com.fu.conversation.dto.response.GeneratedTopicSuggestionResponse;
import com.fu.course.entity.Course;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface ConversationTopicService {

    ConversationTopicResponse createTopic(CreateTopicRequest request, UUID managerId);

    ConversationTopicResponse updateTopic(UUID id, UpdateTopicRequest request, UUID managerId);

    void deleteTopic(UUID id, UUID managerId);

    ConversationTopicResponse updateStatus(UUID id, Course.ContentStatus status, UUID managerId);

    ConversationTopicResponse getTopic(UUID id);

    Page<ConversationTopicSummaryResponse> getTopicsByTheme(UUID themeId, Pageable pageable);

    /**
     * Returns published topics for a theme, enriched with the learner's practice history.
     *
     * @param themeId the theme to fetch topics for
     * @param userId  the learner's user ID used to look up practice stats; may be null
     *                (manager view — practice fields will be left at defaults)
     */
    List<ConversationTopicSummaryResponse> getPublishedTopicsByTheme(UUID themeId, UUID userId);

    /** Returns an active, published topic — throws 404 for drafts/archived/deleted. */
    ConversationTopicResponse getPublishedTopic(UUID id);

    Page<ConversationTopicSummaryResponse> getAllTopics(Pageable pageable);

    /**
     * Uses AI to generate topic suggestions for a theme.
     * Suggestions are not persisted — the manager reviews and saves them manually.
     */
    List<GeneratedTopicSuggestionResponse> generateTopicSuggestions(GenerateTopicRequest request);
}
