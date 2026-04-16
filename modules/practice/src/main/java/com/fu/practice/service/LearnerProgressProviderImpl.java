package com.fu.practice.service;

import com.fu.course.entity.GrammarPoint;
import com.fu.course.entity.Lesson;
import com.fu.course.entity.Vocabulary;
import com.fu.course.repository.GrammarPointRepository;
import com.fu.course.repository.LessonRepository;
import com.fu.course.repository.VocabularyRepository;
import com.fu.infrastructure.learner.LearnerProgressProvider;
import com.fu.infrastructure.learner.LearnerProgressSnapshot;
import com.fu.practice.entity.ItemMastery;
import com.fu.practice.entity.LessonProgress;
import com.fu.practice.entity.PracticeStatus;
import com.fu.practice.repository.ItemMasteryRepository;
import com.fu.practice.repository.LessonProgressRepository;
import com.fu.practice.repository.PracticeSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Practice-module implementation of {@link LearnerProgressProvider}.
 * Assembles a rich snapshot for the chatbot's {@code getLearnerProgress} tool.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LearnerProgressProviderImpl implements LearnerProgressProvider {

    private static final int WEAK_ITEMS_LIMIT   = 5;
    private static final int STRONG_ITEMS_LIMIT = 3;
    private static final int RECENT_DAYS        = 7;

    private final LessonProgressRepository lessonProgressRepository;
    private final ItemMasteryRepository itemMasteryRepository;
    private final PracticeSessionRepository sessionRepository;
    private final LessonRepository lessonRepository;
    private final VocabularyRepository vocabularyRepository;
    private final GrammarPointRepository grammarPointRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<LearnerProgressSnapshot> getProgress(UUID userId, UUID courseId) {
        if (userId == null) return Optional.empty();

        try {
            LearnerProgressSnapshot.CurrentLessonInfo current = buildCurrentLesson(userId, courseId);
            LearnerProgressSnapshot.CourseProgressInfo course = buildCourseProgress(userId, courseId, current);
            List<LearnerProgressSnapshot.WeakItem>   weak   = buildWeakItems(userId);
            List<LearnerProgressSnapshot.StrongItem> strong = buildStrongItems(userId);
            int recent = countRecentSessions(userId);

            LearnerProgressSnapshot snap = new LearnerProgressSnapshot(
                    current, course, weak, strong, recent);
            return snap.hasAnyData() ? Optional.of(snap) : Optional.empty();
        } catch (Exception e) {
            log.warn("Không build được progress snapshot cho userId={}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Current lesson ─────────────────────────────────────────────────────

    private LearnerProgressSnapshot.CurrentLessonInfo buildCurrentLesson(UUID userId, UUID courseId) {
        Optional<LessonProgress> latest = (courseId != null)
                ? lessonProgressRepository.findTopByUserIdAndCourseIdOrderByLastPracticedAtDesc(userId, courseId)
                : lessonProgressRepository.findTopByUserIdOrderByLastPracticedAtDesc(userId);
        if (latest.isEmpty()) return null;

        LessonProgress lp = latest.get();
        Lesson lesson = lessonRepository.findByIdAndNotDeleted(lp.getLessonId()).orElse(null);
        String title           = lesson != null ? lesson.getTitle()         : null;
        String titleJapanese   = lesson != null ? lesson.getTitleJapanese() : null;
        Integer orderIndex     = lesson != null ? lesson.getOrderIndex()    : null;

        return new LearnerProgressSnapshot.CurrentLessonInfo(
                lp.getLessonId(),
                title, titleJapanese, orderIndex,
                lp.getTotalSessions(),
                lp.getBestScore(), lp.getLastScore(),
                lp.getVocabMastery(), lp.getGrammarMastery(),
                lp.getLastPracticedAt());
    }

    // ── Course progress ────────────────────────────────────────────────────

    private LearnerProgressSnapshot.CourseProgressInfo buildCourseProgress(
            UUID userId, UUID courseId, LearnerProgressSnapshot.CurrentLessonInfo current) {

        UUID effectiveCourseId = courseId;
        if (effectiveCourseId == null && current != null) {
            // Best effort — pull courseId from the current lesson's entity.
            effectiveCourseId = lessonRepository.findByIdAndNotDeleted(current.lessonId())
                    .map(l -> l.getCourse() != null ? l.getCourse().getId() : null)
                    .orElse(null);
        }
        if (effectiveCourseId == null) return null;

        List<LessonProgress> progressList = lessonProgressRepository
                .findByUserIdAndCourseId(userId, effectiveCourseId);
        if (progressList.isEmpty()) return null;

        int started = progressList.size();
        int completed = (int) progressList.stream()
                .filter(lp -> lp.getBestScore() != null
                           && lp.getBestScore().compareTo(BigDecimal.valueOf(70)) >= 0)
                .count();

        BigDecimal avgVocab   = averageMastery(progressList, LessonProgress::getVocabMastery);
        BigDecimal avgGrammar = averageMastery(progressList, LessonProgress::getGrammarMastery);

        long totalLessons = lessonRepository.countByCourseIdAndDeletedAtIsNull(effectiveCourseId);

        return new LearnerProgressSnapshot.CourseProgressInfo(
                effectiveCourseId, started, completed, (int) totalLessons, avgVocab, avgGrammar);
    }

    private BigDecimal averageMastery(List<LessonProgress> list,
                                       java.util.function.Function<LessonProgress, BigDecimal> extractor) {
        List<BigDecimal> vals = list.stream()
                .map(extractor).filter(Objects::nonNull).collect(Collectors.toList());
        if (vals.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = vals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(vals.size()), 2, RoundingMode.HALF_UP);
    }

    // ── Weak / strong items ────────────────────────────────────────────────

    private List<LearnerProgressSnapshot.WeakItem> buildWeakItems(UUID userId) {
        List<ItemMastery> weakest = itemMasteryRepository.findByUserIdOrderByEmaScoreAsc(userId);
        if (weakest.isEmpty()) return List.of();

        // Chỉ lấy những item đã được luyện ít nhất 1 lần (attemptCount > 0)
        List<ItemMastery> candidates = weakest.stream()
                .filter(m -> m.getAttemptCount() > 0)
                .limit(WEAK_ITEMS_LIMIT)
                .collect(Collectors.toList());
        if (candidates.isEmpty()) return List.of();

        Map<UUID, String> vocabDisplays   = resolveVocabDisplays(candidates);
        Map<UUID, String> grammarDisplays = resolveGrammarDisplays(candidates);
        Map<UUID, String> lessonTitles    = resolveLessonTitlesForItems(candidates,
                                                                        vocabDisplays.keySet(),
                                                                        grammarDisplays.keySet());

        return candidates.stream().map(m -> new LearnerProgressSnapshot.WeakItem(
                m.getEntityId(),
                m.getEntityType(),
                displayFor(m, vocabDisplays, grammarDisplays),
                lessonTitles.getOrDefault(m.getEntityId(), ""),
                m.getMasteryLevel(),
                m.getEmaScore()
        )).collect(Collectors.toList());
    }

    private List<LearnerProgressSnapshot.StrongItem> buildStrongItems(UUID userId) {
        List<ItemMastery> strongest = itemMasteryRepository
                .findByUserIdAndMasteryLevelGreaterThanEqual(userId, 2);
        if (strongest.isEmpty()) return List.of();

        // Sắp xếp ema DESC để lấy top
        strongest = strongest.stream()
                .sorted(Comparator.comparing(ItemMastery::getEmaScore).reversed())
                .limit(STRONG_ITEMS_LIMIT)
                .collect(Collectors.toList());

        Map<UUID, String> vocabDisplays   = resolveVocabDisplays(strongest);
        Map<UUID, String> grammarDisplays = resolveGrammarDisplays(strongest);

        return strongest.stream().map(m -> new LearnerProgressSnapshot.StrongItem(
                m.getEntityId(),
                m.getEntityType(),
                displayFor(m, vocabDisplays, grammarDisplays),
                m.getMasteryLevel()
        )).collect(Collectors.toList());
    }

    private String displayFor(ItemMastery m,
                               Map<UUID, String> vocabDisplays,
                               Map<UUID, String> grammarDisplays) {
        if ("VOCABULARY".equals(m.getEntityType())) {
            return vocabDisplays.getOrDefault(m.getEntityId(), "?");
        }
        return grammarDisplays.getOrDefault(m.getEntityId(), "?");
    }

    private Map<UUID, String> resolveVocabDisplays(List<ItemMastery> items) {
        List<UUID> vocabIds = items.stream()
                .filter(m -> "VOCABULARY".equals(m.getEntityType()))
                .map(ItemMastery::getEntityId).collect(Collectors.toList());
        if (vocabIds.isEmpty()) return Map.of();
        Map<UUID, String> result = new HashMap<>();
        for (Vocabulary v : vocabularyRepository.findAllById(vocabIds)) {
            String display = v.getJapaneseText();
            if (v.getReading() != null && !v.getReading().isBlank()) {
                display += " [" + v.getReading() + "]";
            }
            if (v.getMeaningVi() != null && !v.getMeaningVi().isBlank()) {
                display += " — " + v.getMeaningVi();
            }
            result.put(v.getId(), display);
        }
        return result;
    }

    private Map<UUID, String> resolveGrammarDisplays(List<ItemMastery> items) {
        List<UUID> grammarIds = items.stream()
                .filter(m -> "GRAMMAR".equals(m.getEntityType()))
                .map(ItemMastery::getEntityId).collect(Collectors.toList());
        if (grammarIds.isEmpty()) return Map.of();
        Map<UUID, String> result = new HashMap<>();
        for (GrammarPoint g : grammarPointRepository.findAllById(grammarIds)) {
            String display = g.getPattern();
            if (g.getMeaningVi() != null && !g.getMeaningVi().isBlank()) {
                display += " — " + g.getMeaningVi();
            }
            result.put(g.getId(), display);
        }
        return result;
    }

    /**
     * Best-effort — looks up the lesson title for each item via the first lesson
     * the entity is associated with. Returns empty string when unknown.
     */
    private Map<UUID, String> resolveLessonTitlesForItems(
            List<ItemMastery> items,
            Set<UUID> vocabIds, Set<UUID> grammarIds) {
        // Skip the expensive join — weak-item lesson context is a nice-to-have.
        // Callers that need it can add a dedicated query later.
        return Map.of();
    }

    // ── Recent activity ────────────────────────────────────────────────────

    private int countRecentSessions(UUID userId) {
        LocalDateTime start = LocalDateTime.now().minusDays(RECENT_DAYS);
        LocalDateTime end   = LocalDateTime.now();
        try {
            return sessionRepository.findCompletedInDateRange(userId, start, end).size();
        } catch (Exception e) {
            // Defensive: never let this block the snapshot
            return 0;
        }
    }
}
