package com.fu.clazz.dto.analytics;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Projection interface for class member information.
 * Queries user and class_member data for learner dashboard.
 */
public interface ClassMemberInfo {
    UUID getUserId();
    String getFullName();
    String getEmail();
    String getAvatarUrl();
    LocalDateTime getJoinedAt();
}
