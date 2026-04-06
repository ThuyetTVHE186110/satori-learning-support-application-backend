package com.fu.practice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.achievement.config.ActivityExpConfig;
import com.fu.achievement.service.AchievementService;
import com.fu.achievement.service.LearningLevelService;
import com.fu.achievement.dto.response.UserAchievementResponse;
import com.fu.achievement.dto.response.LevelUpNotificationResponse;
import com.fu.achievement.enums.BadgeType;
import com.fu.common.exception.BusinessException;
import com.fu.course.entity.Course;
import com.fu.course.entity.Lesson;
import com.fu.course.repository.LessonRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fu.practice.agent.AnswerEvaluatorAgent;
import com.fu.practice.agent.ExerciseGeneratorAgent;
import com.fu.practice.agent.SessionFeedbackAgent;
import com.fu.practice.dto.request.StartPracticeRequest;
import com.fu.practice.dto.request.SubmitAnswerRequest;
import com.fu.practice.dto.request.VerifyPairRequest;
import com.fu.practice.dto.response.*;
import com.fu.practice.entity.*;
import com.fu.practice.repository.LessonProgressRepository;
import com.fu.practice.repository.PracticeItemRepository;
import com.fu.practice.repository.PracticeSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private final ExerciseGeneratorAgent exerciseGeneratorAgent;
    private final AnswerEvaluatorAgent answerEvaluatorAgent;
    private final SessionFeedbackAgent sessionFeedbackAgent;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final AchievementService achievementService;
    private final LearningLevelService learningLevelService;

    // ── Start Session ─────────────────────────────────────────────────────

    /**
     * Bắt đầu phiên luyện tập mới.
     *
     * Sinh toàn bộ câu hỏi ngay khi start để submitAnswer không phải chờ LLM nữa.
     * LLM call runs OUTSIDE the transaction to avoid holding a DB connection for 5-30 seconds.
     */
    public StartPracticeResponse startSession(UUID userId, StartPracticeRequest request) {
        int itemCount = request.getItemCount();
        if (itemCount < 1 || itemCount > 20) {
            throw new BusinessException("INVALID_ITEM_COUNT",
                    "Số lượng câu hỏi phải từ 1 đến 20.");
        }

        // Phase 1: Read-only — load lesson, validate enrollment
        LessonContext lessonCtx = loadLessonContext(userId, request.getLessonId());

        // Phase 2: LLM call — sinh tất cả câu hỏi một lần (NO transaction held)
        List<ExerciseGeneratorAgent.GeneratedItem> allItems =
                exerciseGeneratorAgent.generateItems(
                        request.getLessonId(),
                        lessonCtx.courseId(),
                        request.getSessionType(),
                        itemCount,
                        lessonCtx.jlptLevel(),
                        request.getItemTypes());

        // Phase 3: Persist session + all items
        return persistNewSession(userId, request, lessonCtx, allItems);
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
                                                    List<ExerciseGeneratorAgent.GeneratedItem> firstItemList) {
        // Lưu jlptLevel + itemTypes vào context JSON để dùng lại khi sinh câu tiếp theo
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
                .totalItems(firstItemList.isEmpty() ? request.getItemCount() : firstItemList.size())
                .completedItems(0)
                .correctItems(0)
                .score(BigDecimal.ZERO)
                .startedAt(LocalDateTime.now())
                .context(contextJson)
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

    private record LessonContext(UUID courseId, String jlptLevel) {}

    // ── Submit Answer ─────────────────────────────────────────────────────

    /**
     * Nộp câu trả lời cho một câu hỏi.
     *
     * 1. Validate (short read TX)
     * 2. Evaluate via LLM (no TX)
     * 3. Persist answer + fetch pre-generated next item from DB (short write TX)
     */
    public AnswerFeedbackResponse submitAnswer(UUID sessionId, UUID itemId,
                                               UUID userId, SubmitAnswerRequest request) {
        // Phase 1: validate — short read TX
        SessionItemContext ctx = validateAndLoadItem(sessionId, itemId, userId);

        // Phase 2: LLM evaluation — NO transaction held
        AnswerEvaluatorAgent.EvaluationResult result =
                answerEvaluatorAgent.evaluate(ctx.item(), request.getUserAnswer());

        // Phase 3: Persist answer + fetch pre-generated next item — short write TX
        return persistAnswerResult(sessionId, itemId, request.getUserAnswer(), result, ctx);
    }

    /**
     * Kiểm tra ngay lập tức một cặp MATCHING — không thay đổi trạng thái DB.
     *
     * UI gọi endpoint này mỗi khi learner kéo nối một cặp để hiển thị phản hồi tức thì.
     * Khi {@code allPairsMatched = true}, UI tự động gọi {@code submitAnswer}
     * với toàn bộ mapping để hoàn thành item.
     *
     * @param sessionId session đang chơi
     * @param itemId    MATCHING item cần check
     * @param userId    học viên
     * @param request   leftId + rightId của cặp vừa kéo
     * @param matchedSoFar tất cả cặp learner đã kéo đúng tính đến lúc này (leftId:rightId,...)
     *                     — UI tự track và gửi lên để server tính allPairsMatched
     */
    @Transactional(readOnly = true)
    public VerifyPairResponse verifyMatchingPair(UUID sessionId, UUID itemId,
                                                  UUID userId, VerifyPairRequest request,
                                                  String matchedSoFar) {
        PracticeSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND",
                        "Không tìm thấy phiên luyện tập."));

        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("SESSION_ACCESS_DENIED",
                    "Bạn không có quyền truy cập phiên luyện tập này.");
        }
        if (session.getStatus() != PracticeStatus.IN_PROGRESS) {
            throw new BusinessException("SESSION_NOT_ACTIVE", "Phiên luyện tập đã kết thúc.");
        }

        PracticeItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException("ITEM_NOT_FOUND",
                        "Không tìm thấy câu hỏi."));

        if (!item.getSessionId().equals(sessionId)) {
            throw new BusinessException("ITEM_SESSION_MISMATCH",
                    "Câu hỏi không thuộc phiên luyện tập này.");
        }
        if (item.getItemType() != PracticeItemType.MATCHING) {
            throw new BusinessException("INVALID_ITEM_TYPE",
                    "Endpoint này chỉ áp dụng cho câu hỏi dạng MATCHING.");
        }
        if (item.getUserAnswer() != null) {
            throw new BusinessException("ITEM_ALREADY_ANSWERED",
                    "Câu hỏi này đã được hoàn thành.");
        }

        // Parse correctAnswer "0:4,1:5,2:6,3:7"
        Map<String, String> correctMap = parsePairMap(item.getCorrectAnswer());

        String leftKey  = String.valueOf(request.getLeftId());
        String rightVal = String.valueOf(request.getRightId());
        boolean pairCorrect = rightVal.equals(correctMap.get(leftKey));

        // Kiểm tra allPairsMatched: tất cả cặp đã confirm đúng chưa
        boolean allMatched = false;
        if (pairCorrect) {
            Map<String, String> matchedMap = parsePairMap(matchedSoFar);
            matchedMap.put(leftKey, rightVal); // thêm cặp vừa đúng
            allMatched = matchedMap.equals(correctMap);
        }

        return VerifyPairResponse.builder()
                .correct(pairCorrect)
                .allPairsMatched(allMatched)
                .build();
    }

    /**
     * Validate quyền truy cập và trạng thái, đồng thời đọc session context cần thiết
     * để sinh câu hỏi tiếp theo bên ngoài transaction.
     */
    @Transactional(readOnly = true)
    public SessionItemContext validateAndLoadItem(UUID sessionId, UUID itemId, UUID userId) {
        PracticeSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND",
                        "Không tìm thấy phiên luyện tập."));

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

        if (!item.getSessionId().equals(sessionId)) {
            throw new BusinessException("ITEM_SESSION_MISMATCH",
                    "Câu hỏi không thuộc phiên luyện tập này.");
        }

        if (item.getUserAnswer() != null) {
            throw new BusinessException("ITEM_ALREADY_ANSWERED",
                    "Câu hỏi này đã được trả lời.");
        }

        return new SessionItemContext(
                item,
                session.getCompletedItems(),
                session.getTotalItems(),
                session.getSessionType()
        );
    }

    @Transactional
    public AnswerFeedbackResponse persistAnswerResult(UUID sessionId, UUID itemId,
                                                       String userAnswer,
                                                       AnswerEvaluatorAgent.EvaluationResult result,
                                                       SessionItemContext ctx) {
        // Re-read entities within this transaction (optimistic locking)
        PracticeSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND",
                        "Không tìm thấy phiên luyện tập."));
        PracticeItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException("ITEM_NOT_FOUND",
                        "Không tìm thấy câu hỏi."));

        // Double-check race condition
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

        // Cập nhật session counter
        session.setCompletedItems(session.getCompletedItems() + 1);
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
                .build();
    }

    /** Context từ session cần thiết để persist kết quả câu trả lời. */
    public record SessionItemContext(
            PracticeItem item,
            int completedItemsSnapshot,
            int totalItems,
            PracticeSessionType sessionType
    ) {}

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

        // Weighted average of per-item scores (0–100 each).
        // More fair than binary correct/total: partial MATCHING credit and LLM partial
        // scores for TRANSLATION/FILL_BLANK are properly reflected in the session score.
        double scoreValue = 0.0;
        if (session.getTotalItems() > 0) {
            List<PracticeItem> items = itemRepository.findBySessionIdOrderByItemIndex(session.getId());
            double sumScores = items.stream()
                    .mapToInt(PracticeItem::getScore)
                    .sum();
            scoreValue = sumScores / session.getTotalItems();
        }

        BigDecimal score = BigDecimal.valueOf(scoreValue).setScale(2, RoundingMode.HALF_UP);
        session.setScore(score);
        session.setStatus(PracticeStatus.COMPLETED);
        session.setCompletedAt(LocalDateTime.now());
        sessionRepository.save(session);

        if (session.getLessonId() != null) {
            upsertLessonProgress(session);
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
     * Trả về ngay lập tức, không block HTTP response. Dùng importTaskExecutor
     * vì đây là LLM call tốn thời gian tương tự các agent khác.
     */
    @Async("importTaskExecutor")
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
        
        if (session.getCompletedAt() != null) {
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
                
                levelUpInfo = LevelUpNotificationResponse.builder()
                        .previousLevel(progressInfo.currentLevel() - 1)
                        .newLevel(progressInfo.currentLevel())
                        .totalExp(progressInfo.totalExp())
                        .expToNextLevel(progressInfo.expToNextLevel())
                        .progressPercentage(progressInfo.percentToNextLevel())
                        .isLevelUp(true)
                        .build();
            } catch (Exception e) {
                log.error("Error retrieving level-up info for session summary", e);
            }
        }

        return SessionSummaryResponse.builder()
                .sessionId(sessionId)
                .score(session.getScore())
                .correctItems(session.getCorrectItems())
                .totalItems(session.getTotalItems())
                .aiFeedback(feedbackText)
                .items(results)
                .earnedBadges(earnedBadges)
                .levelUpInfo(levelUpInfo)
                .build();
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

        StartPracticeRequest request = StartPracticeRequest.builder()
                .lessonId(session.getLessonId())
                .sessionType(session.getSessionType())
                .itemCount(session.getTotalItems())
                .build();

        return startSession(userId, request);
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

        // Shuffle options so the correct answer is never predictably at position 0
        List<ExerciseGeneratorAgent.GeneratedItem.OptionItem> options = gen.options();
        if (options != null && options.size() > 1
                && resolvedType != PracticeItemType.TRUE_FALSE) {
            options = new ArrayList<>(options);
            Collections.shuffle(options);
        }

        return PracticeItem.builder()
                .sessionId(session.getId())
                .itemIndex(index)
                .itemType(resolvedType)
                .question(gen.question() != null ? gen.question() : "")
                .questionJapanese(gen.questionJapanese())
                .hint(gen.hint())
                .options(buildOptionsJson(options))
                .correctAnswer(gen.correctAnswer() != null ? gen.correctAnswer() : "")
                .explanation(gen.explanation())
                .refContentId(parseUuid(gen.refContentId()))
                .refContentType(gen.refContentType())
                .build();
    }

    /**
     * Build một MATCHING PracticeItem từ danh sách pairs do LLM sinh ra.
     *
     * Cấu trúc options lưu DB:
     *   id 0..N-1      → LEFT  (tiếng Nhật, giữ nguyên thứ tự)
     *   id N..2N-1     → RIGHT (tiếng Việt, đã shuffle)
     *
     * correctAnswer = "0:X,1:Y,..." — ánh xạ left id → right id.
     */
    private PracticeItem buildMatchingItem(PracticeSession session,
                                            ExerciseGeneratorAgent.GeneratedItem gen,
                                            int index) {
        List<ExerciseGeneratorAgent.GeneratedItem.MatchingPair> pairs = gen.pairs();
        if (pairs == null || pairs.isEmpty()) {
            // Fallback: LLM không trả về pairs — lưu item trống
            log.warn("buildMatchingItem: LLM không trả về pairs cho MATCHING item {}", index);
            return PracticeItem.builder()
                    .sessionId(session.getId())
                    .itemIndex(index)
                    .itemType(PracticeItemType.MATCHING)
                    .question(gen.question() != null ? gen.question() : "Nối từ tiếng Nhật với nghĩa tiếng Việt")
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
                .question(gen.question() != null ? gen.question() : "Nối từ tiếng Nhật với nghĩa tiếng Việt")
                .questionJapanese(gen.questionJapanese())
                .hint(gen.hint())
                .options(optionsJson)
                .correctAnswer(correctAnswer.toString())
                .explanation(gen.explanation())
                .refContentId(parseUuid(gen.refContentId()))
                .refContentType(gen.refContentType())
                .build();
    }

    /** Parse "0:4,1:5,2:6" → Map{"0"→"4","1"→"5","2"→"6"}. */
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
        if (gen.itemType() != null && !gen.itemType().isBlank()) {
            try {
                return PracticeItemType.valueOf(gen.itemType().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("resolveItemType: LLM trả về itemType không hợp lệ '{}', fallback.", gen.itemType());
            }
        }
        List<ExerciseGeneratorAgent.GeneratedItem.OptionItem> options = gen.options();
        if (options != null && options.size() == 2) {
            boolean hasTrueFalse = options.stream()
                    .anyMatch(o -> "Đúng".equals(o.text()) || "Sai".equals(o.text()));
            if (hasTrueFalse) return PracticeItemType.TRUE_FALSE;
        }
        if (options != null && !options.isEmpty()) {
            return PracticeItemType.MULTIPLE_CHOICE;
        }
        return switch (sessionType) {
            case KANJI_READING  -> PracticeItemType.FILL_BLANK;
            case VOCAB_DRILL    -> PracticeItemType.FILL_BLANK;
            case GRAMMAR_DRILL  -> PracticeItemType.FILL_BLANK;
            default             -> PracticeItemType.TRANSLATION;
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

        // Tính mastery: rolling average giữa mastery cũ và điểm mới
        // Vocab mastery dựa trên loại session
        if (session.getSessionType() == PracticeSessionType.VOCAB_DRILL
                || session.getSessionType() == PracticeSessionType.KANJI_READING
                || session.getSessionType() == PracticeSessionType.MIXED_LESSON) {
            BigDecimal newVocab = rollingAverage(progress.getVocabMastery(), session.getScore());
            progress.setVocabMastery(newVocab);
        }

        if (session.getSessionType() == PracticeSessionType.GRAMMAR_DRILL
                || session.getSessionType() == PracticeSessionType.MIXED_LESSON) {
            BigDecimal newGrammar = rollingAverage(progress.getGrammarMastery(), session.getScore());
            progress.setGrammarMastery(newGrammar);
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
        return PracticeItemResponse.builder()
                .id(item.getId())
                .itemIndex(item.getItemIndex())
                .itemType(item.getItemType())
                .question(item.getQuestion())
                .questionJapanese(item.getQuestionJapanese())
                .hint(item.getHint())
                .options(options)
                .build();
        // correctAnswer KHÔNG được thêm vào đây
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
