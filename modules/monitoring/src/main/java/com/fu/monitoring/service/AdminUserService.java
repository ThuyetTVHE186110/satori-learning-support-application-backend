package com.fu.monitoring.service;

import com.fu.monitoring.dto.AdminUserDetailResponse;
import com.fu.monitoring.dto.AdminUserStatsDTO;
import com.fu.monitoring.dto.UserSearchRequest;
import org.springframework.data.domain.Page;

import java.util.UUID;

/**
 * Service for admin user management and statistics
 */
public interface AdminUserService {

    /**
     * Get comprehensive admin user statistics including:
     * - User count breakdown by role, status, auth provider, gender
     * - New user registration metrics
     * - Active session metrics
     * - Security metrics (failed logins, locked accounts)
     * - User activity metrics
     */
    AdminUserStatsDTO getUserStatistics();

    /**
     * Search and filter users with pagination
     */
    Page<AdminUserDetailResponse> searchUsers(UserSearchRequest request);

    /**
     * Get detailed information about a specific user including:
     * - Profile information
     * - Account status and authentication details
     * - Active sessions
     * - Activity statistics
     */
    AdminUserDetailResponse getUserDetail(UUID userId);

}
