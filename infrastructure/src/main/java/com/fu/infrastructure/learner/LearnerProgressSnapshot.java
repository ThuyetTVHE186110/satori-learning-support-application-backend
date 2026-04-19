package com.fu.infrastructure.learner;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Cross-module DTO carrying a learner's practice progress for AI chatbot tools.
 *
 * <p>Consumed by {@code CurriculumQueryTools.getLearnerProgress} — exposed to the
 * LLM via Spring AI function calling so the chatbot can answer questions like
 * "em đang học bài nào?" or "tiến độ của em ra sao?".
 *
 * <p>All fields may be null/empty when the learner has no practice history. The
 * {@link #hasAnyData()} helper lets the caller fall back gracefully.
 */
public record LearnerProgressSnapshot(
        CurrentLessonInfo currentLesson,
        CourseProgressInfo courseProgress,
        List<WeakItem> weakItems,
        List<StrongItem> strongItems,
        int recentSessionsLast7d
) {

    public boolean hasAnyData() {
        return currentLesson != null
                || courseProgress != null
                || (weakItems != null && !weakItems.isEmpty())
                || recentSessionsLast7d > 0;
    }

    /** Bài học được luyện gần nhất. */
    public record CurrentLessonInfo(
            UUID lessonId,
            String title,
            String titleJapanese,
            Integer orderIndex,
            int totalSessions,
            BigDecimal bestScore,
            BigDecimal lastScore,
            BigDecimal vocabMastery,
            BigDecimal grammarMastery,
            LocalDateTime lastPracticedAt
    ) {}

    /** Tổng quan tiến độ khóa học. */
    public record CourseProgressInfo(
            UUID courseId,
            int lessonsStarted,
            int lessonsCompleted,
            int totalLessons,
            BigDecimal avgVocabMastery,
            BigDecimal avgGrammarMastery
    ) {}

    /** Top từ vựng / ngữ pháp yếu (mastery thấp). */
    public record WeakItem(
            UUID entityId,
            String entityType,
            String display,
            String lessonTitle,
            int masteryLevel,
            BigDecimal emaScore
    ) {}

    /** Top từ vựng / ngữ pháp mạnh (mastery cao). */
    public record StrongItem(
            UUID entityId,
            String entityType,
            String display,
            int masteryLevel
    ) {}
}
