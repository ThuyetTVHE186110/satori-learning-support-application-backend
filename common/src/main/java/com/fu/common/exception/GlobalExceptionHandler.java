package com.fu.common.exception;

import com.fu.common.response.ApiResponse;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        log.warn("Business exception: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(Exception ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("FORBIDDEN", "Bạn không có quyền truy cập tài nguyên này"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationException(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        log.warn("Validation failed: {}", errors);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("VALIDATION_ERROR", "Validation failed", errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex) {
        String message = "Invalid request body";
        Throwable cause = ex.getCause();
        if (cause instanceof InvalidFormatException ife) {
            Class<?> targetType = ife.getTargetType();
            if (targetType != null && targetType.isEnum()) {
                Object[] enumConstants = targetType.getEnumConstants();
                String validValues = java.util.Arrays.stream(enumConstants)
                        .map(Object::toString)
                        .collect(java.util.stream.Collectors.joining(", "));
                message = String.format("Invalid value '%s'. Accepted values: [%s]",
                        ife.getValue(), validValues);
            } else {
                message = "Invalid value: " + ife.getValue();
            }
        }
        log.warn("Message not readable: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("VALIDATION_ERROR", message));
    }

    /**
     * Handles malformed path/query parameters — e.g. a string that is too long to be a UUID,
     * or an unrecognised enum value passed as a path variable.
     * Returns 400 so the frontend receives an actionable error instead of an opaque 500.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        String paramName   = ex.getName();
        Object value       = ex.getValue();
        Class<?> required  = ex.getRequiredType();
        String typeName    = required != null ? required.getSimpleName() : "unknown";

        String message = String.format(
                "Invalid value for parameter '%s': '%s' is not a valid %s.",
                paramName, value, typeName);

        log.warn("Type mismatch on parameter '{}': value='{}', required={} — {}",
                paramName, value, typeName, ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("INVALID_PARAMETER", message));
    }

    /**
     * Handles HTTP 405 Method Not Allowed — client called the right URL but with the wrong HTTP verb.
     * Logged at WARN without stack trace to avoid polluting production logs as if it were a server bug.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        String supported = ex.getSupportedHttpMethods() == null
                ? "unknown"
                : ex.getSupportedHttpMethods().toString();
        String message = String.format(
                "HTTP method '%s' not supported for this endpoint. Supported: %s",
                ex.getMethod(), supported);
        log.warn("Method not allowed: {}", message);
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error("METHOD_NOT_ALLOWED", message));
    }

    /**
     * Handles HTTP 404 when no controller matches the requested path.
     * Requires `spring.mvc.throw-exception-if-no-handler-found=true`; harmless otherwise.
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandlerFound(NoHandlerFoundException ex) {
        log.warn("No handler found: {} {}", ex.getHttpMethod(), ex.getRequestURL());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("NOT_FOUND", "Endpoint not found"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        log.error("Unexpected error occurred", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
