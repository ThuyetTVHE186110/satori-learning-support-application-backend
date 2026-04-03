package com.fu.conversation.mapper;

import com.fu.conversation.dto.request.CreateTopicRequest;
import com.fu.conversation.dto.request.UpdateTopicRequest;
import com.fu.conversation.dto.response.ConversationTopicResponse;
import com.fu.conversation.dto.response.ConversationTopicSummaryResponse;
import com.fu.conversation.entity.ConversationTopic;
import org.mapstruct.*;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface ConversationTopicMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "aiGeneratedContext", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    ConversationTopic toEntity(CreateTopicRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "aiGeneratedContext", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    void updateEntity(@MappingTarget ConversationTopic topic, UpdateTopicRequest request);

    @Mapping(target = "courseName", ignore = true)
    @Mapping(target = "conversationCount", ignore = true)
    ConversationTopicResponse toResponse(ConversationTopic topic);

    @Mapping(target = "conversationCount", ignore = true)
    ConversationTopicSummaryResponse toSummaryResponse(ConversationTopic topic);
}
