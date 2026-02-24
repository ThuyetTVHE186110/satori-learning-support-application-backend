package com.fu.conversation.service.impl;

import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.dto.request.CreateTopicRequest;
import com.fu.conversation.dto.request.UpdateTopicRequest;
import com.fu.conversation.dto.response.ConversationTopicResponse;
import com.fu.conversation.dto.response.ConversationTopicSummaryResponse;
import com.fu.conversation.entity.ConversationTopic;
import com.fu.conversation.mapper.ConversationTopicMapper;
import com.fu.conversation.repository.ConversationThemeRepository;
import com.fu.conversation.repository.ConversationTopicRepository;
import com.fu.conversation.service.ConversationTopicService;
import com.fu.course.entity.Course;
import com.fu.course.repository.CourseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConversationTopicServiceImpl implements ConversationTopicService {

    private final ConversationTopicRepository topicRepository;
    private final ConversationTopicMapper topicMapper;
    private final ConversationThemeRepository themeRepository;
    private final CourseRepository courseRepository;

    @Override
    @Transactional
    public ConversationTopicResponse createTopic(CreateTopicRequest request, UUID managerId) {
        if (request.getThemeId() != null) {
            themeRepository.findById(request.getThemeId())
                    .filter(t -> t.getDeletedAt() == null)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "ConversationTheme", "id", request.getThemeId().toString()));
        }
        ConversationTopic topic = topicMapper.toEntity(request);
        topic.setCreatedBy(managerId);
        topic.setUpdatedBy(managerId);
        return buildTopicResponse(topicRepository.save(topic));
    }

    @Override
    @Transactional
    public ConversationTopicResponse updateTopic(UUID id, UpdateTopicRequest request, UUID managerId) {
        ConversationTopic topic = findActiveTopic(id);
        topicMapper.updateEntity(topic, request);
        topic.setUpdatedBy(managerId);
        return buildTopicResponse(topicRepository.save(topic));
    }

    @Override
    @Transactional
    public void deleteTopic(UUID id, UUID managerId) {
        ConversationTopic topic = findActiveTopic(id);
        topic.setDeletedAt(LocalDateTime.now());
        topic.setDeletedBy(managerId);
        topicRepository.save(topic);
        log.info("Soft-deleted conversation topic: {}", id);
    }

    @Override
    @Transactional
    public ConversationTopicResponse updateStatus(UUID id, Course.ContentStatus status, UUID managerId) {
        ConversationTopic topic = findActiveTopic(id);
        topic.setStatus(status);
        topic.setUpdatedBy(managerId);
        return buildTopicResponse(topicRepository.save(topic));
    }

    @Override
    public ConversationTopicResponse getTopic(UUID id) {
        return buildTopicResponse(findActiveTopic(id));
    }

    @Override
    public Page<ConversationTopicSummaryResponse> getTopicsByTheme(UUID themeId, Pageable pageable) {
        return topicRepository.findByThemeIdAndDeletedAtIsNull(themeId, pageable)
                .map(topicMapper::toSummaryResponse);
    }

    @Override
    public List<ConversationTopicSummaryResponse> getPublishedTopicsByTheme(UUID themeId) {
        return topicRepository.findByThemeIdAndStatusAndDeletedAtIsNullOrderByOrderIndexAsc(
                        themeId, Course.ContentStatus.PUBLISHED)
                .stream()
                .map(topicMapper::toSummaryResponse)
                .collect(Collectors.toList());
    }

    @Override
    public Page<ConversationTopicSummaryResponse> getAllTopics(Pageable pageable) {
        return topicRepository.findByDeletedAtIsNull(pageable)
                .map(topicMapper::toSummaryResponse);
    }

    @Override
    public ConversationTopicResponse getPublishedTopic(UUID id) {
        ConversationTopic topic = topicRepository.findById(id)
                .filter(t -> t.getDeletedAt() == null)
                .filter(t -> Course.ContentStatus.PUBLISHED.equals(t.getStatus()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ConversationTopic", "id", id.toString()));
        return buildTopicResponse(topic);
    }

    private ConversationTopic findActiveTopic(UUID id) {
        return topicRepository.findById(id)
                .filter(t -> t.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ConversationTopic", "id", id.toString()));
    }

    private ConversationTopicResponse buildTopicResponse(ConversationTopic topic) {
        ConversationTopicResponse response = topicMapper.toResponse(topic);
        if (topic.getCourseId() != null) {
            courseRepository.findById(topic.getCourseId())
                    .ifPresent(c -> response.setCourseName(c.getName()));
        }
        return response;
    }
}
