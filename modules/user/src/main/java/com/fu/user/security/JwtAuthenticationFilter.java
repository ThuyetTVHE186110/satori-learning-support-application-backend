package com.fu.user.security;

import com.fu.user.repository.UserSessionRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * JWT Authentication Filter.
 * Validates JWT token on each request and sets authentication context.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;
    private final UserSessionRepository sessionRepository;

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        
        try {
            String jwt = extractTokenFromRequest(request);
            
            if (jwt != null) {
                Claims claims = jwtService.validateAccessToken(jwt);
                
                if (claims != null) {
                    UUID userId = UUID.fromString(claims.getSubject());
                    UUID sessionId = UUID.fromString(claims.get("sessionId", String.class));
                    
                    // Verify session is still active
                    boolean sessionActive = isSessionValid(sessionId);
                    
                    if (sessionActive) {
                        UserDetails userDetails = userDetailsService.loadUserByIdWithSession(userId, sessionId);
                        
                        if (userDetails.isEnabled() && userDetails.isAccountNonLocked()) {
                            UsernamePasswordAuthenticationToken authentication = 
                                    new UsernamePasswordAuthenticationToken(
                                            userDetails, 
                                            null, 
                                            userDetails.getAuthorities()
                                    );
                            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                            SecurityContextHolder.getContext().setAuthentication(authentication);
                            
                            log.debug("Authenticated user: {}", userDetails.getUsername());
                        }
                    } else {
                        log.debug("Session {} is no longer active", sessionId);
                    }
                }
            }
        } catch (ExpiredJwtException e) {
            log.debug("JWT token expired: {}", e.getMessage());
            response.setHeader("X-Token-Expired", "true");
        } catch (Exception e) {
            log.error("Cannot set user authentication: {}", e.getMessage());
        }
        
        filterChain.doFilter(request, response);
    }

    /**
     * Extract JWT token from Authorization header.
     */
    private String extractTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    /**
     * Check if session exists and is not revoked (by session ID, not refresh token).
     */
    private boolean isSessionValid(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .map(session -> session.getRevokedAt() == null && 
                               session.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElse(false);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        // Skip JWT validation for public endpoints
        return path.startsWith("/api/v1/auth/login") ||
               path.startsWith("/api/v1/auth/forgot-password") ||
               path.startsWith("/api/v1/auth/reset-password") ||
               path.startsWith("/api/v1/auth/refresh") ||
               path.startsWith("/actuator") ||
               path.startsWith("/swagger-ui") ||
               path.startsWith("/v3/api-docs");
    }
}
