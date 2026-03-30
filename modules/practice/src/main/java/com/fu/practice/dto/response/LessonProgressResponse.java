package com.fu.practice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tiến độ luyện tập của học viên cho một bài học.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LessonProgressResponse {

    private UUID lessonId;
    private int totalSessions;
    private BigDecimal bestScore;
    private BigDecimal lastScore;
    private BigDecimal vocabMastery;
    private BigDecimal grammarMastery;
    private LocalDateTime lastPracticedAt;
}
