package com.fu.achievement.mapper;

import com.fu.achievement.dto.request.BadgeCreateRequest;
import com.fu.achievement.dto.request.BadgeUpdateRequest;
import com.fu.achievement.dto.response.BadgeResponse;
import com.fu.achievement.entity.Badge;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface BadgeMapper {

    Badge toEntity(BadgeCreateRequest request);

    void updateEntity(@MappingTarget Badge badge, BadgeUpdateRequest request);

    // This won't map achieversCount, which is expected since achieversCount is populated via Repository JPQL.
    // For single badge retrieval, we might need a custom mapping in service.
    BadgeResponse toResponse(Badge badge);
}
