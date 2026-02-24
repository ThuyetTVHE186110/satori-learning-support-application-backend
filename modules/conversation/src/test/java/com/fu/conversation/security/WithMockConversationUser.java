package com.fu.conversation.security;

import org.springframework.security.test.context.support.WithSecurityContext;

import java.lang.annotation.*;

/**
 * Test annotation that sets up a {@link MockUserDetails} security context.
 * Use this instead of {@code @WithMockUser} for controller endpoints that
 * resolve {@code @AuthenticationPrincipal(expression = "id")} as a UUID,
 * since the standard {@code @WithMockUser} creates a plain Spring Security
 * {@code User} with no {@code id} property (which causes SpEL evaluation failure).
 *
 * <p>Usage:
 * <pre>
 *   {@literal @}WithMockConversationUser                    // defaults to MANAGER role
 *   {@literal @}WithMockConversationUser(roles = "LEARNER")
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Documented
@WithSecurityContext(factory = WithMockConversationUserSecurityContextFactory.class)
public @interface WithMockConversationUser {

    /** Role(s) to assign — without the {@code ROLE_} prefix. Defaults to {@code MANAGER}. */
    String[] roles() default {"MANAGER"};

    /** Username placed in the mock principal. */
    String username() default "test-manager";
}
