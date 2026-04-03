package com.fu.conversation.mapper;

import com.fu.conversation.dto.request.CreateUserConversationRequest;
import com.fu.conversation.dto.request.UpdateUserConversationRequest;
import com.fu.conversation.dto.response.UserConversationResponse;
import com.fu.conversation.entity.UserConversation;
import org.mapstruct.*;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface UserConversationMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "aiGeneratedContext", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    UserConversation toEntity(CreateUserConversationRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "aiGeneratedContext", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntity(@MappingTarget UserConversation conversation, UpdateUserConversationRequest request);

    @Mapping(target = "courseName", ignore = true)
    UserConversationResponse toResponse(UserConversation conversation);
}
