package com.fu.user.exception;

import com.fu.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Exception handler for User module specific exceptions.
 * This handler has higher priority than GlobalExceptionHandler to catch domain-specific exceptions.
 * This approach maintains proper separation of concerns:
 * - Common module: handles generic exceptions
 * - User module: handles user-domain specific exceptions
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UserExceptionHandler {

    /**
     * Handle DeletedUserExistsException - when trying to create user with deleted email.
     * Returns HTTP 409 CONFLICT with deleted user information for frontend to handle.
     */
    @ExceptionHandler(DeletedUserExistsException.class)
    public ResponseEntity<ApiResponse<Object>> handleDeletedUserExistsException(DeletedUserExistsException ex) {
        log.warn("Deleted user exists: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("DELETED_USER_EXISTS", ex.getMessage(), ex.getDeletedUserInfo()));
    }
}
