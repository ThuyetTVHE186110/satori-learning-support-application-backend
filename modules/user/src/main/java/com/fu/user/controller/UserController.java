package com.fu.user.controller;

import com.fu.common.constant.AppConstants;
import com.fu.common.response.ApiResponse;
import com.fu.common.response.PageResponse;
import com.fu.user.dto.CreateUserRequest;
import com.fu.user.dto.UpdateUserRequest;
import com.fu.user.dto.UserFilterRequest;
import com.fu.user.dto.UserResponse;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
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
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST Controller for User Management.
 * Implements UC-01 to UC-05 from business requirements.
 * 
 * Access Control: Only MANAGER role can access these endpoints.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * UC-02: Create User
     * Manager creates a new user (Learner/Teacher).
     * 
     * POST /api/v1/users
     * 
     * @param request CreateUserRequest with user details
     * @return Created user with HTTP 201
     */
    @PostMapping
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<UserResponse>> createUser(
            @Valid @RequestBody CreateUserRequest request) {
        UserResponse response = userService.createUser(request);
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
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable("id") UUID id) {
        UserResponse response = userService.getUserById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * UC-01: View User List
     * Returns paginated list of users with optional search and filters.
     * Default sort: createdAt DESC
     * 
     * GET /api/v1/users?page=0&size=10&keyword=...&role=LEARNER&status=ACTIVE
     * 
     * @param page Page number (0-indexed)
     * @param size Page size
     * @param sortBy Sort field (default: createdAt)
     * @param sortDir Sort direction (default: desc)
     * @param keyword Search keyword (name, email)
     * @param role Filter by role (LEARNER, TEACHER)
     * @param status Filter by status (ACTIVE, INACTIVE)
     */
    @GetMapping
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<PageResponse<UserResponse>>> getAllUsers(
            @RequestParam(name = "page", defaultValue = AppConstants.DEFAULT_PAGE_NUMBER) int page,
            @RequestParam(name = "size", defaultValue = AppConstants.DEFAULT_PAGE_SIZE) int size,
            @RequestParam(name = "sortBy", defaultValue = "createdAt") String sortBy,
            @RequestParam(name = "sortDir", defaultValue = "desc") String sortDir,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "role", required = false) UserRole role,
            @RequestParam(name = "status", required = false) UserStatus status) {

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
            usersPage = userService.searchUsers(filter, pageable);
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
     * Manager updates user details.
     * 
     * PUT /api/v1/users/{id}
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateUserRequest request) {
        UserResponse response = userService.updateUser(id, request);
        return ResponseEntity.ok(ApiResponse.success("Cập nhật thông tin thành công", response));
    }

    /**
     * UC-05: Delete User (Soft Delete)
     * Manager soft-deletes a user (sets status to INACTIVE).
     * 
     * DELETE /api/v1/users/{id}
     * 
     * Note: deletedByUserId should come from SecurityContext in production.
     * For now, we pass it as a header for testing.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<Void>> deleteUser(
            @PathVariable("id") UUID id,
            @RequestHeader(name = "X-Deleted-By", required = false) UUID deletedByUserId) {
        // TODO: Get deletedByUserId from SecurityContext
        // UUID currentUserId = SecurityUtils.getCurrentUserId();
        userService.deleteUser(id, deletedByUserId);
        return ResponseEntity.ok(ApiResponse.success("Xóa người dùng thành công", null));
    }
}
