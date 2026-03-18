package com.fu.conversation.service.impl;

import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.dto.request.CreateTopicRequest;
import com.fu.conversation.dto.request.GenerateTopicRequest;
import com.fu.conversation.dto.request.UpdateTopicRequest;
import com.fu.conversation.dto.response.ConversationMissionResponse;
import com.fu.conversation.dto.response.ConversationTopicResponse;
import com.fu.conversation.dto.response.ConversationTopicSummaryResponse;
import com.fu.conversation.dto.response.GeneratedTopicSuggestionResponse;
import com.fu.conversation.entity.ConversationMission;
import com.fu.conversation.entity.ConversationTopic;
import com.fu.conversation.mapper.ConversationMissionMapper;
import com.fu.conversation.mapper.ConversationTopicMapper;
import com.fu.conversation.repository.ConversationMissionRepository;
import com.fu.conversation.repository.ConversationThemeRepository;
import com.fu.conversation.repository.ConversationTopicRepository;
import com.fu.conversation.ai.ConversationGenerationService;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    private final ConversationMissionRepository missionRepository;
    private final ConversationMissionMapper missionMapper;
    private final ConversationGenerationService generationService;

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
        ConversationTopic saved = topicRepository.save(topic);

        // Persist inline missions atomically (from AI-generated suggestions)
        if (request.getMissions() != null && !request.getMissions().isEmpty()) {
            List<ConversationMission> missions = request.getMissions().stream()
                    .map(m -> ConversationMission.builder()
                            .topicId(saved.getId())
                            .title(m.getTitle())
                            .titleJapanese(m.getTitleJapanese())
                            .titleVi(m.getTitleVi())
                            .description(m.getDescription())
                            .descriptionVi(m.getDescriptionVi())
                            .missionType(m.getMissionType() != null
                                    ? m.getMissionType() : ConversationMission.MissionType.CUSTOM)
                            .successCriteria(m.getSuccessCriteria() != null ? m.getSuccessCriteria() : "{}")
                            .requiredVocabulary(m.getRequiredVocabulary() != null ? m.getRequiredVocabulary() : "[]")
                            .requiredGrammar(m.getRequiredGrammar() != null ? m.getRequiredGrammar() : "[]")
                            .orderIndex(m.getOrderIndex() != null ? m.getOrderIndex() : 0)
                            .createdBy(managerId)
                            .updatedBy(managerId)
                            .build())
                    .collect(Collectors.toList());
            missionRepository.saveAll(missions);
            log.info("Created {} missions for topic {}", missions.size(), saved.getId());
        }

        return buildTopicResponse(saved);
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

        // Cascade soft-delete all active missions for this topic
        List<ConversationMission> activeMissions =
                missionRepository.findByTopicIdAndDeletedAtIsNullOrderByOrderIndexAsc(id);
        if (!activeMissions.isEmpty()) {
            activeMissions.forEach(m -> m.softDelete(managerId));
            missionRepository.saveAll(activeMissions);
            log.info("Cascade soft-deleted {} missions for topic: {}", activeMissions.size(), id);
        }

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
    public List<ConversationTopicSummaryResponse> getPublishedTopicsByTheme(UUID themeId, UUID userId) {
        List<ConversationTopic> topics = topicRepository
                .findByThemeIdAndStatusAndDeletedAtIsNullOrderByOrderIndexAsc(
                        themeId, Course.ContentStatus.PUBLISHED.name());

        if (topics.isEmpty()) {
            return Collections.emptyList();
        }

        // Build practice stats map in a single query (topic_id → stats)
        Map<UUID, ConversationTopicRepository.PracticeStatsProjection> practiceStats =
                buildPracticeStatsMap(userId, topics);

        return topics.stream()
                .map(topic -> {
                    ConversationTopicSummaryResponse dto = topicMapper.toSummaryResponse(topic);
                    ConversationTopicRepository.PracticeStatsProjection stats =
                            practiceStats.get(topic.getId());
                    if (stats != null) {
                        dto.setPracticed(true);
                        dto.setPracticeCount(stats.getPracticeCount().intValue());
                        dto.setLastPracticedAt(stats.getLastPracticedAt());
                    }
                    return dto;
                })
                .collect(Collectors.toList());
    }

    /**
     * Executes a single query to get practice stats for all given topics.
     * Returns an empty map when userId is null (manager view) or topics list is empty.
     */
    private Map<UUID, ConversationTopicRepository.PracticeStatsProjection> buildPracticeStatsMap(
            UUID userId, List<ConversationTopic> topics) {
        if (userId == null) {
            return Collections.emptyMap();
        }
        List<UUID> topicIds = topics.stream()
                .map(ConversationTopic::getId)
                .collect(Collectors.toList());
        return topicRepository.findPracticeStatsByUserIdAndTopicIds(userId, topicIds)
                .stream()
                .collect(Collectors.toMap(
                        ConversationTopicRepository.PracticeStatsProjection::getTopicId,
                        p -> p));
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

    @Override
    public List<GeneratedTopicSuggestionResponse> generateTopicSuggestions(GenerateTopicRequest request) {
        themeRepository.findById(request.getThemeId())
                .filter(t -> t.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ConversationTheme", "id", request.getThemeId().toString()));

        return generationService.generateTopicsForTheme(
                request.getThemeId(), request.getCourseId(), request.getJlptLevel(), request.getCount());
    }

    private ConversationTopicResponse buildTopicResponse(ConversationTopic topic) {
        ConversationTopicResponse response = topicMapper.toResponse(topic);
        if (topic.getCourseId() != null) {
            courseRepository.findById(topic.getCourseId())
                    .ifPresent(c -> response.setCourseName(c.getName()));
        }
        List<ConversationMissionResponse> missions = missionRepository
                .findByTopicIdAndDeletedAtIsNullOrderByOrderIndexAsc(topic.getId())
                .stream()
                .map(missionMapper::toResponse)
                .collect(Collectors.toList());
        response.setMissions(missions);
        return response;
    }
}
