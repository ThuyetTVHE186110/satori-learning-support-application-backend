package com.fu.practice.service;

import com.fu.achievement.service.AchievementService;
import com.fu.achievement.service.LearningLevelService;
import com.fu.achievement.service.StreakService;
import com.fu.common.exception.BusinessException;
import com.fu.course.repository.LessonRepository;
import com.fu.practice.agent.AnswerEvaluatorAgent;
import com.fu.practice.agent.ExerciseGeneratorAgent;
import com.fu.practice.agent.SessionFeedbackAgent;
import com.fu.practice.dto.request.SubmitAnswerRequest;
import com.fu.practice.dto.response.AnswerFeedbackResponse;
import com.fu.practice.entity.PracticeItem;
import com.fu.practice.entity.PracticeItemType;
import com.fu.practice.entity.PracticeSession;
import com.fu.practice.entity.PracticeSessionType;
import com.fu.practice.entity.PracticeStatus;
import com.fu.practice.repository.ItemMasteryRepository;
import com.fu.practice.repository.LessonProgressRepository;
import com.fu.practice.repository.PracticeItemRepository;
import com.fu.practice.repository.PracticeSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * Unit Test: PracticeSessionService — submitAnswer / persistAnswerResult / generateAndSaveFeedback /
 *            getSessionSummary / retryWrongItems / redoSession
 * Template: docs/SUBMIT_ANSWER_UNIT_TEST_TEMPLATE.md
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PracticeSessionService Unit Tests")
class PracticeSessionServiceTest {

    @Mock private PracticeSessionRepository sessionRepository;
    @Mock private PracticeItemRepository itemRepository;
    @Mock private LessonProgressRepository progressRepository;
    @Mock private LessonRepository lessonRepository;
    @Mock private ItemMasteryRepository masteryRepository;
    @Mock private ItemMasteryService itemMasteryService;
    @Mock private ExerciseGeneratorAgent exerciseGeneratorAgent;
    @Mock private AnswerEvaluatorAgent answerEvaluatorAgent;
    @Mock private SessionFeedbackAgent sessionFeedbackAgent;
    @Mock private ObjectMapper objectMapper;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private AchievementService achievementService;
    @Mock private LearningLevelService learningLevelService;
    @Mock private StreakService streakService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private PracticeSessionService practiceSessionService;

    // ── Fixed UUIDs ───────────────────────────────────────────────────────
    private static final UUID USER_ID    = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SESSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ITEM_ID    = UUID.fromString("22222222-2222-2222-2222-222222222222");

    // ── UTCID04: submitAnswer — item không tồn tại ─────────────────────────

    @Nested
    @DisplayName("submitAnswer — pre-conditions (UTCID04)")
    class SubmitAnswerValidation {

