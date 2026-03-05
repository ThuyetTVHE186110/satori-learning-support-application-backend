package com.fu.conversation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.config.ConversationModuleTestConfig;
import com.fu.conversation.dto.request.CreateUserTopicRequest;
import com.fu.conversation.dto.request.UpdateUserTopicRequest;
import com.fu.conversation.dto.response.ConversationThemeSummaryResponse;
import com.fu.conversation.dto.response.ConversationTopicResponse;
import com.fu.conversation.dto.response.ConversationTopicSummaryResponse;
import com.fu.conversation.dto.response.UserConversationTopicResponse;
import com.fu.conversation.service.ConversationThemeService;
import com.fu.conversation.service.ConversationTopicService;
import com.fu.conversation.service.UserConversationTopicService;
import com.fu.course.entity.Course;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import com.fu.conversation.security.WithMockConversationUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = LearnerConversationController.class)
@ContextConfiguration(classes = ConversationModuleTestConfig.class)
@AutoConfigureMockMvc
@DisplayName("LearnerConversationController Tests")
class LearnerConversationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ConversationThemeService themeService;

    @MockBean
    private ConversationTopicService topicService;

    @MockBean
    private UserConversationTopicService userTopicService;

    private UUID themeId;
    private UUID topicId;

    private ConversationThemeSummaryResponse themeSummary;
    private ConversationTopicSummaryResponse topicSummary;
    private UserConversationTopicResponse userTopicResponse;

    @BeforeEach
    void setUp() {
        themeId  = UUID.randomUUID();
        topicId  = UUID.randomUUID();

        themeSummary = ConversationThemeSummaryResponse.builder()
                .id(themeId)
                .title("Daily Shopping")
                .status(Course.ContentStatus.PUBLISHED)
                .build();

        topicSummary = ConversationTopicSummaryResponse.builder()
                .id(topicId)
                .title("At the Convenience Store")
                .build();

        userTopicResponse = UserConversationTopicResponse.builder()
                .id(topicId)
                .title("My Shopping Practice")
                .status(Course.ContentStatus.DRAFT)
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/learner/conversation/themes")
    class GetPublishedThemesTests {

        @Test
        @DisplayName("Should return 200 with published themes for LEARNER")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200WithPublishedThemes() throws Exception {
            given(themeService.getPublishedThemes(any(), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(themeSummary)));

            mockMvc.perform(get("/api/v1/learner/conversation/themes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content[0].title").value("Daily Shopping"));
        }

        @Test
        @DisplayName("Should return 200 with empty page when no published themes")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200WithEmptyPage() throws Exception {
            given(themeService.getPublishedThemes(any(), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get("/api/v1/learner/conversation/themes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isEmpty());
        }

        @Test
        @DisplayName("Should return 403 for MANAGER (wrong role)")
        @WithMockConversationUser(roles = "MANAGER")
        void shouldReturn403ForManager() throws Exception {
            mockMvc.perform(get("/api/v1/learner/conversation/themes"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Should accept optional jlpt level filter")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldAcceptJlptLevelFilter() throws Exception {
            given(themeService.getPublishedThemes(eq(Course.JlptLevel.N5), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(themeSummary)));

            mockMvc.perform(get("/api/v1/learner/conversation/themes")
                            .param("level", "N5"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should use searchPublishedThemes when keyword is provided")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldSearchWhenKeywordProvided() throws Exception {
            given(themeService.searchPublishedThemes(eq("Shopping"), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(themeSummary)));

            mockMvc.perform(get("/api/v1/learner/conversation/themes")
                            .param("keyword", "Shopping"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content[0].title").value("Daily Shopping"));
        }

        @Test
        @DisplayName("Should ignore blank keyword and return published themes")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldIgnoreBlankKeyword() throws Exception {
            given(themeService.getPublishedThemes(any(), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(themeSummary)));

            mockMvc.perform(get("/api/v1/learner/conversation/themes")
                            .param("keyword", "  "))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].title").value("Daily Shopping"));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/learner/conversation/themes/{themeId}/topics")
    class GetPublishedTopicsByThemeTests {

        @Test
        @DisplayName("Should return 200 with published topics")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200WithTopics() throws Exception {
            given(topicService.getPublishedTopicsByTheme(themeId))
                    .willReturn(List.of(topicSummary));

            mockMvc.perform(get("/api/v1/learner/conversation/themes/{themeId}/topics", themeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].title").value("At the Convenience Store"));
        }

        @Test
        @DisplayName("Should return 200 with empty list when no published topics")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200WithEmptyList() throws Exception {
            given(topicService.getPublishedTopicsByTheme(themeId)).willReturn(List.of());

            mockMvc.perform(get("/api/v1/learner/conversation/themes/{themeId}/topics", themeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("Should return 403 for MANAGER")
        @WithMockConversationUser(roles = "MANAGER")
        void shouldReturn403ForManager() throws Exception {
            mockMvc.perform(get("/api/v1/learner/conversation/themes/{themeId}/topics", themeId))
                    .andExpect(status().isForbidden());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/learner/conversation/topics/{topicId}")
    class GetPublishedTopicByIdTests {

        @Test
        @DisplayName("Should return 200 with full topic detail for LEARNER")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200WithTopicDetail() throws Exception {
            ConversationTopicResponse fullTopic = ConversationTopicResponse.builder()
                    .id(topicId)
                    .title("At the Convenience Store")
                    .status(Course.ContentStatus.PUBLISHED)
                    .build();
            given(topicService.getPublishedTopic(topicId)).willReturn(fullTopic);

            mockMvc.perform(get("/api/v1/learner/conversation/topics/{topicId}", topicId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(topicId.toString()))
                    .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        }

        @Test
        @DisplayName("Should return 404 when topic is not published or not found")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn404WhenNotPublished() throws Exception {
            given(topicService.getPublishedTopic(topicId))
                    .willThrow(new ResourceNotFoundException("ConversationTopic", topicId));

            mockMvc.perform(get("/api/v1/learner/conversation/topics/{topicId}", topicId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 403 for MANAGER")
        @WithMockConversationUser(roles = "MANAGER")
        void shouldReturn403ForManager() throws Exception {
            mockMvc.perform(get("/api/v1/learner/conversation/topics/{topicId}", topicId))
                    .andExpect(status().isForbidden());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/v1/learner/conversation/my-topics")
    class CreateMyTopicTests {

        @Test
        @DisplayName("Should return 201 when LEARNER creates a personal topic")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn201OnCreate() throws Exception {
            CreateUserTopicRequest request = CreateUserTopicRequest.builder()
                    .title("My Shopping Practice").build();
            given(userTopicService.createTopic(any(), any())).willReturn(userTopicResponse);

            mockMvc.perform(post("/api/v1/learner/conversation/my-topics")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.title").value("My Shopping Practice"));
        }

        @Test
        @DisplayName("Should return 400 when title is blank")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn400WhenTitleBlank() throws Exception {
            CreateUserTopicRequest request = CreateUserTopicRequest.builder()
                    .title("").build(); // @NotBlank violation

            mockMvc.perform(post("/api/v1/learner/conversation/my-topics")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 400 when title is missing")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn400WhenTitleMissing() throws Exception {
            String body = "{\"scenario\":\"Some scenario\"}"; // no title

            mockMvc.perform(post("/api/v1/learner/conversation/my-topics")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 403 for MANAGER")
        @WithMockConversationUser(roles = "MANAGER")
        void shouldReturn403ForManager() throws Exception {
            CreateUserTopicRequest request = CreateUserTopicRequest.builder()
                    .title("Test").build();

            mockMvc.perform(post("/api/v1/learner/conversation/my-topics")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/learner/conversation/my-topics")
    class GetMyTopicsTests {

        @Test
        @DisplayName("Should return 200 with paginated personal topics")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200WithPersonalTopics() throws Exception {
            given(userTopicService.getMyTopics(any(), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(userTopicResponse)));

            mockMvc.perform(get("/api/v1/learner/conversation/my-topics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].title").value("My Shopping Practice"));
        }

        @Test
        @DisplayName("Should return 200 with empty page when no topics")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200WithEmptyPage() throws Exception {
            given(userTopicService.getMyTopics(any(), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get("/api/v1/learner/conversation/my-topics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isEmpty());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/learner/conversation/my-topics/{topicId}")
    class GetMyTopicByIdTests {

        @Test
        @DisplayName("Should return 200 when topic owned by user")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200WhenOwned() throws Exception {
            given(userTopicService.getMyTopic(eq(topicId), any())).willReturn(userTopicResponse);

            mockMvc.perform(get("/api/v1/learner/conversation/my-topics/{topicId}", topicId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(topicId.toString()));
        }

        @Test
        @DisplayName("Should return 404 when topic not found or not owned")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn404WhenNotOwned() throws Exception {
            given(userTopicService.getMyTopic(eq(topicId), any()))
                    .willThrow(new ResourceNotFoundException("UserConversationTopic", topicId));

            mockMvc.perform(get("/api/v1/learner/conversation/my-topics/{topicId}", topicId))
                    .andExpect(status().isNotFound());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PUT /api/v1/learner/conversation/my-topics/{topicId}")
    class UpdateMyTopicTests {

        @Test
        @DisplayName("Should return 200 on successful update")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200OnUpdate() throws Exception {
            UpdateUserTopicRequest request = UpdateUserTopicRequest.builder()
                    .title("Updated Title").build();

            UserConversationTopicResponse updated = UserConversationTopicResponse.builder()
                    .id(topicId).title("Updated Title").status(Course.ContentStatus.DRAFT).build();
            given(userTopicService.updateTopic(eq(topicId), any(), any())).willReturn(updated);

            mockMvc.perform(put("/api/v1/learner/conversation/my-topics/{topicId}", topicId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("Updated Title"));
        }

        @Test
        @DisplayName("Should return 404 when topic not found or not owned")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn404WhenNotOwned() throws Exception {
            UpdateUserTopicRequest request = UpdateUserTopicRequest.builder().title("X").build();
            given(userTopicService.updateTopic(eq(topicId), any(), any()))
                    .willThrow(new ResourceNotFoundException("UserConversationTopic", topicId));

            mockMvc.perform(put("/api/v1/learner/conversation/my-topics/{topicId}", topicId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("DELETE /api/v1/learner/conversation/my-topics/{topicId}")
    class DeleteMyTopicTests {

        @Test
        @DisplayName("Should return 200 on hard-delete")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200OnDelete() throws Exception {
            doNothing().when(userTopicService).deleteTopic(eq(topicId), any());

            mockMvc.perform(delete("/api/v1/learner/conversation/my-topics/{topicId}", topicId)
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("Should return 404 when topic not found or not owned")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn404WhenNotOwned() throws Exception {
            doThrow(new ResourceNotFoundException("UserConversationTopic", topicId))
                    .when(userTopicService).deleteTopic(eq(topicId), any());

            mockMvc.perform(delete("/api/v1/learner/conversation/my-topics/{topicId}", topicId)
                            .with(csrf()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 403 for MANAGER")
        @WithMockConversationUser(roles = "MANAGER")
        void shouldReturn403ForManager() throws Exception {
            mockMvc.perform(delete("/api/v1/learner/conversation/my-topics/{topicId}", topicId)
                            .with(csrf()))
                    .andExpect(status().isForbidden());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/v1/learner/conversation/my-topics/{topicId}/generate-context")
    class GenerateContextTests {

        @Test
        @DisplayName("Should return 200 with updated topic containing AI context")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200WithGeneratedContext() throws Exception {
            UserConversationTopicResponse withContext = UserConversationTopicResponse.builder()
                    .id(topicId)
                    .title("My Shopping Practice")
                    .aiGeneratedContext("{\"vocabularyHints\":[\"買い物\"]}")
                    .status(Course.ContentStatus.DRAFT)
                    .build();
            given(userTopicService.generateContext(eq(topicId), any())).willReturn(withContext);

            mockMvc.perform(post("/api/v1/learner/conversation/my-topics/{topicId}/generate-context", topicId)
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.aiGeneratedContext").isNotEmpty());
        }

        @Test
        @DisplayName("Should return 400 when scenario is missing (BusinessException)")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn400WhenScenarioMissing() throws Exception {
            given(userTopicService.generateContext(eq(topicId), any()))
                    .willThrow(new BusinessException("MISSING_SCENARIO",
                            "Topic must have a scenario before generating context"));

            mockMvc.perform(post("/api/v1/learner/conversation/my-topics/{topicId}/generate-context", topicId)
                            .with(csrf()))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 404 when topic not found")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn404WhenTopicNotFound() throws Exception {
            given(userTopicService.generateContext(eq(topicId), any()))
                    .willThrow(new ResourceNotFoundException("UserConversationTopic", topicId));

            mockMvc.perform(post("/api/v1/learner/conversation/my-topics/{topicId}/generate-context", topicId)
                            .with(csrf()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 403 for MANAGER")
        @WithMockConversationUser(roles = "MANAGER")
        void shouldReturn403ForManager() throws Exception {
            mockMvc.perform(post("/api/v1/learner/conversation/my-topics/{topicId}/generate-context", topicId)
                            .with(csrf()))
                    .andExpect(status().isForbidden());
        }
    }
}
