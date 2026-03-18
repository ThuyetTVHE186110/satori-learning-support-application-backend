package com.fu.conversation.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Test-only UserDetails implementation that exposes a {@code UUID id} field,
 * satisfying {@code @AuthenticationPrincipal(expression = "id")} in controller tests.
 */
public class MockUserDetails implements UserDetails {

    private final UUID id;
    private final String username;
    private final Collection<? extends GrantedAuthority> authorities;

    public MockUserDetails(UUID id, String username, String... roles) {
        this.id = id;
        this.username = username;
        this.authorities = Stream.of(roles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());
    }

    /** Used by {@code @AuthenticationPrincipal(expression = "id")}. */
    public UUID getId() {
        return id;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getPassword() {
        return "password";
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
