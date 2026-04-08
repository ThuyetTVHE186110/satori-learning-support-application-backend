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
public class ConversationSummaryResponse {

    private UUID id;
    private UUID topicId;
    private String title;
    private String titleJapanese;
    private String descriptionVi;
    private String category;
    private Course.JlptLevel jlptLevel;
    private Integer difficultyScore;
    private String thumbnailUrl;
    private Course.ContentStatus status;
    private Integer orderIndex;
    private String aiRole;
    private String learnerRole;


    // Practice tracking — populated only for learner endpoints
    private boolean isPracticed;
    private int practiceCount;
    private LocalDateTime lastPracticedAt;
    private PracticeStatus practiceStatus;

    public enum PracticeStatus {
        NOT_STARTED,
        IN_PROGRESS,
        COMPLETED
    }
}
