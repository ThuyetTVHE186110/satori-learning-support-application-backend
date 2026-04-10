package com.fu.assignment.service;

import com.fu.assignment.dto.request.*;
import com.fu.assignment.dto.response.*;
import com.fu.assignment.entity.AssignmentType;
import com.fu.assignment.entity.SubmissionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Assignment service interface.
 * Only TEACHER manages assignments. LEARNER submits.
 */
public interface AssignmentService {

    // ── AI Question Generation (TEACHER) ─────────────────────────────────────

    /**
     * AI-generate quiz questions from a teacher prompt.
     * Returns saved questions (status=DRAFT) for teacher to review.
     */
    List<QuizQuestionResponse> aiGenerateQuestions(AiQuizGenerationRequest request, UUID teacherId);

    /**
     * Ask AI to generate explanation for a question's correct answer.
     */
    QuizQuestionResponse aiGenerateExplanation(UUID questionId, UUID teacherId);

    /**
     * Ask AI to generate explanation directly from draft data on frontend form.
     */
    AiExplanationResponse aiGenerateExplanationFromDraft(AiExplanationRequest request);

    // ── Assignment CRUD (TEACHER) ─────────────────────────────────────────────

    /**
     * Unified UPSERT method for creating or updating a full Quiz Assignment.
     * Inserts/Updates the Assignment, its Questions, and the Mapping all at once.
     * @param assignmentId Null if creating new, UUID if updating existing.
     * @param audioFile    optional audio file upload (for listening comprehension quizzes)
     */
    AssignmentResponse saveFullQuiz(UUID assignmentId, UUID classId, SaveFullQuizRequest request, MultipartFile audioFile, UUID teacherId);

    /**
     * Unified UPSERT method for creating or updating a full Writing/Translation Assignment.
     * Inserts/Updates the Assignment and its Writing Content all at once.
     * @param assignmentId Null if creating new, UUID if updating existing.
     * @param assignmentType WRITING or TRANSLATION
     */
    AssignmentResponse saveFullWriting(UUID assignmentId, UUID classId, SaveFullWritingRequest request,
                                       AssignmentType assignmentType, UUID teacherId);

    void deleteAssignment(UUID assignmentId, UUID teacherId);

    AssignmentResponse getAssignment(UUID assignmentId, UUID teacherId);

    Page<AssignmentSummaryResponse> getAssignmentsByClass(UUID classId, String title, com.fu.assignment.entity.AssignmentStatus status, AssignmentType type, Pageable pageable, UUID teacherId);

    ClassAssignmentStatisticsResponse getClassAssignmentStatistics(UUID classId, UUID teacherId);

    /**
     * Get statistics for a specific assignment in a class.
     * Returns submitted count, not submitted count, and average graded score.
     */
    AssignmentClassStatisticsResponse getAssignmentStatisticsForClass(
            UUID assignmentId,
            UUID classId,
            UUID teacherId);

    Page<AssignmentSummaryResponse> getMyAssignments(UUID teacherId, String title, com.fu.assignment.entity.AssignmentStatus status, AssignmentType type, Pageable pageable);



    // ── Lifecycle (TEACHER) ───────────────────────────────────────────────────


    /**
     * Clone/duplicate an existing assignment to another class.
     * Deep-copies all content (questions for QUIZ, writing content for WRITING/TRANSLATION).
     * The cloned assignment is always DRAFT with no submissions.
     */
    AssignmentResponse cloneAssignment(UUID sourceAssignmentId, CloneAssignmentRequest request, UUID teacherId);

    // ── Submissions — Teacher View ────────────────────────────────────────────

    Page<SubmissionSummaryResponse> getSubmissionsByAssignment(UUID assignmentId, Pageable pageable, UUID teacherId);

    /**
     * Get submissions for an assignment with optional keyword search by learner name/email and status filter.
     * Returns ALL students in the class, even those without submissions (status = NOT_STARTED).
     *
     * @param assignmentId Assignment UUID
     * @param keyword Optional search term (firstName, lastName, displayName, email)
     * @param status Optional submission status filter (NOT_STARTED, IN_PROGRESS, SUBMITTED, GRADED, OVERDUE)
     * @param pageable Pagination parameters
     * @param teacherId Teacher UUID for access verification
     * @return Paginated list of submission summaries (all class students + their submission status)
     */
    Page<SubmissionSummaryResponse> getSubmissionsByAssignment(
            UUID assignmentId,
            String keyword,
            SubmissionStatus status,
            Pageable pageable,
            UUID teacherId);

    SubmissionResponse getSubmission(UUID submissionId, UUID teacherId);

    /** Grade a WRITING or TRANSLATION submission */
    SubmissionResponse gradeWritingSubmission(UUID submissionId, GradeWritingRequest request, UUID teacherId);

    // ── Submissions — Learner Flow ────────────────────────────────────────────

    /**
     * Get learner's available assignments with optional class filter.
     * @param learnerId Current learner ID
     * @param classId Optional class filter. If null, shows assignments from THE single active enrolled class.
     *                If provided, shows assignments from that specific class.
     * @param title Optional title search filter
     * @param type Optional assignment type filter
     * @param status Optional submission status filter
     * @param pageable Pagination parameters
     */
    Page<LearnerAssignmentSummaryResponse> getLearnerAssignments(
            UUID learnerId, 
            UUID classId, 
            String title, 
            AssignmentType type, 
            SubmissionStatus status, 
            Pageable pageable);


    /** Start or Resume assignment → returns full assignment details + current session */
    LearnerAssignmentResponse startAssignment(UUID assignmentId, UUID learnerId);

    /** Submit Quiz (auto-scored) using assignmentId to resolve session */
    SubmissionResponse submitQuiz(UUID assignmentId, SubmitAssignmentRequest request, UUID learnerId);

    /** Submit Writing or Translation (pending teacher grading). Supports optional image uploads. */
    SubmissionResponse submitWriting(UUID assignmentId, MultipartFile[] images, UUID learnerId);

    Page<SubmissionSummaryResponse> getMySubmissions(UUID learnerId, Pageable pageable);

    SubmissionResponse getMySubmission(UUID submissionId, UUID learnerId);

    /** Cancel a SUBMITTED writing/translation assignment, returning it to IN_PROGRESS */
    SubmissionResponse cancelSubmission(UUID assignmentId, UUID learnerId);

    /** Evaluate a writing submission's images and prompt before official submission */
    WritingEvaluationResponse evaluateWriting(WritingEvaluationRequest request, MultipartFile[] images);
}
