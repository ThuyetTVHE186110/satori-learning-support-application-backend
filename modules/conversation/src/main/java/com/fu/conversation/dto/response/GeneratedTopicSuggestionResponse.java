package com.fu.conversation.dto.response;

import com.fu.conversation.entity.ConversationMission;
import com.fu.course.entity.Course;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * AI-generated topic suggestion (not persisted yet — manager reviews and saves).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratedTopicSuggestionResponse {

    private String title;
    private String titleJapanese;
    private String description;
    private String descriptionVi;
    private String scenario;
    private String category;
    private Course.JlptLevel jlptLevel;
    private Integer difficultyScore;
    private List<String> vocabularyHints;
    private List<String> grammarPoints;
    private List<String> usefulExpressions;

    /**
     * Suggested missions to accompany this topic.
     */
    private List<GeneratedMissionDto> missions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeneratedMissionDto {
        private String title;
        private String titleJapanese;
        private String titleVi;
        private String description;
        private String descriptionVi;
        private ConversationMission.MissionType missionType;
        private Map<String, Object> successCriteria;
        private List<String> requiredVocabulary;
        private List<String> requiredGrammar;
        private Integer orderIndex;
    }
}
