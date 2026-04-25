package com.fu.analytics.insight;

import com.fu.achievement.entity.StreakHistory;
import com.fu.achievement.entity.UserStats;
import com.fu.achievement.repository.StreakHistoryRepository;
import com.fu.achievement.repository.UserStatsRepository;
import com.fu.infrastructure.learner.LearningInsightPoint;
import com.fu.infrastructure.learner.SkillScore;
import com.fu.course.entity.Lesson;
import com.fu.course.repository.LessonRepository;
import com.fu.course.repository.GrammarPointRepository;
import com.fu.course.repository.VocabularyRepository;
import com.fu.practice.entity.ItemMastery;
import com.fu.practice.entity.LessonProgress;
import com.fu.practice.entity.PracticeSession;
import com.fu.practice.repository.ItemMasteryRepository;
import com.fu.practice.repository.LessonProgressRepository;
import com.fu.practice.repository.PracticeSessionRepository;
import com.fu.roleplay.entity.ConversationSession;
import com.fu.roleplay.entity.MessagePronunciation;
import com.fu.roleplay.repository.ConversationSessionRepository;
import com.fu.roleplay.repository.MessagePronunciationRepository;
import com.fu.analytics.dto.*;
import org.springframework.data.domain.PageRequest;
import com.fu.user.entity.UserLearningPreferences;
import com.fu.user.repository.UserLearningPreferencesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service providing read-only statistics for the learner dashboard.
 * Queries across practice, achievement, and user modules to build chart data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LearnerStatsService {

    private final UserLearningPreferencesRepository prefsRepository;
    private final StreakHistoryRepository streakHistoryRepository;
    private final UserStatsRepository userStatsRepository;
    private final PracticeSessionRepository practiceSessionRepository;
    private final LessonProgressRepository lessonProgressRepository;
    private final LessonRepository lessonRepository;
    private final ItemMasteryRepository itemMasteryRepository;
    private final VocabularyRepository vocabularyRepository;
    private final GrammarPointRepository grammarPointRepository;
    private final ConversationSessionRepository conversationSessionRepository;
    private final MessagePronunciationRepository messagePronunciationRepository;
    private final InsightRecommendationService recommendationService;

    private static final int MASTERED_LEVEL_THRESHOLD = 2;
    private static final int NEEDS_REVIEW_MAX_LEVEL = 1;
    private static final int NEEDS_REVIEW_LIMIT = 10;
    private static final String ENTITY_VOCABULARY = "VOCABULARY";
    private static final String ENTITY_GRAMMAR = "GRAMMAR";

    private static final List<String> SKILL_DIMENSIONS = List.of(
            "VOCABULARY", "GRAMMAR", "PRONUNCIATION", "FLUENCY", "SPEAKING");

    /** Skills sourced from roleplay/speaking sessions (Azure pronunciation + LLM language assessment). */
    private static final List<String> SPEAKING_SKILLS = List.of("PRONUNCIATION", "FLUENCY", "SPEAKING");

    /** Skills sourced from practice sessions (exercise generator + answer evaluator). */
    private static final List<String> PRACTICE_SKILLS = List.of("VOCABULARY", "GRAMMAR");

    // ── Radar Chart ─────────────────────────────────────────────────────────

    public SkillRadarResponse getSkillRadar(UUID userId) {
        return buildRadar(userId, SKILL_DIMENSIONS);
    }

    /** Speaking-only radar (PRONUNCIATION / FLUENCY / SPEAKING) for the speaking dashboard card. */
    public SkillRadarResponse getSpeakingSkillRadar(UUID userId) {
        return buildRadar(userId, SPEAKING_SKILLS);
    }

    /** Practice-only radar (VOCABULARY / GRAMMAR) for the practice dashboard card. */
    public SkillRadarResponse getPracticeSkillRadar(UUID userId) {
        return buildRadar(userId, PRACTICE_SKILLS);
    }

    private SkillRadarResponse buildRadar(UUID userId, List<String> dimensions) {
        UserLearningPreferences prefs = prefsRepository.findByUserId(userId).orElse(null);

        List<SkillScore> scores = prefs != null && prefs.getSkillScores() != null
                ? prefs.getSkillScores()
                : SKILL_DIMENSIONS.stream().map(SkillScore::defaultScore).toList();

        Set<String> present = scores.stream().map(SkillScore::skill).collect(Collectors.toSet());
        List<SkillScore> allScores = new ArrayList<>(scores);
        for (String skill : dimensions) {
            if (!present.contains(skill)) allScores.add(SkillScore.defaultScore(skill));
        }

        List<SkillRadarResponse.SkillItem> items = allScores.stream()
                .filter(s -> dimensions.contains(s.skill()))
                .map(s -> SkillRadarResponse.SkillItem.builder()
                        .skill(s.skill())
                        .score(s.score())
                        .previousScore(s.previousScore())
                        .trend(s.trend())
                        .color(SkillRadarResponse.colorForScore(s.score()))
                        .dataPoints(s.dataPoints())
                        .build())
                .toList();

        double overall = items.stream().mapToDouble(SkillRadarResponse.SkillItem::getScore).average().orElse(0);
        String targetLevel = prefs != null ? prefs.getTargetJlptLevel() : "N5";
        LocalDateTime lastUpdated = items.isEmpty() ? null : allScores.stream()
                .filter(s -> dimensions.contains(s.skill()))
                .map(SkillScore::lastUpdated)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        return SkillRadarResponse.builder()
                .skills(items)
                .overallScore(Math.round(overall * 100.0) / 100.0)
                .targetJlptLevel(targetLevel)
                .lastUpdated(lastUpdated)
                .build();
    }

    // ── Activity Heatmap ────────────────────────────────────────────────────

    public ActivityHeatmapResponse getActivityHeatmap(UUID userId, int months) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusMonths(months);

        List<StreakHistory> streaks = streakHistoryRepository.findStreaksByDateRange(
                userId, startDate, endDate);

        Map<LocalDate, List<StreakHistory>> byDate = streaks.stream()
                .collect(Collectors.groupingBy(StreakHistory::getStreakDate));

        List<ActivityHeatmapResponse.HeatmapDay> heatmap = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            List<StreakHistory> dayActivities = byDate.getOrDefault(date, List.of());
            int count = dayActivities.size();
            List<String> activities = dayActivities.stream()
                    .map(StreakHistory::getActivityType)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            heatmap.add(ActivityHeatmapResponse.HeatmapDay.builder()
                    .date(date)
                    .activityCount(count)
                    .intensity(ActivityHeatmapResponse.intensityForCount(count))
                    .activities(activities)
                    .build());
        }

        UserStats stats = userStatsRepository.findByUserId(userId).orElse(null);
        int currentStreak = stats != null ? stats.getCurrentStreak() : 0;
        int longestStreak = stats != null ? stats.getLongestStreak() : 0;
        int freezeCount = stats != null ? stats.getStreakFreezeCount() : 0;
        int totalActiveDays = (int) heatmap.stream()
                .filter(d -> d.getActivityCount() > 0).count();

        return ActivityHeatmapResponse.builder()
                .currentStreak(currentStreak)
                .longestStreak(longestStreak)
                .totalActiveDays(totalActiveDays)
                .heatmap(heatmap)
                .streakFreezeCount(freezeCount)
                .build();
    }

    // ── Weekly/Monthly Progress ─────────────────────────────────────────────

    public WeeklyProgressResponse getWeeklyProgress(UUID userId, int count) {
        LocalDate today = LocalDate.now();
        List<WeeklyProgressResponse.ProgressDataPoint> dataPoints = new ArrayList<>();

        for (int i = count - 1; i >= 0; i--) {
            LocalDate weekStart = today.minusWeeks(i).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate weekEnd = weekStart.plusDays(6);

            List<PracticeSession> sessions = practiceSessionRepository.findCompletedInDateRange(
                    userId, weekStart.atStartOfDay(), weekEnd.atTime(LocalTime.MAX));

            int weekNum = weekStart.get(WeekFields.ISO.weekOfWeekBasedYear());
            String label = String.format("W%d (%s - %s)", weekNum,
                    weekStart.format(DateTimeFormatter.ofPattern("dd/MM")),
                    weekEnd.format(DateTimeFormatter.ofPattern("dd/MM")));

            double avgScore = sessions.stream()
                    .map(PracticeSession::getScore)
                    .filter(Objects::nonNull)
                    .mapToDouble(BigDecimal::doubleValue)
                    .average().orElse(0);

            long studyMinutes = sessions.size() * 12L;

            List<StreakHistory> weekStreaks = streakHistoryRepository.findStreaksByDateRange(
                    userId, weekStart, weekEnd);
            int expEarned = weekStreaks.stream()
                    .mapToInt(sh -> sh.getExpEarned() != null ? sh.getExpEarned() : 0).sum();

            // Speaking — pronunciation (Azure, per-utterance)
            List<MessagePronunciation> utterances = messagePronunciationRepository
                    .findByUserIdAndCreatedAtBetween(userId,
                            weekStart.atStartOfDay(), weekEnd.atTime(LocalTime.MAX));
            Integer pronUtterances = utterances.isEmpty() ? null : utterances.size();
            Double pronOverall  = avgBigDecimal(utterances, MessagePronunciation::getOverallScore);
            Double pronAccuracy = avgBigDecimal(utterances, MessagePronunciation::getAccuracyScore);
            Double pronFluency  = avgBigDecimal(utterances, MessagePronunciation::getFluencyScore);

            // Speaking — language & task (LLM, per-completed-session)
            List<ConversationSession> speakingSessions = conversationSessionRepository
                    .findCompletedSpeakingInRange(userId,
                            weekStart.atStartOfDay(), weekEnd.atTime(LocalTime.MAX));
            Integer speakSessCount = speakingSessions.isEmpty() ? null : speakingSessions.size();
            Double languageOverall = avgBigDecimal(speakingSessions, ConversationSession::getLanguageScore);
            Double taskCompletion  = avgBigDecimal(speakingSessions, ConversationSession::getTaskCompletionScore);

            dataPoints.add(WeeklyProgressResponse.ProgressDataPoint.builder()
                    .label(label)
                    .startDate(weekStart)
                    .endDate(weekEnd)
                    .sessionsCompleted(sessions.size())
                    .totalStudyMinutes(studyMinutes)
                    .averageScore(Math.round(avgScore * 100.0) / 100.0)
                    .expEarned(expEarned)
                    .pronunciationUtterances(pronUtterances)
                    .pronunciationOverall(pronOverall)
                    .pronunciationAccuracy(pronAccuracy)
                    .pronunciationFluency(pronFluency)
                    .speakingSessions(speakSessCount)
                    .languageOverall(languageOverall)
                    .speakingTaskCompletion(taskCompletion)
                    .build());
        }

        WeeklyProgressResponse.TrendSummary trends = computeTrends(dataPoints);

        return WeeklyProgressResponse.builder()
                .period("WEEKLY")
                .dataPoints(dataPoints)
                .trends(trends)
                .build();
    }

    // ── Lesson Mastery ──────────────────────────────────────────────────────

    public LessonMasteryResponse getLessonMastery(UUID userId, UUID courseId) {
        List<Lesson> lessons = lessonRepository.findByCourseIdAndNotDeleted(courseId);

        List<LessonProgress> progressList = lessonProgressRepository.findByUserIdAndCourseId(userId, courseId);
        Map<UUID, LessonProgress> progressMap = progressList.stream()
                .collect(Collectors.toMap(LessonProgress::getLessonId, lp -> lp));

        List<LessonMasteryResponse.LessonMasteryItem> items = lessons.stream()
                .sorted(Comparator.comparingInt(Lesson::getOrderIndex))
                .map(lesson -> {
                    LessonProgress lp = progressMap.get(lesson.getId());
                    double vocabM = lp != null ? lp.getVocabMastery().doubleValue() : 0;
                    double grammarM = lp != null ? lp.getGrammarMastery().doubleValue() : 0;
                    double overall = (vocabM + grammarM) / 2.0;
                    int totalSess = lp != null ? lp.getTotalSessions() : 0;
                    double best = lp != null && lp.getBestScore() != null ? lp.getBestScore().doubleValue() : 0;

                    return LessonMasteryResponse.LessonMasteryItem.builder()
                            .lessonId(lesson.getId())
                            .lessonTitle(lesson.getTitle())
                            .orderIndex(lesson.getOrderIndex())
                            .vocabMastery(Math.round(vocabM * 100.0) / 100.0)
                            .grammarMastery(Math.round(grammarM * 100.0) / 100.0)
                            .overallMastery(Math.round(overall * 100.0) / 100.0)
                            .totalSessions(totalSess)
                            .bestScore(best)
                            .lastPracticed(lp != null ? lp.getLastPracticedAt() : null)
                            .status(LessonMasteryResponse.statusForMastery(overall))
                            .build();
                })
                .toList();

        int lessonsStarted = (int) items.stream().filter(i -> i.getTotalSessions() > 0).count();
        double overallMastery = items.stream()
                .mapToDouble(LessonMasteryResponse.LessonMasteryItem::getOverallMastery)
                .average().orElse(0);

        String courseName = lessons.isEmpty() ? "" : lessons.getFirst().getCourse().getName();

        return LessonMasteryResponse.builder()
                .courseId(courseId)
                .courseName(courseName)
                .lessons(items)
                .courseProgress(LessonMasteryResponse.CourseProgressSummary.builder()
                        .lessonsStarted(lessonsStarted)
                        .totalLessons(lessons.size())
                        .overallMastery(Math.round(overallMastery * 100.0) / 100.0)
                        .build())
                .build();
    }

    // ── Insights & Recommendations ──────────────────────────────────────────

    public LearningInsightsResponse getInsights(UUID userId) {
        UserLearningPreferences prefs = prefsRepository.findByUserId(userId).orElse(null);

        List<LearningInsightPoint> weakPoints = prefs != null && prefs.getWeakPoints() != null
                ? prefs.getWeakPoints() : List.of();
        List<LearningInsightPoint> strongPoints = prefs != null && prefs.getStrongPoints() != null
                ? prefs.getStrongPoints() : List.of();
        List<SkillScore> skillScores = prefs != null ? prefs.getSkillScores() : null;

        List<String> recommendations = recommendationService.generateRecommendations(
                weakPoints, strongPoints, skillScores);

        LocalDateTime lastUpdated = weakPoints.stream()
                .map(LearningInsightPoint::lastUpdated)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        return LearningInsightsResponse.builder()
                .weakPoints(weakPoints)
                .strongPoints(strongPoints)
                .recommendations(recommendations)
                .lastUpdated(lastUpdated)
                .build();
    }

    // ── Mastery Counters ────────────────────────────────────────────────────

    /**
     * Aggregate vocab + grammar mastery for the dashboard hero card.
     * Scope precedence: lessonId > courseId > global. PUBLISHED-only at course/global
     * scope to mirror the embedding gate in {@code EmbeddingService.retrieveContext}.
     */
    public MasteryCountResponse getMasteryCounts(UUID userId, UUID courseId, UUID lessonId) {
        ScopeIds scope = resolveScopeIds(courseId, lessonId);

        // Tier counts: GLOBAL = no entityIds filter; otherwise filter to scope.
        List<ItemMasteryRepository.MasteryTierCount> tierCounts = scope.allEntityIds() == null
                ? itemMasteryRepository.countTiersByUser(userId)
                : (scope.allEntityIds().isEmpty()
                        ? List.of()
                        : itemMasteryRepository.countTiersByUserAndEntityIds(userId, scope.allEntityIds()));

        MasteryCountResponse.Bucket vocabBucket = buildBucket(tierCounts, ENTITY_VOCABULARY, scope.vocabTotal());
        MasteryCountResponse.Bucket grammarBucket = buildBucket(tierCounts, ENTITY_GRAMMAR, scope.grammarTotal());

        // Needs-review list: items at level 0..1 in scope, oldest first.
        var pageable = PageRequest.of(0, NEEDS_REVIEW_LIMIT);
        List<ItemMastery> review = scope.allEntityIds() == null
                ? itemMasteryRepository.findNeedsReviewGlobal(userId, NEEDS_REVIEW_MAX_LEVEL, pageable)
                : (scope.allEntityIds().isEmpty()
                        ? List.of()
                        : itemMasteryRepository.findNeedsReviewScoped(
                                userId, NEEDS_REVIEW_MAX_LEVEL, scope.allEntityIds(), pageable));

        List<MasteryCountResponse.ReviewItem> reviewItems = review.stream()
                .map(im -> MasteryCountResponse.ReviewItem.builder()
                        .entityId(im.getEntityId())
                        .entityType(im.getEntityType())
                        .masteryLevel(im.getMasteryLevel())
                        .emaScore(im.getEmaScore() != null ? im.getEmaScore().doubleValue() : 0.0)
                        .lastPracticedAt(im.getLastPracticedAt())
                        .build())
                .toList();

        // lastUpdated = most-recent practice across the *full* scope, not just the
        // needsReview list. The needsReview list is bounded to weak items (level<=1)
        // so when a user has only mastered items it would otherwise return null.
        LocalDateTime lastUpdated = scope.allEntityIds() == null
                ? itemMasteryRepository.findMostRecentPracticedGlobal(userId)
                : (scope.allEntityIds().isEmpty()
                        ? null
                        : itemMasteryRepository.findMostRecentPracticedScoped(userId, scope.allEntityIds()));

        return MasteryCountResponse.builder()
                .scope(MasteryCountResponse.Scope.builder()
                        .type(scope.type)
                        .id(scope.id)
                        .build())
                .vocabulary(vocabBucket)
                .grammar(grammarBucket)
                .needsReview(reviewItems)
                .lastUpdated(lastUpdated)
                .build();
    }

    private ScopeIds resolveScopeIds(UUID courseId, UUID lessonId) {
        if (lessonId != null) {
            List<UUID> vocabIds = vocabularyRepository.findIdsByLessonId(lessonId);
            List<UUID> grammarIds = grammarPointRepository.findIdsByLessonId(lessonId);
            return new ScopeIds("LESSON", lessonId, vocabIds, grammarIds);
        }
        if (courseId != null) {
            List<UUID> vocabIds = vocabularyRepository.findIdsByCourseIdAndPublished(courseId);
            List<UUID> grammarIds = grammarPointRepository.findIdsByCourseIdAndPublished(courseId);
            return new ScopeIds("COURSE", courseId, vocabIds, grammarIds);
        }
        // Global: total = -1 (unknown). Tier counts use the unscoped query;
        // bucket.total falls back to (mastered + learning) so percent reflects engaged items.
        return new ScopeIds("GLOBAL", null, null, null);
    }

    private MasteryCountResponse.Bucket buildBucket(
            List<ItemMasteryRepository.MasteryTierCount> rows, String entityType, Long scopeTotal) {

        Map<Integer, Long> tierMap = new HashMap<>();
        for (var row : rows) {
            if (entityType.equals(row.getEntityType())) {
                tierMap.merge(row.getMasteryLevel(), row.getCnt(), Long::sum);
            }
        }

        long mastered = tierMap.getOrDefault(2, 0L) + tierMap.getOrDefault(3, 0L);
        long learning = tierMap.getOrDefault(0, 0L) + tierMap.getOrDefault(1, 0L);
        long total = scopeTotal != null ? scopeTotal : (mastered + learning);
        long notStarted = Math.max(0, total - (mastered + learning));
        int percent = total > 0 ? (int) Math.round(mastered * 100.0 / total) : 0;

        List<MasteryCountResponse.Tier> tiers = new ArrayList<>(4);
        for (int level = 0; level <= 3; level++) {
            tiers.add(MasteryCountResponse.Tier.builder()
                    .level(level)
                    .label(MasteryCountResponse.labelForLevel(level))
                    .count(tierMap.getOrDefault(level, 0L))
                    .build());
        }

        return MasteryCountResponse.Bucket.builder()
                .mastered(mastered)
                .learning(learning)
                .notStarted(notStarted)
                .total(total)
                .percent(percent)
                .tiers(tiers)
                .build();
    }

    /**
     * @param allEntityIds null = global (no filter); empty = scope resolved but contained
     *                     no items (e.g., empty lesson) → return zeros without querying.
     */
    private record ScopeIds(String type, UUID id, List<UUID> vocabIds, List<UUID> grammarIds) {
        Long vocabTotal() { return vocabIds == null ? null : (long) vocabIds.size(); }
        Long grammarTotal() { return grammarIds == null ? null : (long) grammarIds.size(); }
        List<UUID> allEntityIds() {
            if (vocabIds == null && grammarIds == null) return null;
            List<UUID> all = new ArrayList<>(
                    (vocabIds == null ? 0 : vocabIds.size())
                  + (grammarIds == null ? 0 : grammarIds.size()));
            if (vocabIds != null) all.addAll(vocabIds);
            if (grammarIds != null) all.addAll(grammarIds);
            return all;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private WeeklyProgressResponse.TrendSummary computeTrends(
            List<WeeklyProgressResponse.ProgressDataPoint> dataPoints) {
        if (dataPoints.size() < 2) {
            return WeeklyProgressResponse.TrendSummary.builder()
                    .scoreDirection("STABLE")
                    .studyTimeDirection("STABLE")
                    .consistencyRate(0)
                    .pronunciationDirection("STABLE")
                    .languageDirection("STABLE")
                    .build();
        }

        var prev = dataPoints.get(dataPoints.size() - 2);
        var curr = dataPoints.get(dataPoints.size() - 1);

        String scoreTrend = computeDirection(prev.getAverageScore(), curr.getAverageScore(), 2);
        String timeTrend = computeDirection(prev.getTotalStudyMinutes(), curr.getTotalStudyMinutes(), 10);
        String pronTrend = computeNullableDirection(prev.getPronunciationOverall(), curr.getPronunciationOverall(), 2);
        String langTrend = computeNullableDirection(prev.getLanguageOverall(), curr.getLanguageOverall(), 2);

        double consistency = dataPoints.stream()
                .filter(dp -> dp.getSessionsCompleted() > 0)
                .count() / (double) dataPoints.size();

        return WeeklyProgressResponse.TrendSummary.builder()
                .scoreDirection(scoreTrend)
                .studyTimeDirection(timeTrend)
                .consistencyRate(Math.round(consistency * 100.0) / 100.0)
                .pronunciationDirection(pronTrend)
                .languageDirection(langTrend)
                .sessionsDelta(curr.getSessionsCompleted() - prev.getSessionsCompleted())
                .scoreDelta(roundDelta(curr.getAverageScore() - prev.getAverageScore()))
                .studyMinutesDelta(curr.getTotalStudyMinutes() - prev.getTotalStudyMinutes())
                .speakingSessionsDelta(nullableDelta(curr.getSpeakingSessions(), prev.getSpeakingSessions()))
                .pronunciationDelta(nullableDoubleDelta(curr.getPronunciationOverall(), prev.getPronunciationOverall()))
                .languageDelta(nullableDoubleDelta(curr.getLanguageOverall(), prev.getLanguageOverall()))
                .build();
    }

    private static Integer nullableDelta(Integer curr, Integer prev) {
        if (curr == null || prev == null) return null;
        return curr - prev;
    }

    private static Double nullableDoubleDelta(Double curr, Double prev) {
        if (curr == null || prev == null) return null;
        return roundDelta(curr - prev);
    }

    private static double roundDelta(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String computeDirection(double prev, double curr, double threshold) {
        if (curr > prev + threshold) return "UP";
        if (curr < prev - threshold) return "DOWN";
        return "STABLE";
    }

    /** STABLE when either side is null (no data to compare). */
    private String computeNullableDirection(Double prev, Double curr, double threshold) {
        if (prev == null || curr == null) return "STABLE";
        return computeDirection(prev, curr, threshold);
    }

    /**
     * Average of nullable BigDecimal scores; returns null when no row produced a value.
     * null distinguishes "no data" from "user got 0" — matches the JSON contract where
     * null fields render as "—" in the UI rather than a misleading zero on the chart.
     */
    private static <T> Double avgBigDecimal(List<T> rows, java.util.function.Function<T, BigDecimal> getter) {
        var stats = rows.stream()
                .map(getter)
                .filter(Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .summaryStatistics();
        return stats.getCount() == 0 ? null : Math.round(stats.getAverage() * 100.0) / 100.0;
    }
}
