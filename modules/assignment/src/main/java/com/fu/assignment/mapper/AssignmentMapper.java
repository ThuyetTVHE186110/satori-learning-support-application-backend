package com.fu.assignment.mapper;

import com.fu.assignment.dto.request.CreateAssignmentRequest;
import com.fu.assignment.dto.request.CreateQuizQuestionRequest;
import com.fu.assignment.dto.request.UpdateAssignmentRequest;
import com.fu.assignment.dto.response.*;
import com.fu.assignment.entity.*;
import org.mapstruct.*;

/**
 * MapStruct mapper for Assignment module entities.
 */
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public abstract class AssignmentMapper {

    @Autowired
    protected ObjectMapper objectMapper;

    // ── Assignment ────────────────────────────────────────────────────────────

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    public abstract Assignment toEntity(CreateAssignmentRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "assignmentType", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    public abstract void updateEntity(@MappingTarget Assignment assignment, UpdateAssignmentRequest request);

    public abstract AssignmentSummaryResponse toSummaryResponse(Assignment assignment);

    public abstract AssignmentResponse toResponse(Assignment assignment);

    public abstract LearnerAssignmentResponse toLearnerResponse(Assignment assignment);

    // ── QuizQuestion ──────────────────────────────────────────────────────────

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    public abstract QuizQuestion toEntity(CreateQuizQuestionRequest request);

    @Mapping(target = "options", expression = "java(toJsonNode(question.getOptions()))")
    @Mapping(target = "correctAnswers", expression = "java(toJsonNode(question.getCorrectAnswers()))")
    public abstract QuizQuestionResponse toResponse(QuizQuestion question);

    @Mapping(target = "options", expression = "java(toJsonNode(question.getOptions()))")
    public abstract LearnerQuizQuestionResponse toLearnerQuestionResponse(QuizQuestion question);

    @Mapping(target = "assignmentQuestionId", ignore = true)
    @Mapping(target = "orderIndex", ignore = true)
    @Mapping(target = "points", ignore = true)
    @Mapping(source = "id", target = "questionId")
    @Mapping(target = "options", expression = "java(toJsonNode(question.getOptions()))")
    @Mapping(target = "correctAnswers", expression = "java(toJsonNode(question.getCorrectAnswers()))")
    public abstract AssignmentQuestionResponse toAssignmentQuestionResponse(QuizQuestion question);

    @Mapping(target = "assignmentQuestionId", ignore = true)
    @Mapping(target = "orderIndex", ignore = true)
    @Mapping(target = "points", ignore = true)
    @Mapping(source = "id", target = "questionId")
    @Mapping(target = "options", expression = "java(toJsonNode(question.getOptions()))")
    public abstract LearnerQuizQuestionResponse toLearnerAssignmentQuestionResponse(QuizQuestion question);

    // ── AssignmentSubmission ──────────────────────────────────────────────────

    @Mapping(target = "imageUrls", ignore = true) // String→List handled manually in enrichSubmissionResponse
    public abstract SubmissionResponse toResponse(AssignmentSubmission submission);

    public abstract SubmissionSummaryResponse toSummaryResponse(AssignmentSubmission submission);

    // ── AssignmentTarget ──────────────────────────────────────────────────────

    public abstract AssignmentTargetResponse toResponse(AssignmentTarget target);

    // ── WritingAssignment ─────────────────────────────────────────────────────

    public abstract WritingAssignmentResponse toResponse(WritingAssignment content);
    
    // ── Utility ───────────────────────────────────────────────────────────────

    protected JsonNode toJsonNode(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
