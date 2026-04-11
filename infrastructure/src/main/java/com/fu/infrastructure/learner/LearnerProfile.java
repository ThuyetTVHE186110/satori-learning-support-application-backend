package com.fu.infrastructure.learner;

import java.util.List;

/**
 * Lightweight cross-module DTO carrying a learner's skill scores and insights.
 * Used by AI prompt builders (chatbot, roleplay) for personalization.
 */
public record LearnerProfile(
        List<SkillScore> skillScores,
        List<LearningInsightPoint> weakPoints,
        List<LearningInsightPoint> strongPoints
) {}
