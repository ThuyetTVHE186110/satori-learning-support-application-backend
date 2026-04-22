package com.fu.roleplay.mapper;

import com.fu.common.util.FuriganaRubyRepair;
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

    /**
     * Historical messages persisted before the write-path repair landed hold
     * furigana in legacy format (e.g. {@code 日本語[にほんご]}). Normalize on
     * read so the frontend always receives canonical {@code [日本語]{にほんご}}.
     */
    @AfterMapping
    default void normalizeFurigana(@MappingTarget MessageResponse response) {
        if (response.getJapaneseWithFurigana() != null) {
            response.setJapaneseWithFurigana(
                    FuriganaRubyRepair.repair(response.getJapaneseWithFurigana()));
        }
    }
}
