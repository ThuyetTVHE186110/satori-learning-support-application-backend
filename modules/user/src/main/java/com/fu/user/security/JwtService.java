package com.fu.user.security;

import com.fu.user.entity.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service for JWT token generation and validation.
 * Handles Access Tokens and Refresh Tokens.
 */
@Slf4j
@Service
public class JwtService {

    private final SecretKey accessTokenKey;
    private final SecretKey refreshTokenKey;
    private final long accessTokenExpirationMs;
    private final long refreshTokenExpirationMs;

    public JwtService(
            @Value("${app.security.jwt.access-token-secret}") String accessTokenSecret,
            @Value("${app.security.jwt.refresh-token-secret}") String refreshTokenSecret,
            @Value("${app.security.jwt.access-token-expiration-ms:1800000}") long accessTokenExpirationMs,
            @Value("${app.security.jwt.refresh-token-expiration-ms:604800000}") long refreshTokenExpirationMs) {
        
        this.accessTokenKey = Keys.hmacShaKeyFor(accessTokenSecret.getBytes(StandardCharsets.UTF_8));
        this.refreshTokenKey = Keys.hmacShaKeyFor(refreshTokenSecret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirationMs = accessTokenExpirationMs;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
    }

    /**
     * Generate Access Token for a user.
     * Contains: userId, email, role, sessionId
     * Expiration: 30 minutes (default)
     */
    public String generateAccessToken(User user, UUID sessionId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId().toString());
        claims.put("email", user.getEmail());
        claims.put("role", user.getRole().name());
        claims.put("sessionId", sessionId.toString());

        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpirationMs);

        return Jwts.builder()
                .claims(claims)
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(accessTokenKey)
                .compact();
    }

    /**
     * Generate Refresh Token for a user session.
     * Contains: sessionId only (minimal claims for security)
     * Expiration: 7 days (default)
     */
    public String generateRefreshToken(UUID sessionId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + refreshTokenExpirationMs);

        return Jwts.builder()
                .subject(sessionId.toString())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(refreshTokenKey)
                .compact();
    }

    /**
     * Validate and parse Access Token.
     * @return Claims if valid, null if invalid
     */
    public Claims validateAccessToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(accessTokenKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.debug("Access token expired: {}", e.getMessage());
            throw e;
        } catch (SignatureException e) {
            log.warn("Invalid token signature: {}", e.getMessage());
            return null;
        } catch (MalformedJwtException e) {
            log.warn("Malformed token: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Error validating access token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Validate and parse Refresh Token.
     * @return Session ID if valid, null if invalid
     */
    public UUID validateRefreshToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(refreshTokenKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            
            return UUID.fromString(claims.getSubject());
        } catch (ExpiredJwtException e) {
            log.debug("Refresh token expired: {}", e.getMessage());
            return null;
        } catch (SignatureException e) {
            log.warn("Invalid refresh token signature: {}", e.getMessage());
            return null;
        } catch (MalformedJwtException e) {
            log.warn("Malformed refresh token: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Error validating refresh token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract User ID from Access Token.
     */
    public UUID getUserIdFromToken(String token) {
        Claims claims = validateAccessToken(token);
        if (claims == null) {
            return null;
        }
        return UUID.fromString(claims.getSubject());
    }

    /**
     * Extract Session ID from Access Token.
     */
    public UUID getSessionIdFromToken(String token) {
        Claims claims = validateAccessToken(token);
        if (claims == null) {
            return null;
        }
        String sessionId = claims.get("sessionId", String.class);
        return sessionId != null ? UUID.fromString(sessionId) : null;
    }

    /**
     * Extract User Role from Access Token.
     */
    public String getRoleFromToken(String token) {
        Claims claims = validateAccessToken(token);
        if (claims == null) {
            return null;
        }
        return claims.get("role", String.class);
    }

    /**
     * Check if token is expired.
     */
    public boolean isTokenExpired(String token) {
        try {
            Claims claims = validateAccessToken(token);
            return claims == null || claims.getExpiration().before(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        }
    }

    /**
     * Get access token expiration time in seconds.
     */
    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationMs / 1000;
    }

    /**
     * Get refresh token expiration time in milliseconds.
     */
    public long getRefreshTokenExpirationMs() {
        return refreshTokenExpirationMs;
    }
}
