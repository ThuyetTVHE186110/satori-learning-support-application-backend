package com.fu.user.controller;

import com.fu.common.constant.AppConstants;
import com.fu.common.response.ApiResponse;
import com.fu.common.response.PageResponse;
import com.fu.user.dto.*;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import com.fu.user.security.CustomUserDetails;
import com.fu.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
 * REST Controller for User Management.
 * Implements UC-01 to UC-05 from business requirements.
 * 
 * Access Control:
 * - ADMIN: Full access to all users (LEARNER, TEACHER, MANAGER)
 * - MANAGER: Limited access to LEARNER and TEACHER only
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * UC-02: Create User
     * Manager creates LEARNER/TEACHER.
     * Admin creates LEARNER/TEACHER/MANAGER.
     * 
     * POST /api/v1/users
     * 
     * @param request CreateUserRequest with user details
     * @return Created user with HTTP 201, or error with deleted user info
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> createUser(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody CreateUserRequest request) {
        UUID currentUserId = extractUserId(userDetails);
        UserRole currentUserRole = UserRole.valueOf(userDetails.getRole());
        
        UserResponse response = userService.createUser(request, currentUserId, currentUserRole);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo người dùng thành công", response));
    }

    /**
     * UC-01: View User List (single user by ID)
     * 
     * GET /api/v1/users/{id}
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable("id") UUID id) {
        UserResponse response = userService.getUserById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * UC-03: View User Information (detailed with enrollment history)
     * For LEARNER: includes course and class enrollments
     * For TEACHER: includes taught classes history
     * 
     * GET /api/v1/users/{id}/details
     */
    @GetMapping("/{id}/details")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<UserDetailResponse>> getUserDetail(@PathVariable("id") UUID id) {
        UserDetailResponse response = userService.getUserDetailById(id);
        return ResponseEntity.ok(ApiResponse.success("Chi tiết người dùng", response));
    }

    /**
     * UC-01: View User List
     * Returns paginated list of users with optional search and filters.
     * Manager sees LEARNER/TEACHER only.
     * Admin sees all users.
     * Default sort: createdAt DESC
     * 
     * GET /api/v1/users?page=0&size=10&keyword=...&role=LEARNER&status=ACTIVE
     * 
     * @param page Page number (0-indexed)
     * @param size Page size
     * @param sortBy Sort field (default: createdAt)
     * @param sortDir Sort direction (default: desc)
     * @param keyword Search keyword (name, email)
     * @param role Filter by role
     * @param status Filter by status (ACTIVE, INACTIVE)
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<UserResponse>>> getAllUsers(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(name = "page", defaultValue = AppConstants.DEFAULT_PAGE_NUMBER) int page,
            @RequestParam(name = "size", defaultValue = AppConstants.DEFAULT_PAGE_SIZE) int size,
            @RequestParam(name = "sortBy", defaultValue = "createdAt") String sortBy,
            @RequestParam(name = "sortDir", defaultValue = "desc") String sortDir,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "role", required = false) UserRole role,
            @RequestParam(name = "status", required = false) UserStatus status) {

        UserRole currentUserRole = UserRole.valueOf(userDetails.getRole());
        
        Sort sort = sortDir.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        Pageable pageable = PageRequest.of(page, size, sort);
        
        Page<UserResponse> usersPage;
        
        // If any filter is provided, use search method
        if (keyword != null || role != null || status != null) {
            UserFilterRequest filter = UserFilterRequest.builder()
                    .keyword(keyword)
                    .role(role)
                    .status(status)
                    .build();
            usersPage = userService.searchUsers(filter, pageable, currentUserRole);
        } else {
            usersPage = userService.getAllUsers(pageable);
        }

        // Check if no results found
        if (usersPage.isEmpty()) {
            PageResponse<UserResponse> emptyResponse = PageResponse.of(
                    usersPage.getContent(),
                    usersPage.getNumber(),
                    usersPage.getSize(),
                    usersPage.getTotalElements(),
                    usersPage.getTotalPages()
            );
            return ResponseEntity.ok(ApiResponse.success("Không tìm thấy kết quả", emptyResponse));
        }

        PageResponse<UserResponse> pageResponse = PageResponse.of(
                usersPage.getContent(),
                usersPage.getNumber(),
                usersPage.getSize(),
                usersPage.getTotalElements(),
                usersPage.getTotalPages()
        );

        return ResponseEntity.ok(ApiResponse.success(pageResponse));
    }

    /**
     * UC-04: Update User Information
     * Manager updates LEARNER/TEACHER details.
     * Admin updates all users.
     * 
     * PUT /api/v1/users/{id}
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateUserRequest request) {
        UUID currentUserId = extractUserId(userDetails);
        UserRole currentUserRole = UserRole.valueOf(userDetails.getRole());
        
        UserResponse response = userService.updateUser(id, request, currentUserId, currentUserRole);
        return ResponseEntity.ok(ApiResponse.success("Cập nhật thông tin thành công", response));
    }

    /**
     * UC-05: Delete User (Soft Delete)
     * Manager soft-deletes LEARNER/TEACHER.
     * Admin can delete all users.
     * 
     * DELETE /api/v1/users/{id}
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteUser(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable("id") UUID id) {
        UUID currentUserId = extractUserId(userDetails);
        UserRole currentUserRole = UserRole.valueOf(userDetails.getRole());
        
        userService.deleteUser(id, currentUserId, currentUserRole);
        return ResponseEntity.ok(ApiResponse.success("Xóa người dùng thành công", null));
    }

    // ==================== Admin-only Operations ====================

    /**
     * Admin: Get all deleted users
     * 
     * GET /api/v1/users/deleted?page=0&size=10
     */
    @GetMapping("/deleted")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<UserResponse>>> getDeletedUsers(
            @RequestParam(name = "page", defaultValue = AppConstants.DEFAULT_PAGE_NUMBER) int page,
            @RequestParam(name = "size", defaultValue = AppConstants.DEFAULT_PAGE_SIZE) int size,
            @RequestParam(name = "sortBy", defaultValue = "deletedAt") String sortBy,
            @RequestParam(name = "sortDir", defaultValue = "desc") String sortDir) {

        Sort sort = sortDir.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        Pageable pageable = PageRequest.of(page, size, sort);
        Page<UserResponse> deletedUsersPage = userService.getDeletedUsers(pageable);

        PageResponse<UserResponse> pageResponse = PageResponse.of(
                deletedUsersPage.getContent(),
                deletedUsersPage.getNumber(),
                deletedUsersPage.getSize(),
                deletedUsersPage.getTotalElements(),
                deletedUsersPage.getTotalPages()
        );

        return ResponseEntity.ok(ApiResponse.success("Danh sách người dùng đã xóa", pageResponse));
    }

    /**
     * Admin/Manager: Restore a deleted user
     * 
     * POST /api/v1/users/restore
     */
    @PostMapping("/restore")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> restoreUser(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody RestoreUserRequest request) {
        UUID currentUserId = extractUserId(userDetails);
        UserRole currentUserRole = UserRole.valueOf(userDetails.getRole());
        
        UserResponse response = userService.restoreUser(request, currentUserId, currentUserRole);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ApiResponse.success("Khôi phục người dùng thành công", response));
    }

    /**
     * Extract UUID from CustomUserDetails.
     */
    private UUID extractUserId(CustomUserDetails userDetails) {
        return userDetails.getId();
    }
}
