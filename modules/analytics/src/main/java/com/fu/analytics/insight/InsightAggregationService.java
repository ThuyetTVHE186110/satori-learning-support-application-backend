package com.fu.analytics.insight;

import com.fu.infrastructure.learner.LearningInsightPoint;
import com.fu.infrastructure.learner.SkillScore;
import com.fu.infrastructure.event.LearningInsightsUpdatedEvent;
import com.fu.practice.entity.ItemMastery;
import com.fu.practice.repository.ItemMasteryRepository;
import com.fu.user.entity.User;
import com.fu.user.entity.UserLearningPreferences;
import com.fu.user.repository.UserLearningPreferencesRepository;
import com.fu.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates learning performance data from practice, roleplay, and conversation modules
 * into structured weak/strong points and skill scores (Satori Pentagon).
 *
 * <p>Five skill dimensions: VOCABULARY, GRAMMAR, PRONUNCIATION, FLUENCY, SPEAKING</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightAggregationService {

    private final UserLearningPreferencesRepository prefsRepository;
    private final UserRepository userRepository;
    private final ItemMasteryRepository itemMasteryRepository;
    private final ApplicationEventPublisher eventPublisher;

    private static final int MAX_INSIGHTS_PER_LIST = 20;
    private static final List<String> SKILL_DIMENSIONS = List.of(
            "VOCABULARY", "GRAMMAR", "PRONUNCIATION", "FLUENCY", "SPEAKING");

    // ── Practice aggregation ────────────────────────────────────────────────

    @Transactional
    public void aggregateFromPractice(UUID userId, UUID sessionId, double score) {
        log.debug("Aggregating practice insights for user {} from session {}", userId, sessionId);

        UserLearningPreferences prefs = getOrCreatePreferences(userId);

        // Compute vocab/grammar scores from ItemMastery
        List<ItemMastery> allMastery = itemMasteryRepository.findByUserIdOrderByEmaScoreAsc(userId);

        double vocabAvg = computeAverageEma(allMastery, "VOCABULARY");
        double grammarAvg = computeAverageEma(allMastery, "GRAMMAR");

        // Update skill scores
        List<SkillScore> scores = ensureAllSkills(prefs.getSkillScores());
        scores = updateSkill(scores, "VOCABULARY", vocabAvg * 100);
        scores = updateSkill(scores, "GRAMMAR", grammarAvg * 100);
        prefs.setSkillScores(scores);

        // Build weak/strong insights from mastery data
        List<LearningInsightPoint> weakPoints = new ArrayList<>(
                prefs.getWeakPoints() != null ? prefs.getWeakPoints() : List.of());
        List<LearningInsightPoint> strongPoints = new ArrayList<>(
                prefs.getStrongPoints() != null ? prefs.getStrongPoints() : List.of());

        // Weak: masteryLevel 0-1
        List<ItemMastery> weakItems = allMastery.stream()
                .filter(im -> im.getMasteryLevel() <= 1 && im.getAttemptCount() > 0)
                .limit(10)
                .toList();
        for (ItemMastery im : weakItems) {
            weakPoints = mergeInsight(weakPoints, new LearningInsightPoint(
                    im.getEntityType(),
                    "low_mastery_item",
                    buildWeakDescription(im),
                    1.0 - im.getEmaScore().doubleValue(),
                    "PRACTICE",
                    null,
                    List.of(im.getEntityId()),
                    LocalDateTime.now()));
        }

        // Strong: masteryLevel 3
        List<ItemMastery> strongItems = allMastery.stream()
                .filter(im -> im.getMasteryLevel() >= 3)
                .limit(10)
                .toList();
        for (ItemMastery im : strongItems) {
            strongPoints = mergeInsight(strongPoints, new LearningInsightPoint(
                    im.getEntityType(),
                    "mastered_item",
                    buildStrongDescription(im),
                    im.getEmaScore().doubleValue(),
                    "PRACTICE",
                    null,
                    List.of(im.getEntityId()),
                    LocalDateTime.now()));
        }

        prefs.setWeakPoints(trimInsights(weakPoints));
        prefs.setStrongPoints(trimInsights(strongPoints));
        prefsRepository.save(prefs);

        eventPublisher.publishEvent(new LearningInsightsUpdatedEvent(this, userId));
        log.info("Practice insights updated for user {}: vocabScore={}, grammarScore={}",
                userId, vocabAvg * 100, grammarAvg * 100);
    }

    // ── Roleplay aggregation ────────────────────────────────────────────────

    @Transactional
    public void aggregateFromRoleplay(UUID userId, double overallScore,
                                       double accuracyScore, double pronunciationScore,
                                       double taskCompletionScore, double complexityScore,
                                       Map<String, Integer> errorBreakdown) {
        log.debug("Aggregating roleplay insights for user {}", userId);

        UserLearningPreferences prefs = getOrCreatePreferences(userId);

        List<SkillScore> scores = ensureAllSkills(prefs.getSkillScores());
        scores = updateSkill(scores, "PRONUNCIATION", pronunciationScore);
        scores = updateSkill(scores, "SPEAKING", overallScore);
        scores = updateSkill(scores, "GRAMMAR", accuracyScore);
        scores = updateSkill(scores, "VOCABULARY", complexityScore);
        scores = updateSkill(scores, "FLUENCY", taskCompletionScore);
        prefs.setSkillScores(scores);

        // Use structured error breakdown for weak points (replaces fragile text classification)
        List<LearningInsightPoint> weakPoints = new ArrayList<>(
                prefs.getWeakPoints() != null ? prefs.getWeakPoints() : List.of());
        if (errorBreakdown != null) {
            for (Map.Entry<String, Integer> entry : errorBreakdown.entrySet()) {
                if (entry.getValue() <= 1) continue; // only flag repeated errors
                String category = mapErrorTypeToCategory(entry.getKey());
                String description = "Lỗi " + entry.getKey().toLowerCase() + " trong hội thoại: "
                        + entry.getValue() + " lần";
                weakPoints = mergeInsight(weakPoints, new LearningInsightPoint(
                        category, "roleplay_error_" + entry.getKey().toLowerCase(), description,
                        Math.min(1.0, entry.getValue() / 10.0),
                        "ROLEPLAY", null, null, LocalDateTime.now()));
            }
        }

        // Flag low scores as weak points
        if (accuracyScore < 60) {
            weakPoints = mergeInsight(weakPoints, new LearningInsightPoint(
                    "GRAMMAR", "roleplay_low_accuracy",
                    "Độ chính xác ngữ pháp thấp trong hội thoại: " + String.format("%.0f", accuracyScore) + "/100",
                    0.80, "ROLEPLAY", null, null, LocalDateTime.now()));
        }

        prefs.setWeakPoints(trimInsights(weakPoints));
        prefsRepository.save(prefs);

        eventPublisher.publishEvent(new LearningInsightsUpdatedEvent(this, userId));
        log.info("Roleplay insights updated for user {}: accuracy={}, pronunciation={}, taskCompletion={}, complexity={}",
                userId, accuracyScore, pronunciationScore, taskCompletionScore, complexityScore);
    }

    private String mapErrorTypeToCategory(String errorType) {
        return switch (errorType) {
            case "PARTICLE", "WORD_ORDER", "CONJUGATION" -> "GRAMMAR";
            case "WORD_CHOICE", "SPELLING" -> "VOCABULARY";
            case "FORMALITY" -> "GRAMMAR";
            default -> "GRAMMAR";
        };
    }

    // ── Conversation aggregation ────────────────────────────────────────────

    @Transactional
    public void aggregateFromConversation(UUID userId, int messageCount,
                                           int userMessageCount, int hintsUsed,
                                           int correctionsCount) {
        log.debug("Aggregating conversation insights for user {}", userId);

        UserLearningPreferences prefs = getOrCreatePreferences(userId);

        double engagementScore = userMessageCount > 0
                ? Math.min(100.0, (userMessageCount * 10.0)
                    - (hintsUsed * 5.0)
                    - (correctionsCount * 3.0))
                : 0.0;
        engagementScore = Math.max(0.0, engagementScore);

        List<SkillScore> scores = ensureAllSkills(prefs.getSkillScores());
        scores = updateSkill(scores, "SPEAKING", engagementScore);
        prefs.setSkillScores(scores);

        if (correctionsCount > 2) {
            List<LearningInsightPoint> weakPoints = new ArrayList<>(
                    prefs.getWeakPoints() != null ? prefs.getWeakPoints() : List.of());
            weakPoints = mergeInsight(weakPoints, new LearningInsightPoint(
                    "GRAMMAR", "conversation_corrections",
                    "Cần chú ý ngữ pháp khi hội thoại — có " + correctionsCount + " lần sửa lỗi",
                    Math.min(1.0, correctionsCount / 10.0),
                    "CONVERSATION", null, null, LocalDateTime.now()));
            prefs.setWeakPoints(trimInsights(weakPoints));
        }

        prefsRepository.save(prefs);
        eventPublisher.publishEvent(new LearningInsightsUpdatedEvent(this, userId));
        log.info("Conversation insights updated for user {}: engagement={}, corrections={}",
                userId, engagementScore, correctionsCount);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    UserLearningPreferences getOrCreatePreferences(UUID userId) {
        return prefsRepository.findByUserId(userId)
                .orElseGet(() -> {
                    User user = userRepository.findById(userId).orElseThrow();
                    UserLearningPreferences newPrefs = UserLearningPreferences.builder()
                            .user(user)
                            .dailyStudyGoalMinutes(30)
                            .learningPace("normal")
                            .preferredFormality("polite")
                            .streakReminderEnabled(true)
                            .skillScores(SKILL_DIMENSIONS.stream()
                                    .map(SkillScore::defaultScore)
                                    .collect(Collectors.toList()))
                            .weakPoints(new ArrayList<>())
                            .strongPoints(new ArrayList<>())
                            .build();
                    return prefsRepository.save(newPrefs);
                });
    }

    List<SkillScore> ensureAllSkills(List<SkillScore> existing) {
        if (existing == null) existing = new ArrayList<>();
        Set<String> presentSkills = existing.stream()
                .map(SkillScore::skill).collect(Collectors.toSet());
        List<SkillScore> result = new ArrayList<>(existing);
        for (String skill : SKILL_DIMENSIONS) {
            if (!presentSkills.contains(skill)) result.add(SkillScore.defaultScore(skill));
        }
        return result;
    }

    List<SkillScore> updateSkill(List<SkillScore> scores, String skill, double eventScore) {
        return scores.stream()
                .map(s -> s.skill().equals(skill) ? s.withNewScore(eventScore) : s)
                .collect(Collectors.toList());
    }

    private double computeAverageEma(List<ItemMastery> allMastery, String entityType) {
        return allMastery.stream()
                .filter(im -> entityType.equals(im.getEntityType()))
                .mapToDouble(im -> im.getEmaScore().doubleValue())
                .average()
                .orElse(0.5);
    }

    List<LearningInsightPoint> mergeInsight(List<LearningInsightPoint> existing,
                                             LearningInsightPoint incoming) {
        List<LearningInsightPoint> result = new ArrayList<>();
        boolean merged = false;
        for (LearningInsightPoint e : existing) {
            if (Objects.equals(e.category(), incoming.category())
                    && Objects.equals(e.skill(), incoming.skill())
                    && Objects.equals(e.relatedEntityIds(), incoming.relatedEntityIds())) {
                result.add(incoming);
                merged = true;
            } else {
                result.add(e);
            }
        }
        if (!merged) result.add(incoming);
        return result;
    }

    private List<LearningInsightPoint> trimInsights(List<LearningInsightPoint> insights) {
        if (insights.size() <= MAX_INSIGHTS_PER_LIST) return insights;
        return insights.stream()
                .sorted(Comparator.comparingDouble(LearningInsightPoint::confidence).reversed())
                .limit(MAX_INSIGHTS_PER_LIST)
                .collect(Collectors.toList());
    }

    String classifyInsightText(String text) {
        if (text == null) return "SPEAKING";
        String lower = text.toLowerCase();
        if (lower.contains("ngữ pháp") || lower.contains("grammar") || lower.contains("文法")
                || lower.contains("chia động từ") || lower.contains("conjugat")) return "GRAMMAR";
        if (lower.contains("từ vựng") || lower.contains("vocabulary") || lower.contains("語彙")
                || lower.contains("vocab")) return "VOCABULARY";
        if (lower.contains("phát âm") || lower.contains("pronunciation") || lower.contains("発音")
                || lower.contains("intonation") || lower.contains("ngữ điệu")) return "PRONUNCIATION";
        if (lower.contains("lưu loát") || lower.contains("fluency") || lower.contains("流暢")
                || lower.contains("trôi chảy")) return "FLUENCY";
        return "SPEAKING";
    }

    private String buildWeakDescription(ItemMastery im) {
        String type = "VOCABULARY".equals(im.getEntityType()) ? "Từ vựng" : "Ngữ pháp";
        return String.format("%s cần ôn tập — đúng %d/%d lần (EMA: %.0f%%)",
                type, im.getCorrectCount(), im.getAttemptCount(),
                im.getEmaScore().doubleValue() * 100);
    }

    private String buildStrongDescription(ItemMastery im) {
        String type = "VOCABULARY".equals(im.getEntityType()) ? "Từ vựng" : "Ngữ pháp";
        return String.format("%s đã thành thạo — đúng %d/%d lần (EMA: %.0f%%)",
                type, im.getCorrectCount(), im.getAttemptCount(),
                im.getEmaScore().doubleValue() * 100);
    }
}
