package com.fu.assignment.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.assignment.ai.QuizAiGenerationService;
import com.fu.assignment.ai.QuizExplanationService;
import com.fu.assignment.dto.request.*;
import com.fu.assignment.dto.response.*;
import com.fu.assignment.entity.*;
import com.fu.assignment.exception.AssignmentNotFoundException;
import com.fu.assignment.mapper.AssignmentMapper;
import com.fu.assignment.repository.*;
import com.fu.assignment.service.AssignmentService;
import com.fu.clazz.repository.ClassMemberRepository;
import com.fu.clazz.repository.ClassRepository;
import com.fu.assignment.util.MarkdownUtils;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.course.entity.Course;
import com.fu.infrastructure.cloudinary.CloudinaryService;
import com.fu.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
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

    private static final String SUBMISSION_IMAGE_FOLDER = "satori/assignment-submissions";
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
            // -- CREATE NEW ASSIGNMENT --
            if (classId != null) {
                classRepository.findByIdAndNotDeleted(classId)
                        .orElseThrow(() -> new BusinessException("CLASS_NOT_FOUND", "Class not found."));
            }
            assignment = Assignment.builder()
                    .assignmentType(AssignmentType.QUIZ)
                    .classId(classId)
                    .createdBy(teacherId)
                    .createdAt(LocalDateTime.now())
                    .hasSubmissions(false)
                    .build();
        } else {
            // -- UPDATE EXISTING ASSIGNMENT --
            assignment = findAssignmentOrThrow(assignmentId);
            verifyTeacherOwnership(assignment, teacherId);

            if (assignment.getAssignmentType() != AssignmentType.QUIZ) {
                throw new BusinessException("INVALID_TYPE", "saveFullQuiz is only for QUIZ assignments.");
            }

            hasSubmissions = Boolean.TRUE.equals(assignment.getHasSubmissions());
        }

        // Handle audio upload — blocked when submissions exist
        boolean isAudioChanged = (audioFile != null && !audioFile.isEmpty())
                || (request.getAudioUrl() != null && !request.getAudioUrl().equals(assignment.getAudioUrl()));
        if (isAudioChanged && hasSubmissions) {
            log.warn("Assignment {} update blocked: Audio content changed but assignment has submissions.", assignmentId);
            throw new BusinessException("HAS_SUBMISSIONS",
                    "Bài tập đã có học viên nộp bài. Không thể cập nhật nội dung bài tập đã giao." +
                            "Chỉ được cập nhật thông tin cơ bản (tiêu đề, mô tả, hạn nộp...).");
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
        if (request.getStatus() != null) {
            validateStatusTransition(assignment, request.getStatus(),
                    request.getQuestions() != null ? request.getQuestions().size() : assignment.getQuestionCount());
            assignment.setStatus(request.getStatus());
        } else if (isNewAssignment) {
            assignment.setStatus(AssignmentStatus.DRAFT);
        }

        // Quiz settings — only update when no submissions
        if (!hasSubmissions) {
            assignment.setShuffleQuestions(request.getShuffleQuestions());
            assignment.setShuffleOptions(request.getShuffleOptions());
            assignment.setShowCorrectAnswers(request.getShowCorrectAnswers());
            assignment.setAllowRetry(request.getAllowRetry());
            assignment.setMaxRetries(request.getMaxRetries());
            int qCount = request.getQuestions() != null ? request.getQuestions().size() : 0;
            assignment.setQuestionCount(qCount);
        }

        // If submissions exist, throw ONLY if questions ACTUALLY changed
        if (hasSubmissions && hasQuizQuestionsChanged(request.getQuestions(), assignmentId)) {
            log.warn("Assignment {} update blocked: Quiz questions changed but assignment has submissions.", assignmentId);
            throw new BusinessException("HAS_SUBMISSIONS",
                    "Bài tập đã có học viên nộp bài. Không thể cập nhật nội dung đề bài (câu hỏi). " +
                            "Chỉ được cập nhật thông tin cơ bản (tiêu đề, mô tả, hạn nộp...).");
        }

        // Save assignment
        Assignment savedAssignment = assignmentRepository.save(assignment);
        UUID finalAssignmentId = savedAssignment.getId();

        // If new assignment, bind it to the class target
        if (isNewAssignment && classId != null) {
            AssignmentTarget target = AssignmentTarget.builder()
                    .assignmentId(finalAssignmentId)
                    .classId(classId)
                    .assignedBy(teacherId)
                    .assignedAt(LocalDateTime.now())
                    .build();
            targetRepository.save(target);
        }

        // -- SYNC QUESTIONS (only when no submissions) --
        if (!hasSubmissions) {
            List<AssignmentQuestion> existingLinks = assignmentQuestionRepository
                    .findByAssignmentIdOrderByOrderIndex(finalAssignmentId);
            List<UUID> incomingQuestionIds = new ArrayList<>();

            if (request.getQuestions() != null) {
                for (int i = 0; i < request.getQuestions().size(); i++) {
                    QuizQuestionDraftDto qDto = request.getQuestions().get(i);

                    QuizQuestion q = (qDto.getId() != null)
                            ? questionRepository.findById(qDto.getId()).orElse(new QuizQuestion())
                            : new QuizQuestion();

                    q.setQuestionText(MarkdownUtils.sanitizeAndValidate(qDto.getQuestionText()));
                    q.setQuestionJapanese(MarkdownUtils.sanitizeAndValidate(qDto.getQuestionJapanese()));
                    q.setQuestionType(qDto.getQuestionType() != null ? qDto.getQuestionType() : "multiple_choice");
                    q.setOptions(sanitizeOptionsJson(qDto.getOptions()));
                    q.setCorrectAnswer(MarkdownUtils.sanitizeAndValidate(qDto.getCorrectAnswer()));
                    q.setCorrectAnswers(qDto.getCorrectAnswers());
                    q.setExplanation(MarkdownUtils.sanitizePlainExplanation(qDto.getExplanation()));
                    q.setExplanationVi(MarkdownUtils.sanitizePlainExplanation(qDto.getExplanationVi()));
                    q.setImageUrl(qDto.getImageUrl());
                    q.setJlptLevel(qDto.getJlptLevel());
                    q.setDifficultyScore(qDto.getDifficultyScore() != null ? qDto.getDifficultyScore() : 1);

                    if (q.getId() == null) {
                        q.setStatus(Course.ContentStatus.DRAFT);
                        q.setCreatedBy(teacherId);
                        q.setCreatedAt(LocalDateTime.now());
                    }
                    q.setUpdatedAt(LocalDateTime.now());

                    QuizQuestion savedQ = questionRepository.save(q);
                    incomingQuestionIds.add(savedQ.getId());

                    AssignmentQuestion link = assignmentQuestionRepository
                            .findByAssignmentIdAndQuestionId(finalAssignmentId, savedQ.getId())
                            .orElse(AssignmentQuestion.builder()
                                    .assignmentId(finalAssignmentId)
                                    .questionId(savedQ.getId())
                                    .build());

                    link.setOrderIndex(qDto.getOrderIndex() != null ? qDto.getOrderIndex() : i);
                    link.setPoints(qDto.getPoints() != null ? qDto.getPoints() : 10);
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
    public AssignmentResponse saveFullWriting(UUID assignmentId, UUID classId, SaveFullWritingRequest request,
                                              AssignmentType assignmentType, UUID teacherId) {
        if (assignmentType == AssignmentType.QUIZ) {
            throw new BusinessException("INVALID_TYPE", "Use saveFullQuiz for QUIZ assignments.");
        }

        Assignment assignment;
        boolean isNewAssignment = (assignmentId == null);
        boolean hasSubmissions = false;

        if (assignmentId == null) {
            // -- CREATE NEW --
            if (classId != null) {
                classRepository.findByIdAndNotDeleted(classId)
                        .orElseThrow(() -> new BusinessException("CLASS_NOT_FOUND", "Class not found."));
            }

            if (assignmentType == AssignmentType.TRANSLATION) {
                if (request.getTranslationDirection() != null
                        && !request.getTranslationDirection().equals("JA_TO_VI")
                        && !request.getTranslationDirection().equals("VI_TO_JA")) {
                    throw new BusinessException("INVALID_DIRECTION", "Translation direction must be JA_TO_VI or VI_TO_JA.");
                }
            }

            assignment = Assignment.builder()
                    .assignmentType(assignmentType)
                    .classId(classId)
                    .createdBy(teacherId)
                    .createdAt(LocalDateTime.now())
                    .hasSubmissions(false)
                    .build();
        } else {
            // -- UPDATE EXISTING --
            assignment = findAssignmentOrThrow(assignmentId);
            verifyTeacherOwnership(assignment, teacherId);

            if (assignment.isQuiz()) {
                throw new BusinessException("INVALID_TYPE", "Use saveFullQuiz for QUIZ assignments.");
            }

            hasSubmissions = Boolean.TRUE.equals(assignment.getHasSubmissions());

            // If there are submissions AND writing content ACTUALLY changed → reject
            if (hasSubmissions && hasWritingContentChanged(request, assignment)) {
                log.warn("Assignment {} update blocked: Writing content changed but assignment has submissions.", assignmentId);
                throw new BusinessException("HAS_SUBMISSIONS",
                        "Bài tập đã có học viên nộp bài. Không thể sửa nội dung đề bài. " +
                                "Chỉ được cập nhật thông tin cơ bản (tiêu đề, mô tả, hạn nộp...).");
            }
        }

        // Apply common metadata — includes all hasSubmissions validation rules
        applyCommonMetadata(assignment, request, hasSubmissions, teacherId);
        // Validate status transition for writing assignments
        if (request.getStatus() != null) {
            validateStatusTransition(assignment, request.getStatus(), null);
            assignment.setStatus(request.getStatus());
        } else if (isNewAssignment) {
            assignment.setStatus(AssignmentStatus.DRAFT);
        }

        // Retry settings — only update when no submissions
        if (!hasSubmissions) {
            assignment.setAllowRetry(request.getAllowRetry());
            assignment.setMaxRetries(request.getMaxRetries());
        }

        Assignment savedAssignment = assignmentRepository.save(assignment);
        UUID savedId = savedAssignment.getId();

        // If new assignment, bind it to the class target
        if (isNewAssignment && classId != null) {
            AssignmentTarget target = AssignmentTarget.builder()
                    .assignmentId(savedId)
                    .classId(classId)
                    .build();
            targetRepository.save(target);
        }

        // Save or update writing content — only when no submissions
        if (!hasSubmissions) {
            WritingAssignment content = writingAssignmentRepository.findByAssignmentId(savedId)
                    .orElse(WritingAssignment.builder()
                            .assignmentId(savedId)
                            .createdAt(LocalDateTime.now())
                            .build());

            content.setPrompt(request.getPrompt());
            content.setPromptJapanese(request.getPromptJapanese());
            content.setSampleAnswer(request.getSampleAnswer());
            content.setSampleAnswerJapanese(request.getSampleAnswerJapanese());
            content.setMinWords(request.getMinWords());
            content.setMaxWords(request.getMaxWords());
            content.setSourceText(request.getSourceText());
            content.setTranslationDirection(request.getTranslationDirection());

            writingAssignmentRepository.save(content);
        }

        log.info("UPSERT Full Writing ({}) completed for Assignment {} by Teacher {}",
                assignmentType, savedId, teacherId);
        return enrichAssignmentResponse(savedAssignment, teacherId);
    }

    @Override
    @Transactional
    public AssignmentResponse createAssignment(CreateAssignmentRequest request, UUID teacherId, UUID classId) {
        validateDueDate(request.getDueDate());

        if (classId != null) {
            classRepository.findByIdAndNotDeleted(classId)
                    .orElseThrow(() -> new BusinessException("CLASS_NOT_FOUND", "Class not found."));
        }

        Assignment assignment = mapper.toEntity(request);
        assignment.setClassId(classId);
        assignment.setCreatedBy(teacherId);
        assignment.setCreatedAt(LocalDateTime.now());
        assignment.setUpdatedAt(LocalDateTime.now());
        assignment.setStatus(AssignmentStatus.DRAFT);
        assignment.setHasSubmissions(false);

        Assignment saved = assignmentRepository.save(assignment);
        log.info("Assignment {} created by teacher {} for class {}", saved.getId(), teacherId, classId);
        return enrichAssignmentResponse(saved, teacherId);
    }


    @Override
    @Transactional
    public void deleteAssignment(UUID assignmentId, UUID teacherId) {
        Assignment assignment = findAssignmentOrThrow(assignmentId);
        verifyTeacherOwnership(assignment, teacherId);
        requireDraft(assignment);
        assignment.softDelete(teacherId);
        assignmentRepository.save(assignment);
        log.info("Assignment {} soft-deleted by teacher {}", assignmentId, teacherId);
    }

    @Override
    public AssignmentResponse getAssignment(UUID assignmentId) {
        return enrichAssignmentResponse(findAssignmentOrThrow(assignmentId), null);
    }

    @Override
    public Page<AssignmentSummaryResponse> getAssignmentsByClass(UUID classId, Pageable pageable) {
        return assignmentRepository.findByClassId(classId, pageable)
                .map(a -> buildSummaryResponse(a));
    }

    @Override
    public Page<AssignmentSummaryResponse> getMyAssignments(UUID teacherId, Pageable pageable) {
        return assignmentRepository.findByCreatedByAndNotDeleted(teacherId, pageable)
                .map(a -> buildSummaryResponse(a));
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE (TEACHER)
    // ═══════════════════════════════════════════════════════════════════════════


    @Override
    @Transactional
    public AssignmentResponse cloneAssignment(UUID sourceAssignmentId, CloneAssignmentRequest request, UUID teacherId) {
        // 1. Load & verify source assignment
        Assignment source = findAssignmentOrThrow(sourceAssignmentId);
        verifyTeacherOwnership(source, teacherId);

        // 2. Verify target class exists
        classRepository.findByIdAndNotDeleted(request.getTargetClassId())
                .orElseThrow(() -> new BusinessException("CLASS_NOT_FOUND", "Target class not found."));

        // 3. Build cloned assignment (new entity, DRAFT, no submissions)
        Assignment cloned = Assignment.builder()
                .title(request.getNewTitle() != null ? request.getNewTitle() : source.getTitle())
                .description(source.getDescription())
                .instructions(source.getInstructions())
                .assignmentType(source.getAssignmentType())
                .classId(request.getTargetClassId())
                .dueDate(request.getNewDueDate())
                .startDate(null)
                .timeLimitMinutes(source.getTimeLimitMinutes())
                .lateSubmissionAllowed(source.getLateSubmissionAllowed())
                .lateSubmissionPenaltyPercent(source.getLateSubmissionPenaltyPercent())
                .maxScore(source.getMaxScore())
                .passingScore(source.getPassingScore())
                .shuffleQuestions(source.getShuffleQuestions())
                .shuffleOptions(source.getShuffleOptions())
                .showCorrectAnswers(source.getShowCorrectAnswers())
                .allowRetry(source.getAllowRetry())
                .maxRetries(source.getMaxRetries())
                .lessonId(source.getLessonId())
                .questionCount(source.getQuestionCount())
                .status(AssignmentStatus.DRAFT)
                .hasSubmissions(false)
                .createdBy(teacherId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        Assignment savedClone = assignmentRepository.save(cloned);
        UUID clonedId = savedClone.getId();

        // 4. Deep-copy content based on assignment type
        if (source.isQuiz()) {
            cloneQuizQuestions(sourceAssignmentId, clonedId, teacherId);
        } else if (source.isWritingOrTranslation()) {
            cloneWritingContent(sourceAssignmentId, clonedId);
        }

        log.info("Assignment {} cloned to class {} as new assignment {} by teacher {}",
                sourceAssignmentId, request.getTargetClassId(), clonedId, teacherId);
        return enrichAssignmentResponse(savedClone, teacherId);
    }

    /**
     * Deep-copy quiz questions from source assignment to cloned assignment.
     * Each question is duplicated (new UUID) so edits to the clone don't affect the original.
     */
    private void cloneQuizQuestions(UUID sourceAssignmentId, UUID clonedAssignmentId, UUID teacherId) {
        List<AssignmentQuestion> sourceLinks = assignmentQuestionRepository
                .findByAssignmentIdOrderByOrderIndex(sourceAssignmentId);

        for (AssignmentQuestion sourceLink : sourceLinks) {
            QuizQuestion original = questionRepository.findById(sourceLink.getQuestionId()).orElse(null);
            if (original == null || original.isDeleted()) continue;

            // Deep-copy the question
            QuizQuestion copy = QuizQuestion.builder()
                    .questionText(original.getQuestionText())
                    .questionJapanese(original.getQuestionJapanese())
                    .questionType(original.getQuestionType())
                    .questionSubtype(original.getQuestionSubtype())
                    .options(original.getOptions())
                    .correctAnswer(original.getCorrectAnswer())
                    .correctAnswers(original.getCorrectAnswers())
                    .explanation(original.getExplanation())
                    .explanationVi(original.getExplanationVi())
                    .vocabularyId(original.getVocabularyId())
                    .grammarId(original.getGrammarId())
                    .lessonId(original.getLessonId())
                    .aiGenerated(original.getAiGenerated())
                    .sourcePdfUrl(original.getSourcePdfUrl())
                    .jlptLevel(original.getJlptLevel())
                    .difficultyScore(original.getDifficultyScore())
                    .tags(original.getTags())
                    .imageUrl(original.getImageUrl())
                    .status(Course.ContentStatus.DRAFT)
                    .createdBy(teacherId)
                    .createdAt(LocalDateTime.now())
                    .build();

            QuizQuestion savedCopy = questionRepository.save(copy);

            // Create link to cloned assignment
            AssignmentQuestion link = AssignmentQuestion.builder()
                    .assignmentId(clonedAssignmentId)
                    .questionId(savedCopy.getId())
                    .orderIndex(sourceLink.getOrderIndex())
                    .points(sourceLink.getPoints())
                    .build();
            assignmentQuestionRepository.save(link);
        }
    }

    /**
     * Copy writing content from source assignment to cloned assignment.
     */
    private void cloneWritingContent(UUID sourceAssignmentId, UUID clonedAssignmentId) {
        writingAssignmentRepository.findByAssignmentId(sourceAssignmentId).ifPresent(source -> {
            WritingAssignment copy = WritingAssignment.builder()
                    .assignmentId(clonedAssignmentId)
                    .prompt(source.getPrompt())
                    .promptJapanese(source.getPromptJapanese())
                    .sampleAnswer(source.getSampleAnswer())
                    .sampleAnswerJapanese(source.getSampleAnswerJapanese())
                    .minWords(source.getMinWords())
                    .maxWords(source.getMaxWords())
                    .sourceText(source.getSourceText())
                    .translationDirection(source.getTranslationDirection())
                    .createdAt(LocalDateTime.now())
                    .build();
            writingAssignmentRepository.save(copy);
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SUBMISSIONS — TEACHER VIEW
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public Page<SubmissionSummaryResponse> getSubmissionsByAssignment(UUID assignmentId, Pageable pageable) {
        findAssignmentOrThrow(assignmentId);
        return submissionRepository.findByAssignmentId(assignmentId, pageable)
                .map(s -> enrichSummaryResponse(s));
    }

    @Override
    public SubmissionResponse getSubmission(UUID submissionId) {
        return enrichSubmissionResponse(findSubmissionOrThrow(submissionId));
    }

    @Override
    @Transactional
    public SubmissionResponse gradeWritingSubmission(UUID submissionId, GradeWritingRequest request, UUID teacherId) {
        AssignmentSubmission submission = findSubmissionOrThrow(submissionId);
        Assignment assignment = findAssignmentOrThrow(submission.getAssignmentId());

        if (!assignment.isWritingOrTranslation()) {
            throw new BusinessException("INVALID_TYPE", "Use quiz grading for QUIZ assignments.");
        }
        if (submission.getStatus() != SubmissionStatus.SUBMITTED) {
            throw new BusinessException("INVALID_STATUS", "Only SUBMITTED submissions can be graded.");
        }

        submission.setTeacherScore(request.getTeacherScore());
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
    public Page<AssignmentSummaryResponse> getLearnerAssignments(UUID learnerId, Pageable pageable) {
        List<UUID> classIds = classMemberRepository.findActiveClassIdsByUserId(learnerId);
        return assignmentRepository.findPublishedForLearner(learnerId, classIds, pageable)
                .map(a -> buildSummaryResponse(a));
    }

    @Override
    public LearnerAssignmentResponse getAssignmentForLearner(UUID assignmentId, UUID learnerId) {
        Assignment assignment = findAssignmentOrThrow(assignmentId);
        if (assignment.getStatus() != AssignmentStatus.PUBLISHED) {
            throw new BusinessException("ASSIGNMENT_NOT_AVAILABLE", "This assignment is not available.");
        }

        // Check if learner belongs to the class
        UUID classId = assignment.getClassId();
        if (classId != null && !classMemberRepository.existsActiveByClassIdAndUserId(classId, learnerId)) {
            throw new BusinessException("ACCESS_DENIED", "You are not a member of the class for this assignment.");
        }

        return enrichLearnerAssignmentResponse(assignment, learnerId);
    }

    @Override
    @Transactional
    public SubmissionResponse startAssignment(UUID assignmentId, UUID learnerId) {
        Assignment assignment = findAssignmentOrThrow(assignmentId);

        if (assignment.getStatus() != AssignmentStatus.PUBLISHED) {
            throw new BusinessException("ASSIGNMENT_NOT_PUBLISHED", "This assignment is not available.");
        }
        if (assignment.getDueDate().isBefore(LocalDateTime.now()) && !Boolean.TRUE.equals(assignment.getLateSubmissionAllowed())) {
            throw new BusinessException("PAST_DUE", "This assignment deadline has passed.");
        }
        if (submissionRepository.hasActiveSubmission(assignmentId, learnerId)) {
            throw new BusinessException("ALREADY_IN_PROGRESS", "You already have an active attempt.");
        }

        long attemptCount = submissionRepository.countByAssignmentIdAndUserId(assignmentId, learnerId);
        if (!Boolean.TRUE.equals(assignment.getAllowRetry()) && attemptCount > 0) {
            throw new BusinessException("NO_RETRY", "This assignment does not allow retries.");
        }
        if (Boolean.TRUE.equals(assignment.getAllowRetry()) && attemptCount >= assignment.getMaxRetries()) {
            throw new BusinessException("MAX_RETRIES", "Maximum retry attempts reached.");
        }

        int totalQ = (int) assignmentQuestionRepository.countByAssignmentId(assignmentId);
        if (assignment.getAssignmentType() == AssignmentType.QUIZ) {
            if (totalQ == 0) totalQ = assignment.getQuestionCount() != null ? assignment.getQuestionCount() : 0;
        } else
            totalQ = 1;
        AssignmentSubmission submission = AssignmentSubmission.builder()
                .assignmentId(assignmentId)
                .userId(learnerId)
                .status(SubmissionStatus.IN_PROGRESS)
                .attemptNumber((int) attemptCount + 1)
                .startedAt(LocalDateTime.now())
                .totalQuestions(totalQ)
                .answers("[]")
                .correctCount(0)
                .timeSpentSeconds(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        submissionRepository.save(submission);

        // Mark assignment as having submissions
        if (!Boolean.TRUE.equals(assignment.getHasSubmissions())) {
            assignment.setHasSubmissions(true);
            assignment.setUpdatedAt(LocalDateTime.now());
            assignmentRepository.save(assignment);
        }

        log.info("Learner {} started assignment {}, attempt #{}", learnerId, assignmentId, submission.getAttemptNumber());
        return enrichSubmissionResponse(submission);
    }

    @Override
    @Transactional
    public SubmissionResponse submitQuiz(SubmitAssignmentRequest request, UUID learnerId) {
        AssignmentSubmission submission = findSubmissionOrThrow(request.getSubmissionId());

        if (!submission.getUserId().equals(learnerId)) {
            throw new BusinessException("ACCESS_DENIED", "Cannot submit on behalf of another learner.");
        }
        if (!submission.isInProgress()) {
            throw new BusinessException("INVALID_STATUS", "This submission is not in progress.");
        }

        Assignment assignment = findAssignmentOrThrow(submission.getAssignmentId());
        if (!assignment.isQuiz()) {
            throw new BusinessException("INVALID_TYPE", "Use submitWriting for non-quiz assignments.");
        }

        // Auto-score: compare submitted answers with correct answers
        String answersJson = request.getAnswers();
        AutoScoreResult scored = autoScore(answersJson, submission.getAssignmentId());

        // Map to final score on maxScore scale
        BigDecimal finalScore = BigDecimal.ZERO;
        if (scored.totalPoints > 0) {
            double ratio = (double) scored.earnedPoints / scored.totalPoints;
            finalScore = BigDecimal.valueOf(ratio * assignment.getMaxScore())
                    .setScale(2, RoundingMode.HALF_UP);
        }

        submission.setAnswers(answersJson);
        submission.setCorrectCount(scored.correctCount);
        submission.setAiAutoScore(finalScore);
        submission.setScore(finalScore);
        submission.setTimeSpentSeconds(request.getTimeSpentSeconds());
        submission.setSubmittedAt(LocalDateTime.now());
        submission.setUpdatedAt(LocalDateTime.now());

        // Past due date and late not allowed → OVERDUE
        if (assignment.getDueDate().isBefore(LocalDateTime.now()) && !Boolean.TRUE.equals(assignment.getLateSubmissionAllowed())) {
            submission.setStatus(SubmissionStatus.OVERDUE);
        } else {
            submission.setStatus(SubmissionStatus.GRADED); // Quiz: auto-graded immediately
        }

        submissionRepository.save(submission);
        log.info("Learner {} submitted quiz {}, score={}", learnerId, submission.getAssignmentId(), finalScore);
        return enrichSubmissionResponse(submission);
    }

    @Override
    @Transactional
    public SubmissionResponse submitWriting(UUID assignmentId, SubmitWritingRequest request, MultipartFile[] images, UUID learnerId) {
        boolean hasText = request.getWrittenAnswer() != null && !request.getWrittenAnswer().isBlank();
        boolean hasImages = images != null && images.length > 0
                && java.util.Arrays.stream(images).anyMatch(f -> f != null && !f.isEmpty());

        if (!hasText && !hasImages) {
            throw new BusinessException("INVALID_INPUT",
                    "Either written answer or at least one image must be provided.");
        }

        if (hasImages && images.length > MAX_SUBMISSION_IMAGES) {
            throw new BusinessException("TOO_MANY_IMAGES",
                    "Maximum " + MAX_SUBMISSION_IMAGES + " images allowed per submission.");
        }

        // Find the active IN_PROGRESS submission for this assignment+learner
        List<AssignmentSubmission> active = submissionRepository
                .findByAssignmentIdAndUserIdOrderByAttemptDesc(assignmentId, learnerId);

        AssignmentSubmission submission = active.stream()
                .filter(s -> s.getStatus() == SubmissionStatus.IN_PROGRESS)
                .findFirst()
                .orElseThrow(() -> new BusinessException("NO_ACTIVE_SUBMISSION",
                        "No active submission found. Please start the assignment first."));

        if (!submission.getUserId().equals(learnerId)) {
            throw new BusinessException("ACCESS_DENIED", "Cannot submit on behalf of another learner.");
        }

        Assignment assignment = findAssignmentOrThrow(assignmentId);
        if (assignment.isQuiz()) {
            throw new BusinessException("INVALID_TYPE", "Use submitQuiz for QUIZ assignments.");
        }

        // Upload images to Cloudinary
        if (hasImages) {
            List<String> imageUrls = uploadSubmissionImages(images, learnerId, assignmentId);
            try {
                submission.setImageUrls(objectMapper.writeValueAsString(imageUrls));
            } catch (Exception e) {
                log.error("Failed to serialize image URLs", e);
                throw new BusinessException("INTERNAL_ERROR", "Failed to process uploaded images.");
            }
        }

        submission.setWrittenAnswer(request.getWrittenAnswer());
        submission.setTimeSpentSeconds(request.getTimeSpentSeconds());
        submission.setSubmittedAt(LocalDateTime.now());
        submission.setUpdatedAt(LocalDateTime.now());

        if (assignment.getDueDate().isBefore(LocalDateTime.now()) && !Boolean.TRUE.equals(assignment.getLateSubmissionAllowed())) {
            submission.setStatus(SubmissionStatus.OVERDUE);
        } else {
            submission.setStatus(SubmissionStatus.SUBMITTED); // waiting for teacher grading
        }

        submissionRepository.save(submission);
        log.info("Learner {} submitted writing for assignment {} (images: {})",
                learnerId, assignmentId, hasImages ? images.length : 0);
        return enrichSubmissionResponse(submission);
    }

    @Override
    public Page<SubmissionSummaryResponse> getMySubmissions(UUID learnerId, Pageable pageable) {
        return submissionRepository.findByUserId(learnerId, pageable).map(s -> enrichSummaryResponse(s));
    }

    @Override
    public SubmissionResponse getMySubmission(UUID submissionId, UUID learnerId) {
        AssignmentSubmission submission = findSubmissionOrThrow(submissionId);
        if (!submission.getUserId().equals(learnerId)) {
            throw new BusinessException("ACCESS_DENIED", "You can only view your own submissions.");
        }
        return enrichSubmissionResponse(submission);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private Assignment findAssignmentOrThrow(UUID id) {
        return assignmentRepository.findByIdAndNotDeleted(id)
                .orElseThrow(AssignmentNotFoundException::new);
    }


    private AssignmentSubmission findSubmissionOrThrow(UUID id) {
        return submissionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Submission not found."));
    }

    private void requireDraft(Assignment assignment) {
        if (assignment.getStatus() != AssignmentStatus.DRAFT) {
            throw new BusinessException("INVALID_STATUS", "This operation is only allowed for DRAFT assignments.");
        }
    }

    private void validateDueDate(LocalDateTime dueDate) {
        if (dueDate != null && dueDate.isBefore(LocalDateTime.now())) {
            throw new BusinessException("INVALID_DUE_DATE", "Due date must be in the future.");
        }
    }

    private void verifyTeacherOwnership(Assignment assignment, UUID teacherId) {
        if (!assignment.getCreatedBy().equals(teacherId)) {
            throw new BusinessException("ACCESS_DENIED", "You can only manage your own assignments.");
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
                    throw new BusinessException("UPLOAD_FAILED",
                            "Failed to upload image: " + image.getOriginalFilename());
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
        r.setSubmissionCount(
                submissionRepository.countByAssignmentIdAndStatus(a.getId(), SubmissionStatus.SUBMITTED.name())
                        + submissionRepository.countByAssignmentIdAndStatus(a.getId(), SubmissionStatus.GRADED.name()));
        return r;
    }

    private AssignmentResponse enrichAssignmentResponse(Assignment assignment, UUID requesterId) {
        AssignmentResponse response = mapper.toResponse(assignment);
        List<AssignmentQuestion> aqs = assignmentQuestionRepository
                .findByAssignmentIdOrderByOrderIndex(assignment.getId());
        response.setActualQuestionCount((long) aqs.size());

        List<AssignmentQuestionResponse> qList = aqs.stream().map(aq ->
                questionRepository.findById(aq.getQuestionId()).map(q -> {
                    AssignmentQuestionResponse qr = mapper.toAssignmentQuestionResponse(q);
                    qr.setAssignmentQuestionId(aq.getId());
                    qr.setOrderIndex(aq.getOrderIndex());
                    qr.setPoints(aq.getPoints());
                    return qr;
                }).orElse(new AssignmentQuestionResponse())
        ).toList();
        response.setQuestions(qList);

        response.setSubmissionCount(
                submissionRepository.countByAssignmentIdAndStatus(assignment.getId(), SubmissionStatus.SUBMITTED.name())
                        + submissionRepository.countByAssignmentIdAndStatus(assignment.getId(), SubmissionStatus.GRADED.name()));

        // Enrich writing content for WRITING / TRANSLATION assignments
        if (assignment.isWritingOrTranslation()) {
            writingAssignmentRepository.findByAssignmentId(assignment.getId())
                    .ifPresent(wc -> response.setWritingContent(mapper.toResponse(wc)));
        }

        return response;
    }

    private LearnerAssignmentResponse enrichLearnerAssignmentResponse(Assignment assignment, UUID learnerId) {
        LearnerAssignmentResponse response = mapper.toLearnerResponse(assignment);

        List<AssignmentQuestion> aqs = assignmentQuestionRepository
                .findByAssignmentIdOrderByOrderIndex(assignment.getId());
        response.setActualQuestionCount((long) aqs.size());

        List<LearnerQuizQuestionResponse> qList = aqs.stream().map(aq ->
                questionRepository.findById(aq.getQuestionId()).map(q -> {
                    LearnerQuizQuestionResponse qr = mapper.toLearnerAssignmentQuestionResponse(q);
                    qr.setAssignmentQuestionId(aq.getId());
                    qr.setOrderIndex(aq.getOrderIndex());
                    qr.setPoints(aq.getPoints());
                    return qr;
                }).orElse(new LearnerQuizQuestionResponse())
        ).toList();
        response.setQuestions(qList);

        response.setSubmissionCount(
                submissionRepository.countByAssignmentIdAndStatus(assignment.getId(), SubmissionStatus.SUBMITTED.name())
                        + submissionRepository.countByAssignmentIdAndStatus(assignment.getId(), SubmissionStatus.GRADED.name()));

        // Enrichment for non-quiz
        if (assignment.isWritingOrTranslation()) {
            writingAssignmentRepository.findByAssignmentId(assignment.getId())
                    .ifPresent(wc -> response.setWritingContent(mapper.toResponse(wc)));
        }

        return response;
    }

    private SubmissionResponse enrichSubmissionResponse(AssignmentSubmission s) {
        SubmissionResponse response = mapper.toResponse(s);
        assignmentRepository.findByIdAndNotDeleted(s.getAssignmentId()).ifPresent(a -> {
            response.setAssignmentTitle(a.getTitle());
            if (s.getScore() != null) {
                response.setPassed(s.isPassed(a.getPassingScore()));
            }
        });
        userRepository.findById(s.getUserId()).ifPresent(u -> {
            response.setUserFullName(u.getFullName());
            response.setUserEmail(u.getEmail());
        });
        // Parse imageUrls JSON string → List<String>
        response.setImageUrls(parseImageUrls(s.getImageUrls()));
        return response;
    }

    private SubmissionSummaryResponse enrichSummaryResponse(AssignmentSubmission s) {
        SubmissionSummaryResponse r = mapper.toSummaryResponse(s);
        assignmentRepository.findByIdAndNotDeleted(s.getAssignmentId())
                .ifPresent(a -> {
                    r.setAssignmentTitle(a.getTitle());
                    if (s.getScore() != null) {
                        r.setPassed(s.isPassed(a.getPassingScore()));
                    }
                });
        userRepository.findById(s.getUserId()).ifPresent(u -> r.setUserFullName(u.getFullName()));
        return r;
    }

    // ── Auto-Score for Quiz ───────────────────────────────────────────────────

    private record AutoScoreResult(int correctCount, int earnedPoints, int totalPoints) {
    }

    private AutoScoreResult autoScore(String answersJson, UUID assignmentId) {
        List<AssignmentQuestion> aqs = assignmentQuestionRepository
                .findByAssignmentIdOrderByOrderIndex(assignmentId);

        int correct = 0, earned = 0, total = 0;

        try {
            JsonNode answersArray = objectMapper.readTree(answersJson);
            for (AssignmentQuestion aq : aqs) {
                int points = aq.getPoints() != null ? aq.getPoints() : 10;
                total += points;

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
                    correct++;
                    earned += points;
                }
            }
        } catch (Exception e) {
        }

        return new AutoScoreResult(correct, earned, total);
    }

    private boolean checkAnswer(QuizQuestion question, String selectedAnswer) {
        try {
            return switch (question.getQuestionType()) {
                case "multiple_choice" -> {
                    // Check options array for isCorrect=true and match id
                    if (question.getOptions() == null) yield false;
                    JsonNode opts = objectMapper.readTree(question.getOptions());
                    for (JsonNode opt : opts) {
                    if (opt.path("id").asText("").equalsIgnoreCase(selectedAnswer)
                        && opt.path("isCorrect").asBoolean(false)) {
                        yield true;
                    }
                    }
                    yield false;
                }
                case "fill_blank", "true_false" -> question.getCorrectAnswer() != null
                        && question.getCorrectAnswer().trim().equalsIgnoreCase(selectedAnswer.trim());
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
     * maxScore: locked — changing it would invalidate existing graded submissions
     * passingScore: can only be reduced (more lenient), never raised
     *
     */
    private void applyCommonMetadata(Assignment assignment, AssignmentMetadataRequest request,
                                     boolean hasSubmissions, UUID updatedBy) {
        if (hasSubmissions) {
            // dueDate: only allow extending
            if (request.getDueDate() != null && assignment.getDueDate() != null
                    && request.getDueDate().isBefore(assignment.getDueDate())) {
                throw new BusinessException("INVALID_DUE_DATE",
                        "Bài tập đã có học viên nộp bài. Chỉ được gia hạn deadline, không được rút ngắn.");
            }
            // maxScore: locked
            if (request.getMaxScore() != null && assignment.getMaxScore() != null
                    && !request.getMaxScore().equals(assignment.getMaxScore())) {
                log.warn("Assignment {} update blocked: maxScore changed from {} to {} but assignment has submissions.",
                        assignment.getId(), assignment.getMaxScore(), request.getMaxScore());
                throw new BusinessException("HAS_SUBMISSIONS",
                        "Bài tập đã có học viên nộp bài. Không thể thay đổi điểm tối đa.");
            }
            // passingScore: only allow reducing
            if (request.getPassingScore() != null && assignment.getPassingScore() != null
                    && request.getPassingScore() > assignment.getPassingScore()) {
                log.warn("Assignment {} update blocked: passingScore increased from {} to {} but assignment has submissions.",
                        assignment.getId(), assignment.getPassingScore(), request.getPassingScore());
                throw new BusinessException("HAS_SUBMISSIONS",
                        "Bài tập đã có học viên nộp bài. Không thể tăng điểm đạt (Chỉ được hạ xuống).");
            }
        }

        // Rule 2: passingScore must always be <= maxScore
        Integer effectiveMax = request.getMaxScore() != null ? request.getMaxScore() : assignment.getMaxScore();
        Integer effectivePassing = request.getPassingScore() != null ? request.getPassingScore() : assignment.getPassingScore();
        if (effectiveMax != null && effectivePassing != null && effectivePassing > effectiveMax) {
            throw new BusinessException("INVALID_PASSING_SCORE",
                    "Điểm đạt (" + effectivePassing + ") không được vượt quá điểm tối đa (" + effectiveMax + ").");
        }

        assignment.setTitle(request.getTitle());
        assignment.setDescription(request.getDescription());
        assignment.setInstructions(request.getInstructions());
        assignment.setDueDate(request.getDueDate());
        assignment.setTimeLimitMinutes(request.getTimeLimitMinutes());
        assignment.setLateSubmissionAllowed(request.getLateSubmissionAllowed());
        assignment.setMaxScore(request.getMaxScore());
        assignment.setPassingScore(request.getPassingScore());
        assignment.setUpdatedBy(updatedBy);
        assignment.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * Rule 1: Validates assignment status transitions.
     * <ul>
     *   <li>DRAFT → PUBLISHED: requires content (questions for quiz, prompt for writing) and dueDate set</li>
     *   <li>PUBLISHED → DRAFT: blocked (cannot unpublish)</li>
     *   <li>CLOSED → any: blocked (terminal state)</li>
     * </ul>
     *
     * @param questionCount for QUIZ publishing validation — pass null for writing types
     */
    private void validateStatusTransition(Assignment assignment, AssignmentStatus newStatus, Integer questionCount) {
        AssignmentStatus current = assignment.getStatus();
        if (current == null || current == newStatus) return; // no change

        // CLOSED is terminal — no transitions out
        if (current == AssignmentStatus.CLOSED) {
            throw new BusinessException("INVALID_STATUS_TRANSITION",
                    "Bài tập đã đóng (CLOSED). Không thể chuyển sang trạng thái khác.");
        }

        // PUBLISHED → DRAFT: blocked
        if (current == AssignmentStatus.PUBLISHED && newStatus == AssignmentStatus.DRAFT) {
            throw new BusinessException("INVALID_STATUS_TRANSITION",
                    "Không thể chuyển bài tập từ PUBLISHED về DRAFT.");
        }

        // DRAFT → PUBLISHED: validate content and dueDate
        if (current == AssignmentStatus.DRAFT && newStatus == AssignmentStatus.PUBLISHED) {
            if (assignment.getDueDate() == null) {
                throw new BusinessException("MISSING_DUE_DATE",
                        "Phải đặt hạn nộp (dueDate) trước khi xuất bản bài tập.");
            }
            if (assignment.getDueDate().isBefore(LocalDateTime.now())) {
                throw new BusinessException("PAST_DUE_DATE",
                        "Hạn nộp đã qua. Không thể xuất bản bài tập với deadline trong quá khứ.");
            }
            if (assignment.isQuiz()) {
                int count = questionCount != null ? questionCount : 0;
                if (count == 0) {
                    throw new BusinessException("NO_QUESTIONS",
                            "Bài kiểm tra phải có ít nhất 1 câu hỏi trước khi xuất bản.");
                }
            } else {
                // Writing/Translation: check prompt exists
                boolean hasContent = writingAssignmentRepository.findByAssignmentId(assignment.getId())
                        .map(w -> w.getPrompt() != null && !w.getPrompt().isBlank())
                        .orElse(false);
                if (!hasContent) {
                    throw new BusinessException("NO_WRITING_CONTENT",
                            "Bài viết phải có đề bài (prompt) trước khi xuất bản.");
                }
            }
        }
    }

    /**
     * Returns true if the writing request contains any content fields
     * (prompt, sampleAnswer, sourceText, etc.) that would modify the assignment's core content.
     */
    private boolean hasWritingContent(SaveFullWritingRequest request) {
        return request.getPrompt() != null
                || request.getPromptJapanese() != null
                || request.getSampleAnswer() != null
                || request.getSampleAnswerJapanese() != null
                || request.getSourceText() != null
                || request.getMinWords() != null
                || request.getMaxWords() != null
                || request.getTranslationDirection() != null;
    }

    /**
     * Deep compare writing content from request with what's mathematically saved in the database
     */
    private boolean hasWritingContentChanged(SaveFullWritingRequest request, Assignment assignment) {
        WritingAssignment current = writingAssignmentRepository.findByAssignmentId(assignment.getId()).orElse(null);
        if (current == null) {
            return hasWritingContent(request); // If it historically didn't exist, any content means it changed
        }

        if (!Objects.equals(current.getPrompt(), request.getPrompt())) { log.warn("Writing content changed: prompt. Incoming={}, Existing={}", request.getPrompt(), current.getPrompt()); return true; }
        if (!Objects.equals(current.getPromptJapanese(), request.getPromptJapanese())) { log.warn("Writing content changed: promptJapanese. Incoming={}, Existing={}", request.getPromptJapanese(), current.getPromptJapanese()); return true; }
        if (!Objects.equals(current.getSampleAnswer(), request.getSampleAnswer())) { log.warn("Writing content changed: sampleAnswer. Incoming={}, Existing={}", request.getSampleAnswer(), current.getSampleAnswer()); return true; }
        if (!Objects.equals(current.getSampleAnswerJapanese(), request.getSampleAnswerJapanese())) { log.warn("Writing content changed: sampleAnswerJapanese. Incoming={}, Existing={}", request.getSampleAnswerJapanese(), current.getSampleAnswerJapanese()); return true; }
        if (!Objects.equals(current.getSourceText(), request.getSourceText())) { log.warn("Writing content changed: sourceText. Incoming={}, Existing={}", request.getSourceText(), current.getSourceText()); return true; }
        if (!Objects.equals(current.getMinWords(), request.getMinWords())) { log.warn("Writing content changed: minWords. Incoming={}, Existing={}", request.getMinWords(), current.getMinWords()); return true; }
        if (!Objects.equals(current.getMaxWords(), request.getMaxWords())) { log.warn("Writing content changed: maxWords. Incoming={}, Existing={}", request.getMaxWords(), current.getMaxWords()); return true; }
        if (!Objects.equals(current.getTranslationDirection(), request.getTranslationDirection())) { log.warn("Writing content changed: translationDirection. Incoming={}, Existing={}", request.getTranslationDirection(), current.getTranslationDirection()); return true; }
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

            Integer incPoints = incoming.getPoints() != null ? incoming.getPoints() : 10;
            if (!Objects.equals(incPoints, existingLink.getPoints())) {
                log.warn("Quiz questions changed: points mismatch for question {} (incoming={}, existing={})", incoming.getId(), incPoints, existingLink.getPoints());
                return true;
            }

            // 4. Question inner values changed
            QuizQuestion existing = existingQuestionsMap.get(incoming.getId());
            if (existing == null) {
                log.warn("Quiz questions changed: question {} not found in database", incoming.getId());
                return true;
            }

            String incType = incoming.getQuestionType() != null ? incoming.getQuestionType() : "multiple_choice";
            Integer incDiff = incoming.getDifficultyScore() != null ? incoming.getDifficultyScore() : 1;

            if (!Objects.equals(incoming.getQuestionText(), existing.getQuestionText())) { log.warn("Quiz question {} changed: questionText. Incoming={}, Existing={}", incoming.getId(), incoming.getQuestionText(), existing.getQuestionText()); return true; }
            if (!Objects.equals(incoming.getQuestionJapanese(), existing.getQuestionJapanese())) { log.warn("Quiz question {} changed: questionJapanese. Incoming={}, Existing={}", incoming.getId(), incoming.getQuestionJapanese(), existing.getQuestionJapanese()); return true; }
            if (!Objects.equals(incType, existing.getQuestionType())) { log.warn("Quiz question {} changed: questionType. Incoming={}, Existing={}", incoming.getId(), incType, existing.getQuestionType()); return true; }

            // Options are JSON strings. String comparison will fail if keys are just ordered differently.
            if (!isJsonEqual(incoming.getOptions(), existing.getOptions())) {
                log.warn("Quiz question {} changed: options JSON mismatch. Incoming={}, Existing={}", incoming.getId(), incoming.getOptions(), existing.getOptions());
                return true;
            }

            if (!Objects.equals(incoming.getCorrectAnswer(), existing.getCorrectAnswer())) { log.warn("Quiz question {} changed: correctAnswer. Incoming={}, Existing={}", incoming.getId(), incoming.getCorrectAnswer(), existing.getCorrectAnswer()); return true; }
            if (!Objects.equals(incoming.getCorrectAnswers(), existing.getCorrectAnswers())) { log.warn("Quiz question {} changed: correctAnswers. Incoming={}, Existing={}", incoming.getId(), incoming.getCorrectAnswers(), existing.getCorrectAnswers()); return true; }
            if (!Objects.equals(incoming.getExplanation(), existing.getExplanation())) { log.warn("Quiz question {} changed: explanation. Incoming={}, Existing={}", incoming.getId(), incoming.getExplanation(), existing.getExplanation()); return true; }
            if (!Objects.equals(incoming.getExplanationVi(), existing.getExplanationVi())) { log.warn("Quiz question {} changed: explanationVi. Incoming={}, Existing={}", incoming.getId(), incoming.getExplanationVi(), existing.getExplanationVi()); return true; }
            if (!Objects.equals(incoming.getImageUrl(), existing.getImageUrl())) { log.warn("Quiz question {} changed: imageUrl. Incoming={}, Existing={}", incoming.getId(), incoming.getImageUrl(), existing.getImageUrl()); return true; }
            if (!Objects.equals(incoming.getJlptLevel(), existing.getJlptLevel())) { log.warn("Quiz question {} changed: jlptLevel. Incoming={}, Existing={}", incoming.getId(), incoming.getJlptLevel(), existing.getJlptLevel()); return true; }
            if (!Objects.equals(incDiff, existing.getDifficultyScore())) { log.warn("Quiz question {} changed: difficultyScore. Incoming={}, Existing={}", incoming.getId(), incDiff, existing.getDifficultyScore()); return true; }
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
}

