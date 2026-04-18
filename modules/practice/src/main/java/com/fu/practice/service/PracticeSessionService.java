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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import com.fu.practice.entity.ItemMastery;
import com.fu.practice.entity.PracticePreset;
import com.fu.practice.entity.QuestionDimension;

import com.fu.infrastructure.event.StudyMinutesRecordedEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
    private final HttpServletRequest httpServletRequest;

    /**
     * Store the isFirstActivityOfDay flag in HttpSession.
     * Called by finishSession() to capture the flag for getSessionSummary() to use.
     * Using HttpSession allows the flag to persist across multiple HTTP requests in the same session.
     */
    private void storeFirstActivityFlag(UUID sessionId, boolean isFirstActivityOfDay) {
        if (httpServletRequest == null) {
            log.warn("storeFirstActivityFlag: HttpServletRequest not available, flag not stored");
            return;
        }
        HttpSession httpSession = httpServletRequest.getSession(true);
        String flagKey = "practice_first_activity_" + sessionId;
        httpSession.setAttribute(flagKey, isFirstActivityOfDay);
        log.debug("Stored first activity flag for session {}: {}", sessionId, isFirstActivityOfDay);
    }

    /**
     * Retrieve the isFirstActivityOfDay flag from HttpSession.
     * Called by getSessionSummary() to get the flag stored by finishSession().
     * Falls back to false if not found (safety).
     */
    private boolean getFirstActivityFlag(UUID sessionId) {
        if (httpServletRequest == null) {
            log.warn("getFirstActivityFlag: HttpServletRequest not available, returning false");
            return false;
        }
        HttpSession httpSession = httpServletRequest.getSession(false);
        if (httpSession == null) {
            log.debug("getFirstActivityFlag: No HTTP session found for session {}", sessionId);
            return false;
        }
        String flagKey = "practice_first_activity_" + sessionId;
        Boolean flag = (Boolean) httpSession.getAttribute(flagKey);
        return flag != null ? flag : false;
    }

    /**
     * Clear the isFirstActivityOfDay flag from HttpSession.
     * Should be called after getSessionSummary() completes to prevent session bloat.
     */
    private void clearFirstActivityFlag(UUID sessionId) {
        if (httpServletRequest == null) {
            return;
        }
        HttpSession httpSession = httpServletRequest.getSession(false);
        if (httpSession != null) {
            String flagKey = "practice_first_activity_" + sessionId;
            httpSession.removeAttribute(flagKey);
            log.debug("Cleared first activity flag for session {}", sessionId);
        }
    }

    // ── Start Session ─────────────────────────────────────────────────────

    /**
     * Bắt đầu phiên luyện tập mới.
     * <p>
     * Sinh toàn bộ câu hỏi ngay khi start để submitAnswer không phải chờ LLM nữa.
     * LLM call runs OUTSIDE the transaction to avoid holding a DB connection for 5-30 seconds.
     */
    public StartPracticeResponse startSession(UUID userId, StartPracticeRequest request) {
        // Phase 0: Hard cap explicit selection — tổng (vocab + grammar) ≤ 20 items
        // (chặn trước khi query DB / gọi LLM để tránh tốn token + chậm)
        if (request.isExplicitMode()) {
            int totalSelected =
                    (request.getSelectedVocabIds() != null ? request.getSelectedVocabIds().size() : 0)
                            + (request.getSelectedGrammarIds() != null ? request.getSelectedGrammarIds().size() : 0);
            if (totalSelected > 20) {
                throw new BusinessException("PRACTICE_SELECTION_TOO_LARGE",
                        "Chọn tối đa 20 items mỗi phiên luyện tập. Bạn đã chọn " + totalSelected + ".");
            }
        }

        // Phase 1: Validate lesson + enrollment
        LessonContext lessonCtx = loadLessonContext(userId, request.getLessonId());

        // Phase 2: Load toàn bộ vocab/grammar của bài học (cần cho distractor pool)
        List<Vocabulary> allLessonVocab = vocabularyRepository.findByLessonId(request.getLessonId());
        List<GrammarPoint> allLessonGrammar = grammarPointRepository.findByLessonId(request.getLessonId());

        // Phase 3: Xác định items sẽ luyện tập
        //
        // Explicit mode (hasPool=true):
        //   → dùng đúng items frontend đã chọn, mỗi item 1 câu (tổng ≤ 20 đã check ở Phase 0)
        //
        // Auto mode (hasPool=false):
        //   → số câu = preset.itemCount; nếu pool nhỏ hơn, một số entity sẽ được hỏi nhiều lần
        //     với QuestionDimension khác nhau. Cap tối đa: vocab 5 dim × pool, grammar 4 dim × pool.
        PracticePreset preset = request.getPreset() != null ? request.getPreset() : PracticePreset.STANDARD;
        boolean hasPool = request.isExplicitMode();

        List<Vocabulary> selectedVocab;
        List<GrammarPoint> selectedGrammar;
        Map<UUID, ItemMastery> masteryMap = null; // reused from auto mode when possible
        Map<UUID, Integer> repeatPlan = new LinkedHashMap<>(); // entityId → số câu cần sinh
        int effectiveTarget;
        boolean allowRepetition;

        if (hasPool) {
            // Explicit mode — mỗi entity xuất hiện đúng 1 lần
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
            selectedVocab.forEach(v -> repeatPlan.put(v.getId(), 1));
            selectedGrammar.forEach(g -> repeatPlan.put(g.getId(), 1));
            effectiveTarget = selectedVocab.size() + selectedGrammar.size();
            allowRepetition = false;
        } else {
            // Auto mode: tính slot + repeatPlan
            List<Vocabulary> vocabPool = switch (request.getSessionType()) {
                case GRAMMAR_DRILL -> List.of();
                default -> allLessonVocab;
            };
            List<GrammarPoint> grammarPool = switch (request.getSessionType()) {
                case VOCAB_DRILL -> List.of();
                default -> allLessonGrammar;
            };
            int vPoolSize = vocabPool.size();
            int gPoolSize = grammarPool.size();
            int vCap = vPoolSize * MAX_VOCAB_DIMENSIONS;
            int gCap = gPoolSize * MAX_GRAMMAR_DIMENSIONS;
            int target = preset.getItemCount();

            // vSlots / gSlots KHÔNG bị clamp bởi poolSize — chỉ bởi pool*maxDim
            int vSlots, gSlots;
            switch (request.getSessionType()) {
                case VOCAB_DRILL -> {
                    vSlots = Math.min(target, vCap);
                    gSlots = 0;
                }
                case GRAMMAR_DRILL -> {
                    vSlots = 0;
                    gSlots = Math.min(target, gCap);
                }
                default -> { // MIXED_LESSON
                    int combinedCap = vCap + gCap;
                    int mixTarget = Math.min(target, combinedCap);
                    int totalPool = vPoolSize + gPoolSize;
                    if (totalPool == 0) {
                        vSlots = 0;
                        gSlots = 0;
                    } else {
                        int vShare = (int) Math.round((double) vPoolSize / totalPool * mixTarget);
                        vSlots = Math.min(vShare, vCap);
                        gSlots = Math.min(mixTarget - vSlots, gCap);
                        int leftover = mixTarget - vSlots - gSlots;
                        if (leftover > 0) {
                            vSlots += Math.min(leftover, vCap - vSlots);
                        }
                    }
                }
            }
            effectiveTarget = vSlots + gSlots;
            if (effectiveTarget < target) {
                log.info("Auto mode capped: preset={} → effective={} (vPool={}, gPool={}, vCap={}, gCap={})",
                        target, effectiveTarget, vPoolSize, gPoolSize, vCap, gCap);
            }

            List<UUID> allPoolIds = new ArrayList<>();
            vocabPool.forEach(v -> allPoolIds.add(v.getId()));
            grammarPool.forEach(g -> allPoolIds.add(g.getId()));
            Map<UUID, ItemMastery> poolMastery = itemMasteryService.buildMasteryMap(userId, allPoolIds);

            // Chọn entities sẽ tham gia: top-N mastery thấp nhất (N = min(slots, poolSize))
            selectedVocab = pickByMastery(vocabPool, poolMastery, Math.min(vSlots, vPoolSize));
            selectedGrammar = pickByMastery(grammarPool, poolMastery, Math.min(gSlots, gPoolSize));

            // Phân bổ slots thành repeatPlan — entity nào yếu hơn được thêm extra lần
            if (vSlots > 0 && !selectedVocab.isEmpty()) {
                List<UUID> vIds = selectedVocab.stream().map(Vocabulary::getId).collect(Collectors.toList());
                repeatPlan.putAll(distributeRepeats(vIds, vSlots, MAX_VOCAB_DIMENSIONS));
            }
            if (gSlots > 0 && !selectedGrammar.isEmpty()) {
                List<UUID> gIds = selectedGrammar.stream().map(GrammarPoint::getId).collect(Collectors.toList());
                repeatPlan.putAll(distributeRepeats(gIds, gSlots, MAX_GRAMMAR_DIMENSIONS));
            }

            allowRepetition = vSlots > selectedVocab.size() || gSlots > selectedGrammar.size();
            masteryMap = poolMastery;
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

        int resolvedCount = effectiveTarget;

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
                        masteryMap,
                        allowRepetition,
                        repeatPlan);

        // Phase 8: Persist
        UUID[] savedVocabIds = selectedVocab.stream().map(Vocabulary::getId).toArray(UUID[]::new);
        UUID[] savedGrammarIds = selectedGrammar.stream().map(GrammarPoint::getId).toArray(UUID[]::new);

        return persistNewSession(userId, request, lessonCtx, allItems,
                hasPool, savedVocabIds, savedGrammarIds, sessionDimension);
    }

    /**
     * Số QuestionDimension khả dụng cho mỗi entity type — giới hạn số lần lặp tối đa.
     * Vocab: MEANING, READING, USAGE, CONTEXT, ANTONYM_SYNONYM (5 góc).
     * Grammar: IDENTIFICATION, FORMATION, USAGE, CONTRAST (4 góc).
     */
    private static final int MAX_VOCAB_DIMENSIONS = 5;
    private static final int MAX_GRAMMAR_DIMENSIONS = 4;

    /**
     * Phân bổ {@code slots} câu hỏi xuống các entity trong {@code entityIds} (đã sort theo
     * mastery thấp → cao). Entity yếu hơn (đầu list) được ưu tiên thêm extra 1 câu.
     * <p>
     * Bảo đảm tổng count ≤ slots và mỗi count ≤ maxReps (số dimension khả dụng).
     * Caller đã cap slots ≤ entityIds.size() × maxReps trước khi gọi.
     */
    private Map<UUID, Integer> distributeRepeats(List<UUID> entityIds, int slots, int maxReps) {
        Map<UUID, Integer> plan = new LinkedHashMap<>();
        int pool = entityIds.size();
        if (pool == 0 || slots <= 0) return plan;
        int base = Math.min(slots / pool, maxReps);
        int extras = Math.max(0, slots - base * pool);
        for (int i = 0; i < pool; i++) {
            int count = base + (i < extras && base + 1 <= maxReps ? 1 : 0);
            if (count > 0) plan.put(entityIds.get(i), count);
        }
        return plan;
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
     * <p>
     * DB roundtrips: 3 (item read → evaluate → write TX: session + item re-read).
     * Trước đây: 4 (read TX: session + item + write TX: session + item).
     * <p>
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
        Integer masteryLevelAfter = null;
        String entityJapanese = null;

        if (item.getRefContentId() != null && item.getRefContentType() != null) {
            try {
                ItemMasteryService.MasteryUpdateResult masteryResult = itemMasteryService.upsert(
                        userId, item.getRefContentId(), item.getRefContentType(), result.isCorrect());
                masteryLevelBefore = masteryResult.levelBefore();
                masteryLevelAfter = masteryResult.levelAfter();

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

        // Resolve text của đáp án đúng + đáp án user — chỉ áp dụng cho dạng chọn 1 option.
        String correctAnswerText = resolveSelectionOptionText(item, item.getCorrectAnswer());
        String userAnswerText = resolveSelectionOptionText(item, userAnswer);

        return AnswerFeedbackResponse.builder()
                .isCorrect(result.isCorrect())
                .score(result.score())
                .feedback(result.feedback())
                .correctAnswer(item.getCorrectAnswer())
                .correctAnswerText(correctAnswerText)
                .userAnswerText(userAnswerText)
                .explanation(explanation)
                .sessionCompleted(sessionCompleted)
                .nextItem(nextItemResponse)
                .masteryLevelBefore(masteryLevelBefore)
                .masteryLevelAfter(masteryLevelAfter)
                .entityJapanese(entityJapanese)
                .build();
    }

    /**
     * Resolve text của một option từ index-based answer.
     * Chỉ áp dụng cho dạng chọn 1 option (MC/TRUE_FALSE/FILL_BLANK/TRANSLATION) — các dạng còn lại
     * (SENTENCE_ORDER / MATCHING) trả về null vì không có "text đơn".
     * <p>
     * - Nếu answer là integer id hợp lệ → trả text tại index đó trong options JSON.
     * - Nếu không parse được (legacy text answer): trả nguyên answer.
     * - Null-safe cho mọi input không hợp lệ.
     */
    private String resolveSelectionOptionText(PracticeItem item, String answer) {
        if (item == null || answer == null || answer.isBlank()) return null;
        PracticeItemType type = item.getItemType();
        if (type != PracticeItemType.MULTIPLE_CHOICE
                && type != PracticeItemType.TRUE_FALSE
                && type != PracticeItemType.FILL_BLANK
                && type != PracticeItemType.TRANSLATION) {
            return null;
        }
        try {
            int id = Integer.parseInt(answer.trim());
            com.fasterxml.jackson.databind.JsonNode options = objectMapper.readTree(
                    item.getOptions() != null ? item.getOptions() : "[]");
            if (!options.isArray() || id < 0 || id >= options.size()) return null;
            com.fasterxml.jackson.databind.JsonNode opt = options.get(id);
            com.fasterxml.jackson.databind.JsonNode textNode = opt.get("text");
            return textNode != null && !textNode.isNull() ? textNode.asText() : null;
        } catch (NumberFormatException e) {
            // Legacy item với correctAnswer dạng text → trả nguyên text
            return answer;
        } catch (Exception e) {
            log.debug("resolveSelectionOptionText: không parse được options cho item {}: {}",
                    item.getId(), e.getMessage());
            return null;
        }
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
        LocalDateTime completedAt = LocalDateTime.now();
        session.setCompletedAt(completedAt);
        int durationMinutes = (int) ChronoUnit.MINUTES.between(session.getStartedAt(), completedAt);
        session.setDurationMinutes(durationMinutes);
        sessionRepository.save(session);

        // Publish event so achievement module can track daily study goal
        try {
            eventPublisher.publishEvent(new StudyMinutesRecordedEvent(this, session.getUserId(), durationMinutes));
        } catch (Exception e) {
            log.warn("finishSession: could not publish StudyMinutesRecordedEvent for session {}: {}",
                    session.getId(), e.getMessage());
        }

        if (session.getLessonId() != null) {
            upsertLessonProgress(session);
        }

        // ── Update dimension tracking for all session items ───────────────────
        if (session.getSessionDimension() != null) {
            try {
                List<PracticeItem> sessionItems = itemRepository.findBySessionIdOrderByItemIndex(session.getId());
                Set<UUID> vocabEntityIds = new HashSet<>();
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
        // Store the isFirstActivityOfDay flag in HttpSession so getSessionSummary() can retrieve it
        // This works across multiple HTTP requests since HttpSession persists across requests
        try {
            StreakNotificationResponse streakResponse = streakService.recordDailyActivity(session.getUserId(), "PRACTICE");
            if (streakResponse != null && streakResponse.isFirstActivityToday()) {
                storeFirstActivityFlag(session.getId(), true);
                log.info("Streak notification: User {} first activity today, sessionId {}", 
                    session.getUserId(), session.getId());
            }
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
                // Retrieve the isFirstActivityOfDay flag stored in HttpSession by finishSession()
                boolean isFirstActivityToday = getFirstActivityFlag(sessionId);
                log.debug("Retrieved first activity flag for session {}: {}", sessionId, isFirstActivityToday);
                
                streakNotification = streakService.getUserStatsInfo(userId)
                        .map(stats -> com.fu.achievement.dto.response.StreakNotificationResponse.builder()
                                .isFirstActivityToday(isFirstActivityToday)
                                .currentStreak(stats.getCurrentStreak())
                                .longestStreak(stats.getLongestStreak())
                                .build())
                        .orElse(null);
                log.debug("Streak notification for session {}: isFirstActivityToday={}", 
                    sessionId, isFirstActivityToday);
            } catch (Exception e) {
                log.debug("Error retrieving streak info for session summary", e);
            } finally {
                // Clean up HttpSession to prevent session bloat
                clearFirstActivityFlag(sessionId);
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
            UUID entityId = entry.getKey();
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
            } catch (Exception ignored) {
            }

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
    public LessonItemsResponse getLessonItems(UUID userId, UUID lessonId, List<Integer> masteryLevelFilter) {
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

        // Summary tính trên toàn bộ danh sách (trước khi filter)
        Comparator<LessonItemsResponse.VocabItemDto> vocabComparator = Comparator
                .<LessonItemsResponse.VocabItemDto, Integer>comparing(
                        v -> v.getMastery() != null ? v.getMastery().getLevel() : 0)
                .thenComparing(v -> v.getMastery() != null && v.getMastery().getLastPracticedAt() != null
                        ? v.getMastery().getLastPracticedAt()
                        : LocalDateTime.MIN);

        Comparator<LessonItemsResponse.GrammarItemDto> grammarComparator = Comparator
                .<LessonItemsResponse.GrammarItemDto, Integer>comparing(
                        g -> g.getMastery() != null ? g.getMastery().getLevel() : 0)
                .thenComparing(g -> g.getMastery() != null && g.getMastery().getLastPracticedAt() != null
                        ? g.getMastery().getLastPracticedAt()
                        : LocalDateTime.MIN);

        Set<Integer> filterSet = (masteryLevelFilter != null && !masteryLevelFilter.isEmpty())
                ? new HashSet<>(masteryLevelFilter) : null;

        List<LessonItemsResponse.VocabItemDto> allVocabDtos = vocabList.stream()
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

        List<LessonItemsResponse.GrammarItemDto> allGrammarDtos = grammarList.stream()
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

        // Summary luôn tính trên toàn bộ (không phụ thuộc filter)
        int masteredVocab = (int) allVocabDtos.stream()
                .filter(v -> v.getMastery() != null && v.getMastery().getLevel() == 3).count();
        int masteredGrammar = (int) allGrammarDtos.stream()
                .filter(g -> g.getMastery() != null && g.getMastery().getLevel() == 3).count();
        int needsReview = (int) allVocabDtos.stream()
                .filter(v -> v.getMastery() == null || v.getMastery().getLevel() <= 1).count()
                + (int) allGrammarDtos.stream()
                .filter(g -> g.getMastery() == null || g.getMastery().getLevel() <= 1).count();

        // Apply filter (null filter set = trả về tất cả).
        // Items chưa luyện (mastery == null) có effective level = 0.
        // Chỉ match khi filterSet chứa đúng 0, KHÔNG tự động lọt qua khi filterSet khác null.
        List<LessonItemsResponse.VocabItemDto> vocabDtos = allVocabDtos.stream()
                .filter(v -> {
                    if (filterSet == null) return true;
                    int effectiveLevel = (v.getMastery() != null) ? v.getMastery().getLevel() : 0;
                    return filterSet.contains(effectiveLevel);
                })
                .sorted(vocabComparator)
                .collect(Collectors.toList());

        List<LessonItemsResponse.GrammarItemDto> grammarDtos = allGrammarDtos.stream()
                .filter(g -> {
                    if (filterSet == null) return true;
                    int effectiveLevel = (g.getMastery() != null) ? g.getMastery().getLevel() : 0;
                    return filterSet.contains(effectiveLevel);
                })
                .sorted(grammarComparator)
                .collect(Collectors.toList());

        LessonItemsResponse.LessonItemSummary summary = LessonItemsResponse.LessonItemSummary.builder()
                .totalVocab(allVocabDtos.size())
                .totalGrammar(allGrammarDtos.size())
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
            case 1 -> "Quen thuộc";
            case 2 -> "Nắm vững";
            case 3 -> "Thuộc";
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
        List<UUID> selectedVocabIds = new ArrayList<>();
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
        boolean sessionFinished = session.getStatus() == PracticeStatus.COMPLETED
                || session.getStatus() == PracticeStatus.ABANDONED;
        return items.stream()
                .map(item -> toItemDetailResponse(item, sessionFinished || item.getUserAnswer() != null))
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
        // Tất cả dạng "chọn 1 trong N options" (MC, TRUE_FALSE, FILL_BLANK, TRANSLATION)
        // cùng build logic → correctAnswer = index của option đúng (string integer).
        // TRUE_FALSE KHÔNG shuffle để giữ thứ tự Đúng/Sai cố định do LLM trả về.
        if (resolvedType == PracticeItemType.MULTIPLE_CHOICE
                || resolvedType == PracticeItemType.FILL_BLANK
                || resolvedType == PracticeItemType.TRANSLATION
                || resolvedType == PracticeItemType.TRUE_FALSE) {
            boolean shuffle = resolvedType != PracticeItemType.TRUE_FALSE;
            return buildSelectionItem(session, gen, index, resolvedType, shuffle);
        }

        // Fallback — loại item chưa xử lý (không nên xảy ra với enum hiện tại).
        List<ExerciseGeneratorAgent.GeneratedItem.OptionItem> options = gen.getOptions();
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
     * Build item dạng "chọn 1 trong N options": MULTIPLE_CHOICE, FILL_BLANK, TRANSLATION, TRUE_FALSE.
     * <p>
     * Options có thể được shuffle (trừ TRUE_FALSE) để đáp án không luôn ở vị trí cố định.
     * correctAnswer = index (id) của option có isCorrect=true sau khi shuffle (string integer).
     * Frontend submit id option được chọn → server so sánh integer — không LLM.
     * <p>
     * Fallback: nếu LLM không đánh dấu isCorrect, thử map theo text correctAnswer do LLM trả.
     */
    private PracticeItem buildSelectionItem(PracticeSession session,
                                            ExerciseGeneratorAgent.GeneratedItem gen,
                                            int index,
                                            PracticeItemType itemType,
                                            boolean shuffle) {
        List<ExerciseGeneratorAgent.GeneratedItem.OptionItem> options = gen.getOptions();
        if (options == null || options.isEmpty()) {
            log.warn("buildSelectionItem: LLM không trả về options cho {} item {}", itemType, index);
            return PracticeItem.builder()
                    .sessionId(session.getId())
                    .itemIndex(index)
                    .itemType(itemType)
                    .question(gen.getQuestion() != null ? gen.getQuestion() : "")
                    .options("[]")
                    .correctAnswer("")
                    .explanation(gen.getExplanation())
                    .build();
        }

        List<ExerciseGeneratorAgent.GeneratedItem.OptionItem> finalOptions =
                shuffle && options.size() > 1
                        ? new ArrayList<>(options)
                        : options;
        if (shuffle && options.size() > 1) {
            Collections.shuffle(finalOptions);
        }

        // correctAnswer = index của option có isCorrect=true trong mảng cuối
        int correctId = -1;
        for (int i = 0; i < finalOptions.size(); i++) {
            if (finalOptions.get(i).isCorrect()) {
                correctId = i;
                break;
            }
        }
        // Fallback: LLM không set isCorrect → thử khớp text với correctAnswer mà LLM trả về
        if (correctId == -1 && gen.getCorrectAnswer() != null && !gen.getCorrectAnswer().isBlank()) {
            String target = gen.getCorrectAnswer().trim();
            for (int i = 0; i < finalOptions.size(); i++) {
                String text = finalOptions.get(i).text();
                if (text != null && text.trim().equalsIgnoreCase(target)) {
                    correctId = i;
                    break;
                }
            }
        }
        if (correctId == -1) {
            log.warn("buildSelectionItem: không xác định được đáp án cho {} item {}", itemType, index);
            return PracticeItem.builder()
                    .sessionId(session.getId())
                    .itemIndex(index)
                    .itemType(itemType)
                    .question(gen.getQuestion() != null ? gen.getQuestion() : "")
                    .questionJapanese(gen.getQuestionJapanese())
                    .hint(gen.getHint())
                    .options(buildOptionsJson(finalOptions))
                    .correctAnswer("")
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
                .options(buildOptionsJson(finalOptions))
                .correctAnswer(String.valueOf(correctId))
                .explanation(gen.getExplanation())
                .refContentId(parseUuid(gen.getRefContentId()))
                .refContentType(gen.getRefContentType())
                .build();
    }

    /**
     * Build SENTENCE_ORDER item.
     * <p>
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
                } catch (NumberFormatException ignored) {
                }
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

    private PracticeItemDetailResponse toItemDetailResponse(PracticeItem item, boolean revealAnswer) {
        // MATCHING exposes the pair map (correctAnswer) upfront so the frontend can
        // validate drag-drop client-side — mirrors toItemResponse() contract on POST /sessions.
        boolean isMatching = item.getItemType() == PracticeItemType.MATCHING;
        boolean revealOptionsCorrect = revealAnswer || isMatching;
        List<OptionDto> options = parseOptionsForDisplay(item.getOptions(), revealOptionsCorrect);
        String correctAnswer = (revealAnswer || isMatching) ? item.getCorrectAnswer() : null;
        return PracticeItemDetailResponse.builder()
                .itemId(item.getId())
                .itemIndex(item.getItemIndex())
                .itemType(item.getItemType().name())
                .question(item.getQuestion())
                .questionJapanese(item.getQuestionJapanese())
                .options(options)
                .correctAnswer(correctAnswer)
                .userAnswer(item.getUserAnswer())
                .isCorrect(revealAnswer ? item.getIsCorrect() : null)
                .score(revealAnswer ? item.getScore() : 0)
                .hint(item.getHint())
                .explanation(revealAnswer ? item.getExplanation() : null)
                .aiFeedback(revealAnswer ? item.getAiFeedback() : null)
                .refContentId(item.getRefContentId())
                .refContentType(item.getRefContentType())
                .build();
    }
}
