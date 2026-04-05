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

    List<GeneratedTopicSuggestionResponse> generateTopicSuggestions(GenerateTopicRequest request);

    ConversationTopicResponse createTopic(CreateTopicRequest request, UUID managerId);

    ConversationTopicResponse updateTopic(UUID id, UpdateTopicRequest request, UUID managerId);

    void deleteTopic(UUID id, UUID managerId);

    ConversationTopicResponse updateStatus(UUID id, Course.ContentStatus status, UUID managerId);

    ConversationTopicResponse getTopic(UUID id);

    Page<ConversationTopicSummaryResponse> getAllTopics(Pageable pageable);

    Page<ConversationTopicSummaryResponse> searchTopics(String keyword, Pageable pageable);

    Page<ConversationTopicSummaryResponse> getPublishedTopics(Course.JlptLevel jlptLevel, Pageable pageable);

    Page<ConversationTopicSummaryResponse> searchPublishedTopics(String keyword, Pageable pageable);
}
