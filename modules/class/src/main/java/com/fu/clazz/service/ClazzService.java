package com.fu.clazz.service;

import com.fu.clazz.dto.request.AddMemberRequest;
import com.fu.clazz.dto.request.CreateClassRequest;
import com.fu.clazz.dto.request.UpdateClassRequest;
import com.fu.clazz.dto.response.ClassMemberResponse;
import com.fu.clazz.dto.response.ClassResponse;
import com.fu.clazz.dto.response.ClassSummaryResponse;
import com.fu.clazz.entity.Clazz.JlptLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Service interface for Class management
 */
public interface ClazzService {

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
     * Get all classes with pagination (including deleted)
     */
    Page<ClassSummaryResponse> getAllClasses(Pageable pageable);

    /**
     * Get classes by active status
     */
    Page<ClassSummaryResponse> getClassesByActiveStatus(Boolean isActive, Pageable pageable);

    /**
     * Get classes by JLPT level
     */
    Page<ClassSummaryResponse> getClassesByJlptLevel(JlptLevel jlptLevel, Pageable pageable);

    /**
     * Get classes by teacher ID
     */
    Page<ClassSummaryResponse> getClassesByTeacher(UUID teacherId, Pageable pageable);

    /**
     * Get active classes by teacher ID (TEACHER view)
     */
    Page<ClassSummaryResponse> getActiveClassesByTeacher(UUID teacherId, Pageable pageable);

    /**
     * Get active classes by teacher ID and JLPT level (TEACHER view)
     */
    Page<ClassSummaryResponse> getActiveClassesByTeacherAndLevel(UUID teacherId, JlptLevel jlptLevel,
            Pageable pageable);

    /**
     * Search classes by keyword
     */
    Page<ClassSummaryResponse> searchClasses(String keyword, Pageable pageable);

    /**
     * Search classes by teacher ID and keyword
     */
    Page<ClassSummaryResponse> searchClassesByTeacher(UUID teacherId, String keyword, Pageable pageable);

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
}
