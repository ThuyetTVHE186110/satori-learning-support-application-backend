package com.fu.clazz.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for ClassMember entity
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassMemberResponse {

    private UUID id;
    private UUID classId;
    private UUID userId;
    private LocalDateTime joinedAt;
    private LocalDateTime removedAt;
}

