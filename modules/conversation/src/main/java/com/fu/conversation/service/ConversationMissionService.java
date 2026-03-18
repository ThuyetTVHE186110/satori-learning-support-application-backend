package com.fu.conversation.service;

import com.fu.conversation.dto.request.CreateMissionRequest;
import com.fu.conversation.dto.request.UpdateMissionRequest;
import com.fu.conversation.dto.response.ConversationMissionResponse;

import java.util.List;
import java.util.UUID;

public interface ConversationMissionService {

    ConversationMissionResponse createMission(CreateMissionRequest request, UUID managerId);

    ConversationMissionResponse updateMission(UUID id, UpdateMissionRequest request, UUID managerId);

    void deleteMission(UUID id, UUID managerId);

    ConversationMissionResponse getMission(UUID id);

    List<ConversationMissionResponse> getMissionsByTopic(UUID topicId);
}
