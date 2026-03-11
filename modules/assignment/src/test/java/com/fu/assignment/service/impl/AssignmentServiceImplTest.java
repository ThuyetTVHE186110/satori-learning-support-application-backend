package com.fu.assignment.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.assignment.ai.QuizAiGenerationService;
import com.fu.assignment.ai.QuizExplanationService;
import com.fu.assignment.dto.request.*;
import com.fu.assignment.dto.response.*;
import com.fu.assignment.entity.*;
import com.fu.assignment.exception.AssignmentNotFoundException;
import com.fu.assignment.mapper.AssignmentMapper;
import com.fu.assignment.repository.*;
import com.fu.clazz.repository.ClassMemberRepository;
import com.fu.clazz.repository.ClassRepository;
import com.fu.clazz.entity.Clazz;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.course.entity.Course;
import com.fu.infrastructure.cloudinary.CloudinaryService;
import com.fu.user.entity.User;
import com.fu.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssignmentServiceImplTest {

    @Mock private AssignmentRepository assignmentRepository;
    @Mock private QuizQuestionRepository questionRepository;
    @Mock private AssignmentQuestionRepository assignmentQuestionRepository;
    @Mock private AssignmentTargetRepository targetRepository;
    @Mock private AssignmentSubmissionRepository submissionRepository;
    @Mock private WritingAssignmentRepository writingAssignmentRepository;
    @Mock private AssignmentMapper mapper;
    @Mock private UserRepository userRepository;
    @Mock private ClassRepository classRepository;
    @Mock private ClassMemberRepository classMemberRepository;
    @Mock private QuizAiGenerationService quizAiGenerationService;
    @Mock private QuizExplanationService quizExplanationService;
    @Mock private CloudinaryService cloudinaryService;

    @InjectMocks
    private AssignmentServiceImpl service;

    private UUID teacherId;
    private UUID learnerId;
    private UUID assignmentId;
    private UUID questionId;
    private UUID classId;
    private UUID submissionId;

    private Assignment quizAssignment;
    private Assignment writingAssignment;
    private QuizQuestion sampleQuestion;
    private AssignmentSubmission sampleSubmission;
    private Pageable pageable;

    private AssignmentResponse assignmentResponse;
    private AssignmentSummaryResponse summaryResponse;
    private QuizQuestionResponse questionResponse;
    private SubmissionResponse submissionResponse;
    private SubmissionSummaryResponse submissionSummaryResponse;

    @BeforeEach
    void setUp() {
        teacherId = UUID.randomUUID();
        learnerId = UUID.randomUUID();
        assignmentId = UUID.randomUUID();
        questionId = UUID.randomUUID();
        classId = UUID.randomUUID();
        submissionId = UUID.randomUUID();
        pageable = PageRequest.of(0, 10);

        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        quizAssignment = Assignment.builder()
                .assignmentType(AssignmentType.QUIZ)
                .title("Quiz N5").classId(classId)
                .dueDate(LocalDateTime.now().plusDays(7))
                .status(AssignmentStatus.DRAFT)
                .createdBy(teacherId).hasSubmissions(false)
                .maxScore(100).passingScore(60)
                .allowRetry(false).maxRetries(1)
                .lateSubmissionAllowed(false).showCorrectAnswers(true)
                .build();
        ReflectionTestUtils.setField(quizAssignment, "id", assignmentId);

        writingAssignment = Assignment.builder()
                .assignmentType(AssignmentType.WRITING)
                .title("Writing N5").classId(classId)
                .dueDate(LocalDateTime.now().plusDays(7))
                .status(AssignmentStatus.DRAFT)
                .createdBy(teacherId).hasSubmissions(false)
                .maxScore(100).passingScore(60)
                .build();
        ReflectionTestUtils.setField(writingAssignment, "id", assignmentId);

        sampleQuestion = QuizQuestion.builder()
                .questionText("What is て-form of とります?")
                .questionType("multiple_choice")
                .options("[{\"text\":\"とって\",\"isCorrect\":true}]")
                .correctAnswer("とって")
                .status(Course.ContentStatus.DRAFT)
                .createdBy(teacherId).build();
        ReflectionTestUtils.setField(sampleQuestion, "id", questionId);

        sampleSubmission = AssignmentSubmission.builder()
                .assignmentId(assignmentId).userId(learnerId)
                .status(SubmissionStatus.IN_PROGRESS).attemptNumber(1)
                .startedAt(LocalDateTime.now()).totalQuestions(5)
                .answers("[]").correctCount(0).timeSpentSeconds(0)
                .build();
        ReflectionTestUtils.setField(sampleSubmission, "id", submissionId);

        assignmentResponse = AssignmentResponse.builder()
                .id(assignmentId).title("Quiz N5")
                .assignmentType(AssignmentType.QUIZ)
                .status(AssignmentStatus.DRAFT).build();
        summaryResponse = AssignmentSummaryResponse.builder()
                .id(assignmentId).title("Quiz N5")
                .assignmentType(AssignmentType.QUIZ).build();
        questionResponse = QuizQuestionResponse.builder()
                .id(questionId).questionText("What is て-form of とります?").build();
        submissionResponse = SubmissionResponse.builder()
                .id(submissionId).assignmentId(assignmentId).userId(learnerId).build();
        submissionSummaryResponse = SubmissionSummaryResponse.builder()
                .id(submissionId).assignmentId(assignmentId).build();
    }

    // ── Helper: common mock setup for successful saveFullQuiz ──
    private void mockQuizSaveSuccess() {
        given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));
        given(assignmentRepository.save(any(Assignment.class))).willReturn(quizAssignment);
        given(mapper.toResponse(any(Assignment.class))).willReturn(assignmentResponse);
        given(assignmentQuestionRepository.findByAssignmentIdOrderByOrderIndex(assignmentId)).willReturn(List.of());
    }

    private SaveFullQuizRequest defaultQuizRequest() {
        return SaveFullQuizRequest.builder()
                .title("Quiz").dueDate(LocalDateTime.now().plusDays(14))
                .maxScore(100).passingScore(60)
                .status(AssignmentStatus.DRAFT).build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 1. saveFullQuiz — hasSubmissions rules
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("saveFullQuiz — hasSubmissions rules")
    class SaveFullQuizSubmissionRules {

        @Test @DisplayName("should block questions when hasSubmissions=true AND questions changed")
        void blockQuestions() {
            quizAssignment.setHasSubmissions(true);
            SaveFullQuizRequest req = defaultQuizRequest();
            req.setQuestions(List.of(QuizQuestionDraftDto.builder().questionText("Q").build())); // "Q" differs from DB
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));
            // Return empty list so deep compare says "count changed (new question added)"
            given(assignmentQuestionRepository.findByAssignmentIdOrderByOrderIndex(assignmentId)).willReturn(List.of());

            assertThatThrownBy(() -> service.saveFullQuiz(assignmentId, null, req, null, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "HAS_SUBMISSIONS");
        }

        @Test @DisplayName("should allow updating when hasSubmissions=true AND questions are unchanged")
        void allowSameQuestionsWhenHasSubmissions() {
            quizAssignment.setHasSubmissions(true);
            SaveFullQuizRequest req = defaultQuizRequest();
            
            // Build incoming question to EXACTLY match sampleQuestion in DB
            QuizQuestionDraftDto incoming = QuizQuestionDraftDto.builder()
                    .id(questionId)
                    .questionText("What is て-form of とります?")
                    .questionType("multiple_choice")
                    .options("[{\"text\":\"とって\",\"isCorrect\":true}]")
                    .correctAnswer("とって")
                    .difficultyScore(1)
                    .points(10)
                    .orderIndex(0)
                    .build();
            req.setQuestions(List.of(incoming));

            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));
            AssignmentQuestion link = AssignmentQuestion.builder().assignmentId(assignmentId).questionId(questionId).orderIndex(0).points(10).build();
            given(assignmentQuestionRepository.findByAssignmentIdOrderByOrderIndex(assignmentId)).willReturn(List.of(link));
            given(questionRepository.findById(questionId)).willReturn(Optional.of(sampleQuestion));
            given(assignmentRepository.save(any(Assignment.class))).willReturn(quizAssignment);
            given(mapper.toResponse(any(Assignment.class))).willReturn(assignmentResponse);

            assertThat(service.saveFullQuiz(assignmentId, null, req, null, teacherId)).isNotNull();
        }

        @Test @DisplayName("should block audio change when hasSubmissions=true")
        void blockAudio() {
            quizAssignment.setHasSubmissions(true);
            quizAssignment.setAudioUrl("https://old.com/a.mp3");
            SaveFullQuizRequest req = defaultQuizRequest();
            req.setAudioUrl("https://new.com/b.mp3");
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));

            assertThatThrownBy(() -> service.saveFullQuiz(assignmentId, null, req, null, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "HAS_SUBMISSIONS");
        }

        @Test @DisplayName("should block shortening dueDate when hasSubmissions=true")
        void blockShortenDueDate() {
            quizAssignment.setHasSubmissions(true);
            quizAssignment.setDueDate(LocalDateTime.now().plusDays(7));
            SaveFullQuizRequest req = defaultQuizRequest();
            req.setDueDate(LocalDateTime.now().plusDays(3));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));

            assertThatThrownBy(() -> service.saveFullQuiz(assignmentId, null, req, null, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_DUE_DATE");
        }

        @Test @DisplayName("should allow extending dueDate when hasSubmissions=true")
        void allowExtendDueDate() {
            quizAssignment.setHasSubmissions(true);
            quizAssignment.setDueDate(LocalDateTime.now().plusDays(7));
            SaveFullQuizRequest req = defaultQuizRequest();
            req.setDueDate(LocalDateTime.now().plusDays(14));
            mockQuizSaveSuccess();

            AssignmentResponse result = service.saveFullQuiz(assignmentId, null, req, null, teacherId);
            assertThat(result).isNotNull();
        }

        @Test @DisplayName("should block changing maxScore when hasSubmissions=true")
        void blockChangeMaxScore() {
            quizAssignment.setHasSubmissions(true);
            SaveFullQuizRequest req = defaultQuizRequest();
            req.setMaxScore(50);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));

            assertThatThrownBy(() -> service.saveFullQuiz(assignmentId, null, req, null, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "HAS_SUBMISSIONS");
        }

        @Test @DisplayName("should block increasing passingScore when hasSubmissions=true")
        void blockIncreasePassingScore() {
            quizAssignment.setHasSubmissions(true);
            SaveFullQuizRequest req = defaultQuizRequest();
            req.setPassingScore(80);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));

            assertThatThrownBy(() -> service.saveFullQuiz(assignmentId, null, req, null, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "HAS_SUBMISSIONS");
        }

        @Test @DisplayName("should allow reducing passingScore when hasSubmissions=true")
        void allowReducePassingScore() {
            quizAssignment.setHasSubmissions(true);
            SaveFullQuizRequest req = defaultQuizRequest();
            req.setPassingScore(40);
            mockQuizSaveSuccess();

            assertThat(service.saveFullQuiz(assignmentId, null, req, null, teacherId)).isNotNull();
        }

        @Test @DisplayName("should throw when passingScore > maxScore")
        void passingScoreExceedsMax() {
            SaveFullQuizRequest req = defaultQuizRequest();
            req.setMaxScore(50);
            req.setPassingScore(80);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));

            assertThatThrownBy(() -> service.saveFullQuiz(assignmentId, null, req, null, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_PASSING_SCORE");
        }

        @Test @DisplayName("should allow metadata-only update when hasSubmissions=true")
        void metadataOnly() {
            quizAssignment.setHasSubmissions(true);
            mockQuizSaveSuccess();
            assertThat(service.saveFullQuiz(assignmentId, null, defaultQuizRequest(), null, teacherId)).isNotNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 2. Status Transition Validation
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Status Transition Validation")
    class StatusTransitionValidation {

        @Test @DisplayName("should publish DRAFT quiz with questions")
        void publishQuiz() {
            SaveFullQuizRequest req = defaultQuizRequest();
            req.setStatus(AssignmentStatus.PUBLISHED);
            req.setQuestions(List.of(QuizQuestionDraftDto.builder()
                    .questionText("Q1").options("[]").correctAnswer("A").build()));
            mockQuizSaveSuccess();
            given(questionRepository.save(any(QuizQuestion.class))).willAnswer(inv -> {
                QuizQuestion q = inv.getArgument(0);
                ReflectionTestUtils.setField(q, "id", UUID.randomUUID());
                return q;
            });
            given(assignmentQuestionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.saveFullQuiz(assignmentId, null, req, null, teacherId);
            assertThat(quizAssignment.getStatus()).isEqualTo(AssignmentStatus.PUBLISHED);
        }

        @Test @DisplayName("should throw when publishing quiz with 0 questions")
        void publishNoQuestions() {
            SaveFullQuizRequest req = defaultQuizRequest();
            req.setStatus(AssignmentStatus.PUBLISHED);
            quizAssignment.setQuestionCount(0);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));

            assertThatThrownBy(() -> service.saveFullQuiz(assignmentId, null, req, null, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "NO_QUESTIONS");
        }

        @Test @DisplayName("should throw when publishing writing with no prompt")
        void publishWritingNoPrompt() {
            SaveFullWritingRequest req = SaveFullWritingRequest.builder()
                    .title("W").dueDate(LocalDateTime.now().plusDays(7))
                    .maxScore(100).passingScore(60).prompt("p")
                    .status(AssignmentStatus.PUBLISHED).build();
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(writingAssignment));
            given(assignmentRepository.save(any())).willReturn(writingAssignment);
            given(writingAssignmentRepository.findByAssignmentId(assignmentId)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.saveFullWriting(assignmentId, null, req, AssignmentType.WRITING, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "NO_WRITING_CONTENT");
        }

        @Test @DisplayName("should throw PUBLISHED → DRAFT")
        void publishedToDraft() {
            quizAssignment.setStatus(AssignmentStatus.PUBLISHED);
            SaveFullQuizRequest req = defaultQuizRequest();
            req.setStatus(AssignmentStatus.DRAFT);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));

            assertThatThrownBy(() -> service.saveFullQuiz(assignmentId, null, req, null, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_STATUS_TRANSITION");
        }

        @Test @DisplayName("should throw CLOSED → any")
        void closedToAny() {
            quizAssignment.setStatus(AssignmentStatus.CLOSED);
            SaveFullQuizRequest req = defaultQuizRequest();
            req.setStatus(AssignmentStatus.PUBLISHED);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));

            assertThatThrownBy(() -> service.saveFullQuiz(assignmentId, null, req, null, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_STATUS_TRANSITION");
        }

        @Test @DisplayName("should allow PUBLISHED → CLOSED")
        void publishedToClosed() {
            quizAssignment.setStatus(AssignmentStatus.PUBLISHED);
            SaveFullQuizRequest req = defaultQuizRequest();
            req.setStatus(AssignmentStatus.CLOSED);
            mockQuizSaveSuccess();

            service.saveFullQuiz(assignmentId, null, req, null, teacherId);
            assertThat(quizAssignment.getStatus()).isEqualTo(AssignmentStatus.CLOSED);
        }

        @Test @DisplayName("should skip transition when status same")
        void sameStatus() {
            mockQuizSaveSuccess();
            service.saveFullQuiz(assignmentId, null, defaultQuizRequest(), null, teacherId);
            assertThat(quizAssignment.getStatus()).isEqualTo(AssignmentStatus.DRAFT);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 3. saveFullWriting
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("saveFullWriting")
    class SaveFullWriting {

        private SaveFullWritingRequest buildRequest() {
            return SaveFullWritingRequest.builder()
                    .title("Writing N5").description("desc")
                    .dueDate(LocalDateTime.now().plusDays(7))
                    .prompt("日本語で自己紹介を書いてください。")
                    .sampleAnswer("私の名前は田中です。")
                    .maxScore(100).passingScore(60).build();
        }

        @Test @DisplayName("should create a new WRITING assignment")
        void createNew() {
            SaveFullWritingRequest req = buildRequest();
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(mock(Clazz.class)));
            given(assignmentRepository.save(any(Assignment.class))).willAnswer(inv -> {
                Assignment a = inv.getArgument(0);
                ReflectionTestUtils.setField(a, "id", assignmentId);
                return a;
            });
            given(writingAssignmentRepository.findByAssignmentId(assignmentId)).willReturn(Optional.empty());
            given(writingAssignmentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(mapper.toResponse(any(Assignment.class))).willReturn(assignmentResponse);
            given(assignmentQuestionRepository.findByAssignmentIdOrderByOrderIndex(assignmentId)).willReturn(List.of());

            assertThat(service.saveFullWriting(null, classId, req, AssignmentType.WRITING, teacherId)).isNotNull();
            then(assignmentRepository).should().save(argThat(a -> a.getAssignmentType() == AssignmentType.WRITING));
        }

        @Test @DisplayName("should throw when using QUIZ type")
        void quizType() {
            assertThatThrownBy(() -> service.saveFullWriting(null, classId, buildRequest(), AssignmentType.QUIZ, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_TYPE");
        }

        @Test @DisplayName("should block writing content change when hasSubmissions=true AND content changed")
        void blockContentChange() {
            writingAssignment.setHasSubmissions(true);
            SaveFullWritingRequest req = buildRequest(); // Request has Prompt = "日本語で自己紹介..."
            
            // Mock DB returning a DIFFERENT prompt
            WritingAssignment currentInDb = WritingAssignment.builder().assignmentId(assignmentId).prompt("Different prompt").build();
            given(writingAssignmentRepository.findByAssignmentId(assignmentId)).willReturn(Optional.of(currentInDb));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(writingAssignment));

            assertThatThrownBy(() -> service.saveFullWriting(assignmentId, null, req, AssignmentType.WRITING, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "HAS_SUBMISSIONS");
        }

        @Test @DisplayName("should allow updating when hasSubmissions=true AND content is unchanged")
        void allowSameContentWhenHasSubmissions() {
            writingAssignment.setHasSubmissions(true);
            SaveFullWritingRequest req = buildRequest();
            
            // Mock DB returning the EXACT SAME content
            WritingAssignment currentInDb = WritingAssignment.builder()
                    .assignmentId(assignmentId)
                    .prompt(req.getPrompt())
                    .sampleAnswer(req.getSampleAnswer())
                    .build();
            given(writingAssignmentRepository.findByAssignmentId(assignmentId)).willReturn(Optional.of(currentInDb));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(writingAssignment));
            given(assignmentRepository.save(any(Assignment.class))).willReturn(writingAssignment);
            given(mapper.toResponse(any(Assignment.class))).willReturn(assignmentResponse);

            assertThat(service.saveFullWriting(assignmentId, null, req, AssignmentType.WRITING, teacherId)).isNotNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 4. deleteAssignment
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("deleteAssignment")
    class DeleteAssignment {

        @Test @DisplayName("should soft-delete DRAFT assignment")
        void happyPath() {
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));
            service.deleteAssignment(assignmentId, teacherId);
            assertThat(quizAssignment.getDeletedAt()).isNotNull();
        }

        @Test @DisplayName("should throw when not DRAFT")
        void notDraft() {
            quizAssignment.setStatus(AssignmentStatus.PUBLISHED);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));
            assertThatThrownBy(() -> service.deleteAssignment(assignmentId, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_STATUS");
        }

        @Test @DisplayName("should throw when wrong teacher")
        void wrongTeacher() {
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));
            assertThatThrownBy(() -> service.deleteAssignment(assignmentId, UUID.randomUUID()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "ACCESS_DENIED");
        }

        @Test @DisplayName("should throw when not found")
        void notFound() {
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteAssignment(assignmentId, teacherId))
                    .isInstanceOf(AssignmentNotFoundException.class);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 5. getAssignment / getAssignmentsByClass / getMyAssignments
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getAssignment")
    class GetAssignment {

        @Test @DisplayName("should return enriched assignment")
        void found() {
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));
            given(mapper.toResponse(quizAssignment)).willReturn(assignmentResponse);
            given(assignmentQuestionRepository.findByAssignmentIdOrderByOrderIndex(assignmentId)).willReturn(List.of());

            assertThat(service.getAssignment(assignmentId)).isNotNull();
        }

        @Test @DisplayName("should throw when not found")
        void notFound() {
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.getAssignment(assignmentId)).isInstanceOf(AssignmentNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getAssignmentsByClass")
    class GetAssignmentsByClass {
        @Test @DisplayName("should return paginated summary")
        void happyPath() {
            given(assignmentRepository.findByClassId(classId, pageable)).willReturn(new PageImpl<>(List.of(quizAssignment)));
            given(mapper.toSummaryResponse(quizAssignment)).willReturn(summaryResponse);
            given(assignmentQuestionRepository.countByAssignmentId(assignmentId)).willReturn(5L);

            assertThat(service.getAssignmentsByClass(classId, pageable).getContent()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("getMyAssignments")
    class GetMyAssignments {
        @Test @DisplayName("should return teacher's assignments")
        void happyPath() {
            given(assignmentRepository.findByCreatedByAndNotDeleted(teacherId, pageable)).willReturn(new PageImpl<>(List.of(quizAssignment)));
            given(mapper.toSummaryResponse(quizAssignment)).willReturn(summaryResponse);
            given(assignmentQuestionRepository.countByAssignmentId(assignmentId)).willReturn(3L);

            assertThat(service.getMyAssignments(teacherId, pageable).getContent()).hasSize(1);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 6. Learner Flow
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("startAssignment")
    class StartAssignment {

        @BeforeEach void setPublished() { quizAssignment.setStatus(AssignmentStatus.PUBLISHED); }

        @Test @DisplayName("should create IN_PROGRESS submission")
        void happyPath() {
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));
            given(submissionRepository.hasActiveSubmission(assignmentId, learnerId)).willReturn(false);
            given(submissionRepository.countByAssignmentIdAndUserId(assignmentId, learnerId)).willReturn(0L);
            given(assignmentQuestionRepository.countByAssignmentId(assignmentId)).willReturn(5L);
            given(submissionRepository.save(any())).willAnswer(inv -> {
                AssignmentSubmission s = inv.getArgument(0);
                ReflectionTestUtils.setField(s, "id", submissionId);
                return s;
            });
            given(assignmentRepository.save(any())).willReturn(quizAssignment);
            given(mapper.toResponse(any(AssignmentSubmission.class))).willReturn(submissionResponse);

            assertThat(service.startAssignment(assignmentId, learnerId)).isNotNull();
            assertThat(quizAssignment.getHasSubmissions()).isTrue();
        }

        @Test @DisplayName("should throw when not PUBLISHED")
        void notPublished() {
            quizAssignment.setStatus(AssignmentStatus.DRAFT);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));
            assertThatThrownBy(() -> service.startAssignment(assignmentId, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "ASSIGNMENT_NOT_PUBLISHED");
        }

        @Test @DisplayName("should throw when past due and late not allowed")
        void pastDue() {
            quizAssignment.setDueDate(LocalDateTime.now().minusDays(1));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));
            assertThatThrownBy(() -> service.startAssignment(assignmentId, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "PAST_DUE");
        }

        @Test @DisplayName("should throw when already in progress")
        void alreadyInProgress() {
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));
            given(submissionRepository.hasActiveSubmission(assignmentId, learnerId)).willReturn(true);
            assertThatThrownBy(() -> service.startAssignment(assignmentId, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "ALREADY_IN_PROGRESS");
        }

        @Test @DisplayName("should throw when no retry and has prior attempt")
        void noRetry() {
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));
            given(submissionRepository.hasActiveSubmission(assignmentId, learnerId)).willReturn(false);
            given(submissionRepository.countByAssignmentIdAndUserId(assignmentId, learnerId)).willReturn(1L);
            assertThatThrownBy(() -> service.startAssignment(assignmentId, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "NO_RETRY");
        }

        @Test @DisplayName("should throw when max retries reached")
        void maxRetries() {
            quizAssignment.setAllowRetry(true);
            quizAssignment.setMaxRetries(3);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));
            given(submissionRepository.hasActiveSubmission(assignmentId, learnerId)).willReturn(false);
            given(submissionRepository.countByAssignmentIdAndUserId(assignmentId, learnerId)).willReturn(3L);
            assertThatThrownBy(() -> service.startAssignment(assignmentId, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "MAX_RETRIES");
        }
    }

    @Nested
    @DisplayName("submitQuiz")
    class SubmitQuiz {

        @Test @DisplayName("should auto-score and set GRADED")
        void happyPath() {
            QuizQuestion q = QuizQuestion.builder().questionType("multiple_choice")
                    .options("[{\"id\":\"A\",\"text\":\"とって\",\"isCorrect\":true},{\"id\":\"B\",\"text\":\"とて\",\"isCorrect\":false}]")
                    .build();
            ReflectionTestUtils.setField(q, "id", questionId);
            AssignmentQuestion aq = AssignmentQuestion.builder()
                    .assignmentId(assignmentId).questionId(questionId).points(10).build();
            String answers = "[{\"questionId\":\"" + questionId + "\",\"selectedAnswer\":\"A\",\"timeSpent\":10}]";
            SubmitAssignmentRequest req = SubmitAssignmentRequest.builder()
                    .submissionId(submissionId).answers(answers).timeSpentSeconds(30).build();

            given(submissionRepository.findById(submissionId)).willReturn(Optional.of(sampleSubmission));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));
            quizAssignment.setStatus(AssignmentStatus.PUBLISHED);
            given(assignmentQuestionRepository.findByAssignmentIdOrderByOrderIndex(assignmentId)).willReturn(List.of(aq));
            given(questionRepository.findById(questionId)).willReturn(Optional.of(q));
            given(submissionRepository.save(any())).willReturn(sampleSubmission);
            given(mapper.toResponse(any(AssignmentSubmission.class))).willReturn(submissionResponse);

            assertThat(service.submitQuiz(req, learnerId)).isNotNull();
            assertThat(sampleSubmission.getStatus()).isEqualTo(SubmissionStatus.GRADED);
            assertThat(sampleSubmission.getCorrectCount()).isEqualTo(1);
        }

        @Test @DisplayName("should throw when wrong learner")
        void wrongLearner() {
            SubmitAssignmentRequest req = SubmitAssignmentRequest.builder()
                    .submissionId(submissionId).answers("[]").build();
            given(submissionRepository.findById(submissionId)).willReturn(Optional.of(sampleSubmission));
            assertThatThrownBy(() -> service.submitQuiz(req, UUID.randomUUID()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "ACCESS_DENIED");
        }

        @Test @DisplayName("should set OVERDUE when past due")
        void overdue() {
            quizAssignment.setStatus(AssignmentStatus.PUBLISHED);
            quizAssignment.setDueDate(LocalDateTime.now().minusDays(1));
            SubmitAssignmentRequest req = SubmitAssignmentRequest.builder()
                    .submissionId(submissionId).answers("[]").timeSpentSeconds(10).build();
            given(submissionRepository.findById(submissionId)).willReturn(Optional.of(sampleSubmission));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));
            given(assignmentQuestionRepository.findByAssignmentIdOrderByOrderIndex(assignmentId)).willReturn(List.of());
            given(submissionRepository.save(any())).willReturn(sampleSubmission);
            given(mapper.toResponse(any(AssignmentSubmission.class))).willReturn(submissionResponse);

            service.submitQuiz(req, learnerId);
            assertThat(sampleSubmission.getStatus()).isEqualTo(SubmissionStatus.OVERDUE);
        }
    }

    @Nested
    @DisplayName("submitWriting")
    class SubmitWriting {

        @Test @DisplayName("should submit writing with text")
        void happyPath() {
            SubmitWritingRequest req = SubmitWritingRequest.builder()
                    .assignmentId(assignmentId).writtenAnswer("Answer").timeSpentSeconds(120).build();
            given(submissionRepository.findByAssignmentIdAndUserIdOrderByAttemptDesc(assignmentId, learnerId))
                    .willReturn(List.of(sampleSubmission));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(writingAssignment));
            writingAssignment.setStatus(AssignmentStatus.PUBLISHED);
            given(submissionRepository.save(any())).willReturn(sampleSubmission);
            given(mapper.toResponse(any(AssignmentSubmission.class))).willReturn(submissionResponse);

            assertThat(service.submitWriting(req, null, learnerId)).isNotNull();
            assertThat(sampleSubmission.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
        }

        @Test @DisplayName("should throw when no text and no images")
        void noInput() {
            SubmitWritingRequest req = SubmitWritingRequest.builder().assignmentId(assignmentId).build();
            assertThatThrownBy(() -> service.submitWriting(req, null, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_INPUT");
        }

        @Test @DisplayName("should set OVERDUE when past due")
        void overdue() {
            writingAssignment.setStatus(AssignmentStatus.PUBLISHED);
            writingAssignment.setDueDate(LocalDateTime.now().minusDays(1));
            writingAssignment.setLateSubmissionAllowed(false);
            SubmitWritingRequest req = SubmitWritingRequest.builder()
                    .assignmentId(assignmentId).writtenAnswer("Late").build();
            given(submissionRepository.findByAssignmentIdAndUserIdOrderByAttemptDesc(assignmentId, learnerId))
                    .willReturn(List.of(sampleSubmission));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(writingAssignment));
            given(submissionRepository.save(any())).willReturn(sampleSubmission);
            given(mapper.toResponse(any(AssignmentSubmission.class))).willReturn(submissionResponse);

            service.submitWriting(req, null, learnerId);
            assertThat(sampleSubmission.getStatus()).isEqualTo(SubmissionStatus.OVERDUE);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 7. Teacher Submissions
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("gradeWritingSubmission")
    class GradeWritingSubmission {

        @Test @DisplayName("should grade SUBMITTED writing submission")
        void happyPath() {
            sampleSubmission.setStatus(SubmissionStatus.SUBMITTED);
            GradeWritingRequest req = GradeWritingRequest.builder()
                    .teacherScore(new BigDecimal("85.00")).teacherComment("Good!").feedback("Well done").build();
            given(submissionRepository.findById(submissionId)).willReturn(Optional.of(sampleSubmission));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(writingAssignment));
            given(submissionRepository.save(any())).willReturn(sampleSubmission);
            given(mapper.toResponse(sampleSubmission)).willReturn(submissionResponse);

            assertThat(service.gradeWritingSubmission(submissionId, req, teacherId)).isNotNull();
            assertThat(sampleSubmission.getStatus()).isEqualTo(SubmissionStatus.GRADED);
        }

        @Test @DisplayName("should throw when grading QUIZ submission")
        void quizType() {
            sampleSubmission.setStatus(SubmissionStatus.SUBMITTED);
            GradeWritingRequest req = GradeWritingRequest.builder().teacherScore(new BigDecimal("80")).build();
            given(submissionRepository.findById(submissionId)).willReturn(Optional.of(sampleSubmission));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));
            assertThatThrownBy(() -> service.gradeWritingSubmission(submissionId, req, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_TYPE");
        }

        @Test @DisplayName("should throw when submission is not SUBMITTED")
        void wrongStatus() {
            GradeWritingRequest req = GradeWritingRequest.builder().teacherScore(new BigDecimal("80")).build();
            given(submissionRepository.findById(submissionId)).willReturn(Optional.of(sampleSubmission));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(writingAssignment));
            assertThatThrownBy(() -> service.gradeWritingSubmission(submissionId, req, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_STATUS");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 8. getMySubmission
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMySubmission")
    class GetMySubmission {

        @Test @DisplayName("should return own submission")
        void happyPath() {
            given(submissionRepository.findById(submissionId)).willReturn(Optional.of(sampleSubmission));
            given(mapper.toResponse(sampleSubmission)).willReturn(submissionResponse);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));
            assertThat(service.getMySubmission(submissionId, learnerId)).isNotNull();
        }

        @Test @DisplayName("should throw when viewing another's submission")
        void accessDenied() {
            given(submissionRepository.findById(submissionId)).willReturn(Optional.of(sampleSubmission));
            assertThatThrownBy(() -> service.getMySubmission(submissionId, UUID.randomUUID()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "ACCESS_DENIED");
        }

        @Test @DisplayName("should throw when not found")
        void notFound() {
            given(submissionRepository.findById(submissionId)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.getMySubmission(submissionId, learnerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 9. getAssignmentForLearner
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getAssignmentForLearner")
    class GetAssignmentForLearner {

        @Test @DisplayName("should throw when not PUBLISHED")
        void notPublished() {
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(quizAssignment));
            assertThatThrownBy(() -> service.getAssignmentForLearner(assignmentId, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "ASSIGNMENT_NOT_AVAILABLE");
        }
    }
}
