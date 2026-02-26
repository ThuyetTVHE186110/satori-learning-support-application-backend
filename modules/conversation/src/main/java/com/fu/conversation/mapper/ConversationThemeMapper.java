package com.fu.conversation.mapper;

import com.fu.conversation.dto.request.CreateThemeRequest;
import com.fu.conversation.dto.request.UpdateThemeRequest;
import com.fu.conversation.dto.response.ConversationThemeResponse;
import com.fu.conversation.dto.response.ConversationThemeSummaryResponse;
import com.fu.conversation.entity.ConversationTheme;
import org.mapstruct.*;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface ConversationThemeMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "aiGeneratedContext", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    ConversationTheme toEntity(CreateThemeRequest request);

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
    void updateEntity(@MappingTarget ConversationTheme theme, UpdateThemeRequest request);

    @Mapping(target = "courseName", ignore = true)
    @Mapping(target = "topicCount", ignore = true)
    ConversationThemeResponse toResponse(ConversationTheme theme);

    @Mapping(target = "topicCount", ignore = true)
    ConversationThemeSummaryResponse toSummaryResponse(ConversationTheme theme);
}