        @Test
        @DisplayName("UTCID04 — itemId không tìm thấy → BusinessException 'Không tìm thấy câu hỏi'")
        void shouldThrowWhenItemNotFound() {
            // Setup
            given(itemRepository.findById(ITEM_ID)).willReturn(Optional.empty());

            // Execute & Assert
            assertThatThrownBy(() -> practiceSessionService.submitAnswer(
                    SESSION_ID, ITEM_ID, USER_ID, new SubmitAnswerRequest("答え")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Không tìm thấy câu hỏi");
        }

        @Test
        @DisplayName("item.sessionId != SESSION_ID → BusinessException 'không thuộc phiên'")
        void shouldThrowWhenItemBelongsToDifferentSession() {
            // Setup
            UUID otherSession = UUID.fromString("44444444-4444-4444-4444-444444444444");
            PracticeItem item = buildItem(otherSession);
            given(itemRepository.findById(ITEM_ID)).willReturn(Optional.of(item));

            // Execute & Assert
            assertThatThrownBy(() -> practiceSessionService.submitAnswer(
                    SESSION_ID, ITEM_ID, USER_ID, new SubmitAnswerRequest("答え")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("không thuộc phiên");
        }

        @Test
        @DisplayName("item.userAnswer không null → BusinessException 'đã được trả lời'")
        void shouldThrowWhenItemAlreadyAnswered() {
            // Setup
            PracticeItem item = buildItem(SESSION_ID);
            item.setUserAnswer("既存の回答");
            given(itemRepository.findById(ITEM_ID)).willReturn(Optional.of(item));

            // Execute & Assert
            assertThatThrownBy(() -> practiceSessionService.submitAnswer(
                    SESSION_ID, ITEM_ID, USER_ID, new SubmitAnswerRequest("答え")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("đã được trả lời");
        }
    }

    // ── UTCID01-09: persistAnswerResult ────────────────────────────────────

    @Nested
    @DisplayName("persistAnswerResult — scoring and session completion (UTCID01-09)")
    class PersistAnswerResult {

        @Test
        @DisplayName("UTCID05 — session không tìm thấy → BusinessException 'Không tìm thấy phiên'")
        void shouldThrowWhenSessionNotFound() {
            // Setup
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.empty());
            AnswerEvaluatorAgent.EvaluationResult eval =
                    new AnswerEvaluatorAgent.EvaluationResult(true, 100, "OK", null);

            // Execute & Assert
            assertThatThrownBy(() -> practiceSessionService.persistAnswerResult(
                    SESSION_ID, ITEM_ID, USER_ID, "答え", eval))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Không tìm thấy phiên");
        }

        @Test
        @DisplayName("UTCID06 — session.userId != USER_ID → BusinessException 'không có quyền'")
        void shouldThrowWhenOwnershipViolated() {
            // Setup
            UUID otherUser = UUID.fromString("55555555-5555-5555-5555-555555555555");
            PracticeSession session = buildSession(otherUser, PracticeStatus.IN_PROGRESS, 5, 2, 150);
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
            AnswerEvaluatorAgent.EvaluationResult eval =
                    new AnswerEvaluatorAgent.EvaluationResult(true, 100, "OK", null);

            // Execute & Assert
            assertThatThrownBy(() -> practiceSessionService.persistAnswerResult(
                    SESSION_ID, ITEM_ID, USER_ID, "答え", eval))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("không có quyền");
        }

        @Test
        @DisplayName("UTCID07 — session.status=COMPLETED → BusinessException 'đã kết thúc'")
        void shouldThrowWhenSessionAlreadyCompleted() {
            // Setup
            PracticeSession session = buildSession(USER_ID, PracticeStatus.COMPLETED, 5, 5, 400);
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
            AnswerEvaluatorAgent.EvaluationResult eval =
                    new AnswerEvaluatorAgent.EvaluationResult(true, 100, "OK", null);

            // Execute & Assert
            assertThatThrownBy(() -> practiceSessionService.persistAnswerResult(
                    SESSION_ID, ITEM_ID, USER_ID, "答え", eval))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("đã kết thúc");
        }

        @Test
        @DisplayName("UTCID01 — score=90, correct=true, không phải câu cuối → runningScoreSum tăng, sessionCompleted=false")
        void shouldAccumulateScoreAndNotCompleteSessionWhenNotLastItem() {
            // Setup: 3 total, 1 completed, running=80 → after: running=170, completedItems=2
            PracticeSession session = buildSession(USER_ID, PracticeStatus.IN_PROGRESS, 3, 1, 80);
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

            PracticeItem item = buildItem(SESSION_ID);
            given(itemRepository.findById(ITEM_ID)).willReturn(Optional.of(item));
            given(itemRepository.save(any())).willReturn(item);

            PracticeItem nextItem = buildItem(SESSION_ID);
            given(itemRepository.findBySessionIdAndItemIndex(eq(SESSION_ID), eq(2)))
                    .willReturn(Optional.of(nextItem));

            AnswerEvaluatorAgent.EvaluationResult eval =
                    new AnswerEvaluatorAgent.EvaluationResult(true, 90, "Chính xác!", null);

            // Execute
            AnswerFeedbackResponse response = practiceSessionService.persistAnswerResult(
                    SESSION_ID, ITEM_ID, USER_ID, "答え", eval);

            // Assert
            assertThat(session.getRunningScoreSum()).isEqualTo(170);
            assertThat(session.getCompletedItems()).isEqualTo(2);
            assertThat(session.getCorrectItems()).isEqualTo(1);
            assertThat(response.isCorrect()).isTrue();
            assertThat(response.isSessionCompleted()).isFalse();
        }

        @Test
        @DisplayName("UTCID02 — score=40, correct=false, câu cuối → session COMPLETED, score=50.00")
        void shouldCompleteSessionAndCalculateFinalScoreOnLastItem() {
            // Setup: 2 total, 1 completed, running=60 → last item → score=(60+40)/2=50.00
            PracticeSession session = buildSession(USER_ID, PracticeStatus.IN_PROGRESS, 2, 1, 60);
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

            PracticeItem item = buildItem(SESSION_ID);
            given(itemRepository.findById(ITEM_ID)).willReturn(Optional.of(item));
            given(itemRepository.save(any())).willReturn(item);
            given(sessionRepository.save(any())).willReturn(session);
            given(sessionRepository.countByUserIdAndStatus(eq(USER_ID), eq(PracticeStatus.COMPLETED)))
                    .willReturn(1L);
            given(learningLevelService.getCurrentLevel(USER_ID)).willReturn(1);

            AnswerEvaluatorAgent.EvaluationResult eval =
                    new AnswerEvaluatorAgent.EvaluationResult(false, 40, "Sai rồi!", null);

            // Execute
            AnswerFeedbackResponse response = practiceSessionService.persistAnswerResult(
                    SESSION_ID, ITEM_ID, USER_ID, "間違い", eval);

            // Assert
            assertThat(session.getRunningScoreSum()).isEqualTo(100);
            assertThat(session.getScore()).isEqualByComparingTo(new BigDecimal("50.00"));
            assertThat(session.getStatus()).isEqualTo(PracticeStatus.COMPLETED);
            assertThat(session.getCorrectItems()).isEqualTo(0); // isCorrect=false
            assertThat(response.isCorrect()).isFalse();
            assertThat(response.isSessionCompleted()).isTrue();
        }

        @Test
        @DisplayName("UTCID03 (B) — score=100, correct=true, câu cuối duy nhất → score=100.00")
        void shouldReturnPerfectScoreWhenAllItemsCorrect() {
            // Setup: 1 total, 0 completed, running=0
            PracticeSession session = buildSession(USER_ID, PracticeStatus.IN_PROGRESS, 1, 0, 0);
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

            PracticeItem item = buildItem(SESSION_ID);
            given(itemRepository.findById(ITEM_ID)).willReturn(Optional.of(item));
            given(itemRepository.save(any())).willReturn(item);
            given(sessionRepository.save(any())).willReturn(session);
            given(sessionRepository.countByUserIdAndStatus(any(), any())).willReturn(1L);
            given(learningLevelService.getCurrentLevel(USER_ID)).willReturn(1);

            AnswerEvaluatorAgent.EvaluationResult eval =
                    new AnswerEvaluatorAgent.EvaluationResult(true, 100, "完璧!", null);

            // Execute
            practiceSessionService.persistAnswerResult(SESSION_ID, ITEM_ID, USER_ID, "答え", eval);

            // Assert
            assertThat(session.getScore()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(session.getCorrectItems()).isEqualTo(1);
            assertThat(session.getStatus()).isEqualTo(PracticeStatus.COMPLETED);
        }

        @Test
        @DisplayName("UTCID08 (B) — score=0, correct=false, câu cuối → correctItems không tăng, score tính đúng")
        void shouldNotIncrementCorrectItemsWhenScoreIsZeroAndIncorrect() {
            // Setup: 2 total, 1 completed, running=60 → last item, score=0 → final=(60+0)/2=30.00
            PracticeSession session = buildSession(USER_ID, PracticeStatus.IN_PROGRESS, 2, 1, 60);
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

            PracticeItem item = buildItem(SESSION_ID);
            given(itemRepository.findById(ITEM_ID)).willReturn(Optional.of(item));
            given(itemRepository.save(any())).willReturn(item);
            given(sessionRepository.save(any())).willReturn(session);
            given(sessionRepository.countByUserIdAndStatus(any(), any())).willReturn(0L);
            given(learningLevelService.getCurrentLevel(USER_ID)).willReturn(1);

            AnswerEvaluatorAgent.EvaluationResult eval =
                    new AnswerEvaluatorAgent.EvaluationResult(false, 0, "Sai!", null);

            // Execute
            AnswerFeedbackResponse response = practiceSessionService.persistAnswerResult(
                    SESSION_ID, ITEM_ID, USER_ID, "wrong", eval);

            // Assert
            assertThat(session.getCorrectItems()).isEqualTo(0);
            assertThat(session.getRunningScoreSum()).isEqualTo(60);
            assertThat(session.getScore()).isEqualByComparingTo(new BigDecimal("30.00"));
            assertThat(response.isCorrect()).isFalse();
            assertThat(response.isSessionCompleted()).isTrue();
        }

        @Test
        @DisplayName("UTCID09 — score=60, correct=true, câu cuối → correctItems tăng, session COMPLETED")
        void shouldIncrementCorrectItemsAndCompleteSessionWhenCorrectOnLastItem() {
            // Setup: 2 total, 1 completed, running=80 → final=(80+60)/2=70.00
            PracticeSession session = buildSession(USER_ID, PracticeStatus.IN_PROGRESS, 2, 1, 80);
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

            PracticeItem item = buildItem(SESSION_ID);
            given(itemRepository.findById(ITEM_ID)).willReturn(Optional.of(item));
            given(itemRepository.save(any())).willReturn(item);
            given(sessionRepository.save(any())).willReturn(session);
            given(sessionRepository.countByUserIdAndStatus(any(), any())).willReturn(2L);
            given(learningLevelService.getCurrentLevel(USER_ID)).willReturn(2);

            AnswerEvaluatorAgent.EvaluationResult eval =
                    new AnswerEvaluatorAgent.EvaluationResult(true, 60, "Đúng!", null);

            // Execute
            AnswerFeedbackResponse response = practiceSessionService.persistAnswerResult(
                    SESSION_ID, ITEM_ID, USER_ID, "答え", eval);

            // Assert
            assertThat(session.getCorrectItems()).isEqualTo(1);
            assertThat(session.getScore()).isEqualByComparingTo(new BigDecimal("70.00"));
            assertThat(session.getStatus()).isEqualTo(PracticeStatus.COMPLETED);
            assertThat(response.isCorrect()).isTrue();
            assertThat(response.isSessionCompleted()).isTrue();
        }
    }

    // ── generateAndSaveFeedback ─────────────────────────────────────────────

    @Nested
    @DisplayName("generateAndSaveFeedback — guard conditions and happy path")
    class GenerateAndSaveFeedback {

        @Test
        @DisplayName("session không tồn tại → bỏ qua, không gọi feedbackAgent")
        void shouldSkipWhenSessionNotFound() {
            // Setup
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.empty());

            // Execute
            practiceSessionService.generateAndSaveFeedback(SESSION_ID);

            // Assert
            then(sessionFeedbackAgent).shouldHaveNoInteractions();
            then(eventPublisher).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("session chưa COMPLETED → bỏ qua, không gọi feedbackAgent")
        void shouldSkipWhenSessionNotCompleted() {
            // Setup
            PracticeSession session = buildSession(USER_ID, PracticeStatus.IN_PROGRESS, 3, 1, 50);
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

            // Execute
            practiceSessionService.generateAndSaveFeedback(SESSION_ID);

            // Assert
            then(sessionFeedbackAgent).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("aiFeedback đã có sẵn → bỏ qua, không gọi feedbackAgent lần nữa")
        void shouldSkipWhenFeedbackAlreadyExists() {
            // Setup
            PracticeSession session = buildSession(USER_ID, PracticeStatus.COMPLETED, 3, 3, 270);
            session.setAiFeedback("{\"summary\":\"already done\"}");
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

            // Execute
            practiceSessionService.generateAndSaveFeedback(SESSION_ID);

            // Assert
            then(sessionFeedbackAgent).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("session COMPLETED, không có feedback → gọi feedbackAgent và publish event")
        void shouldGenerateFeedbackAndPublishEventForCompletedSession() {
            // Setup
            PracticeSession session = buildSession(USER_ID, PracticeStatus.COMPLETED, 2, 2, 160);
            session.setScore(new BigDecimal("80.00"));
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
            given(itemRepository.findBySessionIdOrderByItemIndex(SESSION_ID))
                    .willReturn(Collections.emptyList());
            given(sessionFeedbackAgent.generateFeedback(any(), anyDouble(), any(), any()))
                    .willReturn("{\"summary\":\"よくできました\"}");
            given(sessionRepository.save(any())).willReturn(session);

            // Execute
            practiceSessionService.generateAndSaveFeedback(SESSION_ID);

            // Assert
            verify(sessionFeedbackAgent).generateFeedback(eq(SESSION_ID), eq(80.0), any(), any());
            verify(sessionRepository).save(session);
            verify(eventPublisher).publishEvent(any());
        }

        @Test
        @DisplayName("feedbackAgent ném exception → không rethrow, graceful degradation")
        void shouldNotRethrowWhenFeedbackAgentFails() {
            // Setup
            PracticeSession session = buildSession(USER_ID, PracticeStatus.COMPLETED, 1, 1, 70);
            session.setScore(new BigDecimal("70.00"));
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
            given(itemRepository.findBySessionIdOrderByItemIndex(SESSION_ID))
                    .willReturn(Collections.emptyList());
            given(sessionFeedbackAgent.generateFeedback(any(), anyDouble(), any(), any()))
                    .willThrow(new RuntimeException("OpenAI timeout"));

            // Execute & Assert
            assertThatCode(() -> practiceSessionService.generateAndSaveFeedback(SESSION_ID))
                    .doesNotThrowAnyException();

            verify(sessionRepository, never()).save(any());
            then(eventPublisher).shouldHaveNoInteractions();
        }
    }

    // ── getSessionSummary ───────────────────────────────────────────────────

    @Nested
    @DisplayName("getSessionSummary — read access guards")
    class GetSessionSummary {

        @Test
        @DisplayName("session không tồn tại → BusinessException 'Không tìm thấy phiên'")
        void shouldThrowWhenSessionNotFound() {
            // Setup
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.empty());

            // Execute & Assert
            assertThatThrownBy(() -> practiceSessionService.getSessionSummary(SESSION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Không tìm thấy phiên");
        }

        @Test
        @DisplayName("session không thuộc USER_ID → BusinessException 'không có quyền'")
        void shouldThrowWhenNotOwner() {
            // Setup
            UUID otherUser = UUID.fromString("55555555-5555-5555-5555-555555555555");
            PracticeSession session = buildSession(otherUser, PracticeStatus.COMPLETED, 2, 2, 180);
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

            // Execute & Assert
            assertThatThrownBy(() -> practiceSessionService.getSessionSummary(SESSION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("không có quyền");
        }

        @Test
        @DisplayName("session COMPLETED thuộc USER_ID → trả về summary, không ném exception")
        void shouldReturnSummaryForOwnedCompletedSession() {
            // Setup: completedAt=null → EXP/badge block bị bỏ qua → minimal stubbing
            PracticeSession session = buildSession(USER_ID, PracticeStatus.COMPLETED, 2, 2, 160);
            session.setScore(new BigDecimal("80.00"));
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
            given(itemRepository.findBySessionIdOrderByItemIndex(SESSION_ID))
                    .willReturn(Collections.emptyList());

            // Execute & Assert
            assertThatCode(() -> practiceSessionService.getSessionSummary(SESSION_ID, USER_ID))
                    .doesNotThrowAnyException();
        }
    }

    // ── retryWrongItems ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("retryWrongItems — guard conditions")
    class RetryWrongItems {

        @Test
        @DisplayName("session không tồn tại → BusinessException 'Không tìm thấy phiên'")
        void shouldThrowWhenSessionNotFound() {
            // Setup
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.empty());

            // Execute & Assert
            assertThatThrownBy(() -> practiceSessionService.retryWrongItems(SESSION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Không tìm thấy phiên");
        }

        @Test
        @DisplayName("session không thuộc USER_ID → BusinessException 'không có quyền'")
        void shouldThrowWhenNotOwner() {
            // Setup
            UUID otherUser = UUID.fromString("55555555-5555-5555-5555-555555555555");
            PracticeSession session = buildSession(otherUser, PracticeStatus.COMPLETED, 3, 3, 150);
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

            // Execute & Assert
            assertThatThrownBy(() -> practiceSessionService.retryWrongItems(SESSION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("không có quyền");
        }

        @Test
        @DisplayName("session chưa COMPLETED → BusinessException chứa 'hoàn thành'")
        void shouldThrowWhenSessionNotCompleted() {
            // Setup
            PracticeSession session = buildSession(USER_ID, PracticeStatus.IN_PROGRESS, 3, 1, 60);
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

            // Execute & Assert
            assertThatThrownBy(() -> practiceSessionService.retryWrongItems(SESSION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("hoàn thành");
        }

        @Test
        @DisplayName("không có câu sai → BusinessException chứa 'đúng tất cả'")
        void shouldThrowWhenNoWrongItemsExist() {
            // Setup
            PracticeSession session = buildSession(USER_ID, PracticeStatus.COMPLETED, 3, 3, 300);
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
            given(itemRepository.findIncorrectBySessionId(SESSION_ID)).willReturn(Collections.emptyList());

            // Execute & Assert
            assertThatThrownBy(() -> practiceSessionService.retryWrongItems(SESSION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("đúng tất cả");
        }
    }

    // ── redoSession ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("redoSession — guard conditions")
    class RedoSession {

        @Test
        @DisplayName("session không tồn tại → BusinessException 'Không tìm thấy phiên'")
        void shouldThrowWhenSessionNotFound() {
            // Setup
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.empty());

            // Execute & Assert
            assertThatThrownBy(() -> practiceSessionService.redoSession(SESSION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Không tìm thấy phiên");
        }

        @Test
        @DisplayName("session không thuộc USER_ID → BusinessException 'không có quyền'")
        void shouldThrowWhenNotOwner() {
            // Setup
            UUID otherUser = UUID.fromString("55555555-5555-5555-5555-555555555555");
            PracticeSession session = buildSession(otherUser, PracticeStatus.COMPLETED, 3, 3, 270);
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

            // Execute & Assert
            assertThatThrownBy(() -> practiceSessionService.redoSession(SESSION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("không có quyền");
        }

        @Test
        @DisplayName("session chưa COMPLETED → BusinessException chứa 'hoàn thành'")
        void shouldThrowWhenSessionNotCompleted() {
            // Setup
            PracticeSession session = buildSession(USER_ID, PracticeStatus.IN_PROGRESS, 3, 2, 150);
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

            // Execute & Assert
            assertThatThrownBy(() -> practiceSessionService.redoSession(SESSION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("hoàn thành");
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private PracticeSession buildSession(UUID owner, PracticeStatus status,
                                         int totalItems, int completedItems,
                                         int runningScoreSum) {
        PracticeSession s = PracticeSession.builder()
                .userId(owner)
                .courseId(UUID.randomUUID())
                .sessionType(PracticeSessionType.MIXED_LESSON)
                .status(status)
                .totalItems(totalItems)
                .completedItems(completedItems)
                .correctItems(0)
                .runningScoreSum(runningScoreSum)
                .score(BigDecimal.ZERO)
                .build();
        s.setId(SESSION_ID);
        s.setUserId(owner);
        return s;
    }

    private PracticeItem buildItem(UUID ownerSessionId) {
        PracticeItem item = PracticeItem.builder()
                .sessionId(ownerSessionId)
                .itemType(PracticeItemType.MULTIPLE_CHOICE)
                .question("次の言葉の読み方は？")
                .correctAnswer("にほんご")
                .itemIndex(0)
                .build();
        item.setId(ITEM_ID);
        return item;
    }
}
