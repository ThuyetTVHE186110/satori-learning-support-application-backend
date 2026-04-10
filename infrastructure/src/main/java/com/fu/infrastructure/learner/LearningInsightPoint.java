package com.fu.infrastructure.learner;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Structured representation of a learning weak or strong point.
 * Stored as JSONB in UserLearningPreferences.weakPoints / strongPoints.
 */
public record LearningInsightPoint(
        String category,             // VOCABULARY | GRAMMAR | PRONUNCIATION | FLUENCY | SPEAKING
        String skill,                // specific sub-skill: "particle_usage", "te_form", "pitch_accent"
        String description,          // Vietnamese human-readable description
        double confidence,           // 0.0–1.0 how confident the system is about this insight
        String source,               // PRACTICE | ROLEPLAY | CONVERSATION | AGGREGATED
        String jlptLevel,            // nullable — N5, N4, N3, N2, N1
        List<UUID> relatedEntityIds, // nullable — vocab/grammar IDs for actionable linking
        LocalDateTime lastUpdated
) {}
