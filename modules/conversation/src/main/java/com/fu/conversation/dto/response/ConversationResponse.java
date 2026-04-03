package com.fu.conversation.dto.response;

import com.fu.course.entity.Course;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationResponse {

    private UUID id;
    private UUID topicId;
    private String topicName;
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
    private String aiRole;
    private String learnerRole;
    private String roleplayContext;
    private String thumbnailUrl;
    private Course.ContentStatus status;
    private Integer orderIndex;

    private List<ConversationMissionResponse> missions;

    private UUID createdBy;
    private UUID updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
