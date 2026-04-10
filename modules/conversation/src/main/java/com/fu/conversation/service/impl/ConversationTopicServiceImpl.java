package com.fu.conversation.service.impl;

import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.ai.ConversationGenerationService;
import com.fu.conversation.dto.request.CreateTopicRequest;
import com.fu.conversation.dto.request.GenerateTopicRequest;
import com.fu.conversation.dto.request.UpdateTopicRequest;
import com.fu.conversation.dto.response.ConversationTopicResponse;
import com.fu.conversation.dto.response.ConversationTopicSummaryResponse;
import com.fu.conversation.dto.response.GeneratedTopicSuggestionResponse;
import com.fu.conversation.entity.Conversation;
import com.fu.conversation.entity.ConversationTopic;
import com.fu.conversation.mapper.ConversationTopicMapper;
import com.fu.conversation.repository.ConversationRepository;
import com.fu.conversation.repository.ConversationTopicRepository;
import com.fu.conversation.service.ConversationTopicService;
import com.fu.course.entity.Course;
import com.fu.course.repository.CourseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConversationTopicServiceImpl implements ConversationTopicService {

    private final ConversationTopicRepository topicRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationTopicMapper topicMapper;
    private final ConversationGenerationService generationService;
    private final CourseRepository courseRepository;

    @Override
    public List<GeneratedTopicSuggestionResponse> generateTopicSuggestions(GenerateTopicRequest request) {
        log.info("Generating {} topic suggestions (jlpt={}, category={})",
                request.getCount(), request.getJlptLevel(), request.getCategory());
        return generationService.generateTopics(
                request.getJlptLevel(), request.getCategory(), request.getHint(), request.getCount());
    }

    @Override
    @Transactional
    public ConversationTopicResponse createTopic(CreateTopicRequest request, UUID managerId) {
        ConversationTopic topic = topicMapper.toEntity(request);
        topic.setCreatedBy(managerId);
        topic.setUpdatedBy(managerId);
        ConversationTopic saved = topicRepository.save(topic);
        return buildTopicResponse(saved);
    }

    @Override
    @Transactional
    public ConversationTopicResponse updateTopic(UUID id, UpdateTopicRequest request, UUID managerId) {
        if (request.getTitle() != null && request.getTitle().isBlank()) {
            throw new BusinessException("VALIDATION_ERROR", "Title must not be blank");
        }
        ConversationTopic topic = findActiveTopic(id);
        topicMapper.updateEntity(topic, request);
        topic.setUpdatedBy(managerId);
        return buildTopicResponse(topicRepository.save(topic));
    }

    @Override
    @Transactional
    public void deleteTopic(UUID id, UUID managerId) {
        ConversationTopic topic = findActiveTopic(id);
        LocalDateTime now = LocalDateTime.now();
        topic.setDeletedAt(now);
        topic.setDeletedBy(managerId);
        topicRepository.save(topic);

        // Cascade soft-delete all active conversations belonging to this topic
        List<Conversation> activeConversations =
                conversationRepository.findByTopicIdAndDeletedAtIsNull(id);
        if (!activeConversations.isEmpty()) {
            activeConversations.forEach(c -> {
                c.setDeletedAt(now);
                c.setDeletedBy(managerId);
            });
            conversationRepository.saveAll(activeConversations);
        }
        log.info("Soft-deleted conversation topic: {} and {} child conversations", id, activeConversations.size());
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
    public Page<ConversationTopicSummaryResponse> getAllTopics(Pageable pageable) {
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by("orderIndex").ascending());
        return topicRepository.findByDeletedAtIsNull(sorted)
                .map(this::buildTopicSummaryResponse);
    }

    @Override
    public Page<ConversationTopicSummaryResponse> searchTopics(String keyword, Pageable pageable) {
        return topicRepository.searchByKeyword(keyword, pageable)
                .map(this::buildTopicSummaryResponse);
    }

    @Override
    public Page<ConversationTopicSummaryResponse> searchTopics(String keyword, Course.ContentStatus status, Pageable pageable) {
        if (keyword != null && !keyword.isBlank() && status != null) {
            // Both keyword and status provided
            return topicRepository.searchByKeywordAndStatus(keyword.trim(), status.name(), pageable)
                    .map(this::buildTopicSummaryResponse);
        } else if (keyword != null && !keyword.isBlank()) {
            // Only keyword provided
            return topicRepository.searchByKeyword(keyword.trim(), pageable)
                    .map(this::buildTopicSummaryResponse);
        } else if (status != null) {
            // Only status provided
            return topicRepository.findByStatusAndDeletedAtIsNull(status.name(), pageable)
                    .map(this::buildTopicSummaryResponse);
        } else {
            // No filters provided - show all topics
            return topicRepository.findByDeletedAtIsNull(pageable)
                    .map(this::buildTopicSummaryResponse);
        }
    }

    @Override
    public Page<ConversationTopicSummaryResponse> getPublishedTopics(Course.JlptLevel jlptLevel, Pageable pageable) {
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        Page<ConversationTopic> page = jlptLevel != null
                ? topicRepository.findByJlptLevelAndStatusAndDeletedAtIsNull(
                jlptLevel.name(), Course.ContentStatus.PUBLISHED.name(), unsorted)
                : topicRepository.findByStatusAndDeletedAtIsNull(
                Course.ContentStatus.PUBLISHED.name(), unsorted);
        return page.map(this::buildTopicSummaryResponse);
    }

    @Override
    public Page<ConversationTopicSummaryResponse> searchPublishedTopics(String keyword, Pageable pageable) {
        return topicRepository.searchPublishedByKeyword(keyword, pageable)
                .map(this::buildTopicSummaryResponse);
    }

    @Override
    public Page<ConversationTopicSummaryResponse> searchPublishedTopics(String keyword, Course.JlptLevel jlptLevel, Pageable pageable) {

        Page<ConversationTopic> page;
        if (keyword != null && !keyword.isBlank() && jlptLevel != null) {
            // Both keyword and level provided
            page = topicRepository.searchPublishedByKeywordAndJlptLevel(
                    keyword.trim(), jlptLevel.name(), pageable);
        } else if (keyword != null && !keyword.isBlank()) {
            // Only keyword provided
            page = topicRepository.searchPublishedByKeyword(keyword.trim(), pageable);
        } else if (jlptLevel != null) {
            // Only level provided
            page = topicRepository.findByJlptLevelAndStatusAndDeletedAtIsNull(
                    jlptLevel.name(), Course.ContentStatus.PUBLISHED.name(), pageable);
        } else {
            // No filters provided
            page = topicRepository.findByStatusAndDeletedAtIsNull(
                    Course.ContentStatus.PUBLISHED.name(), pageable);
        }

        return page.map(this::buildTopicSummaryResponse);
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
        long conversationCount = conversationRepository.countByTopicIdAndStatusAndDeletedAtIsNull(
                topic.getId(), Course.ContentStatus.PUBLISHED.name());
        response.setConversationCount(conversationCount);
        long n5Count = conversationRepository.countByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNull(
                topic.getId(), Course.JlptLevel.N5.name(), Course.ContentStatus.PUBLISHED.name());
        long n4Count = conversationRepository.countByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNull(
                topic.getId(), Course.JlptLevel.N4.name(), Course.ContentStatus.PUBLISHED.name());
        long n3Count = conversationRepository.countByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNull(
                topic.getId(), Course.JlptLevel.N3.name(), Course.ContentStatus.PUBLISHED.name());
        long n2Count = conversationRepository.countByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNull(
                topic.getId(), Course.JlptLevel.N2.name(), Course.ContentStatus.PUBLISHED.name());
        long n1Count = conversationRepository.countByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNull(
                topic.getId(), Course.JlptLevel.N1.name(), Course.ContentStatus.PUBLISHED.name());
        response.setN5ConversationCount(n5Count);
        response.setN4ConversationCount(n4Count);
        response.setN3ConversationCount(n3Count);
        response.setN2ConversationCount(n2Count);
        response.setN1ConversationCount(n1Count);
        return response;
    }

    private ConversationTopicSummaryResponse buildTopicSummaryResponse(ConversationTopic topic) {
        ConversationTopicSummaryResponse response = topicMapper.toSummaryResponse(topic);
        long conversationCount = conversationRepository.countByTopicIdAndStatusAndDeletedAtIsNull(
                topic.getId(), Course.ContentStatus.PUBLISHED.name());
        response.setConversationCount(conversationCount);
        return response;
    }
}
