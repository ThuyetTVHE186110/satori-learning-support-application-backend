package com.fu.monitoring.dto;

import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to search and filter users")
public class UserSearchRequest {

    @Schema(description = "Search query - searches by email, first name, last name, or user ID", example = "john@example.com")
    private String query;

    @Schema(description = "Filter by user role", example = "LEARNER")
    private UserRole role;

    @Schema(description = "Filter by account status", example = "ACTIVE")
    private UserStatus status;

    @Schema(description = "Page number (0-indexed)", example = "0")
    private Integer page;

    @Schema(description = "Page size (max 100)", example = "20")
    private Integer size;

    public Integer getPage() {
        return page != null && page >= 0 ? page : 0;
    }

    public Integer getSize() {
        return size != null && size > 0 && size <= 100 ? size : 20;
    }
}
