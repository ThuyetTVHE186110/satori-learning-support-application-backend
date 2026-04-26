package com.fu.conversation.service.impl;

import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.dto.request.CreateConversationRequest;
import com.fu.conversation.dto.request.GenerateConversationRequest;
import com.fu.conversation.dto.request.UpdateConversationRequest;
import com.fu.conversation.dto.response.ConversationMissionResponse;
import com.fu.conversation.dto.response.ConversationResponse;
import com.fu.conversation.dto.response.ConversationSummaryResponse;
import com.fu.conversation.dto.response.GeneratedConversationSuggestionResponse;
import com.fu.conversation.entity.Conversation;
import com.fu.conversation.entity.ConversationMission;
import com.fu.conversation.mapper.ConversationMapper;
import com.fu.conversation.mapper.ConversationMissionMapper;
import com.fu.conversation.repository.ConversationMissionRepository;
import com.fu.conversation.repository.ConversationRepository;
import com.fu.conversation.repository.ConversationTopicRepository;
import com.fu.conversation.ai.ConversationGenerationService;
import com.fu.conversation.service.ConversationService;
import com.fu.course.entity.Course;
import com.fu.course.exception.CourseNotFoundException;
import com.fu.course.repository.CourseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
public class ConversationServiceImpl implements ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationMapper conversationMapper;
    private final ConversationTopicRepository topicRepository;
    private final CourseRepository courseRepository;
    private final ConversationMissionRepository missionRepository;
    private final ConversationMissionMapper missionMapper;
    private final ConversationGenerationService generationService;
    private final com.fu.clazz.repository.ClassMemberRepository classMemberRepository;
    private final com.fu.clazz.repository.ClassRepository classRepository;

    @Override
    @Transactional
    public ConversationResponse createConversation(CreateConversationRequest request, UUID managerId) {
        if (request.getTopicId() != null) {
            topicRepository.findById(request.getTopicId())
                    .filter(t -> t.getDeletedAt() == null)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "ConversationTopic", "id", request.getTopicId().toString()));
        }
        Conversation conversation = conversationMapper.toEntity(request);
        conversation.setCreatedBy(managerId);
        conversation.setUpdatedBy(managerId);
        if (request.getCourseId() != null) {
            Course course = courseRepository.findByIdAndNotDeleted(request.getCourseId()).orElseThrow(() -> new CourseNotFoundException(request.getCourseId()));
            conversation.setJlptLevel(course.getJlptLevel());
        }
        if (request.getMissions() == null || request.getMissions().isEmpty()) {
            throw new BusinessException("MIN_MISSION", "Số lượng nhiệm vụ trong một bài hội thoại ít nhất là 1 nhiệm vụ.");
        }
        if (request.getMissions().size() > 3) {
            throw new BusinessException("MAX_MISSION", "Số lượng nhiệm vụ trong một bài hội thoại không được vượt quá 3 nhiệm vụ.");
        }
        Conversation saved = conversationRepository.save(conversation);

        // Persist inline missions atomically
        if (request.getMissions() != null && !request.getMissions().isEmpty()) {
            List<ConversationMission> missions = request.getMissions().stream()
                    .map(m -> ConversationMission.builder()
                            .conversationId(saved.getId())
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
            log.info("Created {} missions for conversation {}", missions.size(), saved.getId());
        }

        return buildConversationResponse(saved);
    }

    @Override
    @Transactional
    public ConversationResponse updateConversation(UUID id, UpdateConversationRequest request, UUID managerId) {
        Conversation conversation = findActiveConversation(id);
        conversationMapper.updateEntity(conversation, request);
        conversation.setUpdatedBy(managerId);
        Conversation saved = conversationRepository.save(conversation);

        if (request.getMissions() != null) {
            syncMissions(saved.getId(), request.getMissions(), managerId);
        }

        return buildConversationResponse(saved);
    }

    private void syncMissions(UUID conversationId,
                              List<UpdateConversationRequest.InlineMissionUpsert> incoming,
                              UUID managerId) {
        List<ConversationMission> existing =
                missionRepository.findByConversationIdAndDeletedAtIsNullOrderByOrderIndexAsc(conversationId);
        Map<UUID, ConversationMission> existingById = existing.stream()
                .collect(Collectors.toMap(ConversationMission::getId, m -> m));

        List<ConversationMission> toSave = new java.util.ArrayList<>();
        java.util.Set<UUID> keepIds = new java.util.HashSet<>();

        for (UpdateConversationRequest.InlineMissionUpsert m : incoming) {
            if (m.getId() != null && existingById.containsKey(m.getId())) {
                ConversationMission target = existingById.get(m.getId());
                target.setTitle(m.getTitle());
                target.setTitleJapanese(m.getTitleJapanese());
                target.setTitleVi(m.getTitleVi());
                target.setDescription(m.getDescription());
                target.setDescriptionVi(m.getDescriptionVi());
                if (m.getMissionType() != null) target.setMissionType(m.getMissionType());
                if (m.getSuccessCriteria() != null) target.setSuccessCriteria(m.getSuccessCriteria());
                if (m.getRequiredVocabulary() != null) target.setRequiredVocabulary(m.getRequiredVocabulary());
                if (m.getRequiredGrammar() != null) target.setRequiredGrammar(m.getRequiredGrammar());
                if (m.getOrderIndex() != null) target.setOrderIndex(m.getOrderIndex());
                target.setUpdatedBy(managerId);
                toSave.add(target);
                keepIds.add(target.getId());
            } else {
                toSave.add(ConversationMission.builder()
                        .conversationId(conversationId)
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
                        .build());
            }
        }

        List<ConversationMission> toRemove = existing.stream()
                .filter(e -> !keepIds.contains(e.getId()))
                .collect(Collectors.toList());
        toRemove.forEach(m -> m.softDelete(managerId));

        if (!toSave.isEmpty()) missionRepository.saveAll(toSave);
        if (!toRemove.isEmpty()) missionRepository.saveAll(toRemove);

        log.info("Synced missions for conversation {} — upsert={}, deleted={}",
                conversationId, toSave.size(), toRemove.size());
    }

    @Override
    @Transactional
    public void deleteConversation(UUID id, UUID managerId) {
        Conversation conversation = findActiveConversation(id);
        conversation.setDeletedAt(LocalDateTime.now());
        conversation.setDeletedBy(managerId);
        conversationRepository.save(conversation);

        List<ConversationMission> activeMissions =
                missionRepository.findByConversationIdAndDeletedAtIsNullOrderByOrderIndexAsc(id);
        if (!activeMissions.isEmpty()) {
            activeMissions.forEach(m -> m.softDelete(managerId));
            missionRepository.saveAll(activeMissions);
            log.info("Cascade soft-deleted {} missions for conversation: {}", activeMissions.size(), id);
        }
        log.info("Soft-deleted conversation: {}", id);
    }

    @Override
    @Transactional
    public ConversationResponse updateStatus(UUID id, Course.ContentStatus status, UUID managerId) {
        Conversation conversation = findActiveConversation(id);
        conversation.setStatus(status);
        conversation.setUpdatedBy(managerId);
        return buildConversationResponse(conversationRepository.save(conversation));
    }

    @Override
    public ConversationResponse getConversation(UUID id) {
        return buildConversationResponse(findActiveConversation(id));
    }

    @Override
    public Page<ConversationSummaryResponse> getConversationsByTopic(UUID topicId, Pageable pageable) {
        return conversationRepository.findByTopicIdAndDeletedAtIsNull(topicId, pageable)
                .map(conversationMapper::toSummaryResponse);
    }

    @Override
    public Page<ConversationSummaryResponse> getConversationsByTopic(
            UUID topicId, String keyword, Course.JlptLevel jlptLevel, Course.ContentStatus status, Pageable pageable) {

        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        Page<Conversation> page;
        if (keyword != null && !keyword.isBlank()) {
            page = conversationRepository.findByTopicIdAndKeywordAndDeletedAtIsNull(
                    topicId, keyword.trim(), unsorted);
        } else if (jlptLevel != null && status != null) {
            page = conversationRepository.findByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNull(
                    topicId, jlptLevel.name(), status.name(), unsorted);
        } else if (jlptLevel != null) {
            page = conversationRepository.findByTopicIdAndJlptLevelAndDeletedAtIsNull(
                    topicId, jlptLevel.name(), unsorted);
        } else if (status != null) {
            page = conversationRepository.findByTopicIdAndStatusAndDeletedAtIsNull(
                    topicId, status.name(), unsorted);
        } else {
            page = conversationRepository.findByTopicIdAndDeletedAtIsNull(topicId, unsorted);
        }

        return page.map(conversationMapper::toSummaryResponse);
    }

    @Override
    public List<ConversationSummaryResponse> getPublishedConversationsByTopic(UUID topicId, UUID userId) {
        List<Conversation> conversations = conversationRepository
                .findByTopicIdAndStatusAndDeletedAtIsNullOrderByOrderIndexAsc(
                        topicId, Course.ContentStatus.PUBLISHED.name());

        if (conversations.isEmpty()) {
            return Collections.emptyList();
        }

        Map<UUID, ConversationRepository.PracticeStatsProjection> practiceStats =
                buildPracticeStatsMap(userId, conversations);

        return conversations.stream()
                .map(conv -> {
                    ConversationSummaryResponse dto = conversationMapper.toSummaryResponse(conv);
                    ConversationRepository.PracticeStatsProjection stats =
                            practiceStats.get(conv.getId());
                    if (stats != null) {
                        dto.setPracticed(true);
                        dto.setPracticeCount(stats.getPracticeCount().intValue());
                        dto.setLastPracticedAt(stats.getLastPracticedAt());
                        dto.setPracticeStatus(Boolean.TRUE.equals(stats.getIsCompleted())
                                ? ConversationSummaryResponse.PracticeStatus.COMPLETED
                                : ConversationSummaryResponse.PracticeStatus.IN_PROGRESS);
                    } else {
                        dto.setPracticeStatus(ConversationSummaryResponse.PracticeStatus.NOT_STARTED);
                    }
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<ConversationSummaryResponse> getPublishedConversationsByTopicAndUserJlptLevel(UUID topicId, UUID userId) {
        // Step 1: Find user's active class enrollment
        List<UUID> activeClassIds = classMemberRepository.findActiveClassIdsByUserId(userId);
        
        if (activeClassIds.isEmpty()) {
            log.debug("User {} has no active class enrollment, returning empty conversations", userId);
            return Collections.emptyList();
        }
        
        // Get the first active class (user can only have one active class at a time per requirement)
        UUID classId = activeClassIds.get(0);
        
        // Step 2: Get the class details to find course ID
        com.fu.clazz.entity.Clazz clazz = classRepository.findByIdAndNotDeleted(classId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Class", "id", classId.toString()));
        
        // Step 3: Get the course to extract JLPT level
        Course course = courseRepository.findByIdAndNotDeleted(clazz.getCourseId())
                .orElseThrow(() -> new CourseNotFoundException(clazz.getCourseId()));
        
        // Step 4: Filter conversations by topic + JLPT level + PUBLISHED status
        List<Conversation> conversations = conversationRepository
                .findByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNullOrderByOrderIndexAsc(
                        topicId,
                        course.getJlptLevel().name(),
                        Course.ContentStatus.PUBLISHED.name());
        
        if (conversations.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Step 5: Enrich with practice stats
        Map<UUID, ConversationRepository.PracticeStatsProjection> practiceStats =
                buildPracticeStatsMap(userId, conversations);

        return conversations.stream()
                .map(conv -> {
                    ConversationSummaryResponse dto = conversationMapper.toSummaryResponse(conv);
                    ConversationRepository.PracticeStatsProjection stats =
                            practiceStats.get(conv.getId());
                    if (stats != null) {
                        dto.setPracticed(true);
                        dto.setPracticeCount(stats.getPracticeCount().intValue());
                        dto.setLastPracticedAt(stats.getLastPracticedAt());
                        dto.setPracticeStatus(Boolean.TRUE.equals(stats.getIsCompleted())
                                ? ConversationSummaryResponse.PracticeStatus.COMPLETED
                                : ConversationSummaryResponse.PracticeStatus.IN_PROGRESS);
                    } else {
                        dto.setPracticeStatus(ConversationSummaryResponse.PracticeStatus.NOT_STARTED);
                    }
                    return dto;
                })
                .collect(Collectors.toList());
    }

    private Map<UUID, ConversationRepository.PracticeStatsProjection> buildPracticeStatsMap(
            UUID userId, List<Conversation> conversations) {
        if (userId == null) {
            return Collections.emptyMap();
        }
        List<UUID> conversationIds = conversations.stream()
                .map(Conversation::getId)
                .collect(Collectors.toList());
        return conversationRepository.findPracticeStatsByUserIdAndConversationIds(userId, conversationIds)
                .stream()
                .collect(Collectors.toMap(
                        ConversationRepository.PracticeStatsProjection::getConversationId,
                        p -> p));
    }

    @Override
    public Page<ConversationSummaryResponse> getAllConversations(Pageable pageable) {
        return conversationRepository.findByDeletedAtIsNull(pageable)
                .map(conversationMapper::toSummaryResponse);
    }

    @Override
    public Page<ConversationSummaryResponse> getAllConversations(String keyword, Course.JlptLevel jlptLevel, Course.ContentStatus status, Pageable pageable) {
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        Page<Conversation> page;
        if (keyword != null && !keyword.isBlank() && jlptLevel != null && status != null) {
            // All three filters
            page = conversationRepository.searchByKeywordAndJlptLevelAndStatusAndDeletedAtIsNull(
                    keyword.trim(), jlptLevel.name(), status.name(), unsorted);
        } else if (keyword != null && !keyword.isBlank() && jlptLevel != null) {
            // Keyword + level
            page = conversationRepository.searchByKeywordAndJlptLevelAndDeletedAtIsNull(
                    keyword.trim(), jlptLevel.name(), unsorted);
        } else if (keyword != null && !keyword.isBlank() && status != null) {
            // Keyword + status
            page = conversationRepository.searchByKeywordAndStatusAndDeletedAtIsNull(
                    keyword.trim(), status.name(), unsorted);
        } else if (jlptLevel != null && status != null) {
            // Level + status
            page = conversationRepository.findByJlptLevelAndStatusAndDeletedAtIsNull(
                    jlptLevel.name(), status.name(), unsorted);
        } else if (keyword != null && !keyword.isBlank()) {
            // Keyword only
            page = conversationRepository.searchByKeywordAndDeletedAtIsNull(keyword.trim(), unsorted);
        } else if (jlptLevel != null) {
            // Level only
            page = conversationRepository.findByJlptLevelAndDeletedAtIsNull(jlptLevel.name(), unsorted);
        } else if (status != null) {
            // Status only
            page = conversationRepository.findByStatusAndDeletedAtIsNull(status.name(), unsorted);
        } else {
            // No filters - all conversations
            page = conversationRepository.findByDeletedAtIsNull(unsorted);
        }

        return page.map(conversationMapper::toSummaryResponse);
    }

    @Override
    public ConversationResponse getPublishedConversation(UUID id) {
        Conversation conversation = conversationRepository.findById(id)
                .filter(c -> c.getDeletedAt() == null)
                .filter(c -> Course.ContentStatus.PUBLISHED.equals(c.getStatus()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Conversation", "id", id.toString()));
        return buildConversationResponse(conversation);
    }

    private Conversation findActiveConversation(UUID id) {
        return conversationRepository.findById(id)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Conversation", "id", id.toString()));
    }

    @Override
    public List<GeneratedConversationSuggestionResponse> generateConversationSuggestions(GenerateConversationRequest request) {
        topicRepository.findById(request.getTopicId())
                .filter(t -> t.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ConversationTopic", "id", request.getTopicId().toString()));

        Course course = courseRepository.findById(request.getCourseId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Course", "id", request.getCourseId().toString()));

        return generationService.generateConversationsForTopic(
                request.getTopicId(), request.getCourseId(), request.getTitle(), request.getDescription(),
                request.getDifficultyLevel(), course.getJlptLevel());
    }

    private ConversationResponse buildConversationResponse(Conversation conversation) {
        ConversationResponse response = conversationMapper.toResponse(conversation);
        if (conversation.getCourseId() != null) {
            courseRepository.findById(conversation.getCourseId())
                    .ifPresent(c -> response.setCourseName(c.getName()));
        }
        if (conversation.getTopicId() != null) {
            topicRepository.findById(conversation.getTopicId())
                    .ifPresent(t -> response.setTopicName(t.getTitle()));
        }
        List<ConversationMissionResponse> missions = missionRepository
                .findByConversationIdAndDeletedAtIsNullOrderByOrderIndexAsc(conversation.getId())
                .stream()
                .map(missionMapper::toResponse)
                .collect(Collectors.toList());
        response.setMissions(missions);
        return response;
    }
}
