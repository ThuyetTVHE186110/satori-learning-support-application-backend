package com.fu.conversation.mapper;

import com.fu.conversation.dto.response.ConversationMissionResponse;
import com.fu.conversation.entity.ConversationMission;
import org.mapstruct.Mapper;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface ConversationMissionMapper {

    ConversationMissionResponse toResponse(ConversationMission mission);
}
