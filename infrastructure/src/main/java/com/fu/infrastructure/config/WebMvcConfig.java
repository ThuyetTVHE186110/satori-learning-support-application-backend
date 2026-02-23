package com.fu.infrastructure.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web MVC configuration.
 * Registers custom argument resolvers for controller method parameters.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthenticationPrincipalUuidResolver authenticationPrincipalUuidResolver;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        // Register UUID resolver for @AuthenticationPrincipal UUID parameters
        // This must be added BEFORE Spring's default AuthenticationPrincipalArgumentResolver
        resolvers.add(authenticationPrincipalUuidResolver);
    }
}

