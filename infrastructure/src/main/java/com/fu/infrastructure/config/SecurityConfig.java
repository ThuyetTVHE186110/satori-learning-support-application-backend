package com.fu.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.Filter;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Comma-separated list of allowed CORS origins.
     * Override via APP_ALLOWED_ORIGINS env var on each environment.
     * Example: "https://satori-learning.com,https://www.satori-learning.com"
     */
    @Value("${app.security.cors.allowed-origins:http://localhost:3000,http://localhost:5173}")
    private String allowedOriginsRaw;

    /**
     * Whether Swagger UI endpoints are publicly accessible.
     * Set to false on production (application-prod.yml).
     */
    @Value("${app.security.swagger.public:true}")
    private boolean swaggerPublic;

    /**
     * When true, allow requests from ANY origin (uses allowedOriginPatterns("*")).
     * Safe to enable only in local/dev — NEVER set true in prod.
     * Note: allowedOrigins("*") + allowCredentials(true) is rejected by browsers,
     *       so we use allowedOriginPatterns which is compatible.
     */
    @Value("${app.security.cors.allow-all:false}")
    private boolean corsAllowAll;

    /**
     * Endpoints that are always publicly accessible regardless of profile.
     * Actuator health probes must be public for load-balancer / k8s readiness checks.
     * Actuator metrics/prometheus are NOT listed here — they require ADMIN role.
     */
    private static final String[] ALWAYS_PUBLIC_ENDPOINTS = {
            "/api/v1/auth/login",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/refresh",
            "/api/v1/auth/verify-otp",
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
    };

    /** Swagger endpoints — only included in the public list when swaggerPublic=true */
    private static final String[] SWAGGER_ENDPOINTS = {
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/webjars/**"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            Filter jwtAuthenticationFilter) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    // Always-public endpoints (auth + health probes)
                    auth.requestMatchers(ALWAYS_PUBLIC_ENDPOINTS).permitAll();

                    // Swagger: public in local/dev, requires auth in prod
                    if (swaggerPublic) {
                        auth.requestMatchers(SWAGGER_ENDPOINTS).permitAll();
                    } else {
                        auth.requestMatchers(SWAGGER_ENDPOINTS).hasRole("ADMIN");
                    }

                    // Actuator: only health probes are public (above).
                    // All other actuator endpoints (metrics, prometheus, info) require ADMIN.
                    auth.requestMatchers("/actuator/**").hasRole("ADMIN");

                    // Public course & lesson browsing — learners can view published content
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/courses/**").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/courses/*/lessons/**").permitAll();

                    // User management — Admin access only
                    auth.requestMatchers("/api/v1/users/**").hasRole("ADMIN");

                    // Everything else requires authentication
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setContentType("application/json;charset=UTF-8");
                            response.setStatus(401);
                            response.getWriter().write(
                                    "{\"success\":false,\"code\":\"UNAUTHORIZED\",\"message\":\"Vui lòng đăng nhập để tiếp tục\"}"
                            );
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setContentType("application/json;charset=UTF-8");
                            response.setStatus(403);
                            response.getWriter().write(
                                    "{\"success\":false,\"code\":\"FORBIDDEN\",\"message\":\"Bạn không có quyền truy cập tài nguyên này\"}"
                            );
                        })
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        if (corsAllowAll) {
            // Dev/local only: allow any origin.
            // Must use allowedOriginPatterns (not allowedOrigins) to be compatible with allowCredentials=true.
            configuration.setAllowedOriginPatterns(List.of("*"));
        } else {
            // Prod/staging: explicit whitelist only.
            List<String> origins = Arrays.stream(allowedOriginsRaw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            configuration.setAllowedOrigins(origins);
        }

        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With", "Accept"));
        configuration.setExposedHeaders(Arrays.asList("X-Token-Expired", "Authorization"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
