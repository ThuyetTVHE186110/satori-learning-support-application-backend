package com.fu.roleplay.service.impl;

import com.fu.common.exception.ResourceNotFoundException;
import com.fu.roleplay.dto.request.CreateMissionRequest;
import com.fu.roleplay.dto.request.UpdateMissionRequest;
import com.fu.roleplay.dto.response.MissionResponse;
import com.fu.roleplay.entity.ConversationMission;
import com.fu.roleplay.mapper.RoleplayMissionMapper;
import com.fu.roleplay.repository.ConversationMissionRepository;
import com.fu.roleplay.service.MissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class MissionServiceImpl implements MissionService {

    private final ConversationMissionRepository missionRepository;
    private final RoleplayMissionMapper missionMapper;

    @Override
    @Transactional
    public MissionResponse createMission(CreateMissionRequest request, UUID managerId) {
        ConversationMission mission = missionMapper.toEntity(request);
        mission.setCreatedBy(managerId);
        mission.setUpdatedBy(managerId);
        return missionMapper.toResponse(missionRepository.save(mission));
    }

    @Override
    @Transactional
    public MissionResponse updateMission(UUID id, UpdateMissionRequest request, UUID managerId) {
        ConversationMission mission = findActiveMission(id);
        missionMapper.updateEntity(mission, request);
        mission.setUpdatedBy(managerId);
        return missionMapper.toResponse(missionRepository.save(mission));
    }

    @Override
    @Transactional
    public void deleteMission(UUID id, UUID managerId) {
        ConversationMission mission = findActiveMission(id);
        mission.setDeletedAt(LocalDateTime.now());
        mission.setDeletedBy(managerId);
        missionRepository.save(mission);
        log.info("Soft-deleted conversation mission: {}", id);
    }

    @Override
    public MissionResponse getMission(UUID id) {
        return missionMapper.toResponse(findActiveMission(id));
    }

    @Override
    public List<MissionResponse> getMissionsByTopic(UUID topicId) {
        return missionRepository.findByTopicIdAndDeletedAtIsNullOrderByOrderIndexAsc(topicId)
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
