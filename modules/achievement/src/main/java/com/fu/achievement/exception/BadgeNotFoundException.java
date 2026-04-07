package com.fu.achievement.exception;

import com.fu.common.exception.ResourceNotFoundException;

import java.util.UUID;

public class BadgeNotFoundException extends ResourceNotFoundException {

    public BadgeNotFoundException(UUID badgeId) {
        super("Không tìm thấy danh hiệu với ID: " + badgeId);
    }

    public BadgeNotFoundException(String message) {
        super(message);
    }
}
