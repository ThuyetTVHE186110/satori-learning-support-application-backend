package com.fu.analytics.insight;

import com.fu.infrastructure.event.PracticeFeedbackReadyEvent;
import com.fu.infrastructure.event.ConversationSessionCompletedEvent;
import com.fu.infrastructure.event.RoleplaySessionCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InsightEventListener {

    private final InsightAggregationService aggregationService;

    @Async
    @EventListener
    public void onPracticeFeedbackReady(PracticeFeedbackReadyEvent event) {
        try {
            log.debug("Insight aggregation triggered by practice session {} for user {}",
                    event.getSessionId(), event.getUserId());
            aggregationService.aggregateFromPractice(
                    event.getUserId(), event.getSessionId(), event.getScore());
        } catch (Exception e) {
            log.error("Failed to aggregate practice insights for user {}: {}",
                    event.getUserId(), e.getMessage(), e);
        }
    }

    @Async
    @EventListener
    public void onRoleplaySessionCompleted(RoleplaySessionCompletedEvent event) {
        try {
            log.debug("Insight aggregation triggered by roleplay session {} for user {}",
                    event.getSessionId(), event.getUserId());
            aggregationService.aggregateFromRoleplay(
                    event.getUserId(),
                    event.getOverallScore(), event.getAccuracyScore(),
                    event.getPronunciationScore(), event.getTaskCompletionScore(),
                    event.getComplexityScore(), event.getErrorBreakdown());
        } catch (Exception e) {
            log.error("Failed to aggregate roleplay insights for user {}: {}",
                    event.getUserId(), e.getMessage(), e);
        }
    }

    @Async
    @EventListener
    public void onConversationSessionCompleted(ConversationSessionCompletedEvent event) {
        try {
            log.debug("Insight aggregation triggered by conversation session {} for user {}",
                    event.getSessionId(), event.getUserId());
            aggregationService.aggregateFromConversation(
                    event.getUserId(),
                    event.getMessageCount(), event.getUserMessageCount(),
                    event.getHintsUsed(), event.getCorrectionsCount());
        } catch (Exception e) {
            log.error("Failed to aggregate conversation insights for user {}: {}",
                    event.getUserId(), e.getMessage(), e);
        }
    }
}
