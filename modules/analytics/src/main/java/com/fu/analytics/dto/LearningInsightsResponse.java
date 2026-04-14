package com.fu.analytics.dto;

import com.fu.infrastructure.learner.LearningInsightPoint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearningInsightsResponse {
    private List<LearningInsightPoint> weakPoints;
    private List<LearningInsightPoint> strongPoints;
    private List<String> recommendations;
    private LocalDateTime lastUpdated;
}
