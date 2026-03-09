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
public class ConversationTopicSummaryResponse {

    private UUID id;
    private UUID themeId;
    private String title;
    private String titleJapanese;
    private String descriptionVi;
    private String category;
    private Course.JlptLevel jlptLevel;
    private Integer difficultyScore;
    private Boolean isRoleplay;
    private String thumbnailUrl;
    private Course.ContentStatus status;
    private Integer orderIndex;

    // Practice tracking — populated only for learner endpoints
    private boolean isPracticed;
    private int practiceCount;
    private LocalDateTime lastPracticedAt;
}
