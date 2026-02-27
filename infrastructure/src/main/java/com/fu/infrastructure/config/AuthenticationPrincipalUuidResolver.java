package com.fu.infrastructure.config;

import org.springframework.core.MethodParameter;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.UUID;

/**
 * Custom argument resolver for @AuthenticationPrincipal UUID parameters.
 *
 * Problem: When the SecurityContext principal is CustomUserDetails (not a raw UUID),
 * Spring's default @AuthenticationPrincipal cannot resolve it to UUID directly,
 * resulting in null values for controller parameters like:
 *   @AuthenticationPrincipal UUID managerId
 *
 * Solution: This resolver extracts the UUID from AuditingConfig.UserPrincipal.getId()
 * when the target parameter type is UUID and annotated with @AuthenticationPrincipal.
 */
@Component
public class AuthenticationPrincipalUuidResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                && UUID.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            @NonNull MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            @NonNull NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object principal = authentication.getPrincipal();

        // Extract UUID from UserPrincipal (CustomUserDetails implements this)
        if (principal instanceof AuditingConfig.UserPrincipal userPrincipal) {
            return userPrincipal.getId();
        }

        // Fallback: parse UUID from String principal
        if (principal instanceof String principalStr) {
            try {
                return UUID.fromString(principalStr);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        return null;
    }
}

