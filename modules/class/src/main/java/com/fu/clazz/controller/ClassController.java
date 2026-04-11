package com.fu.clazz.controller;

import com.fu.clazz.dto.request.AddMemberRequest;
import com.fu.clazz.dto.request.AddMembersRequest;
import com.fu.clazz.dto.request.CreateClassRequest;
import com.fu.clazz.dto.request.UpdateClassRequest;
import com.fu.clazz.dto.response.ClassMemberResponse;
import com.fu.clazz.dto.response.ClassResponse;
import com.fu.clazz.dto.response.ClassSummaryResponse;
import com.fu.clazz.dto.response.TeacherClassHistoryResponse;
import com.fu.clazz.service.ClassService;
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
import java.util.List;

/**
 * Unified REST Controller for Class operations.
 * <p>
 * Accessible by both OPERATION_MANAGER and TEACHER roles.
 * - OPERATION_MANAGER: full CRUD, member management, view all classes
 * - TEACHER: read-only, view own assigned active classes only
 * <p>
 * Base path: /api/v1/classes
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/classes")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPERATION_MANAGER', 'TEACHER')")
public class ClassController {

    private final ClassService clazzService;

    // =====================================================
    // READ OPERATIONS (OPERATION_MANAGER + TEACHER)
    // =====================================================

    /**
     * Get all classes with pagination and optional filters.
     * - OPERATION_MANAGER: sees all non-deleted classes with full filter support
     * - TEACHER: sees only their own non-deleted classes (any status: ACTIVE, CLOSED, NOT_STARTED, etc.)
     * <p>
     * GET
     * /api/v1/classes?page=0&size=10&status=ACTIVE&courseId=xxx&teacherId=xxx&keyword=xxx
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ClassSummaryResponse>>> getAllClasses(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) com.fu.clazz.entity.ClassStatus status,
            @RequestParam(required = false) UUID courseId,
            @RequestParam(required = false) UUID teacherId,
            @RequestParam(required = false) String keyword) {

        Pageable pageable;
        
        // For teacher without status filter: sort by status priority (ACTIVE > NOT_STARTED > CLOSED), then by createdAt
        // For others or with status filter: sort by createdAt only
        if (!isManager() && status == null) {
            // Teacher without status filter: use custom status priority sorting
            // Note: JPA doesn't support CASE expressions in Order, so we'll apply this on service side
            pageable = PageRequest.of(page, Math.min(size, 100));
        } else {
            pageable = PageRequest.of(page, Math.min(size, 100), Sort.by("createdAt").descending());
        }
        Page<ClassSummaryResponse> classPage;

        if (isManager()) {
            // OPERATION_MANAGER: full access with dynamic filters combined by AND
            classPage = clazzService.getClasses(keyword, teacherId, courseId, status, pageable);
        } else {
            // TEACHER: see all non-deleted classes, can optionally filter by status if provided
            classPage = clazzService.getClasses(keyword, userId, courseId, status, pageable);
        }

        PageResponse<ClassSummaryResponse> pageResponse = PageResponse.of(
                classPage.getContent(),
                classPage.getNumber(),
                classPage.getSize(),
                classPage.getTotalElements(),
                classPage.getTotalPages());
        return ResponseEntity.ok(ApiResponse.success(pageResponse.getTotalElements() == 0 ? "Không có lớp học nào." : "Operation completed successfully", pageResponse));
    }

    /**
     * Get class detail by ID.
     * - OPERATION_MANAGER: can view any non-deleted class
     * - TEACHER: can only view classes assigned to them
     * <p>
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
     * - OPERATION_MANAGER: can view members of any class
     * - TEACHER: can only view members of their assigned classes
     * <p>
     * GET /api/v1/classes/{classId}/members?page=0&size=10
     */
    @GetMapping("/{classId}/members")
    public ResponseEntity<ApiResponse<PageResponse<ClassMemberResponse>>> getClassMembers(
            @PathVariable UUID classId,
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100), Sort.by("joinedAt").descending());

        Page<ClassMemberResponse> memberPage;
        if (isManager()) {
            memberPage = clazzService.getClassMembers(classId, keyword, pageable);
        } else {
            memberPage = clazzService.getClassMembersForTeacher(classId, userId, keyword, pageable);
        }

        PageResponse<ClassMemberResponse> pageResponse = PageResponse.of(
                memberPage.getContent(),
                memberPage.getNumber(),
                memberPage.getSize(),
                memberPage.getTotalElements(),
                memberPage.getTotalPages());
        return ResponseEntity.ok(ApiResponse.success(pageResponse));
    }

    /**
     * Get learners eligible to be added to a class.
     * Eligible learners are those with role LEARNER, status ACTIVE/INACTIVE, and not currently an active member.
     * - OPERATION_MANAGER: can view eligible learners for any class
     * <p>
     * GET /api/v1/classes/{classId}/eligible-learners?page=0&size=10&keyword=...&filterByClassId=...
     */
    @GetMapping("/{classId}/eligible-learners")
    @PreAuthorize("hasRole('OPERATION_MANAGER')")
    public ResponseEntity<ApiResponse<PageResponse<com.fu.user.dto.UserResponse>>> getEligibleLearners(
            @PathVariable UUID classId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UUID filterByClassId) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100), Sort.by("email").ascending());

        Page<com.fu.user.dto.UserResponse> userPage = clazzService.getEligibleLearners(classId, keyword, filterByClassId, pageable);

        PageResponse<com.fu.user.dto.UserResponse> pageResponse = PageResponse.of(
                userPage.getContent(),
                userPage.getNumber(),
                userPage.getSize(),
                userPage.getTotalElements(),
                userPage.getTotalPages());
        return ResponseEntity.ok(ApiResponse.success(pageResponse));
    }

    // =====================================================
    // WRITE OPERATIONS (OPERATION_MANAGER ONLY)
    // =====================================================

    /**
     * Create a new class.
     * POST /api/v1/classes
     */
    @PostMapping
    @PreAuthorize("hasRole('OPERATION_MANAGER')")
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
    @PreAuthorize("hasRole('OPERATION_MANAGER')")
    public ResponseEntity<ApiResponse<ClassResponse>> updateClass(
            @PathVariable UUID classId,
            @Valid @RequestBody UpdateClassRequest request,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} updating class: {}", managerId, classId);
        ClassResponse response = clazzService.updateClass(classId, request, managerId);
        return ResponseEntity.ok(ApiResponse.success("Cập nhật thông tin lớp học thành công.", response));
    }

    /**
     * Close an existing class (set isActive to false).
     * PUT /api/v1/classes/{classId}/close
     */
    @PutMapping("/{classId}/close")
    @PreAuthorize("hasRole('OPERATION_MANAGER')")
    public ResponseEntity<ApiResponse<ClassResponse>> closeClass(
            @PathVariable UUID classId,
            @AuthenticationPrincipal UUID managerId) {
        ClassResponse response = clazzService.closeClass(classId, managerId);
        log.info("Manager {} closed class: {}", managerId, classId);
        return ResponseEntity.ok(ApiResponse.success("Đóng lớp học thành công.", response));
    }

    /**
     * Delete a class (soft delete only — does NOT change isActive).
     * DELETE /api/v1/classes/{classId}
     */
    @DeleteMapping("/{classId}")
    @PreAuthorize("hasRole('OPERATION_MANAGER')")
    public ResponseEntity<ApiResponse<ClassResponse>> deleteClass(
            @PathVariable UUID classId,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} deleting class: {}", managerId, classId);
        ClassResponse response = clazzService.deleteClass(classId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Xóa lớp học thành công.", response));
    }

    /**
     * Add member(s) to a class.
     * Supports both single member add (legacy) and batch operations.
     * POST /api/v1/classes/{classId}/members
     */
    @PostMapping("/{classId}/members")
    @PreAuthorize("hasRole('OPERATION_MANAGER')")
    public ResponseEntity<ApiResponse<?>> addMembers(
            @PathVariable UUID classId,
            @Valid @RequestBody AddMembersRequest request,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} adding {} members to class: {}", managerId, request.getLearnerIds().size(), classId);
        List<ClassMemberResponse> responses = clazzService.addMembers(classId, request, managerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Thêm học viên vào lớp thành công.", responses));
    }

    /**
     * Remove a member from a class.
     * DELETE /api/v1/classes/{classId}/members/{userId}
     */
    @DeleteMapping("/{classId}/members/{userId}")
    @PreAuthorize("hasRole('OPERATION_MANAGER')")
    public ResponseEntity<ApiResponse<Void>> removeMember(
            @PathVariable UUID classId,
            @PathVariable UUID userId,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} removing member {} from class: {}", managerId, userId, classId);
        clazzService.removeMember(classId, userId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Xóa học viên ra khỏi lớp học thành công.", null));
    }

    /**
     * Get all classes a teacher has taught or is currently teaching.
     * Returns paginated list sorted by assignment date (most recent first).
     * 
     * Access Control:
     * - TEACHER: Can only view own class history
     * 
     * GET /api/v1/classes/teacher/{teacherId}?page=0&size=10
     */
    @GetMapping("/teacher")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ApiResponse<PageResponse<TeacherClassHistoryResponse>>> getTeacherClassHistory(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {


        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<TeacherClassHistoryResponse> classHistoryPage = clazzService.getTeacherClassHistory(userId, pageable);

        PageResponse<TeacherClassHistoryResponse> pageResponse = PageResponse.of(
                classHistoryPage.getContent(),
                classHistoryPage.getNumber(),
                classHistoryPage.getSize(),
                classHistoryPage.getTotalElements(),
                classHistoryPage.getTotalPages());

        String message = classHistoryPage.getTotalElements() == 0
                ? "Giáo viên chưa dạy lớp nào."
                : "Lấy lịch sử lớp học thành công.";
        return ResponseEntity.ok(ApiResponse.success(message, pageResponse));
    }

    // =====================================================
    // UTILITY
    // =====================================================

    /**
     * Check if the current authenticated user has the OPERATION_MANAGER role.
     */
    private boolean isManager() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> role.equals("ROLE_OPERATION_MANAGER"));
    }
}
