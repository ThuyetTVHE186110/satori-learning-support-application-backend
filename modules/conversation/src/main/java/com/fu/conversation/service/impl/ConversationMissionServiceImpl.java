package com.fu.conversation.service.impl;

import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.dto.request.CreateMissionRequest;
import com.fu.conversation.dto.request.UpdateMissionRequest;
import com.fu.conversation.dto.response.ConversationMissionResponse;
import com.fu.conversation.entity.ConversationMission;
import com.fu.conversation.mapper.ConversationMissionMapper;
import com.fu.conversation.repository.ConversationMissionRepository;
import com.fu.conversation.repository.ConversationTopicRepository;
import com.fu.conversation.service.ConversationMissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConversationMissionServiceImpl implements ConversationMissionService {

    private final ConversationMissionRepository missionRepository;
    private final ConversationMissionMapper missionMapper;
    private final ConversationTopicRepository topicRepository;

    @Override
    @Transactional
    public ConversationMissionResponse createMission(CreateMissionRequest request, UUID managerId) {
        topicRepository.findById(request.getTopicId())
                .filter(t -> t.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ConversationTopic", "id", request.getTopicId().toString()));

        ConversationMission mission = missionMapper.toEntity(request);
        mission.setCreatedBy(managerId);
        mission.setUpdatedBy(managerId);
        ConversationMission saved = missionRepository.save(mission);
        log.info("Manager {} created mission: {} for topic: {}", managerId, saved.getId(), request.getTopicId());
        return missionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public ConversationMissionResponse updateMission(UUID id, UpdateMissionRequest request, UUID managerId) {
        ConversationMission mission = findActiveMission(id);
        missionMapper.updateEntity(mission, request);
        mission.setUpdatedBy(managerId);
        ConversationMission saved = missionRepository.save(mission);
        log.info("Manager {} updated mission: {}", managerId, id);
        return missionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteMission(UUID id, UUID managerId) {
        ConversationMission mission = findActiveMission(id);
        mission.softDelete(managerId);
        missionRepository.save(mission);
        log.info("Manager {} soft-deleted mission: {}", managerId, id);
    }

    @Override
    public ConversationMissionResponse getMission(UUID id) {
        return missionMapper.toResponse(findActiveMission(id));
    }

    @Override
    public List<ConversationMissionResponse> getMissionsByTopic(UUID topicId) {
        return missionRepository
                .findByTopicIdAndDeletedAtIsNullOrderByOrderIndexAsc(topicId)
                .stream()
                .map(missionMapper::toResponse)
                .collect(Collectors.toList());
    }

    private ConversationMission findActiveMission(UUID id) {
        return missionRepository.findById(id)
                .filter(m -> m.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ConversationMission", "id", id.toString()));
    }
}
