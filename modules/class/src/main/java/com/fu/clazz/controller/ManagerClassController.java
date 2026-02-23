package com.fu.clazz.controller;

import com.fu.clazz.dto.request.AddMemberRequest;
import com.fu.clazz.dto.request.CreateClassRequest;
import com.fu.clazz.dto.request.UpdateClassRequest;
import com.fu.clazz.dto.response.ClassMemberResponse;
import com.fu.clazz.dto.response.ClassResponse;
import com.fu.clazz.dto.response.ClassSummaryResponse;
import com.fu.clazz.entity.Clazz.JlptLevel;
import com.fu.clazz.service.ClazzService;
import com.fu.common.response.ApiResponse;
import com.fu.common.response.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST Controller for Manager Class CRUD operations.
 *
 * All endpoints require ROLE_MANAGER.
 * Base path: /api/v1/manager/classes
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/manager/classes")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MANAGER')")
public class ManagerClassController {

    private final ClazzService clazzService;

    /**
     * Create a new class.
     * POST /api/v1/manager/classes
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ClassResponse>> createClass(
            @Valid @RequestBody CreateClassRequest request,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} creating class: {}", managerId, request.getName());
        ClassResponse response = clazzService.createClass(request, managerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Class created successfully", response));
    }

    /**
     * Update an existing class.
     * PUT /api/v1/manager/classes/{classId}
     */
    @PutMapping("/{classId}")
    public ResponseEntity<ApiResponse<ClassResponse>> updateClass(
            @PathVariable UUID classId,
            @Valid @RequestBody UpdateClassRequest request,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} updating class: {}", managerId, classId);
        ClassResponse response = clazzService.updateClass(classId, request, managerId);
        return ResponseEntity.ok(ApiResponse.success("Class updated successfully", response));
    }

    /**
     * Get class by ID.
     * GET /api/v1/manager/classes/{classId}
     */
    @GetMapping("/{classId}")
    public ResponseEntity<ApiResponse<ClassResponse>> getClassById(
            @PathVariable UUID classId) {

        ClassResponse response = clazzService.getClassById(classId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get all classes with pagination and optional filters.
     * GET /api/v1/manager/classes?page=0&size=10&isActive=true&level=N5&teacherId=xxx&keyword=xxx
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ClassSummaryResponse>>> getAllClasses(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) JlptLevel level,
            @RequestParam(required = false) UUID teacherId,
            @RequestParam(required = false) String keyword) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100), Sort.by("createdAt").descending());

        Page<ClassSummaryResponse> classPage;
        if (keyword != null && !keyword.isBlank()) {
            classPage = clazzService.searchClasses(keyword.trim(), pageable);
        } else if (teacherId != null) {
            classPage = clazzService.getClassesByTeacher(teacherId, pageable);
        } else if (isActive != null) {
            classPage = clazzService.getClassesByActiveStatus(isActive, pageable);
        } else if (level != null) {
            classPage = clazzService.getClassesByJlptLevel(level, pageable);
        } else {
            classPage = clazzService.getAllClasses(pageable);
        }

        PageResponse<ClassSummaryResponse> pageResponse = PageResponse.of(
                classPage.getContent(),
                classPage.getNumber(),
                classPage.getSize(),
                classPage.getTotalElements(),
                classPage.getTotalPages()
        );
        return ResponseEntity.ok(ApiResponse.success(pageResponse));
    }

    /**
     * Activate a class.
     * PATCH /api/v1/manager/classes/{classId}/activate
     */
    @PatchMapping("/{classId}/activate")
    public ResponseEntity<ApiResponse<ClassResponse>> activateClass(
            @PathVariable UUID classId,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} activating class: {}", managerId, classId);
        ClassResponse response = clazzService.activateClass(classId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Class activated successfully", response));
    }

    /**
     * Deactivate a class.
     * PATCH /api/v1/manager/classes/{classId}/deactivate
     */
    @PatchMapping("/{classId}/deactivate")
    public ResponseEntity<ApiResponse<ClassResponse>> deactivateClass(
            @PathVariable UUID classId,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} deactivating class: {}", managerId, classId);
        ClassResponse response = clazzService.deactivateClass(classId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Class deactivated successfully", response));
    }

    /**
     * Soft-delete a class.
     * DELETE /api/v1/manager/classes/{classId}
     */
    @DeleteMapping("/{classId}")
    public ResponseEntity<ApiResponse<Void>> deleteClass(
            @PathVariable UUID classId,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} deleting class: {}", managerId, classId);
        clazzService.deleteClass(classId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Class deleted successfully", null));
    }

    /**
     * Assign teacher to class.
     * PATCH /api/v1/manager/classes/{classId}/teacher
     */
    @PatchMapping("/{classId}/teacher")
    public ResponseEntity<ApiResponse<ClassResponse>> assignTeacher(
            @PathVariable UUID classId,
            @RequestParam UUID teacherId,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} assigning teacher {} to class: {}", managerId, teacherId, classId);
        ClassResponse response = clazzService.assignTeacher(classId, teacherId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Teacher assigned successfully", response));
    }

    /**
     * Add a member to a class.
     * POST /api/v1/manager/classes/{classId}/members
     */
    @PostMapping("/{classId}/members")
    public ResponseEntity<ApiResponse<ClassMemberResponse>> addMember(
            @PathVariable UUID classId,
            @Valid @RequestBody AddMemberRequest request,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} adding member {} to class: {}", managerId, request.getUserId(), classId);
        ClassMemberResponse response = clazzService.addMember(classId, request, managerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Member added successfully", response));
    }

    /**
     * Remove a member from a class.
     * DELETE /api/v1/manager/classes/{classId}/members/{userId}
     */
    @DeleteMapping("/{classId}/members/{userId}")
    public ResponseEntity<ApiResponse<Void>> removeMember(
            @PathVariable UUID classId,
            @PathVariable UUID userId,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} removing member {} from class: {}", managerId, userId, classId);
        clazzService.removeMember(classId, userId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Member removed successfully", null));
    }

    /**
     * Get members of a class.
     * GET /api/v1/manager/classes/{classId}/members?page=0&size=10
     */
    @GetMapping("/{classId}/members")
    public ResponseEntity<ApiResponse<PageResponse<ClassMemberResponse>>> getClassMembers(
            @PathVariable UUID classId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100), Sort.by("joinedAt").descending());
        Page<ClassMemberResponse> memberPage = clazzService.getClassMembers(classId, pageable);

        PageResponse<ClassMemberResponse> pageResponse = PageResponse.of(
                memberPage.getContent(),
                memberPage.getNumber(),
                memberPage.getSize(),
                memberPage.getTotalElements(),
                memberPage.getTotalPages()
        );
        return ResponseEntity.ok(ApiResponse.success(pageResponse));
    }
}

