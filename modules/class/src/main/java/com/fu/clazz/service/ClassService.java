package com.fu.clazz.service;

import com.fu.clazz.entity.ClassStatus;
import com.fu.clazz.dto.request.AddMemberRequest;
import com.fu.clazz.dto.request.AddMembersRequest;
import com.fu.clazz.dto.request.ApproveImportClassesRequest;
import com.fu.clazz.dto.request.CreateClassRequest;
import com.fu.clazz.dto.request.UpdateClassRequest;
import com.fu.clazz.dto.response.ClassMemberResponse;
import com.fu.clazz.dto.response.ClassResponse;
import com.fu.clazz.dto.response.ClassSummaryResponse;
import com.fu.clazz.dto.response.ImportClassesPreview;
import com.fu.clazz.dto.response.ImportClassesResult;
import com.fu.clazz.dto.response.LearnerClassResponse;
import com.fu.clazz.dto.response.TeacherClassHistoryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Service interface for Class management
 */
public interface ClassService {
    ClassResponse closeClass(UUID classId, UUID managerId);

    /**
     * Create a new class (MANAGER only)
     */
    ClassResponse createClass(CreateClassRequest request, UUID managerId);

    /**
     * Update class (MANAGER only)
     */
    ClassResponse updateClass(UUID classId, UpdateClassRequest request, UUID managerId);

    /**
     * Get class by ID
     */
    ClassResponse getClassById(UUID classId);

    /**
     * Get classes with dynamic filters (combines all options with AND)
     */
    Page<ClassSummaryResponse> getClasses(String keyword, UUID teacherId, UUID courseId, ClassStatus status,
                                          Pageable pageable);

    /**
     * Delete a class (MANAGER only) — soft delete: records deletion metadata
     * (deletedAt, deletedBy). Does NOT change isActive status.
     */
    ClassResponse deleteClass(UUID classId, UUID managerId);

    /**
     * Add a member to a class (MANAGER only)
     */
    ClassMemberResponse addMember(UUID classId, AddMemberRequest request, UUID managerId);

    /**
     * Add multiple members to a class in a batch operation (MANAGER only).
     * All-or-nothing transaction: if any validation fails, no members are added.
     * 
     * Validation:
     * - Each learner must have status = INACTIVE
     * - Each learner must not have an active ClassMember record (removedAt must be null or not exist)
     * - Total members after adding must not exceed maxStudents
     * 
     * @param classId the class to add members to
     * @param request contains list of learnerIds to add
     * @param managerId the manager performing the operation
     * @return list of ClassMemberResponse for all added members
     * @throws com.fu.common.exception.ResourceNotFoundException if class or any learner not found
     * @throws com.fu.common.exception.BusinessException if learner is not INACTIVE, already active member, or would exceed capacity
     */
    List<ClassMemberResponse> addMembers(UUID classId, com.fu.clazz.dto.request.AddMembersRequest request, UUID managerId);

    /**
     * Remove a member from a class (MANAGER only)
     */
    void removeMember(UUID classId, UUID userId, UUID managerId);

    /**
     * Get active members of a class with optional keyword search
     */
    Page<ClassMemberResponse> getClassMembers(UUID classId, String keyword, Pageable pageable);

    /**
     * Get class by ID for teacher (validates teacher ownership)
     */
    ClassResponse getClassByIdForTeacher(UUID classId, UUID teacherId);

    /**
     * Get class members for teacher (validates teacher ownership) with optional keyword search
     */
    Page<ClassMemberResponse> getClassMembersForTeacher(UUID classId, UUID teacherId, String keyword, Pageable pageable);

    /**
     * Get eligible learners to add to a class (Active or Inactive, and not currently an active member)
     * Optional filtering by a closed class to show members from that specific class
     * 
     * @param classId the target class ID to add members to
     * @param keyword optional search term
     * @param filterByClassId optional closed class ID to filter eligible learners by
     * @param pageable pagination parameters
     * @return paginated list of eligible learners
     */
    Page<com.fu.user.dto.UserResponse> getEligibleLearners(UUID classId, String keyword, UUID filterByClassId, Pageable pageable);

    /**
     * Get learner's enrolled classes (ACTIVE or CLOSED status, active membership only)
     * Returns classes where ClassMember.removedAt IS NULL
     */
    List<LearnerClassResponse> getLearnerClasses(UUID learnerId);

    /**
     * Get all classes a teacher has taught or is currently teaching
     * Returns paginated list sorted by assignment date (descending)
     * 
     * @param teacherId the teacher UUID
     * @param pageable pagination parameters
     * @return paginated list of class information
     */
    Page<TeacherClassHistoryResponse> getTeacherClassHistory(UUID teacherId, Pageable pageable);

    /**
     * Step 1: Import classes from Excel file (Preview)
     * Reads Excel file, validates data, and returns preview for client confirmation.
     * Does not create anything - only validates and shows what would be created.
     * 
     * @param file Excel file (.xlsx or .xls)
     * @param managerId ID of the manager performing import
     * @return ImportClassesPreview containing validation results and data for step 2
     * @throws Exception if file read fails
     */
    ImportClassesPreview importClassesFromExcel(MultipartFile file, UUID managerId) throws Exception;

    /**
     * Step 2: Approve and confirm class import
     * Creates new classes and updates existing ones based on preview data from Step 1.
     * 
     * @param request ApproveImportClassesRequest containing classes to create
     * @param managerId ID of the manager confirming import
     * @return ImportClassesResult with final counts of created/updated/failed
     */
    ImportClassesResult approveImportClasses(ApproveImportClassesRequest request, UUID managerId);

    /**
     * Get class progress and learner performance dashboard data.
     * Returns aggregated class metrics, learning trend data, and individual learner progress.
     * Calculated in real-time on each request.
     * 
     * @param classId the class ID
     * @return ClassProgressResponse containing all dashboard data
     * @throws com.fu.common.exception.ResourceNotFoundException if class not found
     */
    com.fu.clazz.dto.response.ClassProgressResponse getClassProgress(UUID classId);
}
