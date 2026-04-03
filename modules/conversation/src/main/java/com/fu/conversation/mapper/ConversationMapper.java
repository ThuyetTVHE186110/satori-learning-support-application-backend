package com.fu.conversation.mapper;

import com.fu.conversation.dto.request.CreateConversationRequest;
import com.fu.conversation.dto.request.UpdateConversationRequest;
import com.fu.conversation.dto.response.ConversationResponse;
import com.fu.conversation.dto.response.ConversationSummaryResponse;
import com.fu.conversation.entity.Conversation;
import org.mapstruct.*;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface ConversationMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    Conversation toEntity(CreateConversationRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "topicId", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    void updateEntity(@MappingTarget Conversation conversation, UpdateConversationRequest request);

    @Mapping(target = "topicName", ignore = true)
    @Mapping(target = "courseName", ignore = true)
    @Mapping(target = "missions", ignore = true)
    ConversationResponse toResponse(Conversation conversation);

    @Mapping(target = "isPracticed", ignore = true)
    @Mapping(target = "practiceCount", ignore = true)
    @Mapping(target = "lastPracticedAt", ignore = true)
    @Mapping(target = "practiceStatus", ignore = true)
    ConversationSummaryResponse toSummaryResponse(Conversation conversation);
}
