package com.fu.practice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.fu.practice.dto.response.*;
import com.fu.practice.entity.*;
import com.fu.practice.repository.LessonProgressRepository;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.List;
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

    // ── Start Session ─────────────────────────────────────────────────────

    /**
     * Bắt đầu phiên luyện tập mới.
     *
     * Lazy generation: chỉ sinh câu hỏi đầu tiên để giảm thời gian chờ.
     * Các câu tiếp theo được sinh trong submitAnswer() song song với việc chấm điểm.
     *
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

        // Phase 2: LLM call — sinh DUY NHẤT câu đầu tiên (NO transaction held)
        List<ExerciseGeneratorAgent.GeneratedItem> firstItem =
                exerciseGeneratorAgent.generateItems(
                        request.getLessonId(),
                        lessonCtx.courseId(),
                        request.getSessionType(),
                        1,
                        lessonCtx.jlptLevel(),
                        request.getItemTypes());

        // Phase 3: Persist session + item 0 (totalItems = full requested count)
        return persistNewSession(userId, request, lessonCtx, firstItem);
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
                .totalItems(request.getItemCount())   // full count, không phải số đã gen
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
     * Split into 3 phases:
     * 1. Validate (short read TX) — cũng lấy session context để gen câu tiếp
     * 2a. Evaluate via LLM (no TX)
     * 2b. Generate next item if needed (no TX) — chạy sau khi eval xong
     * 3. Persist answer + next item (short write TX)
     */
    public AnswerFeedbackResponse submitAnswer(UUID sessionId, UUID itemId,
                                               UUID userId, SubmitAnswerRequest request) {
        // Phase 1: validate + load session context — short read TX
        SessionItemContext ctx = validateAndLoadItem(sessionId, itemId, userId);

        // Xác định trước xem sau câu này có cần sinh câu tiếp không
        boolean willNeedNext = (ctx.completedItemsSnapshot() + 1) < ctx.totalItems();

        // Phase 2a: LLM evaluation — NO transaction held
        AnswerEvaluatorAgent.EvaluationResult result =
                answerEvaluatorAgent.evaluate(ctx.item(), request.getUserAnswer());

        // Phase 2b: sinh câu tiếp theo ngay bây giờ (NO transaction held)
        ExerciseGeneratorAgent.GeneratedItem nextGen = null;
        if (willNeedNext) {
            try {
                List<ExerciseGeneratorAgent.GeneratedItem> nextItems =
                        exerciseGeneratorAgent.generateItems(
                                ctx.lessonId(), ctx.courseId(),
                                ctx.sessionType(), 1, ctx.jlptLevel(), ctx.itemTypes());
                nextGen = nextItems.isEmpty() ? null : nextItems.get(0);
            } catch (Exception e) {
                log.warn("submitAnswer: Không sinh được câu hỏi tiếp theo cho session {}: {}",
                        sessionId, e.getMessage());
            }
        }

        // Phase 3: Persist answer + next item — short write TX
        return persistAnswerResult(sessionId, itemId, request.getUserAnswer(), result, ctx, nextGen);
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

        String jlptLevel = extractJlptLevel(session.getContext());
        List<PracticeItemType> itemTypes = extractItemTypes(session.getContext());

        return new SessionItemContext(
                item,
                session.getCompletedItems(),
                session.getTotalItems(),
                session.getSessionType(),
                session.getLessonId(),
                session.getCourseId(),
                jlptLevel,
                itemTypes
        );
    }

    @Transactional
    public AnswerFeedbackResponse persistAnswerResult(UUID sessionId, UUID itemId,
                                                       String userAnswer,
                                                       AnswerEvaluatorAgent.EvaluationResult result,
                                                       SessionItemContext ctx,
                                                       ExerciseGeneratorAgent.GeneratedItem nextGen) {
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

        // Lưu câu hỏi tiếp theo (nếu gen thành công)
        PracticeItem savedNextItem = null;
        boolean sessionCompleted = session.getCompletedItems() >= session.getTotalItems();

        if (!sessionCompleted) {
            if (nextGen != null) {
                int nextIndex = session.getCompletedItems(); // sau khi tăng, đây là index câu tiếp
                savedNextItem = saveItem(session, nextGen, nextIndex);
            } else {
                // Sinh thất bại — giảm totalItems để session vẫn có thể kết thúc
                session.setTotalItems(session.getTotalItems() - 1);
                sessionCompleted = session.getCompletedItems() >= session.getTotalItems();
                log.warn("persistAnswerResult: Gen câu {} thất bại, giảm totalItems → {}",
                        session.getCompletedItems(), session.getTotalItems());
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

        PracticeItemResponse nextItemResponse = savedNextItem != null
                ? toItemResponse(savedNextItem)
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

    /** Context cần thiết từ session để sinh câu tiếp theo ngoài transaction. */
    public record SessionItemContext(
            PracticeItem item,
            int completedItemsSnapshot,
            int totalItems,
            PracticeSessionType sessionType,
            UUID lessonId,
            UUID courseId,
            String jlptLevel,
            List<PracticeItemType> itemTypes
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

        double scoreValue = session.getTotalItems() > 0
                ? ((double) session.getCorrectItems() / session.getTotalItems()) * 100.0
                : 0.0;

        BigDecimal score = BigDecimal.valueOf(scoreValue).setScale(2, RoundingMode.HALF_UP);
        session.setScore(score);
        session.setStatus(PracticeStatus.COMPLETED);
        session.setCompletedAt(LocalDateTime.now());
        sessionRepository.save(session);

        if (session.getLessonId() != null) {
            upsertLessonProgress(session);
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
            String feedback = sessionFeedbackAgent.generateFeedback(sessionId, scoreValue, allItems);
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

        return SessionSummaryResponse.builder()
                .sessionId(sessionId)
                .score(session.getScore())
                .correctItems(session.getCorrectItems())
                .totalItems(session.getTotalItems())
                .aiFeedback(feedbackText)
                .items(results)
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
                  AND c.is_active = true
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

    /**
     * Sinh và lưu một câu hỏi đơn lẻ tại vị trí index cho trước.
     * Dùng trong lazy generation khi submit câu trả lời.
     */
    private PracticeItem saveItem(PracticeSession session,
                                   ExerciseGeneratorAgent.GeneratedItem gen,
                                   int index) {
        return itemRepository.save(buildItem(session, gen, index));
    }

    private PracticeItem buildItem(PracticeSession session,
                                    ExerciseGeneratorAgent.GeneratedItem gen,
                                    int index) {
        return PracticeItem.builder()
                .sessionId(session.getId())
                .itemIndex(index)
                .itemType(resolveItemType(session.getSessionType(), gen))
                .question(gen.question() != null ? gen.question() : "")
                .questionJapanese(gen.questionJapanese())
                .hint(gen.hint())
                .options(buildOptionsJson(gen.options()))
                .correctAnswer(gen.correctAnswer() != null ? gen.correctAnswer() : "")
                .explanation(gen.explanation())
                .refContentId(parseUuid(gen.refContentId()))
                .refContentType(gen.refContentType())
                .build();
    }

    /**
     * Đọc jlptLevel từ session.context JSON.
     * Fallback về "N5" nếu không parse được.
     */
    private String extractJlptLevel(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) return "N5";
        try {
            return objectMapper.readTree(contextJson).path("jlptLevel").asText("N5");
        } catch (Exception e) {
            return "N5";
        }
    }

    /**
     * Đọc itemTypes từ session.context JSON.
     * Trả về null nếu không có (generator sẽ dùng default theo sessionType).
     */
    private List<PracticeItemType> extractItemTypes(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(contextJson).path("itemTypes");
            if (node.isMissingNode() || node.isNull() || !node.isArray() || node.isEmpty()) return null;
            return objectMapper.convertValue(node, new TypeReference<List<PracticeItemType>>() {});
        } catch (Exception e) {
            return null;
        }
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
            case SENTENCE_BUILD -> PracticeItemType.SENTENCE_ORDER;
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
                || session.getSessionType() == PracticeSessionType.SENTENCE_BUILD
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
            List<ExerciseGeneratorAgent.GeneratedItem.OptionItem> raw =
                    objectMapper.readValue(optionsJson,
                            new TypeReference<List<ExerciseGeneratorAgent.GeneratedItem.OptionItem>>() {});
            return raw.stream()
                    .map(o -> OptionDto.builder()
                            .text(o.text())
                            .isCorrect(revealCorrect ? o.isCorrect() : null)
                            .build())
                    .collect(Collectors.toList());
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
