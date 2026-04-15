package com.fu.assignment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fu.assignment.entity.SubmissionStatus;
import com.fu.achievement.dto.response.UserAchievementResponse;
import com.fu.achievement.dto.response.LevelUpNotificationResponse;
import com.fu.achievement.dto.response.StreakNotificationResponse;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Full submission response (detail view).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubmissionResponse {

    private UUID id;
    private UUID assignmentId;
    private String assignmentTitle;     // enriched
    private UUID userId;
    private String userFullName;        // enriched
    private String userEmail;           // enriched

    private SubmissionStatus status;
    private Integer attemptNumber;

    private LocalDateTime startedAt;
    private LocalDateTime submittedAt;
    private LocalDateTime gradedAt;
    private UUID gradedBy;

    // Scoring
    private BigDecimal score;
    private Integer correctCount;
    private Integer totalQuestions;
    private String feedback;

    // Answers (JSON) — only returned to learner (own submission) or teacher/manager
    private String answers;

    // Writing submissions
    private List<String> imageUrls;

    // Detailed feedback for quiz
    private List<QuizSubmissionDetailResponse> quizDetails;

    private LocalDateTime createdAt;
}
