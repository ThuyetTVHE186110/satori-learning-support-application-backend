package com.fu.roleplay.mapper;

import com.fu.common.util.FuriganaAnnotator;
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
     * Ensure every response carries canonical ruby-syntax furigana regardless of how the
     * row was originally persisted. Covers three cases:
     * <ul>
     *   <li>Fresh messages written via {@link FuriganaAnnotator#annotate} — pass through.</li>
     *   <li>Legacy messages with degraded syntax ({@code 日本語[にほんご]}, {@code 食べる{たべる}},
     *       double readings) — strip and re-annotate.</li>
     *   <li>MISSION_INTRO / SCENE_INTRO rows from before the annotator landed, which have
     *       {@code japaneseWithFurigana} null — derive from {@code japaneseContent}.</li>
     * </ul>
     */
    @AfterMapping
    default void normalizeFurigana(@MappingTarget MessageResponse response) {
        String withFurigana = response.getJapaneseWithFurigana();
        if (withFurigana == null || withFurigana.isBlank()) {
            String plain = response.getJapaneseContent();
            if (plain != null && !plain.isBlank()) {
                response.setJapaneseWithFurigana(FuriganaAnnotator.annotate(plain));
            }
            return;
        }
        response.setJapaneseWithFurigana(FuriganaAnnotator.normalizeAndAnnotate(withFurigana));
    }
}
