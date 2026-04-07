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
import com.fu.practice.entity.PracticeSession;
import com.fu.practice.entity.PracticeStatus;
import com.fu.practice.entity.PracticeSessionType;
import com.fu.practice.entity.PracticeItemType;
import com.fu.practice.repository.LessonProgressRepository;
import com.fu.practice.repository.PracticeItemRepository;
import com.fu.practice.repository.PracticeSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PracticeSessionService")
class PracticeSessionServiceTest {

    @Mock private PracticeSessionRepository sessionRepository;
    @Mock private PracticeItemRepository itemRepository;
    @Mock private LessonProgressRepository progressRepository;
    @Mock private LessonRepository lessonRepository;
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

    private UUID userId;
    private UUID sessionId;
    private UUID itemId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        itemId = UUID.randomUUID();
    }

    // ── submitAnswer validation ───────────────────────────────────────────

    @Nested
    @DisplayName("submitAnswer — pre-conditions")
    class SubmitAnswerValidation {

        @Test
        @DisplayName("throws ITEM_NOT_FOUND when item does not exist")
        void throwsWhenItemNotFound() {
            when(itemRepository.findById(itemId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> practiceSessionService.submitAnswer(
                    sessionId, itemId, userId, new SubmitAnswerRequest("答え")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Không tìm thấy câu hỏi");
        }

        @Test
        @DisplayName("throws ITEM_SESSION_MISMATCH when item belongs to different session")
        void throwsWhenItemSessionMismatch() {
            PracticeItem item = buildItem(UUID.randomUUID()); // different sessionId
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(item));

            assertThatThrownBy(() -> practiceSessionService.submitAnswer(
                    sessionId, itemId, userId, new SubmitAnswerRequest("答え")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("không thuộc phiên");
        }

        @Test
        @DisplayName("throws ITEM_ALREADY_ANSWERED when item already has answer")
        void throwsWhenItemAlreadyAnswered() {
            PracticeItem item = buildItem(sessionId);
            item.setUserAnswer("既存の回答");
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(item));

            assertThatThrownBy(() -> practiceSessionService.submitAnswer(
                    sessionId, itemId, userId, new SubmitAnswerRequest("答え")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("đã được trả lời");
        }
    }

    // ── persistAnswerResult ───────────────────────────────────────────────

    @Nested
    @DisplayName("persistAnswerResult — session ownership and scoring")
    class PersistAnswerResult {

        @Test
        @DisplayName("throws SESSION_NOT_FOUND when session missing")
        void throwsWhenSessionNotFound() {
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());
            AnswerEvaluatorAgent.EvaluationResult eval =
                    new AnswerEvaluatorAgent.EvaluationResult(true, 100, "OK", null);

            assertThatThrownBy(() -> practiceSessionService.persistAnswerResult(
                    sessionId, itemId, userId, "答え", eval))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Không tìm thấy phiên");
        }

        @Test
        @DisplayName("throws SESSION_ACCESS_DENIED when userId does not match session")
        void throwsWhenOwnershipViolated() {
            UUID otherUser = UUID.randomUUID();
            PracticeSession session = buildSession(otherUser, PracticeStatus.IN_PROGRESS, 5, 2, 150);
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
            AnswerEvaluatorAgent.EvaluationResult eval =
                    new AnswerEvaluatorAgent.EvaluationResult(true, 100, "OK", null);

            assertThatThrownBy(() -> practiceSessionService.persistAnswerResult(
                    sessionId, itemId, userId, "答え", eval))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("không có quyền");
        }

        @Test
        @DisplayName("throws SESSION_NOT_ACTIVE when session already completed")
        void throwsWhenSessionCompleted() {
            PracticeSession session = buildSession(userId, PracticeStatus.COMPLETED, 5, 5, 400);
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
            AnswerEvaluatorAgent.EvaluationResult eval =
                    new AnswerEvaluatorAgent.EvaluationResult(true, 100, "OK", null);

            assertThatThrownBy(() -> practiceSessionService.persistAnswerResult(
                    sessionId, itemId, userId, "答え", eval))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("đã kết thúc");
        }

        @Test
        @DisplayName("accumulates runningScoreSum correctly for correct answer")
        void accumulatesRunningScoreSum() {
            // Session: 3 total, 1 completed, running score = 80
            PracticeSession session = buildSession(userId, PracticeStatus.IN_PROGRESS, 3, 1, 80);
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

            PracticeItem item = buildItem(sessionId);
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(item));
            when(itemRepository.save(any())).thenReturn(item);

            // Next item exists (index 2)
            PracticeItem nextItem = buildItem(sessionId);
            when(itemRepository.findBySessionIdAndItemIndex(eq(sessionId), eq(2)))
                    .thenReturn(Optional.of(nextItem));

            AnswerEvaluatorAgent.EvaluationResult eval =
                    new AnswerEvaluatorAgent.EvaluationResult(true, 90, "Chính xác!", null);

            AnswerFeedbackResponse response = practiceSessionService.persistAnswerResult(
                    sessionId, itemId, userId, "答え", eval);

            // runningScoreSum should be 80 + 90 = 170
            assertThat(session.getRunningScoreSum()).isEqualTo(170);
            // completedItems: 1 → 2
            assertThat(session.getCompletedItems()).isEqualTo(2);
            // correctItems incremented
            assertThat(session.getCorrectItems()).isEqualTo(1);
            assertThat(response.isCorrect()).isTrue();
            assertThat(response.isSessionCompleted()).isFalse();
        }

        @Test
        @DisplayName("completes session and calculates final score when last item answered")
        void completesSessionOnLastItem() {
            // Session: 2 total, 1 completed, running score = 60
            PracticeSession session = buildSession(userId, PracticeStatus.IN_PROGRESS, 2, 1, 60);
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

            PracticeItem item = buildItem(sessionId);
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(item));
            when(itemRepository.save(any())).thenReturn(item);
            when(sessionRepository.save(any())).thenReturn(session);
            when(sessionRepository.countByUserIdAndStatus(eq(userId), eq(PracticeStatus.COMPLETED)))
                    .thenReturn(1L);
            when(learningLevelService.getCurrentLevel(userId)).thenReturn(1);

            AnswerEvaluatorAgent.EvaluationResult eval =
                    new AnswerEvaluatorAgent.EvaluationResult(false, 40, "Sai rồi!", null);

            AnswerFeedbackResponse response = practiceSessionService.persistAnswerResult(
                    sessionId, itemId, userId, "間違い", eval);

            // runningScoreSum = 60 + 40 = 100, totalItems = 2 → score = 50.00
            assertThat(session.getRunningScoreSum()).isEqualTo(100);
            BigDecimal expectedScore = new BigDecimal("50.00");
            assertThat(session.getScore()).isEqualByComparingTo(expectedScore);
            assertThat(session.getStatus()).isEqualTo(PracticeStatus.COMPLETED);
            assertThat(response.isSessionCompleted()).isTrue();
        }

        @Test
        @DisplayName("score = 100 when all items correct")
        void perfectScoreWhenAllCorrect() {
            // 1 total item, 0 completed, running sum = 0
            PracticeSession session = buildSession(userId, PracticeStatus.IN_PROGRESS, 1, 0, 0);
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

            PracticeItem item = buildItem(sessionId);
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(item));
            when(itemRepository.save(any())).thenReturn(item);
            when(sessionRepository.save(any())).thenReturn(session);
            when(sessionRepository.countByUserIdAndStatus(any(), any())).thenReturn(1L);
            when(learningLevelService.getCurrentLevel(userId)).thenReturn(1);

            AnswerEvaluatorAgent.EvaluationResult eval =
                    new AnswerEvaluatorAgent.EvaluationResult(true, 100, "完璧!", null);

            practiceSessionService.persistAnswerResult(sessionId, itemId, userId, "答え", eval);

            assertThat(session.getScore()).isEqualByComparingTo(new BigDecimal("100.00"));
        }
    }

    // ── generateAndSaveFeedback ───────────────────────────────────────────

    @Nested
    @DisplayName("generateAndSaveFeedback")
    class GenerateAndSaveFeedback {

        @Test
        @DisplayName("skips if session not found")
        void skipsWhenSessionNotFound() {
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

            practiceSessionService.generateAndSaveFeedback(sessionId);

            verifyNoInteractions(sessionFeedbackAgent);
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("skips if session not completed")
        void skipsWhenSessionInProgress() {
            PracticeSession session = buildSession(userId, PracticeStatus.IN_PROGRESS, 3, 1, 50);
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

            practiceSessionService.generateAndSaveFeedback(sessionId);

            verifyNoInteractions(sessionFeedbackAgent);
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("skips if feedback already exists")
        void skipsWhenFeedbackAlreadyGenerated() {
            PracticeSession session = buildSession(userId, PracticeStatus.COMPLETED, 3, 3, 270);
            session.setAiFeedback("{\"summary\":\"already done\"}");
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

            practiceSessionService.generateAndSaveFeedback(sessionId);

            verifyNoInteractions(sessionFeedbackAgent);
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("generates feedback and publishes PracticeFeedbackReadyEvent")
        void generatesFeedbackAndPublishesEvent() {
            PracticeSession session = buildSession(userId, PracticeStatus.COMPLETED, 2, 2, 160);
            session.setScore(new BigDecimal("80.00"));
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
            when(itemRepository.findBySessionIdOrderByItemIndex(sessionId)).thenReturn(java.util.Collections.emptyList());
            when(sessionFeedbackAgent.generateFeedback(any(), anyDouble(), any(), any()))
                    .thenReturn("{\"summary\":\"よくできました\"}");
            when(sessionRepository.save(any())).thenReturn(session);

            practiceSessionService.generateAndSaveFeedback(sessionId);

            verify(sessionFeedbackAgent).generateFeedback(eq(sessionId), eq(80.0), any(), any());
            verify(sessionRepository).save(session);
            verify(eventPublisher).publishEvent(any());
        }

        @Test
        @DisplayName("does not rethrow when feedback agent fails — graceful degradation")
        void handlesAgentFailureGracefully() {
            PracticeSession session = buildSession(userId, PracticeStatus.COMPLETED, 1, 1, 70);
            session.setScore(new BigDecimal("70.00"));
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
            when(itemRepository.findBySessionIdOrderByItemIndex(sessionId)).thenReturn(java.util.Collections.emptyList());
            when(sessionFeedbackAgent.generateFeedback(any(), anyDouble(), any(), any()))
                    .thenThrow(new RuntimeException("OpenAI timeout"));

            // Must not throw
            assertThatCode(() -> practiceSessionService.generateAndSaveFeedback(sessionId))
                    .doesNotThrowAnyException();

            verify(sessionRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

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
        // Inject the sessionId via reflection-free approach: set via Setter
        // BaseEntity id is set by DB normally; for tests we need it addressable
        s.setId(sessionId);
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
        item.setId(itemId);
        return item;
    }
}
