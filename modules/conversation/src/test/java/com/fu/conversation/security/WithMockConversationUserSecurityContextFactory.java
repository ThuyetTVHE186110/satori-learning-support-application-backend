package com.fu.conversation.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.util.UUID;

/**
 * Factory that creates a {@link SecurityContext} containing a
 * {@link MockUserDetails} principal for use with {@link WithMockConversationUser}.
 */
public class WithMockConversationUserSecurityContextFactory
        implements WithSecurityContextFactory<WithMockConversationUser> {

    @Override
    public SecurityContext createSecurityContext(WithMockConversationUser annotation) {
        MockUserDetails principal = new MockUserDetails(
                UUID.randomUUID(),
                annotation.username(),
                annotation.roles()
        );

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal,
                principal.getPassword(),
                principal.getAuthorities()
        );

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        return context;
    }
}
