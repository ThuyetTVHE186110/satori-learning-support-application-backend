package com.fu.clazz.service;

import com.fu.clazz.dto.request.AddMemberRequest;
import com.fu.clazz.dto.request.CreateClassRequest;
import com.fu.clazz.dto.request.UpdateClassRequest;
import com.fu.clazz.dto.response.ClassMemberResponse;
import com.fu.clazz.dto.response.ClassResponse;
import com.fu.clazz.dto.response.ClassSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Service interface for Class management
 */
public interface ClassService {

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
    Page<ClassSummaryResponse> getClasses(String keyword, UUID teacherId, UUID courseId, Boolean isActive,
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
     * Remove a member from a class (MANAGER only)
     */
    void removeMember(UUID classId, UUID userId, UUID managerId);

    /**
     * Get active members of a class
     */
    Page<ClassMemberResponse> getClassMembers(UUID classId, Pageable pageable);

    /**
     * Get class by ID for teacher (validates teacher ownership)
     */
    ClassResponse getClassByIdForTeacher(UUID classId, UUID teacherId);

    /**
     * Get class members for teacher (validates teacher ownership)
     */
    Page<ClassMemberResponse> getClassMembersForTeacher(UUID classId, UUID teacherId, Pageable pageable);

    /**
     * Get eligible learners to add to a class (Active or Inactive, and not currently an active member)
     */
    Page<com.fu.user.dto.UserResponse> getEligibleLearners(UUID classId, String keyword, Pageable pageable);
}
