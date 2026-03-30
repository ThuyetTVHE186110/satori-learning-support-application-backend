package com.fu.roleplay.mapper;

import com.fu.roleplay.dto.response.SessionResponse;
import com.fu.roleplay.dto.response.SessionSummaryResponse;
import com.fu.roleplay.entity.ConversationSession;
import org.mapstruct.*;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface RoleplaySessionMapper {

    @Mapping(target = "messages", ignore = true)
    @Mapping(target = "missions", ignore = true)
    SessionResponse toResponse(ConversationSession session);

    SessionSummaryResponse toSummaryResponse(ConversationSession session);
}
