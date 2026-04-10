package com.fu.practice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.achievement.config.ActivityExpConfig;
import com.fu.achievement.service.AchievementService;
import com.fu.achievement.service.LearningLevelService;
import com.fu.achievement.dto.response.UserAchievementResponse;
import com.fu.achievement.dto.response.LevelUpNotificationResponse;
import com.fu.achievement.dto.response.StreakNotificationResponse;
import com.fu.achievement.enums.BadgeType;
import com.fu.achievement.service.StreakService;
import com.fu.infrastructure.event.PracticeFeedbackReadyEvent;
import com.fu.common.exception.BusinessException;
import com.fu.course.entity.Course;
import com.fu.course.entity.GrammarPoint;
import com.fu.course.entity.Lesson;
import com.fu.course.entity.Vocabulary;
import com.fu.course.repository.GrammarPointRepository;
import com.fu.course.repository.LessonRepository;
import com.fu.course.repository.VocabularyRepository;
import com.fu.user.entity.UserLearningPreferences;
import com.fu.user.repository.UserLearningPreferencesRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fu.practice.agent.AnswerEvaluatorAgent;
import com.fu.practice.agent.ExerciseGeneratorAgent;
import com.fu.practice.agent.SessionFeedbackAgent;
import com.fu.practice.dto.request.StartPracticeRequest;
import com.fu.practice.dto.request.SubmitAnswerRequest;
import com.fu.practice.dto.response.*;
import com.fu.practice.entity.*;
import com.fu.practice.repository.LessonProgressRepository;
import com.fu.practice.repository.PracticeItemRepository;
import com.fu.practice.repository.PracticeSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.fu.practice.entity.ItemMastery;
import com.fu.practice.entity.PracticePreset;
import com.fu.practice.entity.QuestionDimension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service xử lý toàn bộ logic phiên luyện tập.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PracticeSessionService {

    private final PracticeSessionRepository sessionRepository;
    private final PracticeItemRepository itemRepository;
    private final LessonProgressRepository progressRepository;
    private final LessonRepository lessonRepository;
    private final VocabularyRepository vocabularyRepository;
    private final GrammarPointRepository grammarPointRepository;
    private final ItemMasteryService itemMasteryService;
    private final UserLearningPreferencesRepository userPreferencesRepository;
    private final ExerciseGeneratorAgent exerciseGeneratorAgent;
    private final AnswerEvaluatorAgent answerEvaluatorAgent;
    private final SessionFeedbackAgent sessionFeedbackAgent;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final AchievementService achievementService;
    private final LearningLevelService learningLevelService;
    private final StreakService streakService;
    private final ApplicationEventPublisher eventPublisher;

    // ── Start Session ─────────────────────────────────────────────────────

    /**
     * Bắt đầu phiên luyện tập mới.
     * <p>
     * Sinh toàn bộ câu hỏi ngay khi start để submitAnswer không phải chờ LLM nữa.
     * LLM call runs OUTSIDE the transaction to avoid holding a DB connection for 5-30 seconds.
     */
    public StartPracticeResponse startSession(UUID userId, StartPracticeRequest request) {
        // Phase 1: Validate lesson + enrollment
        LessonContext lessonCtx = loadLessonContext(userId, request.getLessonId());

        // Phase 2: Load toàn bộ vocab/grammar của bài học (cần cho distractor pool)
        List<Vocabulary>   allLessonVocab   = vocabularyRepository.findByLessonId(request.getLessonId());
        List<GrammarPoint> allLessonGrammar = grammarPointRepository.findByLessonId(request.getLessonId());

        // Phase 3: Xác định items sẽ luyện tập
        //
        // Khi frontend gửi selectedIds (hasPool = true):
        //   → dùng đúng những items đó (frontend đã giới hạn số lượng ≤ preset.itemCount)
        //
        // Khi không có selectedIds (hasPool = false, auto mode):
        //   → backend tự pick top preset.itemCount items, ưu tiên mastery thấp nhất
        PracticePreset preset = request.getPreset() != null ? request.getPreset() : PracticePreset.STANDARD;
        boolean hasPool = request.isExplicitMode();

        List<Vocabulary>   selectedVocab;
        List<GrammarPoint> selectedGrammar;
        Map<UUID, ItemMastery> masteryMap = null; // reused from auto mode when possible

        if (hasPool) {
            // Frontend đã pick đúng số lượng → dùng hết
            Set<UUID> vocabSet = request.getSelectedVocabIds() != null
                    ? new HashSet<>(request.getSelectedVocabIds()) : Set.of();
            Set<UUID> grammarSet = request.getSelectedGrammarIds() != null
                    ? new HashSet<>(request.getSelectedGrammarIds()) : Set.of();
            selectedVocab = allLessonVocab.stream()
                    .filter(v -> vocabSet.contains(v.getId()))
                    .collect(Collectors.toList());
            selectedGrammar = allLessonGrammar.stream()
                    .filter(g -> grammarSet.contains(g.getId()))
                    .collect(Collectors.toList());
        } else {
            // Auto mode: backend pick bằng mastery
            List<Vocabulary> vocabPool = switch (request.getSessionType()) {
                case GRAMMAR_DRILL -> List.of();
                default            -> allLessonVocab;
            };
            List<GrammarPoint> grammarPool = switch (request.getSessionType()) {
                case VOCAB_DRILL -> List.of();
                default          -> allLessonGrammar;
            };
            int target = preset.getItemCount();
            int total  = vocabPool.size() + grammarPool.size();
            int vSlots = (total == 0) ? 0
                    : switch (request.getSessionType()) {
                        case VOCAB_DRILL   -> Math.min(target, vocabPool.size());
                        case GRAMMAR_DRILL -> 0;
                        default -> Math.min(                                   // MIXED: tỷ lệ
                                (int) Math.round((double) vocabPool.size() / total * target),
                                vocabPool.size());
                    };
            int gSlots = Math.min(target - vSlots, grammarPool.size());

            List<UUID> allPoolIds = new ArrayList<>();
            vocabPool.forEach(v -> allPoolIds.add(v.getId()));
            grammarPool.forEach(g -> allPoolIds.add(g.getId()));
            Map<UUID, ItemMastery> poolMastery = itemMasteryService.buildMasteryMap(userId, allPoolIds);

            selectedVocab   = pickByMastery(vocabPool,   poolMastery, vSlots);
            selectedGrammar = pickByMastery(grammarPool, poolMastery, gSlots);
            masteryMap = poolMastery; // reuse — selected items are a subset of poolIds
        }

        if (selectedVocab.isEmpty() && selectedGrammar.isEmpty()) {
            throw new BusinessException("PRACTICE_NO_CONTENT",
                    "Bài học chưa có nội dung để luyện tập. Vui lòng liên hệ giáo viên.");
        }

        // Phase 4: Mastery map + dimension
        List<UUID> selectedIds = new ArrayList<>();
        selectedVocab.forEach(v -> selectedIds.add(v.getId()));
        selectedGrammar.forEach(g -> selectedIds.add(g.getId()));

        if (masteryMap == null) {
            masteryMap = itemMasteryService.buildMasteryMap(userId, selectedIds);
        }

        int resolvedCount = selectedIds.size();

        QuestionDimension sessionDimension = itemMasteryService.pickSessionDimension(
                masteryMap, selectedIds, request.getSessionType());

        // Phase 6: Load user learning preferences (optional)
        UserLearningPreferences prefs = userPreferencesRepository.findByUserId(userId).orElse(null);

        // Phase 7: LLM call — gen toàn bộ câu hỏi ngoài transaction
        List<ExerciseGeneratorAgent.GeneratedItem> allItems =
                exerciseGeneratorAgent.generateItems(
                        request.getLessonId(),
                        lessonCtx.courseId(),
                        request.getSessionType(),
                        resolvedCount,
                        lessonCtx.jlptLevel(),
                        request.getItemTypes(),
                        selectedVocab,
                        selectedGrammar,
                        allLessonVocab,
                        allLessonGrammar,
                        hasPool,            // isExplicitMode = frontend đã cung cấp pool
                        prefs,
                        sessionDimension,
                        masteryMap);

        // Phase 8: Persist
        UUID[] savedVocabIds   = selectedVocab.stream().map(Vocabulary::getId).toArray(UUID[]::new);
        UUID[] savedGrammarIds = selectedGrammar.stream().map(GrammarPoint::getId).toArray(UUID[]::new);

        return persistNewSession(userId, request, lessonCtx, allItems,
                hasPool, savedVocabIds, savedGrammarIds, sessionDimension);
    }

    /**
     * Sort pool by mastery level ascending (low mastery = luyện trước),
     * secondary sort: last_practiced_at ascending (lâu không luyện = ưu tiên hơn).
     * Pick top n items.
     */
    private <T> List<T> pickByMastery(List<T> pool, Map<UUID, ItemMastery> masteryMap,
                                       int n) {
        if (n <= 0 || pool.isEmpty()) return List.of();
        return pool.stream()
                .sorted(Comparator
                        .<T, Integer>comparing(item -> {
                            UUID id = extractId(item);
                            ItemMastery m = masteryMap.get(id);
                            return m != null ? m.getMasteryLevel() : 0;
                        })
                        .thenComparing(item -> {
                            UUID id = extractId(item);
                            ItemMastery m = masteryMap.get(id);
                            return (m != null && m.getLastPracticedAt() != null)
                                    ? m.getLastPracticedAt()
                                    : LocalDateTime.MIN;
                        }))
                .limit(n)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private UUID extractId(Object item) {
        if (item instanceof Vocabulary v) return v.getId();
        if (item instanceof GrammarPoint g) return g.getId();
        throw new IllegalArgumentException("Unknown item type: " + item.getClass());
    }

    @Transactional(readOnly = true)
    public LessonContext loadLessonContext(UUID userId, UUID lessonId) {
        Lesson lesson = lessonRepository.findByIdAndNotDeleted(lessonId)
                .orElseThrow(() -> new BusinessException("LESSON_NOT_FOUND",
                        "Không tìm thấy bài học. Vui lòng thử lại."));
        UUID courseId = lesson.getCourse().getId();
        validateEnrollment(userId, courseId);
        String jlptLevel = resolveJlptLevel(lesson);
        return new LessonContext(courseId, jlptLevel);
    }

    @Transactional
    public StartPracticeResponse persistNewSession(UUID userId, StartPracticeRequest request,
                                                   LessonContext lessonCtx,
                                                   List<ExerciseGeneratorAgent.GeneratedItem> firstItemList,
                                                   boolean isExplicitMode,
                                                   UUID[] selectedVocabIds,
                                                   UUID[] selectedGrammarIds,
                                                   QuestionDimension sessionDimension) {
        // Lưu jlptLevel + itemTypes vào context JSON để dùng lại khi cần
        String itemTypesJson = buildItemTypesJson(request.getItemTypes());
        String contextJson = String.format("{\"jlptLevel\":\"%s\",\"itemTypes\":%s}",
                lessonCtx.jlptLevel() != null ? lessonCtx.jlptLevel() : "N5",
                itemTypesJson);

        PracticeSession session = PracticeSession.builder()
                .userId(userId)
                .lessonId(request.getLessonId())
                .courseId(lessonCtx.courseId())
                .sessionType(request.getSessionType())
                .status(PracticeStatus.IN_PROGRESS)
                .totalItems(firstItemList.isEmpty() ? 0 : firstItemList.size())
                .completedItems(0)
                .correctItems(0)
                .score(BigDecimal.ZERO)
                .startedAt(LocalDateTime.now())
                .context(contextJson)
                .explicitMode(isExplicitMode)
                .selectedVocabIds(isExplicitMode ? selectedVocabIds : null)
                .selectedGrammarIds(isExplicitMode ? selectedGrammarIds : null)
                .sessionDimension(sessionDimension != null ? sessionDimension.name() : null)
                .build();
        session = sessionRepository.save(session);

        List<PracticeItem> savedItems = saveItems(session, firstItemList);

        PracticeSessionResponse sessionResponse = toSessionResponse(session);
        List<PracticeItemResponse> itemResponses = savedItems.stream()
                .map(this::toItemResponse)
                .collect(Collectors.toList());

        return StartPracticeResponse.builder()
                .session(sessionResponse)
                .items(itemResponses)
                .build();
    }

    private record LessonContext(UUID courseId, String jlptLevel) {
    }

    // ── Submit Answer ─────────────────────────────────────────────────────

    /**
     * Nộp câu trả lời cho một câu hỏi.
     *
     * DB roundtrips: 3 (item read → evaluate → write TX: session + item re-read).
     * Trước đây: 4 (read TX: session + item + write TX: session + item).
     *
     * Session ownership/status validation chuyển vào write TX để đảm bảo
     * consistency dưới optimistic lock. Item read ngoài TX chỉ để cung cấp
     * dữ liệu cho evaluation mà không giữ connection pool.
     */
    public AnswerFeedbackResponse submitAnswer(UUID sessionId, UUID itemId,
                                               UUID userId, SubmitAnswerRequest request) {
        // Phase 1: load item cho evaluation — 1 read, không giữ TX
        PracticeItem itemSnapshot = itemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException("ITEM_NOT_FOUND",
                        "Không tìm thấy câu hỏi."));
        if (!itemSnapshot.getSessionId().equals(sessionId)) {
            throw new BusinessException("ITEM_SESSION_MISMATCH",
                    "Câu hỏi không thuộc phiên luyện tập này.");
        }
        if (itemSnapshot.getUserAnswer() != null) {
            throw new BusinessException("ITEM_ALREADY_ANSWERED",
                    "Câu hỏi này đã được trả lời.");
        }

        // Phase 2: evaluate — rule-based: ~0ms; LLM (TRANSLATION): 300–1200ms; không giữ TX
        AnswerEvaluatorAgent.EvaluationResult result =
                answerEvaluatorAgent.evaluate(itemSnapshot, request.getUserAnswer());

        // Phase 3: write TX — validate session ownership + status, persist, tính score
        return persistAnswerResult(sessionId, itemId, userId, request.getUserAnswer(), result);
    }

    @Transactional
    public AnswerFeedbackResponse persistAnswerResult(UUID sessionId, UUID itemId, UUID userId,
                                                       String userAnswer,
                                                       AnswerEvaluatorAgent.EvaluationResult result) {
        // Re-read within write TX — optimistic lock (@Version) bắt concurrent submits
        PracticeSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND",
                        "Không tìm thấy phiên luyện tập."));

        // Session ownership + status — validate tại đây thay vì read TX riêng
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("SESSION_ACCESS_DENIED",
                    "Bạn không có quyền truy cập phiên luyện tập này.");
        }
        if (session.getStatus() != PracticeStatus.IN_PROGRESS) {
            throw new BusinessException("SESSION_NOT_ACTIVE",
                    "Phiên luyện tập đã kết thúc.");
        }

        PracticeItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException("ITEM_NOT_FOUND",
                        "Không tìm thấy câu hỏi."));

        // Double-check race condition dưới write lock
        if (item.getUserAnswer() != null) {
            throw new BusinessException("ITEM_ALREADY_ANSWERED",
                    "Câu hỏi này đã được trả lời.");
        }

        // Cập nhật item
        item.setUserAnswer(userAnswer);
        item.setIsCorrect(result.isCorrect());
        item.setScore(result.score());
        item.setAiFeedback(result.feedback());
        item.setAnsweredAt(LocalDateTime.now());
        itemRepository.save(item);

        // Cập nhật session counters
        session.setCompletedItems(session.getCompletedItems() + 1);
        session.setRunningScoreSum(session.getRunningScoreSum() + result.score());
        if (result.isCorrect()) {
            session.setCorrectItems(session.getCorrectItems() + 1);
        }

        boolean sessionCompleted = session.getCompletedItems() >= session.getTotalItems();

        // Fetch pre-generated next item from DB (no LLM call needed)
        PracticeItem nextItem = null;
        if (!sessionCompleted) {
            int nextIndex = session.getCompletedItems(); // sau khi tăng, đây là index câu tiếp
            nextItem = itemRepository.findBySessionIdAndItemIndex(sessionId, nextIndex).orElse(null);
            if (nextItem == null) {
                log.warn("persistAnswerResult: Không tìm thấy câu pre-generated tại index {} cho session {}",
                        nextIndex, sessionId);
                sessionCompleted = true;
            }
        }

        // ── Mastery tracking ──────────────────────────────────────────────────
        Integer masteryLevelBefore = null;
        Integer masteryLevelAfter  = null;
        String  entityJapanese     = null;

        if (item.getRefContentId() != null && item.getRefContentType() != null) {
            try {
                ItemMasteryService.MasteryUpdateResult masteryResult = itemMasteryService.upsert(
                        userId, item.getRefContentId(), item.getRefContentType(), result.isCorrect());
                masteryLevelBefore = masteryResult.levelBefore();
                masteryLevelAfter  = masteryResult.levelAfter();

                // Fetch Japanese text for display
                if ("VOCABULARY".equals(item.getRefContentType())) {
                    entityJapanese = vocabularyRepository.findById(item.getRefContentId())
                            .map(v -> v.getJapaneseText() != null ? v.getJapaneseText() : v.getReading())
                            .orElse(null);
                } else if ("GRAMMAR".equals(item.getRefContentType())) {
                    entityJapanese = grammarPointRepository.findById(item.getRefContentId())
                            .map(g -> g.getPattern())
                            .orElse(null);
                }
            } catch (Exception e) {
                log.warn("persistAnswerResult: mastery upsert thất bại cho item {}: {}", itemId, e.getMessage());
            }
        }
        // ─────────────────────────────────────────────────────────────────────

        if (sessionCompleted) {
            finishSession(session);
        } else {
            sessionRepository.save(session);
        }

        String explanation = item.getExplanation() != null
                ? item.getExplanation()
                : (result.correction() != null ? result.correction() : result.feedback());

        PracticeItemResponse nextItemResponse = nextItem != null
                ? toItemResponse(nextItem)
                : null;

        return AnswerFeedbackResponse.builder()
                .isCorrect(result.isCorrect())
                .score(result.score())
                .feedback(result.feedback())
                .correctAnswer(item.getCorrectAnswer())
                .explanation(explanation)
                .sessionCompleted(sessionCompleted)
                .nextItem(nextItemResponse)
                .masteryLevelBefore(masteryLevelBefore)
                .masteryLevelAfter(masteryLevelAfter)
                .entityJapanese(entityJapanese)
                .build();
    }

    /**
     * Context từ session cần thiết để persist kết quả câu trả lời.
     */
    public record SessionItemContext(
            PracticeItem item,
            int completedItemsSnapshot,
            int totalItems,
            PracticeSessionType sessionType
    ) {
    }

    // ── Complete Session ──────────────────────────────────────────────────

    /**
     * Hoàn thành phiên luyện tập — called from within persistAnswerResult's transaction.
     * Only handles score calculation, status change, and progress update.
     * AI feedback is generated asynchronously after the transaction commits.
     */
    private void finishSession(PracticeSession session) {
        if (session.getStatus() == PracticeStatus.COMPLETED) {
            return;
        }

        // Weighted average dựa trên runningScoreSum — không cần re-read toàn bộ items.
        // runningScoreSum được cộng dồn sau mỗi persistAnswerResult().
        double scoreValue = session.getTotalItems() > 0
                ? (double) session.getRunningScoreSum() / session.getTotalItems()
                : 0.0;

        BigDecimal score = BigDecimal.valueOf(scoreValue).setScale(2, RoundingMode.HALF_UP);
        session.setScore(score);
        session.setStatus(PracticeStatus.COMPLETED);
        session.setCompletedAt(LocalDateTime.now());
        sessionRepository.save(session);

        if (session.getLessonId() != null) {
            upsertLessonProgress(session);
        }

        // ── Update dimension tracking for all session items ───────────────────
        if (session.getSessionDimension() != null) {
            try {
                List<PracticeItem> sessionItems = itemRepository.findBySessionIdOrderByItemIndex(session.getId());
                Set<UUID> vocabEntityIds  = new HashSet<>();
                Set<UUID> grammarEntityIds = new HashSet<>();
                for (PracticeItem it : sessionItems) {
                    if (it.getRefContentId() != null && it.getRefContentType() != null) {
                        if ("VOCABULARY".equals(it.getRefContentType())) {
                            vocabEntityIds.add(it.getRefContentId());
                        } else if ("GRAMMAR".equals(it.getRefContentType())) {
                            grammarEntityIds.add(it.getRefContentId());
                        }
                    }
                }
                if (!vocabEntityIds.isEmpty()) {
                    itemMasteryService.updateDimension(session.getUserId(), vocabEntityIds,
                            "VOCABULARY", session.getSessionDimension());
                }
                if (!grammarEntityIds.isEmpty()) {
                    itemMasteryService.updateDimension(session.getUserId(), grammarEntityIds,
                            "GRAMMAR", session.getSessionDimension());
                }
            } catch (Exception e) {
                log.warn("finishSession: updateDimension thất bại cho session {}: {}", session.getId(), e.getMessage());
            }
        }
        // ─────────────────────────────────────────────────────────────────────

        // Record daily activity for streak tracking
        // Note: This may be called 2nd time after persistAnswerResult already called it,
        // but recordDailyActivity checks hasActivityOnDate and will return isFirstActivityToday=false
        try {
            streakService.recordDailyActivity(session.getUserId(), "PRACTICE");
        } catch (Exception e) {
            log.error("Error recording daily activity for learner: {}", session.getUserId(), e);
        }
        
        // Award direct EXP for practice completion (with level tier + score bonus)
        try {
            int currentLevel = learningLevelService.getCurrentLevel(session.getUserId());
            Integer scoreForExp = session.getScore() != null ? session.getScore().intValue() : null;
            int expToAward = ActivityExpConfig.ActivityType.PRACTICE_AI.getExpReward(currentLevel, scoreForExp);
            learningLevelService.awardExpAndCheckLevelUp(session.getUserId(), expToAward);
            log.info("Awarded {} EXP for practice session to user {} (level {}, score {}%)",
                    expToAward, session.getUserId(), currentLevel, scoreForExp);
        } catch (Exception e) {
            log.error("Error awarding EXP for practice session to user {}", session.getUserId(), e);
            // Continue even if EXP fails - don't break practice session
        }

        // Check and award achievements for practice completion
        try {
            // Count total completed practice sessions for this user to check badge requirements
            long practiceCount = sessionRepository.countByUserIdAndStatus(session.getUserId(), PracticeStatus.COMPLETED);
            achievementService.checkAndAwardBadges(session.getUserId(), BadgeType.AI_PRACTICE_COUNT, (int) practiceCount);
            log.debug("Achievement check completed for user: {} with practice count: {}", session.getUserId(), practiceCount);
        } catch (Exception e) {
            log.error("Error checking achievements for practice session", e);
            // Don't fail the practice session if achievement check fails
        }
    }

    /**
     * Generate AI feedback for a completed session and persist it — async version.
     * Trả về ngay lập tức, không block HTTP response. Dùng practiceTaskExecutor
     * (virtual threads) để tránh chiếm thread pool của curriculum-import pipeline.
     */
    @Async("practiceTaskExecutor")
    public void generateAndSaveFeedbackAsync(UUID sessionId) {
        generateAndSaveFeedback(sessionId);
    }

    /**
     * Generate AI feedback for a completed session and persist it.
     * Called AFTER the completion transaction commits — no DB connection held during LLM call.
     */
    @Transactional
    public void generateAndSaveFeedback(UUID sessionId) {
        PracticeSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || session.getStatus() != PracticeStatus.COMPLETED) return;
        if (session.getAiFeedback() != null && !session.getAiFeedback().isBlank()) return;

        double scoreValue = session.getScore() != null ? session.getScore().doubleValue() : 0.0;
        try {
            List<PracticeItem> allItems = itemRepository.findBySessionIdOrderByItemIndex(sessionId);
            String sessionType = session.getSessionType() != null
                    ? session.getSessionType().name() : "MIXED_LESSON";
            String feedback = sessionFeedbackAgent.generateFeedback(sessionId, scoreValue, allItems, sessionType);
            session.setAiFeedback(feedback);
            sessionRepository.save(session);
            eventPublisher.publishEvent(new PracticeFeedbackReadyEvent(
                    this, sessionId, session.getUserId(), session.getLessonId(), scoreValue));
        } catch (Exception e) {
            log.warn("generateAndSaveFeedback: Không sinh được AI feedback cho session {}: {}",
                    sessionId, e.getMessage());
        }
    }

    // ── Get Summary ───────────────────────────────────────────────────────

    /**
     * Lấy tổng kết phiên luyện tập.
     */
    @Transactional(readOnly = true)
    public SessionSummaryResponse getSessionSummary(UUID sessionId, UUID userId) {
        PracticeSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND",
                        "Không tìm thấy phiên luyện tập."));

        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("SESSION_ACCESS_DENIED",
                    "Bạn không có quyền xem phiên luyện tập này.");
        }

        List<PracticeItem> items = itemRepository.findBySessionIdOrderByItemIndex(sessionId);
        List<ItemResultDto> results = items.stream()
                .map(this::toItemResultDto)
                .collect(Collectors.toList());

        String feedbackText = extractFeedbackSummary(session.getAiFeedback());

        // Retrieve earned badges from session completion (if any)
        // Badges earned within a small time window after session completion
        List<UserAchievementResponse> earnedBadges = Collections.emptyList();
        LevelUpNotificationResponse levelUpInfo = null;

        // Calculate EXP earned and get old total
        int expEarned = 0;
        Integer oldTotalExp = null;

        if (session.getCompletedAt() != null) {
            try {
                // Get current total EXP
                int currentTotalExp = learningLevelService.getTotalExp(userId);
                Integer sessionScore = session.getScore() != null ? session.getScore().intValue() : null;

                // Re-derive the EXP awarded at completion time.
                // At completion, level was read BEFORE awarding → try decreasing levels
                // until getExpReward matches a consistent oldTotalExp.
                int trialLevel = learningLevelService.getCurrentLevel(userId);
                expEarned = ActivityExpConfig.ActivityType.PRACTICE_AI.getExpReward(trialLevel, sessionScore);
                oldTotalExp = currentTotalExp - expEarned;
                // If level changed due to this EXP, the pre-award level was lower — recalculate
                int priorLevel = learningLevelService.calculateLevelFromExp(Math.max(0, oldTotalExp));
                if (priorLevel < trialLevel) {
                    expEarned = ActivityExpConfig.ActivityType.PRACTICE_AI.getExpReward(priorLevel, sessionScore);
                    oldTotalExp = currentTotalExp - expEarned;
                }

            } catch (Exception e) {
                log.debug("Error calculating EXP details for session summary", e);
            }

            try {
                earnedBadges = achievementService.getUserEarnedBadges(userId).stream()
                        .filter(badge -> badge.getEarnedAt() != null &&
                                badge.getEarnedAt().isAfter(session.getCreatedAt()) &&
                                badge.getEarnedAt().isBefore(session.getCompletedAt().plusSeconds(10)))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("Error retrieving earned badges for session summary", e);
            }

            // Retrieve level-up information
            try {
                LearningLevelService.LevelProgressInfo progressInfo = learningLevelService.getProgressToNextLevel(userId);

                // Calculate previous level from oldTotalExp
                int previousLevel = 0;
                if (oldTotalExp != null && oldTotalExp >= 0) {
                    previousLevel = learningLevelService.calculateLevelFromExp(oldTotalExp);
                }

                int currentLevel = progressInfo.currentLevel();
                boolean actuallyLeveledUp = previousLevel < currentLevel;

                levelUpInfo = LevelUpNotificationResponse.builder()
                        .previousLevel(previousLevel)
                        .newLevel(currentLevel)
                        .expEarned(expEarned)
                        .oldTotalExp(oldTotalExp)
                        .totalExp(progressInfo.totalExp())
                        .expToNextLevel(progressInfo.expToNextLevel())
                        .progressPercentage(progressInfo.percentToNextLevel())
                        .isLevelUp(actuallyLeveledUp)
                        .build();
            } catch (Exception e) {
                log.error("Error retrieving level-up info for session summary", e);
            }
        }

        // Fetch streak info (read-only) if session is completed
        com.fu.achievement.dto.response.StreakNotificationResponse streakNotification = null;
        if (session.getStatus() == PracticeStatus.COMPLETED) {
            try {
                streakNotification = streakService.getUserStatsInfo(userId)
                        .map(stats -> com.fu.achievement.dto.response.StreakNotificationResponse.builder()
                                .isFirstActivityToday(false) // read-only — cannot determine from snapshot
                                .currentStreak(stats.getCurrentStreak())
                                .longestStreak(stats.getLongestStreak())
                                .build())
                        .orElse(null);
            } catch (Exception e) {
                log.debug("Error retrieving streak info for session summary", e);
            }
        }

        // ── Mastery changes summary ────────────────────────────────────────────
        List<SessionSummaryResponse.MasteryChangeDto> masteryChanges = buildMasteryChanges(userId, items);
        // ─────────────────────────────────────────────────────────────────────

        return SessionSummaryResponse.builder()
                .sessionId(sessionId)
                .score(session.getScore())
                .correctItems(session.getCorrectItems())
                .totalItems(session.getTotalItems())
                .aiFeedback(feedbackText)
                .items(results)
                .earnedBadges(earnedBadges)
                .levelUpInfo(levelUpInfo)
                .streakNotification(streakNotification)
                .masteryChanges(masteryChanges.isEmpty() ? null : masteryChanges)
                .build();
    }

    /**
     * Build mastery change summary từ items đã trả lời trong session.
     * Hiển thị trạng thái mastery hiện tại cho từng item có refContentId.
     */
    private List<SessionSummaryResponse.MasteryChangeDto> buildMasteryChanges(UUID userId,
                                                                               List<PracticeItem> items) {
        // Collect unique (entityId, entityType) pairs with refContentId
        Map<UUID, String> entityTypeMap = new LinkedHashMap<>();
        for (PracticeItem item : items) {
            if (item.getRefContentId() != null && item.getRefContentType() != null) {
                entityTypeMap.putIfAbsent(item.getRefContentId(), item.getRefContentType());
            }
        }
        if (entityTypeMap.isEmpty()) return List.of();

        // Batch load mastery records
        Map<UUID, ItemMastery> masteryMap = itemMasteryService.buildMasteryMap(userId, entityTypeMap.keySet());

        // Determine per-entity correctness from items (for levelBefore approximation)
        Map<UUID, Boolean> entityCorrectMap = new LinkedHashMap<>();
        for (PracticeItem item : items) {
            if (item.getRefContentId() != null && item.getIsCorrect() != null) {
                // If multiple items reference same entity, any incorrect answer → false
                entityCorrectMap.merge(item.getRefContentId(), item.getIsCorrect(), (a, b) -> a && b);
            }
        }

        List<SessionSummaryResponse.MasteryChangeDto> changes = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : entityTypeMap.entrySet()) {
            UUID entityId   = entry.getKey();
            String entityType = entry.getValue();
            ItemMastery mastery = masteryMap.get(entityId);
            if (mastery == null) continue; // item never answered

            int levelAfter = mastery.getMasteryLevel();
            // Infer levelBefore from answer correctness — approximation only.
            // The exact value per-item is in AnswerFeedbackResponse.masteryLevelBefore.
            Boolean allCorrect = entityCorrectMap.get(entityId);
            int levelBefore;
            if (allCorrect != null && allCorrect) {
                levelBefore = Math.max(0, levelAfter - 1); // went up or stayed
            } else if (allCorrect != null) {
                levelBefore = Math.min(3, levelAfter + 1); // went down or stayed
            } else {
                levelBefore = levelAfter;
            }

            String japaneseText = null;
            try {
                if ("VOCABULARY".equals(entityType)) {
                    japaneseText = vocabularyRepository.findById(entityId)
                            .map(v -> v.getJapaneseText() != null ? v.getJapaneseText() : v.getReading())
                            .orElse(null);
                } else if ("GRAMMAR".equals(entityType)) {
                    japaneseText = grammarPointRepository.findById(entityId)
                            .map(g -> g.getPattern())
                            .orElse(null);
                }
            } catch (Exception ignored) {}

            changes.add(SessionSummaryResponse.MasteryChangeDto.builder()
                    .entityId(entityId)
                    .entityType(entityType)
                    .japaneseText(japaneseText)
                    .levelBefore(levelBefore)
                    .levelAfter(levelAfter)
                    .newlyMastered(levelAfter == 3 && levelBefore < 3)
                    .build());
        }
        return changes;
    }

    // ── Lesson Items Picker ────────────────────────────────────────────────

    /**
     * Lấy danh sách vocab/grammar của bài học kèm mastery status — dùng cho UI item picker.
     * GET /api/v1/learner/practice/lessons/{lessonId}/items
     */
    @Transactional(readOnly = true)
    public LessonItemsResponse getLessonItems(UUID userId, UUID lessonId) {
        // Validate lesson access
        Lesson lesson = lessonRepository.findByIdAndNotDeleted(lessonId)
                .orElseThrow(() -> new BusinessException("LESSON_NOT_FOUND",
                        "Không tìm thấy bài học."));
        validateEnrollment(userId, lesson.getCourse().getId());

        List<Vocabulary> vocabList = vocabularyRepository.findByLessonId(lessonId);
        List<GrammarPoint> grammarList = grammarPointRepository.findByLessonId(lessonId);

        // Batch load mastery for all entity IDs
        List<UUID> allIds = new ArrayList<>();
        vocabList.forEach(v -> allIds.add(v.getId()));
        grammarList.forEach(g -> allIds.add(g.getId()));
        Map<UUID, ItemMastery> masteryMap = itemMasteryService.buildMasteryMap(userId, allIds);

        List<LessonItemsResponse.VocabItemDto> vocabDtos = vocabList.stream()
                .map(v -> {
                    ItemMastery m = masteryMap.get(v.getId());
                    return LessonItemsResponse.VocabItemDto.builder()
                            .id(v.getId())
                            .japaneseText(v.getJapaneseText())
                            .reading(v.getReading())
                            .meaningVi(v.getMeaningVi())
                            .partOfSpeech(v.getPartOfSpeech())
                            .mastery(m != null ? toMasteryDto(m) : null)
                            .build();
                })
                .collect(Collectors.toList());

        List<LessonItemsResponse.GrammarItemDto> grammarDtos = grammarList.stream()
                .map(g -> {
                    ItemMastery m = masteryMap.get(g.getId());
                    return LessonItemsResponse.GrammarItemDto.builder()
                            .id(g.getId())
                            .pattern(g.getPattern())
                            .meaningVi(g.getMeaningVi())
                            .shortExplanation(g.getShortExplanation())
                            .mastery(m != null ? toMasteryDto(m) : null)
                            .build();
                })
                .collect(Collectors.toList());

        int masteredVocab    = (int) vocabDtos.stream()
                .filter(v -> v.getMastery() != null && v.getMastery().getLevel() == 3).count();
        int masteredGrammar  = (int) grammarDtos.stream()
                .filter(g -> g.getMastery() != null && g.getMastery().getLevel() == 3).count();
        int needsReview      = (int) vocabDtos.stream()
                .filter(v -> v.getMastery() == null || v.getMastery().getLevel() <= 1).count()
                + (int) grammarDtos.stream()
                .filter(g -> g.getMastery() == null || g.getMastery().getLevel() <= 1).count();

        LessonItemsResponse.LessonItemSummary summary = LessonItemsResponse.LessonItemSummary.builder()
                .totalVocab(vocabDtos.size())
                .totalGrammar(grammarDtos.size())
                .masteredVocab(masteredVocab)
                .masteredGrammar(masteredGrammar)
                .needsReviewCount(needsReview)
                .build();

        return LessonItemsResponse.builder()
                .vocabulary(vocabDtos)
                .grammar(grammarDtos)
                .summary(summary)
                .build();
    }

    private LessonItemsResponse.MasteryDto toMasteryDto(ItemMastery m) {
        String label = switch (m.getMasteryLevel()) {
            case 1  -> "Quen thuộc";
            case 2  -> "Nắm vững";
            case 3  -> "Thuộc";
            default -> "Chưa luyện";
        };
        return LessonItemsResponse.MasteryDto.builder()
                .level(m.getMasteryLevel())
                .label(label)
                .attemptCount(m.getAttemptCount())
                .emaScore(m.getEmaScore().doubleValue())
                .lastPracticedAt(m.getLastPracticedAt())
                .build();
    }

    // ── Retry Wrong Items ─────────────────────────────────────────────────

    /**
     * Tạo phiên luyện tập mới chỉ với các câu trả lời sai từ phiên đã hoàn thành.
     * POST /api/v1/learner/practice/sessions/{sessionId}/retry-wrong
     */
    public StartPracticeResponse retryWrongItems(UUID sessionId, UUID userId) {
        PracticeSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND",
                        "Không tìm thấy phiên luyện tập."));

        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("SESSION_ACCESS_DENIED",
                    "Bạn không có quyền truy cập phiên luyện tập này.");
        }

        if (session.getStatus() != PracticeStatus.COMPLETED) {
            throw new BusinessException("SESSION_NOT_COMPLETED",
                    "Chỉ có thể ôn lại câu sai của phiên đã hoàn thành.");
        }

        List<PracticeItem> wrongItems = itemRepository.findIncorrectBySessionId(sessionId);
        if (wrongItems.isEmpty()) {
            throw new BusinessException("NO_WRONG_ITEMS",
                    "Bạn đã trả lời đúng tất cả câu hỏi trong phiên này! Hãy thử phiên mới.");
        }

        // Collect refContentIds split by type
        List<UUID> selectedVocabIds   = new ArrayList<>();
        List<UUID> selectedGrammarIds = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();

        for (PracticeItem item : wrongItems) {
            if (item.getRefContentId() == null || item.getRefContentType() == null) continue;
            if (!seen.add(item.getRefContentId())) continue; // deduplicate

            if ("VOCABULARY".equals(item.getRefContentType())) {
                selectedVocabIds.add(item.getRefContentId());
            } else if ("GRAMMAR".equals(item.getRefContentType())) {
                selectedGrammarIds.add(item.getRefContentId());
            }
        }

        if (selectedVocabIds.isEmpty() && selectedGrammarIds.isEmpty()) {
            // fallback: items have no refContentId → redo the whole session
            log.warn("retryWrongItems: không có refContentId nào trong wrong items của session {}, fallback redo", sessionId);
            StartPracticeRequest fallback = StartPracticeRequest.builder()
                    .lessonId(session.getLessonId())
                    .sessionType(session.getSessionType())
                    .preset(PracticePreset.STANDARD)
                    .build();
            return startSession(userId, fallback);
        }

        StartPracticeRequest request = StartPracticeRequest.builder()
                .lessonId(session.getLessonId())
                .sessionType(session.getSessionType())
                .selectedVocabIds(selectedVocabIds.isEmpty() ? null : selectedVocabIds)
                .selectedGrammarIds(selectedGrammarIds.isEmpty() ? null : selectedGrammarIds)
                .build();

        log.info("retryWrongItems: user={} session={} wrongVocab={} wrongGrammar={}",
                userId, sessionId, selectedVocabIds.size(), selectedGrammarIds.size());
        return startSession(userId, request);
    }

    // ── Progress ──────────────────────────────────────────────────────────

    /**
     * Lấy tiến độ luyện tập của user theo bài học.
     */
    @Transactional(readOnly = true)
    public LessonProgressResponse getLessonProgress(UUID userId, UUID lessonId) {
        return progressRepository.findByUserIdAndLessonId(userId, lessonId)
                .map(this::toProgressResponse)
                .orElse(LessonProgressResponse.builder()
                        .lessonId(lessonId)
                        .totalSessions(0)
                        .bestScore(BigDecimal.ZERO)
                        .lastScore(BigDecimal.ZERO)
                        .vocabMastery(BigDecimal.ZERO)
                        .grammarMastery(BigDecimal.ZERO)
                        .build());
    }

    /**
     * Lấy tiến độ luyện tập của user theo khóa học.
     */
    @Transactional(readOnly = true)
    public List<LessonProgressResponse> getCourseProgress(UUID userId, UUID courseId) {
        return progressRepository.findByUserIdAndCourseId(userId, courseId)
                .stream()
                .map(this::toProgressResponse)
                .collect(Collectors.toList());
    }

    // ── Session History & Redo ─────────────────────────────────────────────

    /**
     * Danh sách phiên luyện tập của user (trừ ABANDONED).
     * Nếu lessonId != null, lọc theo bài học.
     */
    @Transactional(readOnly = true)
    public Page<PracticeSessionSummaryResponse> listSessions(UUID userId, UUID lessonId, Pageable pageable) {
        Page<PracticeSession> page;
        if (lessonId != null) {
            page = sessionRepository.findByUserIdAndLessonIdAndStatusNot(
                    userId, lessonId, PracticeStatus.ABANDONED, pageable);
        } else {
            page = sessionRepository.findByUserIdAndStatusNot(
                    userId, PracticeStatus.ABANDONED, pageable);
        }
        return page.map(this::toSessionSummaryResponse);
    }

    /**
     * Lấy chi tiết tất cả câu hỏi trong một phiên (bao gồm đáp án, feedback).
     */
    @Transactional(readOnly = true)
    public List<PracticeItemDetailResponse> getSessionItems(UUID sessionId, UUID userId) {
        PracticeSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND",
                        "Không tìm thấy phiên luyện tập."));

        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("SESSION_ACCESS_DENIED",
                    "Bạn không có quyền truy cập phiên luyện tập này.");
        }

        List<PracticeItem> items = itemRepository.findBySessionIdOrderByItemIndex(sessionId);
        return items.stream()
                .map(this::toItemDetailResponse)
                .collect(Collectors.toList());
    }

    /**
     * Làm lại phiên luyện tập đã hoàn thành — tạo phiên mới với cùng cấu hình.
     */
    public StartPracticeResponse redoSession(UUID sessionId, UUID userId) {
        PracticeSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND",
                        "Không tìm thấy phiên luyện tập."));

        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("SESSION_ACCESS_DENIED",
                    "Bạn không có quyền truy cập phiên luyện tập này.");
        }

        if (session.getStatus() != PracticeStatus.COMPLETED) {
            throw new BusinessException("INVALID_STATUS",
                    "Chỉ có thể làm lại phiên đã hoàn thành.");
        }

        // Preserve explicit selection from original session if applicable
        StartPracticeRequest.StartPracticeRequestBuilder requestBuilder = StartPracticeRequest.builder()
                .lessonId(session.getLessonId())
                .sessionType(session.getSessionType());

        if (session.isExplicitMode()
                && (session.getSelectedVocabIds() != null && session.getSelectedVocabIds().length > 0
                    || session.getSelectedGrammarIds() != null && session.getSelectedGrammarIds().length > 0)) {
            List<UUID> vocabIds = session.getSelectedVocabIds() != null
                    ? Arrays.asList(session.getSelectedVocabIds()) : null;
            List<UUID> grammarIds = session.getSelectedGrammarIds() != null
                    ? Arrays.asList(session.getSelectedGrammarIds()) : null;
            requestBuilder.selectedVocabIds(vocabIds).selectedGrammarIds(grammarIds);
        } else {
            requestBuilder.preset(PracticePreset.STANDARD);
        }

        return startSession(userId, requestBuilder.build());
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Kiểm tra học viên có đang tham gia một lớp học thuộc khóa học này không.
     * Class lấy nội dung từ course, nên phải enrolled mới được luyện tập.
     */
    private void validateEnrollment(UUID userId, UUID courseId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM user_management.class_members cm
                        JOIN user_management.classes c ON c.id = cm.class_id
                        WHERE cm.user_id = ?
                          AND c.course_id = ?
                          AND cm.removed_at IS NULL
                          AND c.deleted_at IS NULL
                          AND c.status = 'ACTIVE'
                        """,
                Integer.class, userId, courseId
        );
        if (count == null || count == 0) {
            throw new BusinessException("NOT_ENROLLED",
                    "Bạn chưa tham gia lớp học nào thuộc khóa học này.");
        }
    }

    private List<PracticeItem> saveItems(PracticeSession session,
                                         List<ExerciseGeneratorAgent.GeneratedItem> generatedItems) {
        List<PracticeItem> items = new ArrayList<>();
        for (int i = 0; i < generatedItems.size(); i++) {
            items.add(buildItem(session, generatedItems.get(i), i));
        }
        return itemRepository.saveAll(items);
    }

    private PracticeItem buildItem(PracticeSession session,
                                   ExerciseGeneratorAgent.GeneratedItem gen,
                                   int index) {
        PracticeItemType resolvedType = resolveItemType(session.getSessionType(), gen);

        if (resolvedType == PracticeItemType.MATCHING) {
            return buildMatchingItem(session, gen, index);
        }
        if (resolvedType == PracticeItemType.SENTENCE_ORDER) {
            return buildSentenceOrderItem(session, gen, index);
        }
        // FILL_BLANK và TRANSLATION đều là chọn 1 trong N options → cùng build logic
        if (resolvedType == PracticeItemType.FILL_BLANK
                || resolvedType == PracticeItemType.TRANSLATION) {
            return buildSelectionItem(session, gen, index, resolvedType);
        }

        // MULTIPLE_CHOICE, TRUE_FALSE: shuffle options (trừ TRUE_FALSE giữ Đúng/Sai cố định)
        List<ExerciseGeneratorAgent.GeneratedItem.OptionItem> options = gen.getOptions();
        if (options != null && options.size() > 1
                && resolvedType != PracticeItemType.TRUE_FALSE) {
            options = new ArrayList<>(options);
            Collections.shuffle(options);
        }

        return PracticeItem.builder()
                .sessionId(session.getId())
                .itemIndex(index)
                .itemType(resolvedType)
                .question(gen.getQuestion() != null ? gen.getQuestion() : "")
                .questionJapanese(gen.getQuestionJapanese())
                .hint(gen.getHint())
                .options(buildOptionsJson(options))
                .correctAnswer(gen.getCorrectAnswer() != null ? gen.getCorrectAnswer() : "")
                .explanation(gen.getExplanation())
                .refContentId(parseUuid(gen.getRefContentId()))
                .refContentType(gen.getRefContentType())
                .build();
    }

    /**
     * Build item dạng "chọn 1 trong N options": FILL_BLANK và TRANSLATION.
     *
     * Options được shuffle để đáp án không luôn ở vị trí cố định.
     * correctAnswer = index (id) của option có isCorrect=true sau khi shuffle.
     * Frontend submit id option được chọn → server so sánh integer — không LLM.
     */
    private PracticeItem buildSelectionItem(PracticeSession session,
                                             ExerciseGeneratorAgent.GeneratedItem gen,
                                             int index,
                                             PracticeItemType itemType) {
        List<ExerciseGeneratorAgent.GeneratedItem.OptionItem> options = gen.getOptions();
        if (options == null || options.isEmpty()) {
            log.warn("buildSelectionItem: LLM không trả về options cho {} item {}", itemType, index);
            return PracticeItem.builder()
                    .sessionId(session.getId())
                    .itemIndex(index)
                    .itemType(itemType)
                    .question(gen.getQuestion() != null ? gen.getQuestion() : "")
                    .options("[]")
                    .correctAnswer(gen.getCorrectAnswer() != null ? gen.getCorrectAnswer() : "")
                    .explanation(gen.getExplanation())
                    .build();
        }

        List<ExerciseGeneratorAgent.GeneratedItem.OptionItem> shuffled = new ArrayList<>(options);
        Collections.shuffle(shuffled);

        // correctAnswer = index của option có isCorrect=true trong mảng sau shuffle
        int correctId = -1;
        for (int i = 0; i < shuffled.size(); i++) {
            if (shuffled.get(i).isCorrect()) {
                correctId = i;
                break;
            }
        }
        if (correctId == -1) {
            // Fallback: LLM không đánh dấu isCorrect → dùng correctAnswer text của LLM
            log.warn("buildSelectionItem: không tìm thấy isCorrect=true cho {} item {}, fallback text", itemType, index);
            return PracticeItem.builder()
                    .sessionId(session.getId())
                    .itemIndex(index)
                    .itemType(itemType)
                    .question(gen.getQuestion() != null ? gen.getQuestion() : "")
                    .hint(gen.getHint())
                    .options(buildOptionsJson(shuffled))
                    .correctAnswer(gen.getCorrectAnswer() != null ? gen.getCorrectAnswer() : "")
                    .explanation(gen.getExplanation())
                    .refContentId(parseUuid(gen.getRefContentId()))
                    .refContentType(gen.getRefContentType())
                    .build();
        }

        return PracticeItem.builder()
                .sessionId(session.getId())
                .itemIndex(index)
                .itemType(itemType)
                .question(gen.getQuestion() != null ? gen.getQuestion() : "")
                .questionJapanese(gen.getQuestionJapanese())
                .hint(gen.getHint())
                .options(buildOptionsJson(shuffled))
                .correctAnswer(String.valueOf(correctId))
                .explanation(gen.getExplanation())
                .refContentId(parseUuid(gen.getRefContentId()))
                .refContentType(gen.getRefContentType())
                .build();
    }

    /**
     * Build SENTENCE_ORDER item.
     *
     * Tokens được lưu ĐÚNG THỨ TỰ như LLM sinh ra (thứ tự đúng của câu).
     * Frontend tự shuffle để hiển thị — server không cần biết display order.
     * correctAnswer = "0,1,2,...,N-1" (thứ tự ID đúng).
     * Frontend submit ordered IDs theo thứ tự user chọn → server so sánh int array.
     */
    private PracticeItem buildSentenceOrderItem(PracticeSession session,
                                                 ExerciseGeneratorAgent.GeneratedItem gen,
                                                 int index) {
        List<ExerciseGeneratorAgent.GeneratedItem.OptionItem> options = gen.getOptions();
        if (options == null || options.isEmpty()) {
            log.warn("buildSentenceOrderItem: LLM không trả về options cho SENTENCE_ORDER item {}", index);
            return PracticeItem.builder()
                    .sessionId(session.getId())
                    .itemIndex(index)
                    .itemType(PracticeItemType.SENTENCE_ORDER)
                    .question(gen.getQuestion() != null ? gen.getQuestion() : "")
                    .options("[]")
                    .correctAnswer("")
                    .explanation(gen.getExplanation())
                    .build();
        }

        // correctAnswer = "0,1,2,...,N-1" — thứ tự đúng chính là thứ tự LLM trả về
        StringBuilder correctAnswer = new StringBuilder();
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) correctAnswer.append(",");
            correctAnswer.append(i);
        }

        return PracticeItem.builder()
                .sessionId(session.getId())
                .itemIndex(index)
                .itemType(PracticeItemType.SENTENCE_ORDER)
                .question(gen.getQuestion() != null ? gen.getQuestion() : "")
                .questionJapanese(gen.getQuestionJapanese())
                .hint(gen.getHint())
                .options(buildOptionsJson(options))   // giữ thứ tự đúng, frontend tự shuffle
                .correctAnswer(correctAnswer.toString())
                .explanation(gen.getExplanation())
                .refContentId(parseUuid(gen.getRefContentId()))
                .refContentType(gen.getRefContentType())
                .build();
    }

    /**
     * Build một MATCHING PracticeItem từ danh sách pairs do LLM sinh ra.
     * <p>
     * Cấu trúc options lưu DB:
     * id 0..N-1      → LEFT  (tiếng Nhật, giữ nguyên thứ tự)
     * id N..2N-1     → RIGHT (tiếng Việt, đã shuffle)
     * <p>
     * correctAnswer = "0:X,1:Y,..." — ánh xạ left id → right id.
     */
    private PracticeItem buildMatchingItem(PracticeSession session,
                                           ExerciseGeneratorAgent.GeneratedItem gen,
                                           int index) {
        List<ExerciseGeneratorAgent.GeneratedItem.MatchingPair> pairs = gen.getPairs();
        if (pairs == null || pairs.isEmpty()) {
            // Fallback: LLM không trả về pairs — lưu item trống
            log.warn("buildMatchingItem: LLM không trả về pairs cho MATCHING item {}", index);
            return PracticeItem.builder()
                    .sessionId(session.getId())
                    .itemIndex(index)
                    .itemType(PracticeItemType.MATCHING)
                    .question(gen.getQuestion() != null ? gen.getQuestion() : "Nối từ tiếng Nhật với nghĩa tiếng Việt")
                    .options("[]")
                    .correctAnswer("")
                    .build();
        }

        int n = pairs.size();

        // Sinh thứ tự shuffle cho cột phải
        List<Integer> shuffledPairIndices = new ArrayList<>();
        for (int i = 0; i < n; i++) shuffledPairIndices.add(i);
        Collections.shuffle(shuffledPairIndices);

        // shuffledPairIndices[pos] = pairIdx → right option tại vị trí pos lấy nghĩa từ pairs[pairIdx]
        // Để tính correctAnswer: với left id = i (pair i), right id = n + shufflePos của pair i
        int[] rightIdForPair = new int[n];
        for (int pos = 0; pos < n; pos++) {
            rightIdForPair[shuffledPairIndices.get(pos)] = n + pos;
        }

        StringBuilder correctAnswer = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) correctAnswer.append(",");
            correctAnswer.append(i).append(":").append(rightIdForPair[i]);
        }

        // Build options list: LEFT trước, RIGHT (shuffled) sau
        List<Map<String, Object>> optionsList = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("text", pairs.get(i).left());
            opt.put("isCorrect", false);
            opt.put("side", "LEFT");
            optionsList.add(opt);
        }
        for (int pos = 0; pos < n; pos++) {
            int pairIdx = shuffledPairIndices.get(pos);
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("text", pairs.get(pairIdx).right());
            opt.put("isCorrect", false);
            opt.put("side", "RIGHT");
            optionsList.add(opt);
        }

        String optionsJson;
        try {
            optionsJson = objectMapper.writeValueAsString(optionsList);
        } catch (Exception e) {
            optionsJson = "[]";
        }

        return PracticeItem.builder()
                .sessionId(session.getId())
                .itemIndex(index)
                .itemType(PracticeItemType.MATCHING)
                .question(gen.getQuestion() != null ? gen.getQuestion() : "Nối từ tiếng Nhật với nghĩa tiếng Việt")
                .questionJapanese(gen.getQuestionJapanese())
                .hint(gen.getHint())
                .options(optionsJson)
                .correctAnswer(correctAnswer.toString())
                .explanation(gen.getExplanation())
                .refContentId(parseUuid(gen.getRefContentId()))
                .refContentType(gen.getRefContentType())
                .build();
    }

    /**
     * Parse "0:4,1:5,2:6" → Map{"0"→"4","1"→"5","2"→"6"}.
     */
    private Map<String, String> parsePairMap(String pairStr) {
        Map<String, String> map = new LinkedHashMap<>();
        if (pairStr == null || pairStr.isBlank()) return map;
        for (String pair : pairStr.split(",")) {
            String[] parts = pair.trim().split(":");
            if (parts.length == 2) {
                map.put(parts[0].trim(), parts[1].trim());
            }
        }
        return map;
    }

    private String buildItemTypesJson(List<PracticeItemType> itemTypes) {
        if (itemTypes == null || itemTypes.isEmpty()) return "null";
        try {
            return objectMapper.writeValueAsString(
                    itemTypes.stream().map(Enum::name).collect(Collectors.toList()));
        } catch (Exception e) {
            return "null";
        }
    }

    /**
     * Xác định loại câu hỏi:
     * 1. Ưu tiên trường itemType do LLM khai báo (nguồn chính xác nhất)
     * 2. Fallback: suy luận từ cấu trúc options
     * 3. Fallback cuối: dựa theo sessionType
     */
    private PracticeItemType resolveItemType(PracticeSessionType sessionType,
                                             ExerciseGeneratorAgent.GeneratedItem gen) {
        if (gen.getItemType() != null && !gen.getItemType().isBlank()) {
            try {
                return PracticeItemType.valueOf(gen.getItemType().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("resolveItemType: LLM trả về itemType không hợp lệ '{}', fallback.", gen.getItemType());
            }
        }
        List<ExerciseGeneratorAgent.GeneratedItem.OptionItem> options = gen.getOptions();
        if (options != null && options.size() == 2) {
            boolean hasTrueFalse = options.stream()
                    .anyMatch(o -> "Đúng".equals(o.text()) || "Sai".equals(o.text()));
            if (hasTrueFalse) return PracticeItemType.TRUE_FALSE;
        }
        if (options != null && !options.isEmpty()) {
            return PracticeItemType.MULTIPLE_CHOICE;
        }
        return switch (sessionType) {
            case VOCAB_DRILL -> PracticeItemType.FILL_BLANK;
            case GRAMMAR_DRILL -> PracticeItemType.FILL_BLANK;
            default -> PracticeItemType.TRANSLATION;
        };
    }

    private String buildOptionsJson(List<ExerciseGeneratorAgent.GeneratedItem.OptionItem> options) {
        if (options == null || options.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(options);
        } catch (Exception e) {
            return "[]";
        }
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String resolveJlptLevel(Lesson lesson) {
        try {
            Course course = lesson.getCourse();
            if (course != null && course.getJlptLevel() != null) {
                return course.getJlptLevel().name();
            }
        } catch (Exception e) {
            log.debug("resolveJlptLevel: Không thể lấy JLPT level từ course: {}", e.getMessage());
        }
        return "N5";
    }

    private void upsertLessonProgress(PracticeSession session) {
        LessonProgress progress = progressRepository
                .findByUserIdAndLessonId(session.getUserId(), session.getLessonId())
                .orElse(LessonProgress.builder()
                        .userId(session.getUserId())
                        .lessonId(session.getLessonId())
                        .totalSessions(0)
                        .bestScore(BigDecimal.ZERO)
                        .lastScore(BigDecimal.ZERO)
                        .vocabMastery(BigDecimal.ZERO)
                        .grammarMastery(BigDecimal.ZERO)
                        .build());

        progress.setTotalSessions(progress.getTotalSessions() + 1);
        progress.setLastScore(session.getScore());
        progress.setLastPracticedAt(LocalDateTime.now());

        if (session.getScore().compareTo(progress.getBestScore()) > 0) {
            progress.setBestScore(session.getScore());
        }

        // Tính mastery từ item_mastery EMA (aggregateLessonMastery)
        // Vocab mastery dựa trên loại session
        if (session.getSessionType() == PracticeSessionType.VOCAB_DRILL
                || session.getSessionType() == PracticeSessionType.MIXED_LESSON) {
            List<UUID> allVocabIds = vocabularyRepository.findByLessonId(session.getLessonId())
                    .stream().map(v -> v.getId()).collect(Collectors.toList());
            if (!allVocabIds.isEmpty()) {
                double vocabMastery = itemMasteryService.aggregateLessonMastery(
                        session.getUserId(), allVocabIds, "VOCABULARY");
                progress.setVocabMastery(BigDecimal.valueOf(vocabMastery).setScale(2, RoundingMode.HALF_UP));
            }
        }

        if (session.getSessionType() == PracticeSessionType.GRAMMAR_DRILL
                || session.getSessionType() == PracticeSessionType.MIXED_LESSON) {
            List<UUID> allGrammarIds = grammarPointRepository.findByLessonId(session.getLessonId())
                    .stream().map(g -> g.getId()).collect(Collectors.toList());
            if (!allGrammarIds.isEmpty()) {
                double grammarMastery = itemMasteryService.aggregateLessonMastery(
                        session.getUserId(), allGrammarIds, "GRAMMAR");
                progress.setGrammarMastery(BigDecimal.valueOf(grammarMastery).setScale(2, RoundingMode.HALF_UP));
            }
        }

        progressRepository.save(progress);
    }

    /**
     * Tính rolling average giữa giá trị cũ và mới (trọng số 70% cũ, 30% mới).
     */
    private BigDecimal rollingAverage(BigDecimal oldValue, BigDecimal newValue) {
        if (oldValue == null || oldValue.compareTo(BigDecimal.ZERO) == 0) {
            return newValue.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal avg = oldValue.multiply(new BigDecimal("0.7"))
                .add(newValue.multiply(new BigDecimal("0.3")));
        return avg.setScale(2, RoundingMode.HALF_UP);
    }

    private String extractFeedbackSummary(String aiFeedbackJson) {
        if (aiFeedbackJson == null || aiFeedbackJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(aiFeedbackJson).path("summary").asText(aiFeedbackJson);
        } catch (Exception e) {
            return aiFeedbackJson;
        }
    }

    // ── Mappers ────────────────────────────────────────────────────────────

    private PracticeSessionResponse toSessionResponse(PracticeSession s) {
        return PracticeSessionResponse.builder()
                .sessionId(s.getId())
                .lessonId(s.getLessonId())
                .courseId(s.getCourseId())
                .sessionType(s.getSessionType())
                .status(s.getStatus())
                .totalItems(s.getTotalItems())
                .completedItems(s.getCompletedItems())
                .correctItems(s.getCorrectItems())
                .score(s.getScore())
                .startedAt(s.getStartedAt())
                .completedAt(s.getCompletedAt())
                .build();
    }

    private PracticeItemResponse toItemResponse(PracticeItem item) {
        List<OptionDto> options = parseOptionsForDisplay(item.getOptions(), false);

        // MATCHING: gửi correctMapping upfront để frontend validate client-side,
        // tránh phải gọi verify-pair API cho mỗi lần kéo.
        Map<Integer, Integer> correctMapping = null;
        if (item.getItemType() == PracticeItemType.MATCHING
                && item.getCorrectAnswer() != null
                && !item.getCorrectAnswer().isBlank()) {
            correctMapping = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : parsePairMap(item.getCorrectAnswer()).entrySet()) {
                try {
                    correctMapping.put(Integer.parseInt(e.getKey()), Integer.parseInt(e.getValue()));
                } catch (NumberFormatException ignored) {}
            }
        }

        return PracticeItemResponse.builder()
                .id(item.getId())
                .itemIndex(item.getItemIndex())
                .itemType(item.getItemType())
                .question(item.getQuestion())
                .questionJapanese(item.getQuestionJapanese())
                .hint(item.getHint())
                .options(options)
                .correctMapping(correctMapping)
                .build();
    }

    private List<OptionDto> parseOptionsForDisplay(String optionsJson, boolean revealCorrect) {
        if (optionsJson == null || optionsJson.isBlank() || "[]".equals(optionsJson.trim())) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode arr = objectMapper.readTree(optionsJson);
            List<OptionDto> result = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                com.fasterxml.jackson.databind.JsonNode node = arr.get(i);
                String text = node.path("text").asText(null);
                boolean isCorrect = node.path("isCorrect").asBoolean(false);
                String side = node.has("side") && !node.path("side").isNull()
                        ? node.path("side").asText(null) : null;
                result.add(OptionDto.builder()
                        .id(i)
                        .text(text)
                        .side(side)
                        .isCorrect(revealCorrect ? isCorrect : null)
                        .build());
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private ItemResultDto toItemResultDto(PracticeItem item) {
        return ItemResultDto.builder()
                .itemIndex(item.getItemIndex())
                .question(item.getQuestion())
                .userAnswer(item.getUserAnswer())
                .correctAnswer(item.getCorrectAnswer())
                .isCorrect(item.getIsCorrect())
                .score(item.getScore())
                .explanation(item.getExplanation())
                .aiFeedback(item.getAiFeedback())
                .build();
    }

    private LessonProgressResponse toProgressResponse(LessonProgress p) {
        return LessonProgressResponse.builder()
                .lessonId(p.getLessonId())
                .totalSessions(p.getTotalSessions())
                .bestScore(p.getBestScore())
                .lastScore(p.getLastScore())
                .vocabMastery(p.getVocabMastery())
                .grammarMastery(p.getGrammarMastery())
                .lastPracticedAt(p.getLastPracticedAt())
                .build();
    }

    private PracticeSessionSummaryResponse toSessionSummaryResponse(PracticeSession s) {
        return PracticeSessionSummaryResponse.builder()
                .sessionId(s.getId())
                .lessonId(s.getLessonId())
                .courseId(s.getCourseId())
                .sessionType(s.getSessionType().name())
                .status(s.getStatus().name())
                .score(s.getScore())
                .correctItems(s.getCorrectItems())
                .totalItems(s.getTotalItems())
                .createdAt(s.getCreatedAt())
                .completedAt(s.getCompletedAt())
                .build();
    }

    private PracticeItemDetailResponse toItemDetailResponse(PracticeItem item) {
        List<OptionDto> options = parseOptionsForDisplay(item.getOptions(), true);
        return PracticeItemDetailResponse.builder()
                .itemId(item.getId())
                .itemIndex(item.getItemIndex())
                .itemType(item.getItemType().name())
                .question(item.getQuestion())
                .questionJapanese(item.getQuestionJapanese())
                .options(options)
                .correctAnswer(item.getCorrectAnswer())
                .userAnswer(item.getUserAnswer())
                .isCorrect(item.getIsCorrect())
                .score(item.getScore())
                .hint(item.getHint())
                .explanation(item.getExplanation())
                .aiFeedback(item.getAiFeedback())
                .refContentId(item.getRefContentId())
                .refContentType(item.getRefContentType())
                .build();
    }
}
