package com.fu.infrastructure.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;
import java.util.UUID;

@Getter
public class RoleplaySessionCompletedEvent extends ApplicationEvent {

    private final UUID sessionId;
    private final UUID userId;
    private final double overallScore;
    private final double languageScore;
    private final double pronunciationScore;
    private final double fluencyScore;
    private final double accuracyScore;
    private final double vocabularyScore;
    private final double grammarScore;
    private final List<String> strengths;
    private final List<String> improvements;

    public RoleplaySessionCompletedEvent(Object source, UUID sessionId, UUID userId,
                                          double overallScore, double languageScore,
                                          double pronunciationScore, double fluencyScore,
                                          double accuracyScore, double vocabularyScore,
                                          double grammarScore,
                                          List<String> strengths, List<String> improvements) {
        super(source);
        this.sessionId = sessionId;
        this.userId = userId;
        this.overallScore = overallScore;
        this.languageScore = languageScore;
        this.pronunciationScore = pronunciationScore;
        this.fluencyScore = fluencyScore;
        this.accuracyScore = accuracyScore;
        this.vocabularyScore = vocabularyScore;
        this.grammarScore = grammarScore;
        this.strengths = strengths;
        this.improvements = improvements;
    }
}
