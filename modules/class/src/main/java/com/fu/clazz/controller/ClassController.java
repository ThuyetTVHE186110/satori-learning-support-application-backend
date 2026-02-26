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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Unified REST Controller for Class operations.
 *
 * Accessible by both MANAGER and TEACHER roles.
 * - MANAGER: full CRUD, member management, view all classes
 * - TEACHER: read-only, view own assigned active classes only
 *
 * Base path: /api/v1/classes
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/classes")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MANAGER', 'TEACHER')")
public class ClassController {

    private final ClazzService clazzService;

    // =====================================================
    // READ OPERATIONS (MANAGER + TEACHER)
    // =====================================================

    /**
     * Get all classes with pagination and optional filters.
     * - MANAGER: sees all non-deleted classes with full filter support
     * - TEACHER: sees only their own active classes (filtered by teacher ID auto)
     *
     * GET
     * /api/v1/classes?page=0&size=10&isActive=true&level=N5&teacherId=xxx&keyword=xxx
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ClassSummaryResponse>>> getAllClasses(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) JlptLevel level,
            @RequestParam(required = false) UUID teacherId,
            @RequestParam(required = false) String keyword) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100), Sort.by("createdAt").descending());
        Page<ClassSummaryResponse> classPage;

        if (isManager()) {
            // MANAGER: full access with filter priority (BR-75)
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
        } else {
            // TEACHER: only their own active classes
            if (keyword != null && !keyword.isBlank()) {
                classPage = clazzService.searchClassesByTeacher(userId, keyword.trim(), pageable);
            } else if (level != null) {
                classPage = clazzService.getActiveClassesByTeacherAndLevel(userId, level, pageable);
            } else {
                classPage = clazzService.getActiveClassesByTeacher(userId, pageable);
            }
        }

        PageResponse<ClassSummaryResponse> pageResponse = PageResponse.of(
                classPage.getContent(),
                classPage.getNumber(),
                classPage.getSize(),
                classPage.getTotalElements(),
                classPage.getTotalPages());
        return ResponseEntity.ok(ApiResponse.success(pageResponse));
    }

    /**
     * Get class detail by ID.
     * - MANAGER: can view any non-deleted class
     * - TEACHER: can only view classes assigned to them
     *
     * GET /api/v1/classes/{classId}
     */
    @GetMapping("/{classId}")
    public ResponseEntity<ApiResponse<ClassResponse>> getClassById(
            @PathVariable UUID classId,
            @AuthenticationPrincipal UUID userId) {

        ClassResponse response;
        if (isManager()) {
            response = clazzService.getClassById(classId);
        } else {
            response = clazzService.getClassByIdForTeacher(classId, userId);
        }
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get members of a class with pagination.
     * - MANAGER: can view members of any class
     * - TEACHER: can only view members of their assigned classes
     *
     * GET /api/v1/classes/{classId}/members?page=0&size=10
     */
    @GetMapping("/{classId}/members")
    public ResponseEntity<ApiResponse<PageResponse<ClassMemberResponse>>> getClassMembers(
            @PathVariable UUID classId,
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100), Sort.by("joinedAt").descending());

        Page<ClassMemberResponse> memberPage;
        if (isManager()) {
            memberPage = clazzService.getClassMembers(classId, pageable);
        } else {
            memberPage = clazzService.getClassMembersForTeacher(classId, userId, pageable);
        }

        PageResponse<ClassMemberResponse> pageResponse = PageResponse.of(
                memberPage.getContent(),
                memberPage.getNumber(),
                memberPage.getSize(),
                memberPage.getTotalElements(),
                memberPage.getTotalPages());
        return ResponseEntity.ok(ApiResponse.success(pageResponse));
    }

    // =====================================================
    // WRITE OPERATIONS (MANAGER ONLY)
    // =====================================================

    /**
     * Create a new class.
     * POST /api/v1/classes
     */
    @PostMapping
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<ClassResponse>> createClass(
            @Valid @RequestBody CreateClassRequest request,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} creating class: {}", managerId, request.getName());
        ClassResponse response = clazzService.createClass(request, managerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo lớp học thành công.", response));
    }

    /**
     * Update an existing class (supports partial update including isActive toggle).
     * PUT /api/v1/classes/{classId}
     */
    @PutMapping("/{classId}")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<ClassResponse>> updateClass(
            @PathVariable UUID classId,
            @Valid @RequestBody UpdateClassRequest request,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} updating class: {}", managerId, classId);
        ClassResponse response = clazzService.updateClass(classId, request, managerId);
        return ResponseEntity.ok(ApiResponse.success("Cập nhật thông tin lớp học thành công.", response));
    }

    /**
     * Delete a class (soft delete only — does NOT change isActive).
     * DELETE /api/v1/classes/{classId}
     */
    @DeleteMapping("/{classId}")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<ClassResponse>> deleteClass(
            @PathVariable UUID classId,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} deleting class: {}", managerId, classId);
        ClassResponse response = clazzService.deleteClass(classId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Xóa lớp học thành công.", response));
    }

    /**
     * Add a member to a class.
     * POST /api/v1/classes/{classId}/members
     */
    @PostMapping("/{classId}/members")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<ClassMemberResponse>> addMember(
            @PathVariable UUID classId,
            @Valid @RequestBody AddMemberRequest request,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} adding member {} to class: {}", managerId, request.getUserId(), classId);
        ClassMemberResponse response = clazzService.addMember(classId, request, managerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Thêm học viên vào lớp thành công.", response));
    }

    /**
     * Remove a member from a class.
     * DELETE /api/v1/classes/{classId}/members/{userId}
     */
    @DeleteMapping("/{classId}/members/{userId}")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<Void>> removeMember(
            @PathVariable UUID classId,
            @PathVariable UUID userId,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} removing member {} from class: {}", managerId, userId, classId);
        clazzService.removeMember(classId, userId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Xóa học viên ra khỏi lớp học thành công.", null));
    }

    // =====================================================
    // UTILITY
    // =====================================================

    /**
     * Check if the current authenticated user has the MANAGER role.
     */
    private boolean isManager() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> role.equals("ROLE_MANAGER"));
    }
}
