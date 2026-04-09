package com.fu.clazz.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for adding multiple members to a class (batch operation)
 * OPERATION_MANAGER only
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddMembersRequest {

    @NotEmpty(message = "At least one learner ID is required")
    private List<UUID> learnerIds;
}
