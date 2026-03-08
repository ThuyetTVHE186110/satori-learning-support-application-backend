package com.fu.conversation.dto.response;

import com.fu.course.entity.Course;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationTopicResponse {

    private UUID id;
    private UUID themeId;
    private String themeName;
    private UUID courseId;
    private String courseName;
    private String title;
    private String titleJapanese;
    private String description;
    private String descriptionVi;
    private String scenario;
    private String vocabularyHints;
    private String grammarPoints;
    private String usefulExpressions;
    private String category;
    private Course.JlptLevel jlptLevel;
    private Integer difficultyScore;
    private Boolean isRoleplay;
    private String roleplayContext;
    private String thumbnailUrl;
    private Course.ContentStatus status;
    private Integer orderIndex;

    private UUID createdBy;
    private UUID updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
