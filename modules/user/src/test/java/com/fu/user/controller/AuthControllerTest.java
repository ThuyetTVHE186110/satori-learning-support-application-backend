package com.fu.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.user.config.UserModuleTestConfig;
import com.fu.user.dto.auth.LoginRequest;
import com.fu.user.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller tests for AuthController — covers Bean Validation layer (UC-06 login endpoint).
 * Validation errors are handled before the service is called, returning HTTP 400.
 *
 * Test cases covered:
 * UTCID01 — null email          → 400
 * UTCID02 — blank/whitespace email → 400
 * UTCID03 — invalid email format → 400
 * UTCID05 — null password       → 400
 */
@WebMvcTest(controllers = AuthController.class)
@ContextConfiguration(classes = UserModuleTestConfig.class)
@AutoConfigureMockMvc
@DisplayName("AuthController Unit Tests - Login validation")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @Nested
    @DisplayName("POST /api/v1/auth/login — Bean Validation (HTTP 400 cases)")
    class LoginValidationTests {

        // UTCID01 [A] — null email
        @Test
        @DisplayName("UTCID01 [A] - Should return 400 when email is null")
        void shouldReturn400WhenEmailIsNull() throws Exception {
            Map<String, Object> body = new HashMap<>();
            body.put("email", null);
            body.put("password", "Password123!");

            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                    .andDo(print())
                    .andExpect(status().isBadRequest());

            verify(authService, never()).login(any(), anyString(), anyString());
        }

        // UTCID02 [N] — blank/whitespace email
        @Test
        @DisplayName("UTCID02 [N] - Should return 400 when email is blank (whitespace only)")
        void shouldReturn400WhenEmailIsBlank() throws Exception {
            LoginRequest request = LoginRequest.builder()
                    .email("   ")
                    .password("Password123!")
                    .build();

            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isBadRequest());

            verify(authService, never()).login(any(), anyString(), anyString());
        }

        // UTCID03 [N] — invalid email format (no '@' sign)
        @Test
        @DisplayName("UTCID03 [N] - Should return 400 when email format is invalid")
        void shouldReturn400WhenEmailFormatIsInvalid() throws Exception {
            LoginRequest request = LoginRequest.builder()
                    .email("learner1test.satori.com")   // missing @
                    .password("Password123!")
                    .build();

            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isBadRequest());

            verify(authService, never()).login(any(), anyString(), anyString());
        }

        // UTCID05 [N] — null password
        @Test
        @DisplayName("UTCID05 [N] - Should return 400 when password is null")
        void shouldReturn400WhenPasswordIsNull() throws Exception {
            Map<String, Object> body = new HashMap<>();
            body.put("email", "active@test.satori.com");
            body.put("password", null);

            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                    .andDo(print())
                    .andExpect(status().isBadRequest());

            verify(authService, never()).login(any(), anyString(), anyString());
        }
    }
}
