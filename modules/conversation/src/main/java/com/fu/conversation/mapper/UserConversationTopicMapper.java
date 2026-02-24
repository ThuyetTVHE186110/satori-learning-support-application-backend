package com.fu.conversation.mapper;

import com.fu.conversation.dto.request.CreateUserTopicRequest;
import com.fu.conversation.dto.request.UpdateUserTopicRequest;
import com.fu.conversation.dto.response.UserConversationTopicResponse;
import com.fu.conversation.entity.UserConversationTopic;
import org.mapstruct.*;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface UserConversationTopicMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "aiGeneratedContext", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    UserConversationTopic toEntity(CreateUserTopicRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "aiGeneratedContext", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntity(@MappingTarget UserConversationTopic topic, UpdateUserTopicRequest request);

    @Mapping(target = "courseName", ignore = true)
    UserConversationTopicResponse toResponse(UserConversationTopic topic);
}
