package com.fu.assignment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.assignment.ai.AiGradingService;
import com.fu.assignment.ai.QuizAiGenerationService;
import com.fu.assignment.ai.QuizExplanationService;
import com.fu.assignment.dto.request.*;
import com.fu.assignment.dto.response.*;
import com.fu.assignment.entity.*;
import com.fu.assignment.exception.AssignmentNotFoundException;
import com.fu.assignment.mapper.AssignmentMapper;
import com.fu.assignment.repository.*;
import com.fu.assignment.scheduler.AssignmentScheduleManager;
import com.fu.assignment.service.impl.AssignmentServiceImpl;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.repository.ClassMemberRepository;
import com.fu.clazz.repository.ClassRepository;
import com.fu.clazz.repository.ClassTeacherHistoryRepository;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.infrastructure.cloudinary.CloudinaryService;
import com.fu.user.entity.User;
import com.fu.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AssignmentServiceImpl.
 * Covers: CRUD, lifecycle, submissions, grading, status validation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AssignmentService Unit Tests")
@SuppressWarnings("unchecked")
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
    @Mock private ObjectMapper objectMapper;
    @Mock private CloudinaryService cloudinaryService;
    @Mock private SubmissionService submissionService;
    @Mock private AiGradingService aiGradingService;
    @Mock private ClassTeacherHistoryRepository classTeacherHistoryRepository;
    @Mock private AssignmentScheduleManager scheduleManager;


    @InjectMocks
    private AssignmentServiceImpl assignmentService;

    @Captor private ArgumentCaptor<Assignment> assignmentCaptor;
    @Captor private ArgumentCaptor<AssignmentSubmission> submissionCaptor;

    // ── Test fixtures ─────────────────────────────────────────────────────────
    private UUID teacherId;
    private UUID learnerId;
    private UUID classId;
    private UUID assignmentId;
    private UUID submissionId;
    private UUID questionId;
    private Assignment testQuizAssignment;
    private Assignment testWritingAssignment;
    private AssignmentSubmission testSubmission;
    private Clazz testClazz;
    private AssignmentResponse testAssignmentResponse;
    private AssignmentSummaryResponse testSummaryResponse;
    private LearnerAssignmentResponse testLearnerAssignmentResponse;
    private LearnerAssignmentSummaryResponse testLearnerSummaryResponse;
    private SubmissionResponse testSubmissionResponse;
    private QuizQuestion testQuestion;

    @BeforeEach
    void setUp() {
        teacherId = UUID.randomUUID();
        learnerId = UUID.randomUUID();
        classId = UUID.randomUUID();
        assignmentId = UUID.randomUUID();
        submissionId = UUID.randomUUID();
        questionId = UUID.randomUUID();

        testClazz = Clazz.builder()
                .name("N5 Morning Class")
                .build();
        ReflectionTestUtils.setField(testClazz, "id", classId);
        try {
            // Set status to ACTIVE using reflection
            Object activeStatus = Enum.valueOf(
                    (Class<? extends Enum>) Class.forName("com.fu.clazz.entity.ClassStatus"), "ACTIVE");
            ReflectionTestUtils.setField(testClazz, "status", activeStatus);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        testQuizAssignment = Assignment.builder()
                .title("Japanese Quiz")
                .description("A quiz about [日本語]{にほんご}")
                .assignmentType(AssignmentType.QUIZ)
                .classId(classId)
                .dueDate(LocalDateTime.now().plusDays(7))
                .status(AssignmentStatus.DRAFT)
                .hasSubmissions(false)
                .questionCount(5)
                .createdBy(teacherId)
                .build();
        ReflectionTestUtils.setField(testQuizAssignment, "id", assignmentId);

        testWritingAssignment = Assignment.builder()
                .title("Writing Bài 1")
                .description("Bài viết cho bài 1")
                .assignmentType(AssignmentType.WRITING)
                .classId(classId)
                .dueDate(LocalDateTime.now().plusDays(7))
                .status(AssignmentStatus.DRAFT)
                .hasSubmissions(false)
                .createdBy(teacherId)
                .build();
        ReflectionTestUtils.setField(testWritingAssignment, "id", assignmentId);

        testSubmission = AssignmentSubmission.builder()
                .assignmentId(assignmentId)
                .userId(learnerId)
                .status(SubmissionStatus.IN_PROGRESS)
                .attemptNumber(1)
                .startedAt(LocalDateTime.now())
                .totalQuestions(5)
                .answers("[]")
                .correctCount(0)
                .build();
        ReflectionTestUtils.setField(testSubmission, "id", submissionId);

        testAssignmentResponse = AssignmentResponse.builder()
                .id(assignmentId)
                .title("Quiz Bài 1")
                .assignmentType(AssignmentType.QUIZ)
                .status(AssignmentStatus.DRAFT)
                .build();

        testSummaryResponse = AssignmentSummaryResponse.builder()
                .id(assignmentId)
                .title("Quiz Bài 1")
                .assignmentType(AssignmentType.QUIZ)
                .status(AssignmentStatus.DRAFT)
                .build();

        testLearnerSummaryResponse = LearnerAssignmentSummaryResponse.builder()
                .id(assignmentId)
                .title("Quiz Bài 1")
                .assignmentType(AssignmentType.QUIZ)
                .build();

        testLearnerAssignmentResponse = LearnerAssignmentResponse.builder()
                .id(assignmentId)
                .title("Quiz Bài 1")
                .assignmentType(AssignmentType.QUIZ)
                .build();

        testSubmissionResponse = SubmissionResponse.builder()
                .id(submissionId)
                .assignmentId(assignmentId)
                .userId(learnerId)
                .status(SubmissionStatus.IN_PROGRESS)
                .build();

        testQuestion = QuizQuestion.builder()
                .id(questionId)
                .questionText("What is ++this++?")
                .questionType("multiple_choice")
                .options("{\"options\":[{\"id\":\"A\",\"text\":\"[本]{ほん}\"}]}")
                .correctAnswer("A")
                .explanation("This is [本]{ほん}.")
                .build();
    }

    // ── Helper to stub teacher-access verification ────────────────────────────
    private void stubTeacherAccess() {
        given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
        given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId)).willReturn(Optional.of(teacherId));
    }

    private void stubEnrichResponse(Assignment a) {
        given(mapper.toResponse(any(Assignment.class))).willReturn(testAssignmentResponse);
        given(assignmentQuestionRepository.findByAssignmentIdOrderByOrderIndex(a.getId())).willReturn(List.of());
        given(submissionRepository.countByAssignmentIdAndStatus(eq(a.getId()), anyString())).willReturn(0L);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 1. SAVE FULL QUIZ
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SaveFullQuiz Tests")
    class SaveFullQuizTests {

        @Test
        @DisplayName("Should create new quiz assignment successfully")
        void shouldCreateNewQuizAssignment() {
            SaveFullQuizRequest request = SaveFullQuizRequest.builder()
                    .title("Quiz Bài 1")
                    .description("Quiz cho bài 1")
                    .dueDate(LocalDateTime.now().plusDays(7))
                    .status(AssignmentStatus.DRAFT)
                    .build();

            stubTeacherAccess();
            given(assignmentRepository.save(any(Assignment.class))).willReturn(testQuizAssignment);
            stubEnrichResponse(testQuizAssignment);

            AssignmentResponse result = assignmentService.saveFullQuiz(null, classId, request, null, teacherId);

            assertThat(result).isNotNull();
            assertThat(result.getTitle()).isEqualTo("Quiz Bài 1");
            verify(assignmentRepository).save(assignmentCaptor.capture());
            assertThat(assignmentCaptor.getValue().getAssignmentType()).isEqualTo(AssignmentType.QUIZ);
            assertThat(assignmentCaptor.getValue().getCreatedBy()).isEqualTo(teacherId);
            verify(targetRepository).save(any(AssignmentTarget.class));
        }

        @Test
        @DisplayName("Should throw when updating non-quiz assignment via saveFullQuiz")
        void shouldThrowWhenTypeIsNotQuiz() {
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testWritingAssignment));
            stubTeacherAccess();

            SaveFullQuizRequest request = SaveFullQuizRequest.builder()
                    .title("Quiz")
                    .dueDate(LocalDateTime.now().plusDays(7))
                    .build();

            assertThatThrownBy(() -> assignmentService.saveFullQuiz(assignmentId, classId, request, null, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("QUIZ");
        }

        @Test
        @DisplayName("Should throw when due date is in the past")
        void shouldThrowWhenDueDatePast() {
            SaveFullQuizRequest request = SaveFullQuizRequest.builder()
                    .title("Quiz")
                    .dueDate(LocalDateTime.now().minusDays(1))
                    .build();

            assertThatThrownBy(() -> assignmentService.saveFullQuiz(null, classId, request, null, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Hạn nộp");
        }

        @Test
        @DisplayName("Should block question changes when assignment has submissions")
        void shouldBlockQuestionsChangeWhenHasSubmissions() {
            testQuizAssignment.setHasSubmissions(true);
            testQuizAssignment.setStatus(AssignmentStatus.PUBLISHED);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            stubTeacherAccess();

            QuizQuestionDraftDto newQ = QuizQuestionDraftDto.builder()
                    .questionText("New question")
                    .questionType("multiple_choice")
                    .build();

            SaveFullQuizRequest request = SaveFullQuizRequest.builder()
                    .title("Quiz Bài 1")
                    .dueDate(LocalDateTime.now().plusDays(14))
                    .status(AssignmentStatus.PUBLISHED)
                    .questions(List.of(newQ))
                    .build();

            assertThatThrownBy(() -> assignmentService.saveFullQuiz(assignmentId, classId, request, null, teacherId))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 2. SAVE FULL WRITING
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SaveFullWriting Tests")
    class SaveFullWritingTests {

        @Test
        @DisplayName("Should create new writing assignment successfully")
        void shouldCreateNewWritingAssignment() {
            SaveFullWritingRequest request = SaveFullWritingRequest.builder()
                    .title("Writing Bài 1")
                    .description("Bài viết cho bài 1")
                    .dueDate(LocalDateTime.now().plusDays(7))
                    .prompt("Write about your daily routine")
                    .build();

            stubTeacherAccess();
            given(assignmentRepository.save(any(Assignment.class))).willReturn(testWritingAssignment);
            stubEnrichResponse(testWritingAssignment);
            given(writingAssignmentRepository.findByAssignmentId(any(UUID.class))).willReturn(Optional.empty());

            AssignmentResponse result = assignmentService.saveFullWriting(null, classId, request, AssignmentType.WRITING, teacherId);

            assertThat(result).isNotNull();
            verify(assignmentRepository).save(assignmentCaptor.capture());
            assertThat(assignmentCaptor.getValue().getAssignmentType()).isEqualTo(AssignmentType.WRITING);
            verify(writingAssignmentRepository).save(any(WritingAssignment.class));
            verify(targetRepository).save(any(AssignmentTarget.class));
        }

        @Test
        @DisplayName("Should throw when using saveFullWriting for QUIZ type")
        void shouldThrowWhenTypeIsQuiz() {
            SaveFullWritingRequest request = SaveFullWritingRequest.builder()
                    .title("Test").dueDate(LocalDateTime.now().plusDays(7)).prompt("p").build();

            assertThatThrownBy(() -> assignmentService.saveFullWriting(null, classId, request, AssignmentType.QUIZ, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("QUIZ");
        }

        @Test
        @DisplayName("Should block content changes when assignment has submissions")
        void shouldBlockContentChangeWhenHasSubmissions() {
            testWritingAssignment.setHasSubmissions(true);
            testWritingAssignment.setStatus(AssignmentStatus.PUBLISHED);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testWritingAssignment));
            stubTeacherAccess();

            WritingAssignment existingContent = WritingAssignment.builder()
                    .assignmentId(assignmentId).prompt("Old prompt").build();
            given(writingAssignmentRepository.findByAssignmentId(assignmentId)).willReturn(Optional.of(existingContent));

            SaveFullWritingRequest request = SaveFullWritingRequest.builder()
                    .title("Writing Bài 1")
                    .dueDate(LocalDateTime.now().plusDays(14))
                    .prompt("NEW prompt changed")
                    .build();

            assertThatThrownBy(() -> assignmentService.saveFullWriting(assignmentId, classId, request, AssignmentType.WRITING, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Không thể sửa nội dung");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 3. DELETE ASSIGNMENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DeleteAssignment Tests")
    class DeleteAssignmentTests {

        @Test
        @DisplayName("Should soft-delete draft assignment successfully")
        void shouldSoftDeleteDraftAssignment() {
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            stubTeacherAccess();
            given(assignmentRepository.save(any(Assignment.class))).willReturn(testQuizAssignment);

            assignmentService.deleteAssignment(assignmentId, teacherId);

            verify(assignmentRepository).save(assignmentCaptor.capture());
            assertThat(assignmentCaptor.getValue().getDeletedAt()).isNotNull();
            assertThat(assignmentCaptor.getValue().getDeletedBy()).isEqualTo(teacherId);
        }

        @Test
        @DisplayName("Should throw when assignment not found for delete")
        void shouldThrowWhenNotFound() {
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.empty());

            assertThatThrownBy(() -> assignmentService.deleteAssignment(assignmentId, teacherId))
                    .isInstanceOf(AssignmentNotFoundException.class);
        }

        @Test
        @DisplayName("Should throw when assignment is not DRAFT and has submission")
        void shouldThrowWhenNotDraft() {
            testQuizAssignment.setStatus(AssignmentStatus.PUBLISHED);
            testQuizAssignment.setHasSubmissions(true);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            stubTeacherAccess();

            assertThatThrownBy(() -> assignmentService.deleteAssignment(assignmentId, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("nháp");

            verify(assignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw when teacher does not own the assignment")
        void shouldThrowWhenAccessDenied() {
            UUID otherTeacherId = UUID.randomUUID();
            Clazz otherClazz = Clazz.builder().name("Other").build();
            ReflectionTestUtils.setField(otherClazz, "id", classId);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(otherClazz));
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId)).willReturn(Optional.of(otherTeacherId));

            UUID wrongTeacher = UUID.randomUUID();
            assertThatThrownBy(() -> assignmentService.deleteAssignment(assignmentId, wrongTeacher))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("không phải là giáo viên");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 4. GET ASSIGNMENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GetAssignment Tests")
    class GetAssignmentTests {

        @Test
        @DisplayName("Should get assignment by ID successfully")
        void shouldGetAssignmentById() {
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            stubTeacherAccess();
            stubEnrichResponse(testQuizAssignment);

            AssignmentResponse result = assignmentService.getAssignment(assignmentId, teacherId);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(assignmentId);
        }

        @Test
        @DisplayName("Should throw when assignment not found")
        void shouldThrowWhenNotFound() {
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.empty());

            assertThatThrownBy(() -> assignmentService.getAssignment(assignmentId, teacherId))
                    .isInstanceOf(AssignmentNotFoundException.class);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 5. GET ASSIGNMENTS BY CLASS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GetAssignmentsByClass Tests")
    class GetAssignmentsByClassTests {

        @Test
        @DisplayName("Should return paginated assignments for class")
        void shouldReturnAssignmentsForClass() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<Assignment> page = new PageImpl<>(List.of(testQuizAssignment), pageable, 1);
            stubTeacherAccess();
            given(assignmentRepository.findAll(any(Specification.class), eq(pageable))).willReturn(page);
            given(mapper.toSummaryResponse(testQuizAssignment)).willReturn(testSummaryResponse);
            given(assignmentQuestionRepository.countByAssignmentId(assignmentId)).willReturn(5L);
            given(submissionRepository.countByAssignmentIdAndStatus(eq(assignmentId), anyString())).willReturn(0L);
            given(submissionRepository.countUngradedByAssignmentIds(any())).willReturn(List.of());

            Page<AssignmentSummaryResponse> result = assignmentService.getAssignmentsByClass(
                    classId, null, null, null, pageable, teacherId);

            assertThat(result.getTotalElements()).isEqualTo(1);
            verify(assignmentRepository).findAll(any(Specification.class), eq(pageable));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 6. GET MY ASSIGNMENTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GetMyAssignments Tests")
    class GetMyAssignmentsTests {

        @Test
        @DisplayName("Should return teacher's own assignments")
        void shouldReturnTeacherOwnAssignments() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<Assignment> page = new PageImpl<>(List.of(testQuizAssignment), pageable, 1);
            given(assignmentRepository.findAll(any(Specification.class), eq(pageable))).willReturn(page);
            given(mapper.toSummaryResponse(testQuizAssignment)).willReturn(testSummaryResponse);
            given(assignmentQuestionRepository.countByAssignmentId(assignmentId)).willReturn(5L);
            given(submissionRepository.countByAssignmentIdAndStatus(eq(assignmentId), anyString())).willReturn(0L);
            given(submissionRepository.countUngradedByAssignmentIds(any())).willReturn(List.of());

            Page<AssignmentSummaryResponse> result = assignmentService.getMyAssignments(
                    teacherId, null, null, null, pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 7. CLONE ASSIGNMENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("CloneAssignment Tests")
    class CloneAssignmentTests {

        @Test
        @DisplayName("Should clone quiz assignment to another class")
        void shouldCloneQuizAssignment() {
            UUID targetClassId = UUID.randomUUID();
            Clazz targetClazz = Clazz.builder().name("Target Class").build();
            ReflectionTestUtils.setField(targetClazz, "id", targetClassId);

            CloneAssignmentRequest request = CloneAssignmentRequest.builder()
                    .targetClassId(targetClassId)
                    .newDueDate(LocalDateTime.now().plusDays(14))
                    .newTitle("Cloned Quiz")
                    .build();

            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            given(classRepository.findByIdAndNotDeleted(any(UUID.class))).willReturn(Optional.of(targetClazz));
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(any(UUID.class))).willReturn(Optional.of(teacherId));

            Assignment clonedAssignment = Assignment.builder()
                    .title("Cloned Quiz")
                    .assignmentType(AssignmentType.QUIZ)
                    .status(AssignmentStatus.DRAFT)
                    .hasSubmissions(false)
                    .build();
            UUID clonedId = UUID.randomUUID();
            ReflectionTestUtils.setField(clonedAssignment, "id", clonedId);

            given(assignmentRepository.save(any(Assignment.class))).willReturn(clonedAssignment);
            given(assignmentQuestionRepository.findByAssignmentIdOrderByOrderIndex(assignmentId)).willReturn(List.of());
            given(mapper.toResponse(any(Assignment.class))).willReturn(
                    AssignmentResponse.builder().id(clonedId).title("Cloned Quiz").status(AssignmentStatus.DRAFT).build());
            given(assignmentQuestionRepository.findByAssignmentIdOrderByOrderIndex(clonedId)).willReturn(List.of());
            given(submissionRepository.countByAssignmentIdAndStatus(eq(clonedId), anyString())).willReturn(0L);

            AssignmentResponse result = assignmentService.cloneAssignment(assignmentId, request, teacherId);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(AssignmentStatus.DRAFT);
            verify(assignmentRepository).save(any(Assignment.class));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 8. GRADE WRITING SUBMISSION
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GradeWritingSubmission Tests")
    class GradeWritingTests {

        @Test
        @DisplayName("Should grade writing submission successfully")
        void shouldGradeWritingSubmission() {
            testSubmission.setStatus(SubmissionStatus.SUBMITTED);
            given(submissionRepository.findById(submissionId)).willReturn(Optional.of(testSubmission));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testWritingAssignment));
            stubTeacherAccess();
            given(submissionRepository.save(any(AssignmentSubmission.class))).willReturn(testSubmission);
            given(mapper.toResponse(any(AssignmentSubmission.class))).willReturn(testSubmissionResponse);
            given(userRepository.findById(learnerId)).willReturn(Optional.empty());

            GradeWritingRequest request = GradeWritingRequest.builder()
                    .teacherScore(BigDecimal.valueOf(8.5))
                    .feedback("Good work!")
                    .build();

            SubmissionResponse result = assignmentService.gradeWritingSubmission(submissionId, request, teacherId);

            assertThat(result).isNotNull();
            verify(submissionRepository).save(submissionCaptor.capture());
            AssignmentSubmission graded = submissionCaptor.getValue();
            assertThat(graded.getScore()).isEqualByComparingTo(BigDecimal.valueOf(8.5));
            assertThat(graded.getStatus()).isEqualTo(SubmissionStatus.GRADED);
            assertThat(graded.getGradedBy()).isEqualTo(teacherId);
        }

        @Test
        @DisplayName("Should throw when grading quiz type via writing grading")
        void shouldThrowWhenGradingQuiz() {
            testSubmission.setStatus(SubmissionStatus.SUBMITTED);
            given(submissionRepository.findById(submissionId)).willReturn(Optional.of(testSubmission));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            stubTeacherAccess();

            GradeWritingRequest request = GradeWritingRequest.builder()
                    .teacherScore(BigDecimal.valueOf(8.5)).feedback("f").build();

            assertThatThrownBy(() -> assignmentService.gradeWritingSubmission(submissionId, request, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("QUIZ sử dụng chấm điểm tự động");
        }

        @Test
        @DisplayName("Should throw when submission status is not SUBMITTED")
        void shouldThrowWhenStatusNotSubmitted() {
            testSubmission.setStatus(SubmissionStatus.GRADED);
            given(submissionRepository.findById(submissionId)).willReturn(Optional.of(testSubmission));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testWritingAssignment));
            stubTeacherAccess();

            GradeWritingRequest request = GradeWritingRequest.builder()
                    .teacherScore(BigDecimal.valueOf(8.5)).feedback("f").build();

            assertThatThrownBy(() -> assignmentService.gradeWritingSubmission(submissionId, request, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Chỉ có thể chấm điểm bài nộp ở trạng thái SUBMITTED");
        }

        @Test
        @DisplayName("Should throw when submission not found")
        void shouldThrowWhenSubmissionNotFound() {
            given(submissionRepository.findById(submissionId)).willReturn(Optional.empty());

            GradeWritingRequest request = GradeWritingRequest.builder()
                    .teacherScore(BigDecimal.valueOf(8.5)).feedback("f").build();

            assertThatThrownBy(() -> assignmentService.gradeWritingSubmission(submissionId, request, teacherId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 9. START ASSIGNMENT (LEARNER)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("StartAssignment Tests")
    class StartAssignmentTests {

        @Test
        @DisplayName("Should start new assignment successfully when no active submission")
        void shouldStartNewAssignment() {
            testQuizAssignment.setStatus(AssignmentStatus.PUBLISHED);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            given(submissionRepository.findActiveByAssignmentIdAndUserId(assignmentId, learnerId)).willReturn(Optional.empty());
            given(submissionRepository.countByAssignmentIdAndUserId(assignmentId, learnerId)).willReturn(0L);
            given(assignmentQuestionRepository.countByAssignmentId(assignmentId)).willReturn(5L);
            given(submissionRepository.save(any(AssignmentSubmission.class))).willReturn(testSubmission);
            given(assignmentRepository.save(any(Assignment.class))).willReturn(testQuizAssignment);
            given(mapper.toLearnerResponse(any(Assignment.class))).willReturn(testLearnerAssignmentResponse);
            given(assignmentQuestionRepository.countByAssignmentId(assignmentId)).willReturn(5L);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            
            // For enrichment
            given(assignmentQuestionRepository.findByAssignmentIdOrderByOrderIndex(assignmentId)).willReturn(List.of());

            LearnerAssignmentResponse result = assignmentService.startAssignment(assignmentId, learnerId);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(assignmentId);
            verify(submissionRepository).save(any(AssignmentSubmission.class));
            verify(assignmentRepository).save(any(Assignment.class));
        }

        @Test
        @DisplayName("Should resume assignment successfully when active submission exists")
        void shouldResumeAssignment() {
            testQuizAssignment.setStatus(AssignmentStatus.PUBLISHED);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            given(submissionRepository.findActiveByAssignmentIdAndUserId(assignmentId, learnerId)).willReturn(Optional.of(testSubmission));
            
            // For enrichment
            given(mapper.toLearnerResponse(any(Assignment.class))).willReturn(testLearnerAssignmentResponse);
            given(assignmentQuestionRepository.findByAssignmentIdOrderByOrderIndex(assignmentId)).willReturn(List.of());

            LearnerAssignmentResponse result = assignmentService.startAssignment(assignmentId, learnerId);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(assignmentId);
            verify(submissionRepository, never()).save(any(AssignmentSubmission.class));
        }

        @Test
        @DisplayName("Should throw when assignment is not published")
        void shouldThrowWhenNotPublished() {
            testQuizAssignment.setStatus(AssignmentStatus.DRAFT);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));

            assertThatThrownBy(() -> assignmentService.startAssignment(assignmentId, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("không khả dụng");
        }

        @Test
        @DisplayName("Should throw when assignment due date has passed for NEW attempt")
        void shouldThrowWhenPastDue() {
            testQuizAssignment.setStatus(AssignmentStatus.PUBLISHED);
            testQuizAssignment.setDueDate(LocalDateTime.now().minusDays(1));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            given(submissionRepository.findActiveByAssignmentIdAndUserId(assignmentId, learnerId)).willReturn(Optional.empty());

            assertThatThrownBy(() -> assignmentService.startAssignment(assignmentId, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("đã hết hạn nộp");
        }

        @Test
        @DisplayName("Should throw when no retry allowed and previous attempt exists")
        void shouldThrowWhenNoRetry() {
            testQuizAssignment.setStatus(AssignmentStatus.PUBLISHED);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            given(submissionRepository.findActiveByAssignmentIdAndUserId(assignmentId, learnerId)).willReturn(Optional.empty());
            given(submissionRepository.countByAssignmentIdAndUserId(assignmentId, learnerId)).willReturn(1L);

            assertThatThrownBy(() -> assignmentService.startAssignment(assignmentId, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("không cho phép làm lại");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 10. SUBMIT QUIZ (LEARNER)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SubmitQuiz Tests")
    class SubmitQuizTests {
        @Test
        @DisplayName("Should submit quiz successfully")
        void shouldSubmitQuizSuccessfully() throws Exception {
            SubmitAssignmentRequest request = SubmitAssignmentRequest.builder()
                    .answers("[]")
                    .build();

            testSubmission.setStatus(SubmissionStatus.IN_PROGRESS);
            given(submissionRepository.findActiveByAssignmentIdAndUserId(assignmentId, learnerId)).willReturn(Optional.of(testSubmission));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            given(submissionRepository.save(any(AssignmentSubmission.class))).willReturn(testSubmission);
            given(mapper.toResponse(any(AssignmentSubmission.class))).willReturn(testSubmissionResponse);
            
            ObjectMapper realMapper = new ObjectMapper();
            JsonNode arrayNode = realMapper.readTree("[]");
            given(objectMapper.readTree(request.getAnswers())).willReturn(arrayNode);

            SubmissionResponse result = assignmentService.submitQuiz(assignmentId, request, learnerId);

            assertThat(result).isNotNull();
            verify(submissionRepository).save(submissionCaptor.capture());
            assertThat(submissionCaptor.getValue().getStatus()).isEqualTo(SubmissionStatus.GRADED);
        }

        @Test
        @DisplayName("Should throw when no active submission found")
        void shouldThrowWhenNoActiveSubmission() {
            SubmitAssignmentRequest request = SubmitAssignmentRequest.builder().answers("[]").build();
            given(submissionRepository.findActiveByAssignmentIdAndUserId(assignmentId, learnerId)).willReturn(Optional.empty());

            assertThatThrownBy(() -> assignmentService.submitQuiz(assignmentId, request, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Không tìm thấy bài nộp đang làm");
        }

        @Test
        @DisplayName("Should mark as OVERDUE if submitted past due date")
        void shouldMarkOverdueIfPastDue() throws Exception {
            testQuizAssignment.setDueDate(LocalDateTime.now().minusDays(1));
            SubmitAssignmentRequest request = SubmitAssignmentRequest.builder()
                    .answers("[]")
                    .build();

            testSubmission.setStatus(SubmissionStatus.IN_PROGRESS);
            given(submissionRepository.findActiveByAssignmentIdAndUserId(assignmentId, learnerId)).willReturn(Optional.of(testSubmission));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));

            assertThatThrownBy(() -> assignmentService.submitQuiz(assignmentId, request, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("hết hạn nộp");

            verify(submissionService).markSubmissionAsOverdueAndSave(submissionId);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 11. SUBMIT WRITING (LEARNER)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SubmitWriting Tests")
    class SubmitWritingTests {

    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 11.5. CANCEL SUBMISSION (LEARNER)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("CancelSubmission Tests")
    class CancelSubmissionTests {

        @Test
        @DisplayName("Should successfully cancel submission")
        void shouldCancelSubmission() {
            testWritingAssignment.setDueDate(LocalDateTime.now().plusDays(2));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testWritingAssignment));
            
            testSubmission.setStatus(SubmissionStatus.SUBMITTED);
            given(submissionRepository.findByAssignmentIdAndUserIdOrderByAttemptDesc(assignmentId, learnerId))
                    .willReturn(List.of(testSubmission));
            
            given(submissionRepository.save(any(AssignmentSubmission.class))).willReturn(testSubmission);
            given(mapper.toResponse(any(AssignmentSubmission.class))).willReturn(testSubmissionResponse);
            
            SubmissionResponse result = assignmentService.cancelSubmission(assignmentId, learnerId);
            
            assertThat(result).isNotNull();
            verify(submissionRepository).save(submissionCaptor.capture());
            assertThat(submissionCaptor.getValue().getStatus()).isEqualTo(SubmissionStatus.IN_PROGRESS);
            assertThat(submissionCaptor.getValue().getSubmittedAt()).isNull();
        }

        @Test
        @DisplayName("Should throw when assignment is QUIZ")
        void shouldThrowWhenQuiz() {
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            
            assertThatThrownBy(() -> assignmentService.cancelSubmission(assignmentId, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("trắc nghiệm");
        }

        @Test
        @DisplayName("Should throw when past due date")
        void shouldThrowWhenPastDueDate() {
            testWritingAssignment.setDueDate(LocalDateTime.now().minusDays(1));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testWritingAssignment));
            
            assertThatThrownBy(() -> assignmentService.cancelSubmission(assignmentId, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("quá hạn nộp");
        }

        @Test
        @DisplayName("Should throw when no active submission")
        void shouldThrowWhenNoSubmission() {
            testWritingAssignment.setDueDate(LocalDateTime.now().plusDays(2));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testWritingAssignment));
            given(submissionRepository.findByAssignmentIdAndUserIdOrderByAttemptDesc(assignmentId, learnerId))
                    .willReturn(Collections.emptyList());
            
            assertThatThrownBy(() -> assignmentService.cancelSubmission(assignmentId, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Không tìm thấy bài nộp");
        }

        @Test
        @DisplayName("Should throw when submission is GRADED")
        void shouldThrowWhenGraded() {
            testWritingAssignment.setDueDate(LocalDateTime.now().plusDays(2));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testWritingAssignment));
            
            testSubmission.setStatus(SubmissionStatus.GRADED);
            given(submissionRepository.findByAssignmentIdAndUserIdOrderByAttemptDesc(assignmentId, learnerId))
                    .willReturn(List.of(testSubmission));
            
            assertThatThrownBy(() -> assignmentService.cancelSubmission(assignmentId, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("đã được chấm điểm");
        }

        @Test
        @DisplayName("Should throw when submission is IN_PROGRESS")
        void shouldThrowWhenInProgress() {
            testWritingAssignment.setDueDate(LocalDateTime.now().plusDays(2));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testWritingAssignment));
            
            testSubmission.setStatus(SubmissionStatus.IN_PROGRESS);
            given(submissionRepository.findByAssignmentIdAndUserIdOrderByAttemptDesc(assignmentId, learnerId))
                    .willReturn(List.of(testSubmission));
            
            assertThatThrownBy(() -> assignmentService.cancelSubmission(assignmentId, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("đang ở trạng thái nháp");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 12. GET MY SUBMISSION (LEARNER)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GetMySubmission Tests")
    class GetMySubmissionTests {

        @Test
        @DisplayName("Should get own submission successfully")
        void shouldGetOwnSubmission() {
            given(submissionRepository.findById(submissionId)).willReturn(Optional.of(testSubmission));
            given(mapper.toResponse(any(AssignmentSubmission.class))).willReturn(testSubmissionResponse);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            given(userRepository.findById(learnerId)).willReturn(Optional.empty());

            SubmissionResponse result = assignmentService.getMySubmission(submissionId, learnerId);

            assertThat(result).isNotNull();
            assertThat(result.getUserId()).isEqualTo(learnerId);
        }

        @Test
        @DisplayName("Should throw when viewing another learner's submission")
        void shouldThrowWhenNotOwn() {
            UUID otherLearnerId = UUID.randomUUID();
            given(submissionRepository.findById(submissionId)).willReturn(Optional.of(testSubmission));

            assertThatThrownBy(() -> assignmentService.getMySubmission(submissionId, otherLearnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("chỉ có thể xem bài nộp của chính mình");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 13. LEARNER ASSIGNMENT ACCESS
    // ═══════════════════════════════════════════════════════════════════════════


    // ═══════════════════════════════════════════════════════════════════════════
    // 14. CLASS ASSIGNMENT STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ClassAssignmentStatistics Tests")
    class ClassAssignmentStatisticsTests {

        @Test
        @DisplayName("Should return statistics for class")
        void shouldReturnStatistics() {
            stubTeacherAccess();

            List<Object[]> assignmentCounts = new ArrayList<>();
            assignmentCounts.add(new Object[]{"DRAFT", 2L});
            assignmentCounts.add(new Object[]{"PUBLISHED", 3L});
            assignmentCounts.add(new Object[]{"CLOSED", 1L});

            List<Object[]> submissionCounts = new ArrayList<>();
            submissionCounts.add(new Object[]{"SUBMITTED", 5L});
            submissionCounts.add(new Object[]{"GRADED", 10L});
            submissionCounts.add(new Object[]{"IN_PROGRESS", 2L});
            submissionCounts.add(new Object[]{"OVERDUE", 1L});

            given(assignmentRepository.countByStatusForClass(classId)).willReturn(assignmentCounts);
            given(submissionRepository.countSubmissionsByStatusForClass(classId)).willReturn(submissionCounts);
            given(submissionRepository.getAverageScoreForGradedSubmissions(classId)).willReturn(85.5);
            given(assignmentRepository.findPublishedByClassAndDueWithin24h(classId)).willReturn(Collections.emptyList());
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(20L);

            ClassAssignmentStatisticsResponse result = assignmentService.getClassAssignmentStatistics(classId, teacherId);

            assertThat(result.getClassId()).isEqualTo(classId);
            assertThat(result.getTotalAssignments()).isEqualTo(6L);
            assertThat(result.getDraftAssignments()).isEqualTo(2L);
            assertThat(result.getPublishedAssignments()).isEqualTo(3L);
            assertThat(result.getClosedAssignments()).isEqualTo(1L);
            assertThat(result.getTotalSubmissions()).isEqualTo(18L);
            assertThat(result.getUngradedSubmissions()).isEqualTo(5L);
            assertThat(result.getGradedSubmissions()).isEqualTo(10L);
            assertThat(result.getInProgressSubmissions()).isEqualTo(2L);
            assertThat(result.getOverdueSubmissions()).isEqualTo(1L);
            assertThat(result.getAverageGradedScore()).isEqualTo(85.5);
            assertThat(result.getDueSoon24hCount()).isEqualTo(0L);
            assertThat(result.getCompletionRate()).isEqualTo((15.0 / (6L * 20L)) * 100.0);
        }

        @Test
        @DisplayName("Should calculate average graded score correctly")
        void shouldCalculateAverageGradedScore() {
            stubTeacherAccess();

            List<Object[]> assignmentCounts = new ArrayList<>();
            assignmentCounts.add(new Object[]{"PUBLISHED", 1L});
            List<Object[]> submissionCounts = new ArrayList<>();
            submissionCounts.add(new Object[]{"GRADED", 5L});

            given(assignmentRepository.countByStatusForClass(classId)).willReturn(assignmentCounts);
            given(submissionRepository.countSubmissionsByStatusForClass(classId)).willReturn(submissionCounts);
            given(submissionRepository.getAverageScoreForGradedSubmissions(classId)).willReturn(92.0);
            given(assignmentRepository.findPublishedByClassAndDueWithin24h(classId)).willReturn(Collections.emptyList());
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(10L);

            ClassAssignmentStatisticsResponse result = assignmentService.getClassAssignmentStatistics(classId, teacherId);

            assertThat(result.getAverageGradedScore()).isEqualTo(92.0);
            verify(submissionRepository).getAverageScoreForGradedSubmissions(classId);
        }

        @Test
        @DisplayName("Should return 0.0 when no graded submissions exist")
        void shouldReturnZeroWhenNoGradedSubmissions() {
            stubTeacherAccess();

            List<Object[]> assignmentCounts = new ArrayList<>();
            assignmentCounts.add(new Object[]{"PUBLISHED", 2L});
            List<Object[]> submissionCounts = new ArrayList<>();
            submissionCounts.add(new Object[]{"SUBMITTED", 3L});

            given(assignmentRepository.countByStatusForClass(classId)).willReturn(assignmentCounts);
            given(submissionRepository.countSubmissionsByStatusForClass(classId)).willReturn(submissionCounts);
            given(submissionRepository.getAverageScoreForGradedSubmissions(classId)).willReturn(null);
            given(assignmentRepository.findPublishedByClassAndDueWithin24h(classId)).willReturn(Collections.emptyList());
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(15L);

            ClassAssignmentStatisticsResponse result = assignmentService.getClassAssignmentStatistics(classId, teacherId);

            assertThat(result.getAverageGradedScore()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should count assignments due within 24 hours")
        void shouldCountAssignmentsDueWithin24Hours() {
            stubTeacherAccess();

            List<Object[]> assignmentCounts = new ArrayList<>();
            assignmentCounts.add(new Object[]{"PUBLISHED", 5L});
            List<Object[]> submissionCounts = new ArrayList<>();
            submissionCounts.add(new Object[]{"GRADED", 4L});

            Assignment dueSoonAssignment1 = new Assignment();
            dueSoonAssignment1.setId(UUID.randomUUID());
            Assignment dueSoonAssignment2 = new Assignment();
            dueSoonAssignment2.setId(UUID.randomUUID());

            given(assignmentRepository.countByStatusForClass(classId)).willReturn(assignmentCounts);
            given(submissionRepository.countSubmissionsByStatusForClass(classId)).willReturn(submissionCounts);
            given(submissionRepository.getAverageScoreForGradedSubmissions(classId)).willReturn(75.0);
            given(assignmentRepository.findPublishedByClassAndDueWithin24h(classId))
                    .willReturn(List.of(dueSoonAssignment1, dueSoonAssignment2));
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(10L);

            ClassAssignmentStatisticsResponse result = assignmentService.getClassAssignmentStatistics(classId, teacherId);

            assertThat(result.getDueSoon24hCount()).isEqualTo(2L);
            verify(assignmentRepository).findPublishedByClassAndDueWithin24h(classId);
        }

        @Test
        @DisplayName("Should calculate completion rate correctly")
        void shouldCalculateCompletionRateCorrectly() {
            stubTeacherAccess();

            List<Object[]> assignmentCounts = new ArrayList<>();
            assignmentCounts.add(new Object[]{"PUBLISHED", 10L});
            List<Object[]> submissionCounts = new ArrayList<>();
            submissionCounts.add(new Object[]{"SUBMITTED", 30L});
            submissionCounts.add(new Object[]{"GRADED", 50L});

            given(assignmentRepository.countByStatusForClass(classId)).willReturn(assignmentCounts);
            given(submissionRepository.countSubmissionsByStatusForClass(classId)).willReturn(submissionCounts);
            given(submissionRepository.getAverageScoreForGradedSubmissions(classId)).willReturn(80.0);
            given(assignmentRepository.findPublishedByClassAndDueWithin24h(classId)).willReturn(Collections.emptyList());
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(10L);

            ClassAssignmentStatisticsResponse result = assignmentService.getClassAssignmentStatistics(classId, teacherId);

            // completion rate = (30 + 50) / (10 * 10) * 100 = 80 / 100 * 100 = 80.0%
            assertThat(result.getCompletionRate()).isEqualTo(80.0);
        }

        @Test
        @DisplayName("Should return 0.0 completion rate when no assignments exist")
        void shouldReturnZeroCompletionRateWhenNoAssignments() {
            stubTeacherAccess();

            List<Object[]> assignmentCounts = Collections.emptyList();
            List<Object[]> submissionCounts = Collections.emptyList();

            given(assignmentRepository.countByStatusForClass(classId)).willReturn(assignmentCounts);
            given(submissionRepository.countSubmissionsByStatusForClass(classId)).willReturn(submissionCounts);
            given(submissionRepository.getAverageScoreForGradedSubmissions(classId)).willReturn(null);
            given(assignmentRepository.findPublishedByClassAndDueWithin24h(classId)).willReturn(Collections.emptyList());
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(15L);

            ClassAssignmentStatisticsResponse result = assignmentService.getClassAssignmentStatistics(classId, teacherId);

            assertThat(result.getCompletionRate()).isEqualTo(0.0);
            assertThat(result.getTotalAssignments()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should return 0.0 completion rate when no students exist")
        void shouldReturnZeroCompletionRateWhenNoStudents() {
            stubTeacherAccess();

            List<Object[]> assignmentCounts = new ArrayList<>();
            assignmentCounts.add(new Object[]{"PUBLISHED", 5L});
            List<Object[]> submissionCounts = new ArrayList<>();
            submissionCounts.add(new Object[]{"GRADED", 10L});

            given(assignmentRepository.countByStatusForClass(classId)).willReturn(assignmentCounts);
            given(submissionRepository.countSubmissionsByStatusForClass(classId)).willReturn(submissionCounts);
            given(submissionRepository.getAverageScoreForGradedSubmissions(classId)).willReturn(75.0);
            given(assignmentRepository.findPublishedByClassAndDueWithin24h(classId)).willReturn(Collections.emptyList());
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);

            ClassAssignmentStatisticsResponse result = assignmentService.getClassAssignmentStatistics(classId, teacherId);

            assertThat(result.getCompletionRate()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should cap completion rate at 100%")
        void shouldCapCompletionRateAt100Percent() {
            stubTeacherAccess();

            List<Object[]> assignmentCounts = new ArrayList<>();
            assignmentCounts.add(new Object[]{"PUBLISHED", 2L});
            List<Object[]> submissionCounts = new ArrayList<>();
            submissionCounts.add(new Object[]{"SUBMITTED", 50L});
            submissionCounts.add(new Object[]{"GRADED", 100L});

            given(assignmentRepository.countByStatusForClass(classId)).willReturn(assignmentCounts);
            given(submissionRepository.countSubmissionsByStatusForClass(classId)).willReturn(submissionCounts);
            given(submissionRepository.getAverageScoreForGradedSubmissions(classId)).willReturn(85.0);
            given(assignmentRepository.findPublishedByClassAndDueWithin24h(classId)).willReturn(Collections.emptyList());
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(5L);

            ClassAssignmentStatisticsResponse result = assignmentService.getClassAssignmentStatistics(classId, teacherId);

            // completion rate would be (50 + 100) / (2 * 5) * 100 = 1500%, capped at 100%
            assertThat(result.getCompletionRate()).isEqualTo(100.0);
        }
    }
    // ═══════════════════════════════════════════════════════════════════════════
    // 15. GET LEARNER ASSIGNMENTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GetLearnerAssignments Tests")
    class GetLearnerAssignmentsTests {

        @Test
        @DisplayName("Should return assignments from active class when classId is null")
        void shouldReturnAssignmentsFromActiveClassWhenClassIdNull() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<Assignment> page = new PageImpl<>(List.of(testQuizAssignment), pageable, 1);
            
            given(classMemberRepository.findActiveClassIdsByUserId(learnerId)).willReturn(List.of(classId));
            given(classRepository.findAllById(List.of(classId))).willReturn(List.of(testClazz));
            given(assignmentRepository.findLearnerAssignmentsSorted(eq(learnerId), eq(List.of(classId)), 
                    nullable(String.class), nullable(String.class), nullable(String.class), eq(pageable)))
                    .willReturn(page);
            given(mapper.toLearnerSummaryResponse(any(Assignment.class))).willReturn(testLearnerSummaryResponse);
            
            UUID subId = UUID.randomUUID();
            List<Object[]> statuses = Collections.singletonList(new Object[]{assignmentId, "SUBMITTED", subId});
            given(submissionRepository.findLatestStatusByAssignmentIdsAndUserId(anyList(), eq(learnerId)))
                    .willReturn(statuses);

            Page<LearnerAssignmentSummaryResponse> result = assignmentService.getLearnerAssignments(
                    learnerId, null, null, null, null, pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
            LearnerAssignmentSummaryResponse r = result.getContent().get(0);
            assertThat(r.getLearnerSubmissionStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
            assertThat(r.getLearnerSubmissionId()).isEqualTo(subId);
            verify(classRepository).findAllById(List.of(classId));
        }

        @Test
        @DisplayName("Should return empty page when learner has no active class and classId is null")
        void shouldReturnEmptyWhenNoActiveClass() {
            Pageable pageable = PageRequest.of(0, 10);
            
            given(classMemberRepository.findActiveClassIdsByUserId(learnerId)).willReturn(Collections.emptyList());

            Page<LearnerAssignmentSummaryResponse> result = assignmentService.getLearnerAssignments(
                    learnerId, null, null, null, null, pageable);

            assertThat(result).isEmpty();
            assertThat(result.getTotalElements()).isZero();
            verify(assignmentRepository, never()).findLearnerAssignmentsSorted(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should return assignments for specific class when classId is provided")
        void shouldReturnAssignmentsForSpecificClass() {
            Pageable pageable = PageRequest.of(0, 10);
            UUID specificClassId = UUID.randomUUID();
            Page<Assignment> page = new PageImpl<>(List.of(testQuizAssignment), pageable, 1);
            
            given(classMemberRepository.findActiveClassIdsByUserId(learnerId))
                    .willReturn(List.of(classId, specificClassId));
            given(assignmentRepository.findLearnerAssignmentsSorted(eq(learnerId), eq(List.of(specificClassId)), 
                    nullable(String.class), nullable(String.class), nullable(String.class), eq(pageable)))
                    .willReturn(page);
            given(mapper.toLearnerSummaryResponse(any(Assignment.class))).willReturn(testLearnerSummaryResponse);
            given(submissionRepository.findLatestStatusByAssignmentIdsAndUserId(anyList(), eq(learnerId)))
                    .willReturn(Collections.emptyList());

            Page<LearnerAssignmentSummaryResponse> result = assignmentService.getLearnerAssignments(
                    learnerId, specificClassId, null, null, null, pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
            verify(assignmentRepository).findLearnerAssignmentsSorted(
                    eq(learnerId), eq(List.of(specificClassId)), any(), any(), any(), eq(pageable));
        }

        @Test
        @DisplayName("Should return empty when classId provided but learner is not a member")
        void shouldReturnEmptyWhenNotMemberOfClass() {
            Pageable pageable = PageRequest.of(0, 10);
            UUID otherClassId = UUID.randomUUID();
            
            given(classMemberRepository.findActiveClassIdsByUserId(learnerId))
                    .willReturn(List.of(classId));

            Page<LearnerAssignmentSummaryResponse> result = assignmentService.getLearnerAssignments(
                    learnerId, otherClassId, null, null, null, pageable);

            assertThat(result).isEmpty();
            verify(assignmentRepository, never()).findLearnerAssignmentsSorted(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should return NOT_STARTED when no submission exists")
        void shouldReturnNotStartedWhenNoSubmission() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<Assignment> page = new PageImpl<>(List.of(testQuizAssignment), pageable, 1);
            
            given(classMemberRepository.findActiveClassIdsByUserId(learnerId)).willReturn(List.of(classId));
            given(classRepository.findAllById(List.of(classId))).willReturn(List.of(testClazz));
            given(assignmentRepository.findLearnerAssignmentsSorted(eq(learnerId), anyList(), 
                    nullable(String.class), nullable(String.class), nullable(String.class), eq(pageable)))
                    .willReturn(page);
            given(mapper.toLearnerSummaryResponse(any(Assignment.class))).willReturn(testLearnerSummaryResponse);
            given(submissionRepository.findLatestStatusByAssignmentIdsAndUserId(anyList(), eq(learnerId)))
                    .willReturn(Collections.emptyList());

            Page<LearnerAssignmentSummaryResponse> result = assignmentService.getLearnerAssignments(
                    learnerId, null, null, null, null, pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
            LearnerAssignmentSummaryResponse r = result.getContent().get(0);
            assertThat(r.getLearnerSubmissionStatus()).isEqualTo(SubmissionStatus.NOT_STARTED);
            assertThat(r.getLearnerSubmissionId()).isNull();
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // 16. GET ASSIGNMENT STATISTICS FOR CLASS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GetAssignmentStatisticsForClass Tests")
    class GetAssignmentStatisticsForClassTests {

        private UUID assignmentId;

        @BeforeEach
        void setUp() {
            assignmentId = UUID.randomUUID();
        }

        @Test
        @DisplayName("Should return statistics for assignment in class")
        void shouldReturnStatisticsForAssignment() {
            testQuizAssignment.setId(assignmentId);
            testQuizAssignment.setTitle("Test Quiz");
            testQuizAssignment.setClassId(classId);

            given(assignmentRepository.findByIdAndNotDeleted(assignmentId))
                    .willReturn(Optional.of(testQuizAssignment));
            given(classRepository.findByIdAndNotDeleted(classId))
                    .willReturn(Optional.of(testClazz));
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId))
                    .willReturn(Optional.of(teacherId));
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(30L);
            given(submissionRepository.countSubmissionsInClassForAssignment(assignmentId, classId))
                    .willReturn(25L);
            given(submissionRepository.getAverageScoreForAssignmentInClass(assignmentId, classId))
                    .willReturn(82.5);
            given(assignmentRepository.getAssignmentTitleById(assignmentId))
                    .willReturn(Optional.of("Test Quiz"));

            AssignmentClassStatisticsResponse result = assignmentService.getAssignmentStatisticsForClass(
                    assignmentId, classId, teacherId);

            assertThat(result).isNotNull();
            assertThat(result.getAssignmentId()).isEqualTo(assignmentId);
            assertThat(result.getClassId()).isEqualTo(classId);
            assertThat(result.getAssignmentTitle()).isEqualTo("Test Quiz");
            assertThat(result.getTotalStudents()).isEqualTo(30L);
            assertThat(result.getSubmittedCount()).isEqualTo(25L);
            assertThat(result.getNotSubmittedCount()).isEqualTo(5L);
            assertThat(result.getAverageScore()).isEqualTo(82.5);
        }

        @Test
        @DisplayName("Should calculate not submitted count correctly")
        void shouldCalculateNotSubmittedCount() {
            testQuizAssignment.setId(assignmentId);
            testQuizAssignment.setTitle("Test Quiz");
            testQuizAssignment.setClassId(classId);

            given(assignmentRepository.findByIdAndNotDeleted(assignmentId))
                    .willReturn(Optional.of(testQuizAssignment));
            given(classRepository.findByIdAndNotDeleted(classId))
                    .willReturn(Optional.of(testClazz));
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId))
                    .willReturn(Optional.of(teacherId));
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(50L);
            given(submissionRepository.countSubmissionsInClassForAssignment(assignmentId, classId))
                    .willReturn(30L);
            given(submissionRepository.getAverageScoreForAssignmentInClass(assignmentId, classId))
                    .willReturn(75.0);
            given(assignmentRepository.getAssignmentTitleById(assignmentId))
                    .willReturn(Optional.of("Test Quiz"));

            AssignmentClassStatisticsResponse result = assignmentService.getAssignmentStatisticsForClass(
                    assignmentId, classId, teacherId);

            assertThat(result.getNotSubmittedCount()).isEqualTo(20L);
        }

        @Test
        @DisplayName("Should return 0.0 average score when no graded submissions")
        void shouldReturnZeroAverageWhenNoGradedSubmissions() {
            testQuizAssignment.setId(assignmentId);
            testQuizAssignment.setTitle("Test Quiz");
            testQuizAssignment.setClassId(classId);

            given(assignmentRepository.findByIdAndNotDeleted(assignmentId))
                    .willReturn(Optional.of(testQuizAssignment));
            given(classRepository.findByIdAndNotDeleted(classId))
                    .willReturn(Optional.of(testClazz));
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId))
                    .willReturn(Optional.of(teacherId));
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(30L);
            given(submissionRepository.countSubmissionsInClassForAssignment(assignmentId, classId))
                    .willReturn(5L);
            given(submissionRepository.getAverageScoreForAssignmentInClass(assignmentId, classId))
                    .willReturn(null);
            given(assignmentRepository.getAssignmentTitleById(assignmentId))
                    .willReturn(Optional.of("Test Quiz"));

            AssignmentClassStatisticsResponse result = assignmentService.getAssignmentStatisticsForClass(
                    assignmentId, classId, teacherId);

            assertThat(result.getAverageScore()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should throw exception when teacher not verified")
        void shouldThrowExceptionWhenTeacherNotVerified() {
            testQuizAssignment.setId(assignmentId);
            testQuizAssignment.setClassId(classId);

            UUID otherTeacherId = UUID.randomUUID();

            given(assignmentRepository.findByIdAndNotDeleted(assignmentId))
                    .willReturn(Optional.of(testQuizAssignment));
            given(classRepository.findByIdAndNotDeleted(classId))
                    .willReturn(Optional.of(testClazz));
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId))
                    .willReturn(Optional.of(otherTeacherId));

            assertThatThrownBy(() -> assignmentService.getAssignmentStatisticsForClass(
                    assignmentId, classId, teacherId))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("Should throw exception when assignment not in class")
        void shouldThrowExceptionWhenAssignmentNotInClass() {
            UUID otherClassId = UUID.randomUUID();
            testQuizAssignment.setId(assignmentId);
            testQuizAssignment.setClassId(otherClassId);

            given(assignmentRepository.findByIdAndNotDeleted(assignmentId))
                    .willReturn(Optional.of(testQuizAssignment));

            assertThatThrownBy(() -> assignmentService.getAssignmentStatisticsForClass(
                    assignmentId, classId, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("không thuộc về lớp học này");
        }

        @Test
        @DisplayName("Should throw exception when assignment not found")
        void shouldThrowExceptionWhenAssignmentNotFound() {
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> assignmentService.getAssignmentStatisticsForClass(
                    assignmentId, classId, teacherId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("GetSubmissionsByAssignment with Keyword Tests")
    class GetSubmissionsByAssignmentKeywordTests {

        @Test
        @DisplayName("Should return all students (with and without submissions)")
        void shouldReturnAllStudentsIncludingNotStarted() {
            Pageable pageable = PageRequest.of(0, 10);
            UUID student1Id = UUID.randomUUID();
            UUID student2Id = UUID.randomUUID();
            UUID student3Id = UUID.randomUUID();
            
            User user1 = User.builder()
                    .firstName("John").lastName("Doe").displayName("John Doe")
                    .email("john@example.com").build();
            ReflectionTestUtils.setField(user1, "id", student1Id);
            
            User user2 = User.builder()
                    .firstName("Jane").lastName("Smith").displayName("Jane Smith")
                    .email("jane@example.com").build();
            ReflectionTestUtils.setField(user2, "id", student2Id);
            
            User user3 = User.builder()
                    .firstName("Bob").lastName("Johnson").displayName("Charlie Brown")
                    .email("bob@example.com").build();
            ReflectionTestUtils.setField(user3, "id", student3Id);

            AssignmentSubmission submission1 = AssignmentSubmission.builder()
                    .id(UUID.randomUUID())
                    .assignmentId(assignmentId)
                    .userId(student1Id)
                    .status(SubmissionStatus.SUBMITTED)
                    .attemptNumber(1)
                    .build();

            given(assignmentRepository.findByIdAndNotDeleted(assignmentId))
                    .willReturn(Optional.of(testQuizAssignment));
            given(classRepository.findByIdAndNotDeleted(classId))
                    .willReturn(Optional.of(testClazz));
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId))
                    .willReturn(Optional.of(teacherId));
            given(classMemberRepository.findActiveUserIdsByClassId(classId))
                    .willReturn(List.of(student1Id, student2Id, student3Id));
            given(submissionRepository.findByAssignmentId(assignmentId))
                    .willReturn(List.of(submission1));
            given(userRepository.findAllById(List.of(student1Id, student2Id, student3Id)))
                    .willReturn(List.of(user1, user2, user3));
            given(mapper.toSummaryResponse(submission1))
                    .willReturn(SubmissionSummaryResponse.builder()
                            .id(submission1.getId())
                            .userFullName(user1.getDisplayName())
                            .status(SubmissionStatus.SUBMITTED)
                            .build());

            Page<SubmissionSummaryResponse> result = assignmentService.getSubmissionsByAssignment(
                    assignmentId, null, null, pageable, teacherId);

            assertThat(result).isNotNull();
            assertThat(result.getTotalElements()).isEqualTo(3);
            assertThat(result.getContent())
                    .extracting(SubmissionSummaryResponse::getStatus)
                    .contains(SubmissionStatus.SUBMITTED, SubmissionStatus.NOT_STARTED, SubmissionStatus.NOT_STARTED);
        }

        @Test
        @DisplayName("Should filter by status (NOT_STARTED)")
        void shouldFilterByStatusNotStarted() {
            Pageable pageable = PageRequest.of(0, 10);
            UUID student1Id = UUID.randomUUID();
            UUID student2Id = UUID.randomUUID();
            UUID student3Id = UUID.randomUUID();
            
            User user1 = User.builder()
                    .firstName("John").lastName("Doe").displayName("John Doe")
                    .email("john@example.com").build();
            ReflectionTestUtils.setField(user1, "id", student1Id);
            
            User user2 = User.builder()
                    .firstName("Jane").lastName("Smith").displayName("Jane Smith")
                    .email("jane@example.com").build();
            ReflectionTestUtils.setField(user2, "id", student2Id);
            
            User user3 = User.builder()
                    .firstName("Bob").lastName("Johnson").displayName("Charlie Brown")
                    .email("bob@example.com").build();
            ReflectionTestUtils.setField(user3, "id", student3Id);

            AssignmentSubmission submission1 = AssignmentSubmission.builder()
                    .id(UUID.randomUUID())
                    .assignmentId(assignmentId)
                    .userId(student1Id)
                    .status(SubmissionStatus.SUBMITTED)
                    .attemptNumber(1)
                    .build();

            given(assignmentRepository.findByIdAndNotDeleted(assignmentId))
                    .willReturn(Optional.of(testQuizAssignment));
            given(classRepository.findByIdAndNotDeleted(classId))
                    .willReturn(Optional.of(testClazz));
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId))
                    .willReturn(Optional.of(teacherId));
            given(classMemberRepository.findActiveUserIdsByClassId(classId))
                    .willReturn(List.of(student1Id, student2Id, student3Id));
            given(submissionRepository.findByAssignmentId(assignmentId))
                    .willReturn(List.of(submission1));
            given(userRepository.findAllById(List.of(student1Id, student2Id, student3Id)))
                    .willReturn(List.of(user1, user2, user3));
            given(mapper.toSummaryResponse(submission1))
                    .willReturn(SubmissionSummaryResponse.builder()
                            .id(submission1.getId())
                            .userFullName(user1.getDisplayName())
                            .status(SubmissionStatus.SUBMITTED)
                            .build());

            Page<SubmissionSummaryResponse> result = assignmentService.getSubmissionsByAssignment(
                    assignmentId, null, SubmissionStatus.NOT_STARTED, pageable, teacherId);

            assertThat(result).isNotNull();
            assertThat(result.getTotalElements()).isEqualTo(2);
            assertThat(result.getContent())
                    .extracting(SubmissionSummaryResponse::getStatus)
                    .allMatch(status -> status == SubmissionStatus.NOT_STARTED);
        }

        @Test
        @DisplayName("Should filter by keyword (student name)")
        void shouldFilterByKeyword() {
            Pageable pageable = PageRequest.of(0, 10);
            UUID student1Id = UUID.randomUUID();
            UUID student2Id = UUID.randomUUID();
            UUID student3Id = UUID.randomUUID();

            User user1 = User.builder()
                    .firstName("John").lastName("Doe").displayName("John Doe")
                    .email("john@example.com").build();
            ReflectionTestUtils.setField(user1, "id", student1Id);

            User user2 = User.builder()
                    .firstName("Jane").lastName("Smith").displayName("Jane Smith")
                    .email("jane@example.com").build();
            ReflectionTestUtils.setField(user2, "id", student2Id);

            User user3 = User.builder()
                    .firstName("Bob").lastName("Johnson").displayName("Charlie Brown")
                    .email("bob@example.com").build();
            ReflectionTestUtils.setField(user3, "id", student3Id);

            AssignmentSubmission submission1 = AssignmentSubmission.builder()
                    .id(UUID.randomUUID())
                    .assignmentId(assignmentId)
                    .userId(student1Id)
                    .status(SubmissionStatus.SUBMITTED)
                    .attemptNumber(1)
                    .build();

            given(assignmentRepository.findByIdAndNotDeleted(assignmentId))
                    .willReturn(Optional.of(testQuizAssignment));
            given(classRepository.findByIdAndNotDeleted(classId))
                    .willReturn(Optional.of(testClazz));
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId))
                    .willReturn(Optional.of(teacherId));
            given(classMemberRepository.findActiveUserIdsByClassId(classId))
                    .willReturn(List.of(student1Id, student2Id, student3Id));
            given(submissionRepository.findByAssignmentId(assignmentId))
                    .willReturn(List.of(submission1));
            given(userRepository.findAllById(List.of(student1Id, student2Id, student3Id)))
                    .willReturn(List.of(user1, user2, user3));
            given(mapper.toSummaryResponse(submission1))
                    .willReturn(SubmissionSummaryResponse.builder()
                            .id(submission1.getId())
                            .userFullName(user1.getDisplayName())
                            .status(SubmissionStatus.SUBMITTED)
                            .build());

            Page<SubmissionSummaryResponse> result = assignmentService.getSubmissionsByAssignment(
                    assignmentId, "Jane", null, pageable, teacherId);

            assertThat(result).isNotNull();
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().get(0).getUserFullName()).contains("Jane");
        }

        @Test
        @DisplayName("Should filter by keyword AND status")
        void shouldFilterByKeywordAndStatus() {
            Pageable pageable = PageRequest.of(0, 10);
            UUID student1Id = UUID.randomUUID();
            UUID student2Id = UUID.randomUUID();
            UUID student3Id = UUID.randomUUID();

            User user1 = User.builder()
                    .firstName("John").lastName("Doe").displayName("John Doe")
                    .email("john@example.com").build();
            ReflectionTestUtils.setField(user1, "id", student1Id);

            User user2 = User.builder()
                    .firstName("Jane").lastName("Smith").displayName("Jane Smith")
                    .email("jane@example.com").build();
            ReflectionTestUtils.setField(user2, "id", student2Id);

            User user3 = User.builder()
                    .firstName("Bob").lastName("Johnson").displayName("Charlie Brown")
                    .email("bob@example.com").build();
            ReflectionTestUtils.setField(user3, "id", student3Id);

            AssignmentSubmission submission1 = AssignmentSubmission.builder()
                    .id(UUID.randomUUID())
                    .assignmentId(assignmentId)
                    .userId(student1Id)
                    .status(SubmissionStatus.SUBMITTED)
                    .attemptNumber(1)
                    .build();
 
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId))
                    .willReturn(Optional.of(testQuizAssignment));
            given(classRepository.findByIdAndNotDeleted(classId))
                    .willReturn(Optional.of(testClazz));
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId))
                    .willReturn(Optional.of(teacherId));
            given(classMemberRepository.findActiveUserIdsByClassId(classId))
                    .willReturn(List.of(student1Id, student2Id, student3Id));
            given(submissionRepository.findByAssignmentId(assignmentId))
                    .willReturn(List.of(submission1));
            given(userRepository.findAllById(List.of(student1Id, student2Id, student3Id)))
                    .willReturn(List.of(user1, user2, user3));
            given(mapper.toSummaryResponse(submission1))
                    .willReturn(SubmissionSummaryResponse.builder()
                            .id(submission1.getId())
                            .userFullName(user1.getDisplayName())
                            .status(SubmissionStatus.SUBMITTED)
                            .build());

            Page<SubmissionSummaryResponse> result = assignmentService.getSubmissionsByAssignment(
                    assignmentId, "Jane", SubmissionStatus.NOT_STARTED, pageable, teacherId);

            assertThat(result).isNotNull();
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().get(0).getUserFullName()).isEqualTo("Jane Smith");
            assertThat(result.getContent().get(0).getStatus()).isEqualTo(SubmissionStatus.NOT_STARTED);
        }

        @Test
        @DisplayName("Should return empty when keyword matches no students")
        void shouldReturnEmptyWhenKeywordNoMatch() {
            Pageable pageable = PageRequest.of(0, 10);
            UUID student1Id = UUID.randomUUID();
            UUID student2Id = UUID.randomUUID();
            UUID student3Id = UUID.randomUUID();
            
            User user1 = User.builder()
                    .firstName("John").lastName("Doe").displayName("John Doe")
                    .email("john@example.com").build();
            ReflectionTestUtils.setField(user1, "id", student1Id);
            
            User user2 = User.builder()
                    .firstName("Jane").lastName("Smith").displayName("Jane Smith")
                    .email("jane@example.com").build();
            ReflectionTestUtils.setField(user2, "id", student2Id);
            
            User user3 = User.builder()
                    .firstName("Bob").lastName("Johnson").displayName("Charlie Brown")
                    .email("bob@example.com").build();
            ReflectionTestUtils.setField(user3, "id", student3Id);

            given(assignmentRepository.findByIdAndNotDeleted(assignmentId))
                    .willReturn(Optional.of(testQuizAssignment));
            given(classRepository.findByIdAndNotDeleted(classId))
                    .willReturn(Optional.of(testClazz));
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId))
                    .willReturn(Optional.of(teacherId));
            given(classMemberRepository.findActiveUserIdsByClassId(classId))
                    .willReturn(List.of(student1Id, student2Id, student3Id));
            given(submissionRepository.findByAssignmentId(assignmentId))
                    .willReturn(List.of());
            given(userRepository.findAllById(List.of(student1Id, student2Id, student3Id)))
                    .willReturn(List.of(user1, user2, user3));

            Page<SubmissionSummaryResponse> result = assignmentService.getSubmissionsByAssignment(
                    assignmentId, "NonExistent", null, pageable, teacherId);

            assertThat(result).isNotNull();
            assertThat(result.getTotalElements()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should paginate results correctly")
        void shouldPaginateResultsCorrectly() {
            Pageable pageable = PageRequest.of(1, 1); // page 2, size 1
            UUID student1Id = UUID.randomUUID();
            UUID student2Id = UUID.randomUUID();
            UUID student3Id = UUID.randomUUID();
            
            User user1 = User.builder()
                    .firstName("Alice").lastName("Anderson").displayName("Alice Anderson")
                    .email("alice@example.com").build();
            ReflectionTestUtils.setField(user1, "id", student1Id);
            
            User user2 = User.builder()
                    .firstName("Bob").lastName("Brown").displayName("Bob Brown")
                    .email("bob@example.com").build();
            ReflectionTestUtils.setField(user2, "id", student2Id);
            
            User user3 = User.builder()
                    .firstName("Charlie").lastName("Charles").displayName("Charlie Charles")
                    .email("charlie@example.com").build();
            ReflectionTestUtils.setField(user3, "id", student3Id);

            given(assignmentRepository.findByIdAndNotDeleted(assignmentId))
                    .willReturn(Optional.of(testQuizAssignment));
            given(classRepository.findByIdAndNotDeleted(classId))
                    .willReturn(Optional.of(testClazz));
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId))
                    .willReturn(Optional.of(teacherId));
            given(classMemberRepository.findActiveUserIdsByClassId(classId))
                    .willReturn(List.of(student1Id, student2Id, student3Id));
            given(submissionRepository.findByAssignmentId(assignmentId))
                    .willReturn(List.of());
            given(userRepository.findAllById(List.of(student1Id, student2Id, student3Id)))
                    .willReturn(List.of(user1, user2, user3));

            Page<SubmissionSummaryResponse> result = assignmentService.getSubmissionsByAssignment(
                    assignmentId, null, null, pageable, teacherId);

            assertThat(result).isNotNull();
            assertThat(result.getNumber()).isEqualTo(1);
            assertThat(result.getSize()).isEqualTo(1);
            assertThat(result.getTotalElements()).isEqualTo(3);
            assertThat(result.getTotalPages()).isEqualTo(3);
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("Should use latest attempt when student has multiple submissions")
        void shouldUseLatestAttempt() {
            Pageable pageable = PageRequest.of(0, 10);
            UUID student1Id = UUID.randomUUID();
            
            User user1 = User.builder()
                    .firstName("John").lastName("Doe").displayName("John Doe")
                    .email("john@example.com").build();
            ReflectionTestUtils.setField(user1, "id", student1Id);

            AssignmentSubmission submission1 = AssignmentSubmission.builder()
                    .id(UUID.randomUUID())
                    .assignmentId(assignmentId)
                    .userId(student1Id)
                    .status(SubmissionStatus.SUBMITTED)
                    .attemptNumber(1)
                    .build();
            
            AssignmentSubmission submission2 = AssignmentSubmission.builder()
                    .id(UUID.randomUUID())
                    .assignmentId(assignmentId)
                    .userId(student1Id)
                    .status(SubmissionStatus.GRADED)
                    .attemptNumber(2)
                    .score(BigDecimal.valueOf(85.5))
                    .build();

            given(assignmentRepository.findByIdAndNotDeleted(assignmentId))
                    .willReturn(Optional.of(testQuizAssignment));
            given(classRepository.findByIdAndNotDeleted(classId))
                    .willReturn(Optional.of(testClazz));
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId))
                    .willReturn(Optional.of(teacherId));
            given(classMemberRepository.findActiveUserIdsByClassId(classId))
                    .willReturn(List.of(student1Id));
            given(submissionRepository.findByAssignmentId(assignmentId))
                    .willReturn(List.of(submission1, submission2));
            given(userRepository.findAllById(List.of(student1Id)))
                    .willReturn(List.of(user1));
            given(mapper.toSummaryResponse(submission2))
                    .willReturn(SubmissionSummaryResponse.builder()
                            .id(submission2.getId())
                            .userFullName(user1.getDisplayName())
                            .status(SubmissionStatus.GRADED)
                            .score(BigDecimal.valueOf(85.5))
                            .build());

            Page<SubmissionSummaryResponse> result = assignmentService.getSubmissionsByAssignment(
                    assignmentId, null, null, pageable, teacherId);

            assertThat(result).isNotNull();
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().get(0).getStatus()).isEqualTo(SubmissionStatus.GRADED);
            assertThat(result.getContent().get(0).getScore()).isEqualTo(BigDecimal.valueOf(85.5));
        }
    }

}

