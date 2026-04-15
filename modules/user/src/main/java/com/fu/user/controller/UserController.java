package com.fu.user.controller;

import com.fu.common.constant.AppConstants;
import com.fu.common.exception.BusinessException;
import com.fu.common.response.ApiResponse;
import com.fu.common.response.PageResponse;
import com.fu.user.dto.*;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import com.fu.user.security.CustomUserDetails;
import com.fu.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * REST Controller for User Management.
 * Implements UC-01 to UC-05 from business requirements.
 * <p>
 * Access Control:
 * - ADMIN: Full access to all user management operations
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;

    /**
     * UC-02: Create User
     * Admin creates LEARNER/TEACHER/CONTENT_MANAGER/OPERATION_MANAGER.
     * <p>
     * POST /api/v1/users
     *
     * @param request CreateUserRequest with user details
     * @return Created user with HTTP 201, or error with deleted user info
     */
    @PostMapping
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
     * <p>
     * GET /api/v1/users/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable("id") UUID id) {
        UserResponse response = userService.getUserById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * UC-03: View User Information (detailed with enrollment history)
     * For LEARNER: includes course and class enrollments
     * For TEACHER: includes taught classes history
     * <p>
     * GET /api/v1/users/{id}/details
     */
    @GetMapping("/{id}/details")
    public ResponseEntity<ApiResponse<UserDetailResponse>> getUserDetail(@AuthenticationPrincipal CustomUserDetails currentUser, @PathVariable("id") UUID id) {
        UserRole currentUserRole = UserRole.valueOf(currentUser.getRole());
        UserDetailResponse response = userService.getUserDetailById(currentUserRole, id);
        return ResponseEntity.ok(ApiResponse.success("Chi tiết người dùng", response));
    }

    /**
     * UC-01: View User List
     * Returns paginated list of users with optional search and filters.
     * - Admin: sees all users including deleted users
     * Default sort: createdAt DESC
     * <p>
     * GET /api/v1/users?page=0&size=10&keyword=...&role=LEARNER&status=ACTIVE
     *
     * @param page    Page number (0-indexed)
     * @param size    Page size
     * @param sortBy  Sort field (default: createdAt)
     * @param sortDir Sort direction (default: desc)
     * @param keyword Search keyword (name, email)
     * @param role    Filter by role
     * @param status  Filter by status (ACTIVE, INACTIVE)
     */
    @GetMapping
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
            usersPage = userService.getAllUsers(pageable, currentUserRole);
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
     * Admin updates all users.
     * <p>
     * PUT /api/v1/users/{id}
     */
    @PutMapping("/{id}")
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
     * Admin can delete all users.
     * <p>
     * DELETE /api/v1/users/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteUser(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable("id") UUID id) {
        UUID currentUserId = extractUserId(userDetails);
        UserRole currentUserRole = UserRole.valueOf(userDetails.getRole());

        userService.deleteUser(id, currentUserId, currentUserRole);
        return ResponseEntity.ok(ApiResponse.success("Xóa người dùng thành công", null));
    }

    // ==================== Admin Operations ====================

    /**
     * Admin: Restore a deleted user
     * <p>
     * POST /api/v1/users/restore
     */
    @PostMapping("/restore")
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
     * Admin: Import excel file to bulk create users
     * POST /api/v1/users/import
     */
    @PostMapping("/import")
    public ResponseEntity<ApiResponse<ImportUsersResult>> importUsersFromExcel(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException("EMPTY_FILE", "File không được rỗng");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.endsWith(".xlsx") && !filename.endsWith(".xls"))) {
            throw new BusinessException("INVALID_FILE_TYPE", "Chỉ chấp nhận file Excel (.xlsx, .xls)");
        }
        UUID currentUserId = extractUserId(userDetails);
        return ResponseEntity.ok(ApiResponse.success("Trích xuất dữ liệu từ file excel thành công.", userService.importUsersFromExcel(file, currentUserId)));
    }

    @PostMapping("/import/approve")
    public ResponseEntity<ApiResponse<ImportUserResponse>> approveImportFromExcel(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody ApproveImportRequest request
    ) {
        UUID currentUserId = extractUserId(userDetails);
        return ResponseEntity.ok(ApiResponse.success("Nhập thành công dữ liệu.", userService.approveImport(request, currentUserId)));
    }

    @GetMapping("/import/template")
    public ResponseEntity downloadExcelTemplate() {
        try {
            // Đọc file từ thư mục src/main/resources/static/ hoặc bất kỳ đâu trong resources
            Resource resource = new ClassPathResource("templates/template.xlsx");

            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                    .body(resource);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error("ERROR", "Lỗi khi tải xuống mẫu file excel."));
        }
    }

    /**
     * Extract UUID from CustomUserDetails.
     */
    private UUID extractUserId(CustomUserDetails userDetails) {
        return userDetails.getId();
    }
}
