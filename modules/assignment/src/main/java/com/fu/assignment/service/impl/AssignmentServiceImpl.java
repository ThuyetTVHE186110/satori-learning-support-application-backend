package com.fu.assignment.service.impl;

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
import com.fu.assignment.service.AssignmentScheduleManager;
import com.fu.assignment.service.AssignmentService;
import com.fu.assignment.service.SubmissionService;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.repository.ClassMemberRepository;
import com.fu.clazz.repository.ClassRepository;
import com.fu.assignment.util.MarkdownUtils;
import com.fu.clazz.repository.ClassTeacherHistoryRepository;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.infrastructure.cloudinary.CloudinaryService;
import com.fu.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AssignmentServiceImpl implements AssignmentService {

    private final AssignmentRepository assignmentRepository;
    private final QuizQuestionRepository questionRepository;
    private final AssignmentQuestionRepository assignmentQuestionRepository;
    private final AssignmentTargetRepository targetRepository;
    private final AssignmentSubmissionRepository submissionRepository;
    private final WritingAssignmentRepository writingAssignmentRepository;
    private final AssignmentMapper mapper;
    private final UserRepository userRepository;
    private final ClassRepository classRepository;
    private final ClassMemberRepository classMemberRepository;
    private final QuizAiGenerationService quizAiGenerationService;
    private final QuizExplanationService quizExplanationService;
    private final ObjectMapper objectMapper;
    private final CloudinaryService cloudinaryService;
    private final SubmissionService submissionService;
    private final AiGradingService aiGradingService;
    private final AssignmentScheduleManager scheduleManager;
    private final ClassTeacherHistoryRepository classTeacherHistoryRepository;
    private static final String SUBMISSION_IMAGE_FOLDER = "satori/assignment-submissions";
    private static final String SUBMISSION_FAILED_MSG = "Không thể lưu hoặc chấm điểm bài nộp.";
    private static final int MAX_SUBMISSION_IMAGES = 10;

    // ═══════════════════════════════════════════════════════════════════════════
    // AI QUESTION GENERATION
    // ═══════════════════════════════════════════════════════════════════════════


    @Override
    public List<QuizQuestionResponse> aiGenerateQuestions(AiQuizGenerationRequest request, UUID teacherId) {
        // Preview only — questions are NOT saved to DB.
        // Teacher reviews on UI, then confirms via saveFullQuiz.
        return quizAiGenerationService.generateQuestions(request);
    }

    @Override
    @Transactional
    public QuizQuestionResponse aiGenerateExplanation(UUID questionId, UUID teacherId) {
        QuizQuestion updated = quizExplanationService.generateExplanation(questionId, teacherId);
        return mapper.toResponse(updated);
    }

    @Override
    public AiExplanationResponse aiGenerateExplanationFromDraft(AiExplanationRequest request) {
        return quizExplanationService.generateExplanationFromDraft(request);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ASSIGNMENT CRUD (TEACHER)
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public AssignmentResponse saveFullQuiz(UUID assignmentId, UUID classId, SaveFullQuizRequest request, MultipartFile audioFile, UUID teacherId) {
        validateDueDate(request.getDueDate());

        Assignment assignment;
        boolean isNewAssignment = (assignmentId == null);
        boolean hasSubmissions = false;

        if (assignmentId == null) {
            if (classId != null) {
                Clazz clazz = classRepository.findByIdAndNotDeleted(classId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lớp học"));
                verifyTeacherAccess(clazz, teacherId);
            }
            assignment = Assignment.builder().assignmentType(AssignmentType.QUIZ).classId(classId).createdBy(teacherId).createdAt(LocalDateTime.now()).hasSubmissions(false).build();
        } else {
            // -- UPDATE EXISTING ASSIGNMENT --
            assignment = findAssignmentOrThrow(assignmentId);
            verifyAssignmentAccess(assignment, teacherId);

            if (assignment.getAssignmentType() != AssignmentType.QUIZ) {
                throw new BusinessException("INVALID_TYPE", "Hàm saveFullQuiz chỉ dành cho bài tập dạng QUIZ.");
            }

            hasSubmissions = Boolean.TRUE.equals(assignment.getHasSubmissions());
        }

        // Handle audio upload — blocked when submissions exist
        boolean isAudioChanged = (audioFile != null && !audioFile.isEmpty()) || (request.getAudioUrl() != null && !request.getAudioUrl().equals(assignment.getAudioUrl()));
        if (isAudioChanged && hasSubmissions) {
            log.warn("Assignment {} update blocked: Audio content changed but assignment has submissions.", assignmentId);
            throw new BusinessException("HAS_SUBMISSIONS", "Bài tập đã có học viên nộp bài. Không thể cập nhật nội dung bài tập đã giao." + "Chỉ được cập nhật thông tin cơ bản (tiêu đề, mô tả, hạn nộp...).");
        }
        // If submissions exist, throw ONLY if questions ACTUALLY changed
        if (hasSubmissions && hasQuizQuestionsChanged(request.getQuestions(), assignmentId)) {
            log.warn("Assignment {} update blocked: Quiz questions changed but assignment has submissions.", assignmentId);
            throw new BusinessException("HAS_SUBMISSIONS", "Bài tập đã có học viên nộp bài. Không thể cập nhật nội dung đề bài (câu hỏi). " + "Chỉ được cập nhật thông tin cơ bản (tiêu đề, mô tả, hạn nộp...).");
        }
        if (!hasSubmissions) {
            if (audioFile != null && !audioFile.isEmpty()) {
                // Delete old audio from Cloudinary before uploading the new one
                if (assignment.getAudioUrl() != null) {
                    String oldPublicId = cloudinaryService.extractPublicId(assignment.getAudioUrl());
                    if (oldPublicId != null) {
                        cloudinaryService.deleteAudio(oldPublicId);
                    }
                }
                String audioUrl = cloudinaryService.uploadAudio(audioFile, "satori/assignment-audio");
                assignment.setAudioUrl(audioUrl);
            } else if (request.getAudioUrl() != null) {
                assignment.setAudioUrl(request.getAudioUrl());
            }
        }

        // Apply common metadata — includes all hasSubmissions validation rules
        applyCommonMetadata(assignment, request, hasSubmissions, teacherId);
        // Status — validate transition before applying
        if (request.getStatus() != null && !isNewAssignment) {
            validateStatusTransition(assignment, request.getStatus(), request.getQuestions() != null ? request.getQuestions().size() : assignment.getQuestionCount());
            assignment.setStatus(request.getStatus());
        } else if (isNewAssignment && request.getStatus() != null) {
            assignment.setStatus(request.getStatus());
        } else {
            assignment.setStatus(AssignmentStatus.DRAFT);
        }

        // Quiz settings — only update when no submissions
        if (!hasSubmissions) {
            int qCount = request.getQuestions() != null ? request.getQuestions().size() : 0;
            assignment.setQuestionCount(qCount);
        }


        // Save assignment
        Assignment savedAssignment = assignmentRepository.save(assignment);
        UUID finalAssignmentId = savedAssignment.getId();

        scheduleManager.scheduleClosing(savedAssignment);

        // If new assignment, bind it to the class target
        if (isNewAssignment && classId != null) {
            AssignmentTarget target = AssignmentTarget.builder().assignmentId(finalAssignmentId).classId(classId).assignedBy(teacherId).assignedAt(LocalDateTime.now()).build();
            targetRepository.save(target);
        }

        // -- SYNC QUESTIONS (only when no submissions) --
        if (!hasSubmissions) {
            List<AssignmentQuestion> existingLinks = assignmentQuestionRepository.findByAssignmentIdOrderByOrderIndex(finalAssignmentId);
            List<UUID> incomingQuestionIds = new ArrayList<>();

            if (request.getQuestions() != null) {
                for (int i = 0; i < request.getQuestions().size(); i++) {
                    QuizQuestionDraftDto qDto = request.getQuestions().get(i);

                    QuizQuestion q = (qDto.getId() != null) ? questionRepository.findById(qDto.getId()).orElse(new QuizQuestion()) : new QuizQuestion();

                    q.setQuestionText(MarkdownUtils.sanitizeAndValidate(qDto.getQuestionText()));
                    q.setQuestionType(qDto.getQuestionType() != null ? qDto.getQuestionType() : "multiple_choice");
                    q.setOptions(sanitizeOptionsJson(qDto.getOptions()));
                    q.setCorrectAnswer(MarkdownUtils.sanitizeAndValidate(qDto.getCorrectAnswer()));
                    q.setCorrectAnswers(qDto.getCorrectAnswers());
                    q.setExplanation(MarkdownUtils.sanitizePlainExplanation(qDto.getExplanation()));
                    q.setJlptLevel(qDto.getJlptLevel());

                    if (q.getId() == null) {
                        q.setCreatedBy(teacherId);
                        q.setCreatedAt(LocalDateTime.now());
                    }
                    q.setUpdatedAt(LocalDateTime.now());

                    QuizQuestion savedQ = questionRepository.save(q);
                    incomingQuestionIds.add(savedQ.getId());

                    AssignmentQuestion link = assignmentQuestionRepository.findByAssignmentIdAndQuestionId(finalAssignmentId, savedQ.getId()).orElse(AssignmentQuestion.builder().assignmentId(finalAssignmentId).questionId(savedQ.getId()).build());

                    link.setOrderIndex(qDto.getOrderIndex() != null ? qDto.getOrderIndex() : i);
                    assignmentQuestionRepository.save(link);
                }
            }

            // Remove orphan links (questions removed from the form)
            for (AssignmentQuestion oldLink : existingLinks) {
                if (!incomingQuestionIds.contains(oldLink.getQuestionId())) {

                    assignmentQuestionRepository.delete(oldLink);
                }
            }
        }

        log.info("UPSERT Full Quiz completed for Assignment {} by Teacher {}", finalAssignmentId, teacherId);
        return enrichAssignmentResponse(savedAssignment, teacherId);
    }

    @Override
    @Transactional
    public AssignmentResponse saveFullWriting(UUID assignmentId, UUID classId, SaveFullWritingRequest request, AssignmentType assignmentType, UUID teacherId) {
        if (assignmentType == AssignmentType.QUIZ) {
            throw new BusinessException("INVALID_TYPE", "Vui lòng sử dụng saveFullQuiz cho bài tập dạng QUIZ.");
        }

        Assignment assignment;
        boolean isNewAssignment = (assignmentId == null);
        boolean hasSubmissions = false;

        if (assignmentId == null) {
            // -- CREATE NEW --
            if (classId != null) {
                Clazz clazz = classRepository.findByIdAndNotDeleted(classId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lớp học"));
                verifyTeacherAccess(clazz, teacherId);
            }


            assignment = Assignment.builder().assignmentType(assignmentType).classId(classId).createdBy(teacherId).createdAt(LocalDateTime.now()).hasSubmissions(false).build();
        } else {
            // -- UPDATE EXISTING --
            assignment = findAssignmentOrThrow(assignmentId);
            verifyAssignmentAccess(assignment, teacherId);

            if (assignment.isQuiz()) {
                throw new BusinessException("INVALID_TYPE", "Vui lòng sử dụng saveFullQuiz cho bài tập dạng QUIZ.");
            }

            hasSubmissions = Boolean.TRUE.equals(assignment.getHasSubmissions());

            // If there are submissions AND writing content ACTUALLY changed → reject
            if (hasSubmissions && hasWritingContentChanged(request, assignment)) {
                log.warn("Assignment {} update blocked: Writing content changed but assignment has submissions.", assignmentId);
                throw new BusinessException("HAS_SUBMISSIONS", "Bài tập đã có học viên nộp bài. Không thể sửa nội dung đề bài. " + "Chỉ được cập nhật thông tin cơ bản (tiêu đề, mô tả, hạn nộp...).");
            }
        }

        // Apply common metadata — includes all hasSubmissions validation rules
        applyCommonMetadata(assignment, request, hasSubmissions, teacherId);
        // Validate status transition for writing assignments
        if (request.getStatus() != null && !isNewAssignment) {
            validateStatusTransition(assignment, request.getStatus(), null);
            assignment.setStatus(request.getStatus());
        } else if (isNewAssignment && request.getStatus() != null) {
            assignment.setStatus(request.getStatus());
        } else {
            assignment.setStatus(AssignmentStatus.DRAFT);
        }

        // Retry settings — only update when no submissions
        if (!hasSubmissions) {
            // Deprecated fields removed.
        }

        Assignment savedAssignment = assignmentRepository.save(assignment);
        UUID savedId = savedAssignment.getId();

        scheduleManager.scheduleClosing(savedAssignment);

        // If new assignment, bind it to the class target
        if (isNewAssignment && classId != null) {
            AssignmentTarget target = AssignmentTarget.builder().assignmentId(savedId).classId(classId).build();
            targetRepository.save(target);
        }

        // Save or update writing content — only when no submissions
        if (!hasSubmissions) {
            WritingAssignment content = writingAssignmentRepository.findByAssignmentId(savedId).orElse(WritingAssignment.builder().assignmentId(savedId).createdAt(LocalDateTime.now()).build());
            content.setPrompt(request.getPrompt());
            writingAssignmentRepository.save(content);
        }

        log.info("UPSERT Full Writing ({}) completed for Assignment {} by Teacher {}", assignmentType, savedId, teacherId);
        return enrichAssignmentResponse(savedAssignment, teacherId);
    }


    @Override
    @Transactional
    public void deleteAssignment(UUID assignmentId, UUID teacherId) {
        Assignment assignment = findAssignmentOrThrow(assignmentId);
        verifyAssignmentAccess(assignment, teacherId);
        requireDraftOrNoSubmission(assignment);
        assignment.softDelete(teacherId);
        assignmentRepository.save(assignment);
        scheduleManager.cancelScheduledClosing(assignmentId);
        log.info("Assignment {} soft-deleted by teacher {}", assignmentId, teacherId);
    }

    @Override
    public AssignmentResponse getAssignment(UUID assignmentId, UUID teacherId) {
        Assignment assignment = findAssignmentOrThrow(assignmentId);
        verifyAssignmentAccess(assignment, teacherId);
        return enrichAssignmentResponse(assignment, null);
    }

    @Override
    public Page<AssignmentSummaryResponse> getAssignmentsByClass(UUID classId, String title, com.fu.assignment.entity.AssignmentStatus status, AssignmentType type, Pageable pageable, UUID teacherId) {
        Clazz clazz = classRepository.findByIdAndNotDeleted(classId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lớp học"));
        // Teacher must be in class to see its assignments
        verifyTeacherAccess(clazz, teacherId);

        Specification<Assignment> spec = Specification.where(AssignmentSpecification.withClassId(classId)).and(AssignmentSpecification.isNotDeleted()).and(AssignmentSpecification.withTitle(title)).and(AssignmentSpecification.withStatus(status)).and(AssignmentSpecification.withType(type));

        Page<Assignment> assignments = assignmentRepository.findAll(spec, pageable);
        return populateTeacherSummaryResponses(assignments);
    }

    @Override
    public ClassAssignmentStatisticsResponse getClassAssignmentStatistics(UUID classId, UUID teacherId) {
        Clazz clazz = classRepository.findByIdAndNotDeleted(classId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lớp học"));

        verifyTeacherAccess(clazz, teacherId);

        List<Object[]> assignmentCounts = assignmentRepository.countByStatusForClass(classId);
        List<Object[]> submissionCounts = submissionRepository.countSubmissionsByStatusForClass(classId);

        ClassAssignmentStatisticsResponse.ClassAssignmentStatisticsResponseBuilder builder = ClassAssignmentStatisticsResponse.builder().classId(classId);

        long totalA = 0;
        for (Object[] row : assignmentCounts) {
            String status = row[0].toString();
            long count = ((Number) row[1]).longValue();
            totalA += count;
            if ("DRAFT".equals(status)) builder.draftAssignments(count);
            else if ("PUBLISHED".equals(status)) builder.publishedAssignments(count);
            else if ("CLOSED".equals(status)) builder.closedAssignments(count);
        }
        builder.totalAssignments(totalA);

        long totalS = 0;
        long ungraded = 0;
        long graded = 0;
        long inProgress = 0;
        long overdue = 0;
        for (Object[] row : submissionCounts) {
            String status = row[0].toString();
            long count = ((Number) row[1]).longValue();
            totalS += count;
            if ("SUBMITTED".equals(status)) ungraded += count;
            else if ("GRADED".equals(status)) graded += count;
            else if ("IN_PROGRESS".equals(status)) inProgress += count;
            else if ("OVERDUE".equals(status)) overdue += count;
        }
        builder.totalSubmissions(totalS);
        builder.ungradedSubmissions(ungraded);
        builder.gradedSubmissions(graded);
        builder.inProgressSubmissions(inProgress);
        builder.overdueSubmissions(overdue);

        return builder.build();
    }

    @Override
    public Page<AssignmentSummaryResponse> getMyAssignments(UUID teacherId, String title, com.fu.assignment.entity.AssignmentStatus status, AssignmentType type, Pageable pageable) {
        log.warn("title={}, status={}, type={}", title, status, type);
        Specification<Assignment> spec = Specification.where(AssignmentSpecification.withCreatedBy(teacherId)).and(AssignmentSpecification.isNotDeleted()).and(AssignmentSpecification.withTitle(title)).and(AssignmentSpecification.withStatus(status)).and(AssignmentSpecification.withType(type));

        Page<Assignment> assignments = assignmentRepository.findAll(spec, pageable);
        return populateTeacherSummaryResponses(assignments);
    }

    private Page<AssignmentSummaryResponse> populateTeacherSummaryResponses(Page<Assignment> assignments) {
        List<UUID> ids = assignments.getContent().stream().map(Assignment::getId).toList();
        List<UUID> classIds = assignments.getContent().stream()
                .map(Assignment::getClassId)
                .filter(Objects::nonNull)
                .distinct().toList();

        Map<UUID, String> classNames = new HashMap<>();
        if (!classIds.isEmpty()) {
            classRepository.findAllById(classIds).forEach(c -> classNames.put(c.getId(), c.getName()));
        }

        Map<UUID, Long> ungradedCounts = new HashMap<>();
        if (!ids.isEmpty()) {
            List<Object[]> counts = submissionRepository.countUngradedByAssignmentIds(ids);
            for (Object[] row : counts) {
                ungradedCounts.put((UUID) row[0], ((Number) row[1]).longValue());
            }
        }

        return assignments.map(a -> {
            AssignmentSummaryResponse r = buildSummaryResponse(a);
            r.setUngradedCount(ungradedCounts.getOrDefault(a.getId(), 0L));
            r.setClassId(a.getClassId());
            r.setClassName(classNames.get(a.getClassId()));
            return r;
        });
    }

    private Page<LearnerAssignmentSummaryResponse> populateLearnerSummaryResponses(Page<Assignment> assignments, UUID learnerId) {
        List<UUID> ids = assignments.getContent().stream().map(Assignment::getId).toList();
        Map<UUID, SubmissionStatus> learnerStatuses = new HashMap<>();
        Map<UUID, UUID> learnerSubmissionIds = new HashMap<>();

        if (!ids.isEmpty()) {
            List<Object[]> statuses = submissionRepository.findLatestStatusByAssignmentIdsAndUserId(ids, learnerId);
            for (Object[] row : statuses) {
                learnerStatuses.put((UUID) row[0], SubmissionStatus.valueOf((String) row[1]));
                learnerSubmissionIds.put((UUID) row[0], (UUID) row[2]);
            }
        }

        return assignments.map(a -> {
            LearnerAssignmentSummaryResponse r = mapper.toLearnerSummaryResponse(a);
            SubmissionStatus status = learnerStatuses.getOrDefault(a.getId(), SubmissionStatus.NOT_STARTED);

            // If not finished and past dueDate -> mark as OVERDUE for the learner view
            if ((status == SubmissionStatus.NOT_STARTED || status == SubmissionStatus.IN_PROGRESS) && a.getDueDate() != null && a.getDueDate().isBefore(LocalDateTime.now())) {
                status = SubmissionStatus.OVERDUE;
            }

            r.setLearnerSubmissionStatus(status);
            r.setLearnerSubmissionId(learnerSubmissionIds.get(a.getId()));
            return r;
        });
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE (TEACHER)
    // ═══════════════════════════════════════════════════════════════════════════


    @Override
    @Transactional
    public AssignmentResponse cloneAssignment(UUID sourceAssignmentId, CloneAssignmentRequest request, UUID teacherId) {
        // 1. Load & verify source assignment access
        Assignment source = findAssignmentOrThrow(sourceAssignmentId);
        verifyAssignmentAccess(source, teacherId);

        // 2. Verify target class exists and teacher has access
        UUID targetClassId = request.getTargetClassId();
        Clazz targetClazz = classRepository.findByIdAndNotDeleted(targetClassId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lớp học đích"));
        verifyTeacherAccess(targetClazz, teacherId);

        // 3. Build cloned assignment (new entity, DRAFT, no submissions)
        Assignment cloned = Assignment.builder().title(request.getNewTitle() != null ? request.getNewTitle() : source.getTitle()).description(source.getDescription()).instructions(source.getInstructions()).assignmentType(source.getAssignmentType()).classId(targetClassId).dueDate(request.getNewDueDate()).startDate(null).questionCount(source.getQuestionCount()).status(AssignmentStatus.DRAFT).hasSubmissions(false).createdBy(teacherId).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        Assignment savedClone = assignmentRepository.save(cloned);
        UUID clonedId = savedClone.getId();

        // 4. Deep-copy content based on assignment type
        if (source.isQuiz()) {
            cloneQuizQuestions(sourceAssignmentId, clonedId, teacherId);
        } else if (source.isWritingOrTranslation()) {
            cloneWritingContent(sourceAssignmentId, clonedId);
        }

        log.info("Assignment {} cloned to class {} as new assignment {} by teacher {}", sourceAssignmentId, request.getTargetClassId(), clonedId, teacherId);
        return enrichAssignmentResponse(savedClone, teacherId);
    }

    /**
     * Deep-copy quiz questions from source assignment to cloned assignment.
     * Each question is duplicated (new UUID) so edits to the clone don't affect the original.
     */
    private void cloneQuizQuestions(UUID sourceAssignmentId, UUID clonedAssignmentId, UUID teacherId) {
        List<AssignmentQuestion> sourceLinks = assignmentQuestionRepository.findByAssignmentIdOrderByOrderIndex(sourceAssignmentId);

        for (AssignmentQuestion sourceLink : sourceLinks) {
            QuizQuestion original = questionRepository.findById(sourceLink.getQuestionId()).orElse(null);
            if (original == null || original.isDeleted()) continue;

            // Deep-copy the question
            QuizQuestion copy = QuizQuestion.builder().questionText(original.getQuestionText()).questionType(original.getQuestionType()).questionSubtype(original.getQuestionSubtype()).options(original.getOptions()).correctAnswer(original.getCorrectAnswer()).correctAnswers(original.getCorrectAnswers()).explanation(original.getExplanation()).aiGenerated(original.getAiGenerated()).jlptLevel(original.getJlptLevel()).createdBy(teacherId).createdAt(LocalDateTime.now()).build();

            QuizQuestion savedCopy = questionRepository.save(copy);

            // Create link to cloned assignment
            AssignmentQuestion link = AssignmentQuestion.builder().assignmentId(clonedAssignmentId).questionId(savedCopy.getId()).orderIndex(sourceLink.getOrderIndex()).build();
            assignmentQuestionRepository.save(link);
        }
    }

    /**
     * Copy writing content from source assignment to cloned assignment.
     */
    private void cloneWritingContent(UUID sourceAssignmentId, UUID clonedAssignmentId) {
        writingAssignmentRepository.findByAssignmentId(sourceAssignmentId).ifPresent(source -> {
            WritingAssignment copy = WritingAssignment.builder().assignmentId(clonedAssignmentId).prompt(source.getPrompt()).createdAt(LocalDateTime.now()).build();
            writingAssignmentRepository.save(copy);
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SUBMISSIONS — TEACHER VIEW
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public Page<SubmissionSummaryResponse> getSubmissionsByAssignment(UUID assignmentId, Pageable pageable, UUID teacherId) {
        Assignment assignment = findAssignmentOrThrow(assignmentId);
        verifyAssignmentAccess(assignment, teacherId);
        return submissionRepository.findByAssignmentId(assignmentId, pageable).map(s -> enrichSummaryResponse(s));
    }

    @Override
    public SubmissionResponse getSubmission(UUID submissionId, UUID teacherId) {
        AssignmentSubmission submission = findSubmissionOrThrow(submissionId);
        Assignment assignment = findAssignmentOrThrow(submission.getAssignmentId());
        verifyAssignmentAccess(assignment, teacherId);
        return enrichSubmissionResponse(submission);
    }

    @Override
    @Transactional
    public SubmissionResponse gradeWritingSubmission(UUID submissionId, GradeWritingRequest request, UUID teacherId) {
        AssignmentSubmission submission = findSubmissionOrThrow(submissionId);
        Assignment assignment = findAssignmentOrThrow(submission.getAssignmentId());

        verifyAssignmentAccess(assignment, teacherId);

        if (!assignment.isWritingOrTranslation()) {
            throw new BusinessException("INVALID_TYPE", "Bài tập dạng QUIZ sử dụng chấm điểm tự động, không dùng chấm thủ công.");
        }
        if (submission.getStatus() != SubmissionStatus.SUBMITTED) {
            throw new BusinessException("INVALID_STATUS", "Chỉ có thể chấm điểm bài nộp ở trạng thái SUBMITTED.");
        }

        submission.setFeedback(request.getFeedback());
        submission.setScore(request.getTeacherScore());
        submission.setStatus(SubmissionStatus.GRADED);
        submission.setGradedBy(teacherId);
        submission.setGradedAt(LocalDateTime.now());
        submission.setUpdatedAt(LocalDateTime.now());
        submission.setUpdatedBy(teacherId);

        submissionRepository.save(submission);
        log.info("Writing submission {} graded by teacher {} with score {}", submissionId, teacherId, request.getTeacherScore());
        return enrichSubmissionResponse(submission);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNER FLOW
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public Page<LearnerAssignmentSummaryResponse> getLearnerAssignments(UUID learnerId, String title, AssignmentType type, SubmissionStatus status, Pageable pageable) {
        List<UUID> classIds = classMemberRepository.findActiveClassIdsByUserId(learnerId);

        String titleStr = (title != null) ? title : "";
        String typeStr = (type != null) ? type.name() : "";
        String statusStr = (status != null) ? status.name() : "";
        Page<Assignment> assignments = assignmentRepository
                .findLearnerAssignmentsSorted(learnerId, classIds, titleStr, typeStr, statusStr, pageable);

        return populateLearnerSummaryResponses(assignments, learnerId);
    }


    @Override
    @Transactional
    public LearnerAssignmentResponse startAssignment(UUID assignmentId, UUID learnerId) {
        Assignment assignment = findAssignmentOrThrow(assignmentId);

        if (assignment.getStatus() == AssignmentStatus.DRAFT) {
            throw new BusinessException("ASSIGNMENT_NOT_PUBLISHED", "Bài tập này hiện không khả dụng.");
        }

        // 1. Check for Resume (Active Submission)
        Optional<AssignmentSubmission> activeOpt = submissionRepository.findActiveByAssignmentIdAndUserId(assignmentId, learnerId);
        if (activeOpt.isPresent()) {
            log.info("Learner {} resuming assignment {}", learnerId, assignmentId);
            return enrichLearnerAssignmentResponse(assignment, learnerId);
        }

        // 2. Start Logic (New Submission)
        if (assignment.getDueDate().isBefore(LocalDateTime.now())) {
            throw new BusinessException("PAST_DUE", "Bài tập này đã hết hạn nộp.");
        }

        long attemptCount = submissionRepository.countByAssignmentIdAndUserId(assignmentId, learnerId);
        // Only check NO_RETRY if it's the first time and we haven't checked for Resume above
        if (attemptCount > 0) {
            // For now, if attemptCount > 0 and no active submission, we assume it's a retry check
            // Our current logic only allows 1 attempt for now.
            throw new BusinessException("NO_RETRY", "Bài tập này không cho phép làm lại.");
        }

        int totalQ = (int) assignmentQuestionRepository.countByAssignmentId(assignmentId);
        if (assignment.getAssignmentType() == AssignmentType.QUIZ) {
            if (totalQ == 0) totalQ = assignment.getQuestionCount() != null ? assignment.getQuestionCount() : 0;
        } else {
            totalQ = 1;
        }

        AssignmentSubmission submission = AssignmentSubmission.builder().assignmentId(assignmentId).userId(learnerId).status(SubmissionStatus.IN_PROGRESS).attemptNumber((int) attemptCount + 1).startedAt(LocalDateTime.now()).totalQuestions(totalQ).answers("[]").correctCount(0).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        submissionRepository.save(submission);

        // Mark assignment as having submissions
        if (!Boolean.TRUE.equals(assignment.getHasSubmissions())) {
            assignment.setHasSubmissions(true);
            assignment.setUpdatedAt(LocalDateTime.now());
            assignmentRepository.save(assignment);
        }

        log.info("Learner {} started assignment {}, attempt #{}", learnerId, assignmentId, submission.getAttemptNumber());
        return enrichLearnerAssignmentResponse(assignment, learnerId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SubmissionResponse submitQuiz(UUID assignmentId, SubmitAssignmentRequest request, UUID learnerId) {
        // Resolve active submission by assignmentId and learnerId
        AssignmentSubmission submission = submissionRepository.findActiveByAssignmentIdAndUserId(assignmentId, learnerId).orElseThrow(() -> new BusinessException("NO_ACTIVE_SUBMISSION", "Không tìm thấy bài nộp đang làm. Vui lòng bắt đầu bài tập trước."));

        Assignment assignment = findAssignmentOrThrow(assignmentId);
        if (!assignment.isQuiz()) {
            throw new BusinessException("INVALID_TYPE", "Vui lòng sử dụng submitWriting cho bài tập không phải dạng QUIZ.");
        }
        if (assignment.getDueDate().isBefore(LocalDateTime.now())) {
            submission.setAnswers(request.getAnswers());
            submission.setSubmittedAt(LocalDateTime.now());
            submissionService.markSubmissionAsOverdueAndSave(submission.getId());
            throw new BusinessException("PAST_DUE", "Bài tập này đã hết hạn nộp.");
        } else {

            // Auto-score: compare submitted answers with correct answers
            String answersJson = request.getAnswers();
            AutoScoreResult scored = autoScore(answersJson, assignment);

            submission.setAnswers(answersJson);
            submission.setCorrectCount(scored.correctCount);
            submission.setScore(BigDecimal.valueOf(scored.earnedPoints));
            submission.setSubmittedAt(LocalDateTime.now());
            submission.setUpdatedAt(LocalDateTime.now());
            submission.setStatus(SubmissionStatus.GRADED); // Quiz: auto-graded immediately
            submissionRepository.save(submission);
            log.info("Learner {} submitted quiz {}, score={}", learnerId, submission.getAssignmentId(), BigDecimal.valueOf(scored.earnedPoints));
            return enrichSubmissionResponse(submission);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SubmissionResponse submitWriting(UUID assignmentId, MultipartFile[] images, UUID learnerId) {
        boolean hasImages = images != null && images.length > 0 && java.util.Arrays.stream(images).anyMatch(f -> f != null && !f.isEmpty());

        if (!hasImages) {
            throw new BusinessException("INVALID_INPUT", "Phải cung cấp ít nhất một hình ảnh.");
        }

        if (hasImages && images.length > MAX_SUBMISSION_IMAGES) {
            throw new BusinessException("TOO_MANY_IMAGES", "Tối đa " + MAX_SUBMISSION_IMAGES + " hình ảnh cho mỗi bài nộp.");
        }

        // Find the active IN_PROGRESS submission for this assignment+learner
        List<AssignmentSubmission> active = submissionRepository.findByAssignmentIdAndUserIdOrderByAttemptDesc(assignmentId, learnerId);

        AssignmentSubmission submission = active.stream().filter(s -> s.getStatus() == SubmissionStatus.IN_PROGRESS).findFirst().orElseThrow(() -> new BusinessException("NO_ACTIVE_SUBMISSION", "Không tìm thấy bài nộp đang làm. Vui lòng bắt đầu bài tập trước."));

        Assignment assignment = findAssignmentOrThrow(assignmentId);
        if (assignment.isQuiz()) {
            throw new BusinessException("INVALID_TYPE", "Vui lòng sử dụng submitQuiz cho bài tập dạng QUIZ.");
        }
        // Upload images to Cloudinary
        if (hasImages) {
            List<String> imageUrls = uploadSubmissionImages(images, learnerId, assignmentId);
            try {
                submission.setImageUrls(objectMapper.writeValueAsString(imageUrls));
            } catch (Exception e) {
                log.error("Failed to serialize image URLs", e);
                throw new BusinessException("INTERNAL_ERROR", "Xử lý hình ảnh tải lên thất bại.");
            }
        }

        submission.setSubmittedAt(LocalDateTime.now());
        if (assignment.getDueDate().isBefore(LocalDateTime.now())) {
            submissionService.markSubmissionAsOverdueAndSave(submission.getId());
            throw new BusinessException("PAST_DUE", "Bài tập này đã hết hạn nộp.");
        } else {
            submission.setUpdatedAt(LocalDateTime.now());
            submission.setStatus(SubmissionStatus.SUBMITTED); // waiting for teacher grading
            submissionRepository.save(submission);
            log.info("Learner {} submitted writing for assignment {} (images: {})", learnerId, assignmentId, hasImages ? images.length : 0);
            return enrichSubmissionResponse(submission);
        }
    }

    @Override
    @Transactional
    public SubmissionResponse cancelSubmission(UUID assignmentId, UUID learnerId) {
        Assignment assignment = findAssignmentOrThrow(assignmentId);

        if (assignment.isQuiz()) {
            throw new BusinessException("INVALID_TYPE", "Không thể hủy nộp bài dạng trắc nghiệm (QUIZ).");
        }

        if (assignment.getDueDate().isBefore(LocalDateTime.now())) {
            throw new BusinessException("PAST_DUE", "Đã quá hạn nộp, không thể hủy nộp bài.");
        }

        List<AssignmentSubmission> active = submissionRepository.findByAssignmentIdAndUserIdOrderByAttemptDesc(assignmentId, learnerId);
        if (active.isEmpty()) {
            throw new BusinessException("NO_SUBMISSION", "Không tìm thấy bài nộp nào để hủy.");
        }

        AssignmentSubmission submission = active.get(0);

        if (submission.getStatus() == SubmissionStatus.GRADED) {
            throw new BusinessException("ALREADY_GRADED", "Bài nộp đã được chấm điểm, không thể hủy.");
        }

        if (submission.getStatus() == SubmissionStatus.IN_PROGRESS) {
            throw new BusinessException("ALREADY_IN_PROGRESS", "Bài nộp đang ở trạng thái nháp.");
        }

        if (submission.getStatus() != SubmissionStatus.SUBMITTED) {
            throw new BusinessException("INVALID_STATUS", "Chỉ có thể hủy bài nộp ở trạng thái SUBMITTED.");
        }

        submission.setStatus(SubmissionStatus.IN_PROGRESS);
        submission.setSubmittedAt(null);
        submission.setUpdatedAt(LocalDateTime.now());

        log.info("Learner {} canceled submission for assignment {}", learnerId, assignmentId);
        deleteSubmittedImages(submission);
        submissionRepository.save(submission);
        return enrichSubmissionResponse(submission);
    }

    private void deleteSubmittedImages(AssignmentSubmission submission) {
        List<String> imageUrls = parseImageUrls(submission.getImageUrls());
        if (imageUrls != null && !imageUrls.isEmpty()) {
            for (String s : imageUrls) {
                String publicId = cloudinaryService.extractPublicId(s);
                if (publicId != null) {
                    cloudinaryService.deleteImage(publicId);
                }
            }
        }
        submission.setImageUrls("[]");
    }

    @Override
    public Page<SubmissionSummaryResponse> getMySubmissions(UUID learnerId, Pageable pageable) {
        return submissionRepository.findByUserId(learnerId, pageable).map(s -> enrichSummaryResponse(s));
    }

    @Override
    public SubmissionResponse getMySubmission(UUID submissionId, UUID learnerId) {
        AssignmentSubmission submission = findSubmissionOrThrow(submissionId);
        if (!submission.getUserId().equals(learnerId)) {
            throw new BusinessException("ACCESS_DENIED", "Bạn chỉ có thể xem bài nộp của chính mình.");
        }
        return enrichSubmissionResponse(submission);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private Assignment findAssignmentOrThrow(UUID id) {
        return assignmentRepository.findByIdAndNotDeleted(id).orElseThrow(AssignmentNotFoundException::new);
    }


    private AssignmentSubmission findSubmissionOrThrow(UUID id) {
        return submissionRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài nộp."));
    }

    private void requireDraftOrNoSubmission(Assignment assignment) {
        if (assignment.getStatus() != AssignmentStatus.DRAFT && assignment.getHasSubmissions() == Boolean.TRUE) {
            throw new BusinessException("INVALID_STATUS", "Thao tác này chỉ được phép với bài tập ở trạng thái nháp hoặc chưa có bài được nộp.");
        }
    }

    private void validateDueDate(LocalDateTime dueDate) {
        if (dueDate != null && !dueDate.isAfter(LocalDateTime.now())) {
            throw new BusinessException("INVALID_DUE_DATE", "Hạn nộp phải là một thời điểm trong tương lai (sau thời điểm hiện tại).");
        }
    }

    private void verifyTeacherAccess(Clazz clazz, UUID teacherId) {
        if (clazz != null && !classTeacherHistoryRepository.findCurrentTeacherIdByClassId(clazz.getId()).get().equals(teacherId)) {
            throw new BusinessException("ACCESS_DENIED", "Bạn không phải là giáo viên của lớp này.");
        }
    }

    private void verifyAssignmentAccess(Assignment assignment, UUID teacherId) {
        if (assignment.getClassId() != null) {
            Clazz clazz = classRepository.findByIdAndNotDeleted(assignment.getClassId()).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lớp học chứa bài tập này."));
            verifyTeacherAccess(clazz, teacherId);
        } else {
            verifyTeacherOwnership(assignment, teacherId);
        }
    }

    private void verifyTeacherOwnership(Assignment assignment, UUID teacherId) {
        if (!assignment.getCreatedBy().equals(teacherId)) {
            throw new BusinessException("ACCESS_DENIED", "Bạn chỉ có thể quản lý bài tập của chính mình.");
        }
    }


    /**
     * Upload submission images to Cloudinary and return list of secure URLs.
     */
    private List<String> uploadSubmissionImages(MultipartFile[] images, UUID learnerId, UUID assignmentId) {
        List<String> urls = new ArrayList<>();
        String folder = SUBMISSION_IMAGE_FOLDER + "/" + assignmentId + "/" + learnerId;
        for (MultipartFile image : images) {
            if (image != null && !image.isEmpty()) {
                try {
                    String url = cloudinaryService.uploadImage(image, folder);
                    urls.add(url);
                    log.debug("Uploaded submission image: {}", url);
                } catch (Exception e) {
                    log.error("Failed to upload submission image: {}", image.getOriginalFilename(), e);
                    throw new BusinessException("UPLOAD_FAILED", "Tải hình ảnh lên thất bại: " + image.getOriginalFilename());
                }
            }
        }
        return urls;
    }

    /**
     * Parse imageUrls JSON string to List for response.
     */
    @SuppressWarnings("unchecked")
    private List<String> parseImageUrls(String imageUrlsJson) {
        if (imageUrlsJson == null || imageUrlsJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(imageUrlsJson, List.class);
        } catch (Exception e) {
            return null;
        }
    }


    private AssignmentSummaryResponse buildSummaryResponse(Assignment a) {
        AssignmentSummaryResponse r = mapper.toSummaryResponse(a);
        r.setQuestionCount(assignmentQuestionRepository.countByAssignmentId(a.getId()));
        r.setSubmissionCount(submissionRepository.countByAssignmentIdAndStatus(a.getId(), SubmissionStatus.SUBMITTED.name()) + submissionRepository.countByAssignmentIdAndStatus(a.getId(), SubmissionStatus.GRADED.name()));
        return r;
    }

    private AssignmentResponse enrichAssignmentResponse(Assignment assignment, UUID requesterId) {
        AssignmentResponse response = mapper.toResponse(assignment);
        List<AssignmentQuestion> aqs = assignmentQuestionRepository.findByAssignmentIdOrderByOrderIndex(assignment.getId());
        response.setActualQuestionCount((long) aqs.size());

        List<AssignmentQuestionResponse> qList = aqs.stream().map(aq -> questionRepository.findById(aq.getQuestionId()).map(q -> {
            AssignmentQuestionResponse qr = mapper.toAssignmentQuestionResponse(q);
            qr.setAssignmentQuestionId(aq.getId());
            qr.setOrderIndex(aq.getOrderIndex());
            return qr;
        }).orElse(new AssignmentQuestionResponse())).toList();
        response.setQuestions(qList);

        response.setSubmissionCount(submissionRepository.countByAssignmentIdAndStatus(assignment.getId(), SubmissionStatus.SUBMITTED.name()) + submissionRepository.countByAssignmentIdAndStatus(assignment.getId(), SubmissionStatus.GRADED.name()));

        // Enrich writing content for WRITING / TRANSLATION assignments
        if (assignment.isWritingOrTranslation()) {
            writingAssignmentRepository.findByAssignmentId(assignment.getId()).ifPresent(wc -> response.setWritingContent(mapper.toResponse(wc)));
        }

        if (assignment.getClassId() != null) {
            classRepository.findByIdAndNotDeleted(assignment.getClassId()).ifPresent(c -> {
                response.setClassName(c.getName());
                response.setClassId(c.getId());
            });
        }

        return response;
    }

    private LearnerAssignmentResponse enrichLearnerAssignmentResponse(Assignment assignment, UUID learnerId) {
        LearnerAssignmentResponse response = mapper.toLearnerResponse(assignment);

        List<AssignmentQuestion> aqs = assignmentQuestionRepository.findByAssignmentIdOrderByOrderIndex(assignment.getId());
        response.setActualQuestionCount((long) aqs.size());

        List<LearnerQuizQuestionResponse> qList = aqs.stream().map(aq -> questionRepository.findById(aq.getQuestionId()).map(q -> {
            LearnerQuizQuestionResponse qr = mapper.toLearnerAssignmentQuestionResponse(q);
            qr.setAssignmentQuestionId(aq.getId());
            qr.setOrderIndex(aq.getOrderIndex());
            return qr;
        }).orElse(new LearnerQuizQuestionResponse())).toList();
        response.setQuestions(qList);

        // Enrichment for non-quiz
        if (assignment.isWritingOrTranslation()) {
            writingAssignmentRepository.findByAssignmentId(assignment.getId()).ifPresent(wc -> response.setWritingContent(mapper.toResponse(wc)));
        }

        return response;
    }

    private SubmissionResponse enrichSubmissionResponse(AssignmentSubmission s) {
        SubmissionResponse response = mapper.toResponse(s);
        Assignment assignment = assignmentRepository.findByIdAndNotDeleted(s.getAssignmentId()).orElse(null);
        if (assignment != null) {
            response.setAssignmentTitle(assignment.getTitle());
            // Enrich quiz details if completed
            if (assignment.isQuiz() && (s.getStatus() == SubmissionStatus.GRADED || s.getStatus() == SubmissionStatus.OVERDUE)) {
                response.setQuizDetails(enrichQuizDetails(s.getAnswers()));
            }
        }

        userRepository.findById(s.getUserId()).ifPresent(u -> {
            response.setUserFullName(u.getFullName());
            response.setUserEmail(u.getEmail());
        });
        // Parse imageUrls JSON string → List<String>
        response.setImageUrls(parseImageUrls(s.getImageUrls()));
        return response;
    }

    private List<QuizSubmissionDetailResponse> enrichQuizDetails(String answersJson) {
        List<QuizSubmissionDetailResponse> details = new ArrayList<>();
        if (answersJson == null || answersJson.isBlank()) return details;

        try {
            JsonNode answersArray = objectMapper.readTree(answersJson);
            for (JsonNode ansNode : answersArray) {
                UUID questionId = UUID.fromString(ansNode.path("questionId").asText());
                String selectedAnswer = ansNode.path("selectedAnswer").asText(null);

                questionRepository.findById(questionId).ifPresent(q -> {
                    boolean isCorrect = checkAnswer(q, selectedAnswer);
                    details.add(QuizSubmissionDetailResponse.builder().questionId(questionId).questionText(q.getQuestionText()).questionType(q.getQuestionType()).selectedAnswer(selectedAnswer).correctAnswer(formatCorrectAnswer(q)).explanation(q.getExplanation()).isCorrect(isCorrect).options(toJsonNode(q.getOptions())).build());
                });
            }
        } catch (Exception e) {
            log.error("Failed to enrich quiz details", e);
        }
        return details;
    }

    private String formatCorrectAnswer(QuizQuestion q) {
        if ("multiple_choice".equalsIgnoreCase(q.getQuestionType())) {
            try {
                JsonNode root = objectMapper.readTree(q.getOptions());
                JsonNode options = root.isArray() ? root : root.path("options");
                if (options.isArray()) {
                    for (JsonNode opt : options) {
                        if (opt.path("id").asText().equalsIgnoreCase(q.getCorrectAnswer())) {
                            return opt.path("id").asText() + ": " + opt.path("text").asText();
                        }
                    }
                }
            } catch (Exception e) {
                // fallback
            }
        }
        if ("fill_blank".equalsIgnoreCase(q.getQuestionType()) || "true_false".equalsIgnoreCase(q.getQuestionType())) {
            return q.getCorrectAnswer();
        }
        if (q.getCorrectAnswers() != null) {
            return q.getCorrectAnswers(); // For matching/ordering, already JSON
        }
        return q.getCorrectAnswer();
    }

    private JsonNode toJsonNode(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private SubmissionSummaryResponse enrichSummaryResponse(AssignmentSubmission s) {
        SubmissionSummaryResponse r = mapper.toSummaryResponse(s);
        assignmentRepository.findByIdAndNotDeleted(s.getAssignmentId()).ifPresent(a -> {
            r.setAssignmentTitle(a.getTitle());
        });
        userRepository.findById(s.getUserId()).ifPresent(u -> r.setUserFullName(u.getFullName()));
        return r;
    }

    // ── Auto-Score for Quiz ───────────────────────────────────────────────────

    private record AutoScoreResult(int correctCount, double earnedPoints, int totalPoints) {
    }

    private AutoScoreResult autoScore(String answersJson, Assignment assignment) {
        UUID assignmentId = assignment.getId();
        List<AssignmentQuestion> aqs = assignmentQuestionRepository.findByAssignmentIdOrderByOrderIndex(assignmentId);

        double points = 0;
        int correctCount = 0;
        try {
            JsonNode answersArray = objectMapper.readTree(answersJson);
            if (answersArray == null || !answersArray.isArray()) {
                throw new BusinessException("INVALID_JSON_FORMAT", "Định dạng JSON của câu trả lời không hợp lệ.");
            }
            for (AssignmentQuestion aq : aqs) {
                QuizQuestion q = questionRepository.findById(aq.getQuestionId()).orElse(null);
                if (q == null) continue;
                // Find the submitted answer for this question
                String selectedAnswer = null;
                for (JsonNode ans : answersArray) {
                    if (aq.getQuestionId().toString().equals(ans.path("questionId").asText())) {
                        selectedAnswer = ans.path("selectedAnswer").asText(null);
                        break;
                    }
                }
                if (selectedAnswer == null) continue;

                boolean isCorrect = checkAnswer(q, selectedAnswer);
                if (isCorrect) {
                    correctCount += 1;
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Invalid quiz answers JSON: {}", answersJson);
            throw new BusinessException("INVALID_JSON_FORMAT", "Định dạng JSON của câu trả lời không hợp lệ.");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error scoring quiz", e);
            throw new BusinessException("INTERNAL_ERROR", "Lỗi không xác định khi chấm điểm bài thi.");
        }
        int actualCount = aqs.size();
        points = actualCount > 0 ? (100.0 * correctCount / actualCount) : 0;
        return new AutoScoreResult(correctCount, points, 100);
    }

    private boolean checkAnswer(QuizQuestion question, String selectedAnswer) {
        if (selectedAnswer == null) return false;
        try {
            return switch (question.getQuestionType()) {
                case "multiple_choice", "fill_blank", "true_false" ->
                        question.getCorrectAnswer() != null && question.getCorrectAnswer().trim().equalsIgnoreCase(selectedAnswer.trim());
                default -> false;
            };
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Validates and applies assignment metadata common to all types (Quiz/Writing/Translation).
     * <p>
     * When {@code hasSubmissions=true}, the following rules are enforced:
     * <p>
     * dueDate: can only be extended (pushed later), never shortened
     *
     */
    private void applyCommonMetadata(Assignment assignment, AssignmentMetadataRequest request, boolean hasSubmissions, UUID updatedBy) {
        if (hasSubmissions) {
            // dueDate: only allow extending
            if (request.getDueDate() != null && assignment.getDueDate() != null && request.getDueDate().isBefore(assignment.getDueDate())) {
                throw new BusinessException("INVALID_DUE_DATE", "Bài tập đã có học viên nộp bài. Chỉ được gia hạn deadline, không được rút ngắn.");
            }
        }


        assignment.setTitle(request.getTitle());
        assignment.setDescription(request.getDescription());
        assignment.setInstructions(request.getInstructions());
        assignment.setDueDate(request.getDueDate());
        assignment.setUpdatedBy(updatedBy);
        assignment.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * Rule 1: Validates assignment status transitions.
     * <p>
     * DRAFT → PUBLISHED: requires content (questions for quiz, prompt for writing) and dueDate set
     * PUBLISHED → DRAFT: blocked (cannot unpublish)
     * CLOSED → any: blocked (terminal state)
     *
     * @param questionCount for QUIZ publishing validation — pass null for writing types
     */
    private void validateStatusTransition(Assignment assignment, AssignmentStatus newStatus, Integer questionCount) {
        AssignmentStatus current = assignment.getStatus();
        if (current == null || current == newStatus) return; // no change

        // CLOSED is terminal — no transitions out
        if (current == AssignmentStatus.CLOSED) {
            throw new BusinessException("INVALID_STATUS_TRANSITION", "Bài tập đã đóng (CLOSED). Không thể chuyển sang trạng thái khác.");
        }

        // PUBLISHED → DRAFT: blocked
        if (current == AssignmentStatus.PUBLISHED && newStatus == AssignmentStatus.DRAFT) {
            throw new BusinessException("INVALID_STATUS_TRANSITION", "Không thể chuyển bài tập từ PUBLISHED về DRAFT.");
        }

        // DRAFT → PUBLISHED: validate content and dueDate
        if (current == AssignmentStatus.DRAFT && newStatus == AssignmentStatus.PUBLISHED) {
            if (assignment.getDueDate() == null) {
                throw new BusinessException("MISSING_DUE_DATE", "Phải đặt hạn nộp (dueDate) trước khi xuất bản bài tập.");
            }
            if (assignment.getDueDate().isBefore(LocalDateTime.now())) {
                throw new BusinessException("PAST_DUE_DATE", "Hạn nộp đã qua. Không thể xuất bản bài tập với deadline trong quá khứ.");
            }
            if (assignment.isQuiz()) {
                int count = questionCount != null ? questionCount : 0;
                if (count == 0) {
                    throw new BusinessException("NO_QUESTIONS", "Bài kiểm tra phải có ít nhất 1 câu hỏi trước khi xuất bản.");
                }
            } else {
                // Writing/Translation: check prompt exists
                boolean hasContent = writingAssignmentRepository.findByAssignmentId(assignment.getId()).map(w -> w.getPrompt() != null && !w.getPrompt().isBlank()).orElse(false);
                if (!hasContent) {
                    throw new BusinessException("NO_WRITING_CONTENT", "Bài viết phải có đề bài (prompt) trước khi xuất bản.");
                }
            }
        }
    }

    /**
     * Returns true if the writing request contains any content fields
     * (prompt, sampleAnswer, sourceText, etc.) that would modify the assignment's core content.
     */
    private boolean hasWritingContent(SaveFullWritingRequest request) {
        return request.getPrompt() != null;
    }

    /**
     * Deep compare writing content from request with what's mathematically saved in the database
     */
    private boolean hasWritingContentChanged(SaveFullWritingRequest request, Assignment assignment) {
        WritingAssignment current = writingAssignmentRepository.findByAssignmentId(assignment.getId()).orElse(null);
        if (current == null) {
            return hasWritingContent(request); // If it historically didn't exist, any content means it changed
        }

        if (!Objects.equals(current.getPrompt(), request.getPrompt())) {
            log.warn("Writing content changed: prompt. Incoming={}, Existing={}", request.getPrompt(), current.getPrompt());
            return true;
        }

        return false;
    }

    /**
     * Deep compare quiz questions from request with the list currently saved for the assignment
     */
    private boolean hasQuizQuestionsChanged(List<QuizQuestionDraftDto> incomingQuestions, UUID assignmentId) {
        if (incomingQuestions == null || incomingQuestions.isEmpty()) return false;
        if (assignmentId == null) return true; // Newly creating Questions, necessarily changed

        List<AssignmentQuestion> existingLinks = assignmentQuestionRepository.findByAssignmentIdOrderByOrderIndex(assignmentId);

        // 1. If counts differ, layout definitely changed
        if (existingLinks.size() != incomingQuestions.size()) {
            log.warn("Quiz questions changed: count mismatch (existing={}, incoming={})", existingLinks.size(), incomingQuestions.size());
            return true;
        }

        // Populate a Map to avoid hitting DB N times ideally, though lazy evaluation helps
        Map<UUID, QuizQuestion> existingQuestionsMap = new HashMap<>();
        for (AssignmentQuestion link : existingLinks) {
            questionRepository.findById(link.getQuestionId()).ifPresent(q -> existingQuestionsMap.put(q.getId(), q));
        }

        for (int i = 0; i < incomingQuestions.size(); i++) {
            QuizQuestionDraftDto incoming = incomingQuestions.get(i);
            AssignmentQuestion existingLink = existingLinks.get(i);

            if (incoming.getId() == null || !incoming.getId().equals(existingLink.getQuestionId())) {
                log.warn("Quiz questions changed: ID mismatch at index {} (incoming ID={}, existing link QID={})", i, incoming.getId(), existingLink.getQuestionId());
                return true;
            }

            // 3. Link properties changed
            Integer incOrder = incoming.getOrderIndex() != null ? incoming.getOrderIndex() : i;
            if (!Objects.equals(incOrder, existingLink.getOrderIndex())) {
                log.warn("Quiz questions changed: orderIndex mismatch for question {} (incoming={}, existing={})", incoming.getId(), incOrder, existingLink.getOrderIndex());
                return true;
            }


            // 4. Question inner values changed
            QuizQuestion existing = existingQuestionsMap.get(incoming.getId());
            if (existing == null) {
                log.warn("Quiz questions changed: question {} not found in database", incoming.getId());
                return true;
            }

            String incType = incoming.getQuestionType() != null ? incoming.getQuestionType() : "multiple_choice";

            if (!Objects.equals(incoming.getQuestionText(), existing.getQuestionText())) {
                log.warn("Quiz question {} changed: questionText. Incoming={}, Existing={}", incoming.getId(), incoming.getQuestionText(), existing.getQuestionText());
                return true;
            }

            if (!Objects.equals(incType, existing.getQuestionType())) {
                log.warn("Quiz question {} changed: questionType. Incoming={}, Existing={}", incoming.getId(), incType, existing.getQuestionType());
                return true;
            }

            // Options are JSON strings. String comparison will fail if keys are just ordered differently.
            if (!isJsonEqual(incoming.getOptions(), existing.getOptions())) {
                log.warn("Quiz question {} changed: options JSON mismatch. Incoming={}, Existing={}", incoming.getId(), incoming.getOptions(), existing.getOptions());
                return true;
            }

            if (!Objects.equals(incoming.getCorrectAnswer(), existing.getCorrectAnswer())) {
                log.warn("Quiz question {} changed: correctAnswer. Incoming={}, Existing={}", incoming.getId(), incoming.getCorrectAnswer(), existing.getCorrectAnswer());
                return true;
            }
            if (!Objects.equals(incoming.getCorrectAnswers(), existing.getCorrectAnswers())) {
                log.warn("Quiz question {} changed: correctAnswers. Incoming={}, Existing={}", incoming.getId(), incoming.getCorrectAnswers(), existing.getCorrectAnswers());
                return true;
            }
            if (!Objects.equals(incoming.getExplanation(), existing.getExplanation())) {
                log.warn("Quiz question {} changed: explanation. Incoming={}, Existing={}", incoming.getId(), incoming.getExplanation(), existing.getExplanation());
                return true;
            }

            if (!Objects.equals(incoming.getJlptLevel(), existing.getJlptLevel())) {
                if (!Objects.equals(incoming.getJlptLevel(), existing.getJlptLevel())) {
                    log.warn("Quiz question {} changed: jlptLevel. Incoming={}, Existing={}", incoming.getId(), incoming.getJlptLevel(), existing.getJlptLevel());
                    return true;
                }
            }
        }
        return false;
    }


    private String sanitizeOptionsJson(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) return optionsJson;
        try {
            JsonNode root = objectMapper.readTree(optionsJson);
            // Multiple choice options
            if (root.has("options") && root.get("options").isArray()) {
                for (JsonNode optionNode : root.get("options")) {
                    if (optionNode instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
                        if (obj.has("text") && obj.get("text").isTextual()) {
                            obj.put("text", MarkdownUtils.sanitizeAndValidate(obj.get("text").asText()));
                        }
                        if (obj.has("text_reading") && obj.get("text_reading").isTextual()) {
                            obj.put("text_reading", MarkdownUtils.sanitizeAndValidate(obj.get("text_reading").asText()));
                        }
                    }
                }
            }
            // Matching items
            if (root.has("left_items") && root.get("left_items").isArray()) {
                for (JsonNode item : root.get("left_items")) {
                    if (item instanceof com.fasterxml.jackson.databind.node.ObjectNode obj && obj.has("text") && obj.get("text").isTextual()) {
                        obj.put("text", MarkdownUtils.sanitizeAndValidate(obj.get("text").asText()));
                    }
                }
            }
            if (root.has("right_items") && root.get("right_items").isArray()) {
                for (JsonNode item : root.get("right_items")) {
                    if (item instanceof com.fasterxml.jackson.databind.node.ObjectNode obj && obj.has("text") && obj.get("text").isTextual()) {
                        obj.put("text", MarkdownUtils.sanitizeAndValidate(obj.get("text").asText()));
                    }
                }
            }
            // Ordering items
            if (root.has("items") && root.get("items").isArray()) {
                for (JsonNode item : root.get("items")) {
                    if (item instanceof com.fasterxml.jackson.databind.node.ObjectNode obj && obj.has("text") && obj.get("text").isTextual()) {
                        obj.put("text", MarkdownUtils.sanitizeAndValidate(obj.get("text").asText()));
                    }
                }
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Failed to sanitize options JSON: {}", e.getMessage());
            return optionsJson;
        }
    }

    /**
     * Safely compares two JSON strings ignoring formatting or key ordering.
     */
    private boolean isJsonEqual(String json1, String json2) {
        if (Objects.equals(json1, json2)) return true;
        if (json1 == null || json2 == null) return false;
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode tree1 = mapper.readTree(json1);
            JsonNode tree2 = mapper.readTree(json2);
            return tree1.equals(tree2);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public WritingEvaluationResponse evaluateWriting(WritingEvaluationRequest request, MultipartFile[] images) {
        return aiGradingService.evaluateWriting(request, images);
    }
}

