package com.fu.roleplay.mapper;

import com.fu.roleplay.dto.response.MessageResponse;
import com.fu.roleplay.entity.ConversationMessage;
import org.mapstruct.*;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface RoleplayMessageMapper {

    @Mapping(target = "pronunciationScore", ignore = true)
    MessageResponse toResponse(ConversationMessage message);
}
