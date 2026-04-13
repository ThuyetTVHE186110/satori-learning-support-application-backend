package com.fu.infrastructure.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Getter
public class RoleplaySessionCompletedEvent extends ApplicationEvent {

    private final UUID sessionId;
    private final UUID userId;
    private final double overallScore;
    private final double accuracyScore;
    private final double pronunciationScore;
    private final double taskCompletionScore;
    private final double complexityScore;
    private final Map<String, Integer> errorBreakdown;
    private final Map<String, BigDecimal> jlptDistribution;

    public RoleplaySessionCompletedEvent(Object source, UUID sessionId, UUID userId,
                                          double overallScore, double accuracyScore,
                                          double pronunciationScore, double taskCompletionScore,
                                          double complexityScore,
                                          Map<String, Integer> errorBreakdown,
                                          Map<String, BigDecimal> jlptDistribution) {
        super(source);
        this.sessionId = sessionId;
        this.userId = userId;
        this.overallScore = overallScore;
        this.accuracyScore = accuracyScore;
        this.pronunciationScore = pronunciationScore;
        this.taskCompletionScore = taskCompletionScore;
        this.complexityScore = complexityScore;
        this.errorBreakdown = errorBreakdown;
        this.jlptDistribution = jlptDistribution;
    }
}
