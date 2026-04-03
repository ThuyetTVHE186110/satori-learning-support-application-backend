package com.fu.roleplay.service;

import com.fu.roleplay.dto.request.CreateMissionRequest;
import com.fu.roleplay.dto.request.UpdateMissionRequest;
import com.fu.roleplay.dto.response.MissionResponse;

import java.util.List;
import java.util.UUID;

public interface MissionService {

    MissionResponse createMission(CreateMissionRequest request, UUID managerId);

    MissionResponse updateMission(UUID id, UpdateMissionRequest request, UUID managerId);

    void deleteMission(UUID id, UUID managerId);

    MissionResponse getMission(UUID id);

    List<MissionResponse> getMissionsByConversation(UUID conversationId);
}
