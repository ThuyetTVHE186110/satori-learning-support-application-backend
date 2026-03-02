package com.fu.roleplay.mapper;

import com.fu.roleplay.dto.request.CreateMissionRequest;
import com.fu.roleplay.dto.request.UpdateMissionRequest;
import com.fu.roleplay.dto.response.MissionResponse;
import com.fu.roleplay.dto.response.SessionMissionResponse;
import com.fu.roleplay.entity.ConversationMission;
import com.fu.roleplay.entity.SessionMission;
import org.mapstruct.*;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface RoleplayMissionMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    ConversationMission toEntity(CreateMissionRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "topicId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    void updateEntity(@MappingTarget ConversationMission mission, UpdateMissionRequest request);

    MissionResponse toResponse(ConversationMission mission);

    SessionMissionResponse toSessionMissionResponse(SessionMission sessionMission);
}
