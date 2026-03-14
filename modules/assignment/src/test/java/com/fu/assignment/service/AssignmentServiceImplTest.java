package com.fu.assignment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.assignment.ai.QuizAiGenerationService;
import com.fu.assignment.ai.QuizExplanationService;
import com.fu.assignment.dto.request.*;
import com.fu.assignment.dto.response.*;
import com.fu.assignment.entity.*;
import com.fu.assignment.exception.AssignmentNotFoundException;
import com.fu.assignment.mapper.AssignmentMapper;
import com.fu.assignment.repository.*;
import com.fu.assignment.service.impl.AssignmentServiceImpl;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.repository.ClassMemberRepository;
import com.fu.clazz.repository.ClassRepository;
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
    private SubmissionResponse testSubmissionResponse;

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
                .teacherId(teacherId)
                .build();
        ReflectionTestUtils.setField(testClazz, "id", classId);

        testQuizAssignment = Assignment.builder()
                .title("Quiz Bài 1")
                .description("Quiz cho bài 1")
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
                .timeSpentSeconds(0)
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

        testSubmissionResponse = SubmissionResponse.builder()
                .id(submissionId)
                .assignmentId(assignmentId)
                .userId(learnerId)
                .status(SubmissionStatus.IN_PROGRESS)
                .build();
    }

    // ── Helper to stub teacher-access verification ────────────────────────────
    private void stubTeacherAccess() {
        given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
    }

    private void stubEnrichResponse(Assignment a) {
        given(mapper.toResponse(a)).willReturn(testAssignmentResponse);
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
            given(writingAssignmentRepository.findByAssignmentId(any())).willReturn(Optional.empty());

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
        @DisplayName("Should throw when assignment is not DRAFT")
        void shouldThrowWhenNotDraft() {
            testQuizAssignment.setStatus(AssignmentStatus.PUBLISHED);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            stubTeacherAccess();

            assertThatThrownBy(() -> assignmentService.deleteAssignment(assignmentId, teacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("DRAFT");

            verify(assignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw when teacher does not own the assignment")
        void shouldThrowWhenAccessDenied() {
            UUID otherTeacherId = UUID.randomUUID();
            Clazz otherClazz = Clazz.builder().name("Other").teacherId(otherTeacherId).build();
            ReflectionTestUtils.setField(otherClazz, "id", classId);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(otherClazz));

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
            Clazz targetClazz = Clazz.builder().name("Target Class").teacherId(teacherId).build();
            ReflectionTestUtils.setField(targetClazz, "id", targetClassId);

            CloneAssignmentRequest request = CloneAssignmentRequest.builder()
                    .targetClassId(targetClassId)
                    .newDueDate(LocalDateTime.now().plusDays(14))
                    .newTitle("Cloned Quiz")
                    .build();

            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            stubTeacherAccess();
            given(classRepository.findByIdAndNotDeleted(targetClassId)).willReturn(Optional.of(targetClazz));

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
            given(mapper.toResponse(clonedAssignment)).willReturn(
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
            assertThat(graded.getTeacherScore()).isEqualByComparingTo(BigDecimal.valueOf(8.5));
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
        @DisplayName("Should start assignment successfully")
        void shouldStartAssignment() {
            testQuizAssignment.setStatus(AssignmentStatus.PUBLISHED);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            given(submissionRepository.hasActiveSubmission(assignmentId, learnerId)).willReturn(false);
            given(submissionRepository.countByAssignmentIdAndUserId(assignmentId, learnerId)).willReturn(0L);
            given(assignmentQuestionRepository.countByAssignmentId(assignmentId)).willReturn(5L);
            given(submissionRepository.save(any(AssignmentSubmission.class))).willReturn(testSubmission);
            given(assignmentRepository.save(any(Assignment.class))).willReturn(testQuizAssignment);
            given(mapper.toResponse(any(AssignmentSubmission.class))).willReturn(testSubmissionResponse);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            given(userRepository.findById(learnerId)).willReturn(Optional.empty());

            SubmissionResponse result = assignmentService.startAssignment(assignmentId, learnerId);

            assertThat(result).isNotNull();
            verify(submissionRepository).save(any(AssignmentSubmission.class));
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
        @DisplayName("Should throw when assignment due date has passed")
        void shouldThrowWhenPastDue() {
            testQuizAssignment.setStatus(AssignmentStatus.PUBLISHED);
            testQuizAssignment.setDueDate(LocalDateTime.now().minusDays(1));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));

            assertThatThrownBy(() -> assignmentService.startAssignment(assignmentId, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("đã hết hạn nộp");
        }

        @Test
        @DisplayName("Should throw when learner already has active submission")
        void shouldThrowWhenAlreadyInProgress() {
            testQuizAssignment.setStatus(AssignmentStatus.PUBLISHED);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            given(submissionRepository.hasActiveSubmission(assignmentId, learnerId)).willReturn(true);

            assertThatThrownBy(() -> assignmentService.startAssignment(assignmentId, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("đang có một lần làm bài chưa hoàn thành");
        }

        @Test
        @DisplayName("Should throw when no retry allowed and previous attempt exists")
        void shouldThrowWhenNoRetry() {
            testQuizAssignment.setStatus(AssignmentStatus.PUBLISHED);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            given(submissionRepository.hasActiveSubmission(assignmentId, learnerId)).willReturn(false);
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
        @DisplayName("Should throw when submission does not belong to learner")
        void shouldThrowWhenAccessDenied() {
            UUID otherLearnerId = UUID.randomUUID();
            given(submissionRepository.findById(submissionId)).willReturn(Optional.of(testSubmission));

            SubmitAssignmentRequest request = SubmitAssignmentRequest.builder()
                    .submissionId(submissionId).answers("[]").build();

            assertThatThrownBy(() -> assignmentService.submitQuiz(request, otherLearnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Không thể nộp bài thay cho học viên khác");
        }

        @Test
        @DisplayName("Should throw when submission is not in progress")
        void shouldThrowWhenNotInProgress() {
            testSubmission.setStatus(SubmissionStatus.GRADED);
            given(submissionRepository.findById(submissionId)).willReturn(Optional.of(testSubmission));

            SubmitAssignmentRequest request = SubmitAssignmentRequest.builder()
                    .submissionId(submissionId).answers("[]").build();

            assertThatThrownBy(() -> assignmentService.submitQuiz(request, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("không ở trạng thái đang làm");
        }

        @Test
        @DisplayName("Should throw when assignment is not quiz type")
        void shouldThrowWhenNotQuizType() {
            given(submissionRepository.findById(submissionId)).willReturn(Optional.of(testSubmission));
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testWritingAssignment));

            SubmitAssignmentRequest request = SubmitAssignmentRequest.builder()
                    .submissionId(submissionId).answers("[]").build();

            assertThatThrownBy(() -> assignmentService.submitQuiz(request, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("submitWriting");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 11. SUBMIT WRITING (LEARNER)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SubmitWriting Tests")
    class SubmitWritingTests {

        @Test
        @DisplayName("Should throw when no content provided")
        void shouldThrowWhenNoContent() {
            SubmitWritingRequest request = SubmitWritingRequest.builder()
                    .writtenAnswer("").build();

            assertThatThrownBy(() -> assignmentService.submitWriting(assignmentId, request, null, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("bài viết hoặc ít nhất một hình ảnh");
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
            given(mapper.toResponse(testSubmission)).willReturn(testSubmissionResponse);
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

    @Nested
    @DisplayName("GetAssignmentForLearner Tests")
    class GetAssignmentForLearnerTests {

        @Test
        @DisplayName("Should throw when assignment is not published")
        void shouldThrowWhenNotPublished() {
            testQuizAssignment.setStatus(AssignmentStatus.DRAFT);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));

            assertThatThrownBy(() -> assignmentService.getAssignmentForLearner(assignmentId, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("không khả dụng");
        }

        @Test
        @DisplayName("Should throw when learner is not class member")
        void shouldThrowWhenNotClassMember() {
            testQuizAssignment.setStatus(AssignmentStatus.PUBLISHED);
            given(assignmentRepository.findByIdAndNotDeleted(assignmentId)).willReturn(Optional.of(testQuizAssignment));
            given(classMemberRepository.existsActiveByClassIdAndUserId(classId, learnerId)).willReturn(false);

            assertThatThrownBy(() -> assignmentService.getAssignmentForLearner(assignmentId, learnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("không phải là thành viên");
        }
    }

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

            List<Object[]> assignmentCounts = List.of(
                    new Object[]{"DRAFT", 2L},
                    new Object[]{"PUBLISHED", 3L},
                    new Object[]{"CLOSED", 1L}
            );
            List<Object[]> submissionCounts = List.of(
                    new Object[]{"SUBMITTED", 5L},
                    new Object[]{"GRADED", 10L},
                    new Object[]{"IN_PROGRESS", 2L},
                    new Object[]{"OVERDUE", 1L}
            );

            given(assignmentRepository.countByStatusForClass(classId)).willReturn(assignmentCounts);
            given(submissionRepository.countSubmissionsByStatusForClass(classId)).willReturn(submissionCounts);

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
        }
    }
}
