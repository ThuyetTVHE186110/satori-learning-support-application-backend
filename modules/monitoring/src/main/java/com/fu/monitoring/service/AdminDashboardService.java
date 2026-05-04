package com.fu.monitoring.service;

import com.fu.monitoring.dto.response.AdminDashboardResponse;

public interface AdminDashboardService {
    /**
     * Returns all data needed to render the admin overview dashboard in a single call.
     */
    AdminDashboardResponse getDashboard();
}
