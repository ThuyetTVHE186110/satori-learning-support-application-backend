package com.fu.conversation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.config.ConversationModuleTestConfig;
import com.fu.conversation.dto.request.CreateThemeRequest;
import com.fu.conversation.dto.request.CreateTopicRequest;
import com.fu.conversation.dto.request.GenerateThemeRequest;
import com.fu.conversation.dto.request.UpdateThemeRequest;
import com.fu.conversation.dto.request.UpdateTopicRequest;
import com.fu.conversation.dto.response.ConversationThemeResponse;
import com.fu.conversation.dto.response.ConversationThemeSummaryResponse;
import com.fu.conversation.dto.response.ConversationTopicResponse;
import com.fu.conversation.dto.response.ConversationTopicSummaryResponse;
import com.fu.conversation.dto.response.GeneratedThemeSuggestionResponse;
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

@WebMvcTest(controllers = ManagerConversationController.class)
@ContextConfiguration(classes = ConversationModuleTestConfig.class)
@AutoConfigureMockMvc
@DisplayName("ManagerConversationController Tests")
class ManagerConversationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ConversationThemeService themeService;

    @MockBean
    private ConversationTopicService topicService;

    // Required because ConversationModuleTestConfig component-scans the whole
    // controller package, pulling in LearnerConversationController which depends
    // on this service even though it is not under test here.
    @MockBean
    private UserConversationTopicService userConversationTopicService;

    private UUID themeId;
    private UUID topicId;
    private UUID courseId;

    private ConversationThemeResponse themeResponse;
    private ConversationThemeSummaryResponse themeSummary;
    private ConversationTopicResponse topicResponse;
    private ConversationTopicSummaryResponse topicSummary;

    @BeforeEach
    void setUp() {
        themeId  = UUID.randomUUID();
        topicId  = UUID.randomUUID();
        courseId = UUID.randomUUID();

        themeResponse = ConversationThemeResponse.builder()
                .id(themeId)
                .title("Daily Shopping")
                .status(Course.ContentStatus.DRAFT)
                .build();

        themeSummary = ConversationThemeSummaryResponse.builder()
                .id(themeId)
                .title("Daily Shopping")
                .status(Course.ContentStatus.DRAFT)
                .build();

        topicResponse = ConversationTopicResponse.builder()
                .id(topicId)
                .title("At the Convenience Store")
                .status(Course.ContentStatus.DRAFT)
                .build();

        topicSummary = ConversationTopicSummaryResponse.builder()
                .id(topicId)
                .title("At the Convenience Store")
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/v1/manager/conversation/themes/generate")
    class GenerateThemesTests {

        @Test
        @DisplayName("Should return 200 with suggestions for MANAGER")
        @WithMockConversationUser
        void shouldReturn200WithSuggestions() throws Exception {
            GenerateThemeRequest request = GenerateThemeRequest.builder()
                    .courseId(courseId).count(2).build();

            GeneratedThemeSuggestionResponse suggestion = GeneratedThemeSuggestionResponse.builder()
                    .title("Shopping").build();
            given(themeService.generateThemeSuggestions(any())).willReturn(List.of(suggestion));

            mockMvc.perform(post("/api/v1/manager/conversation/themes/generate")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[0].title").value("Shopping"));
        }

        @Test
        @DisplayName("Should return 403 for LEARNER")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn403ForLearner() throws Exception {
            GenerateThemeRequest request = GenerateThemeRequest.builder()
                    .courseId(courseId).count(2).build();

            mockMvc.perform(post("/api/v1/manager/conversation/themes/generate")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Should return 400 when courseId is missing")
        @WithMockConversationUser
        void shouldReturn400WhenCourseIdMissing() throws Exception {
            // No courseId — violates @NotNull
            String body = "{\"count\": 2}";

            mockMvc.perform(post("/api/v1/manager/conversation/themes/generate")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/v1/manager/conversation/themes")
    class CreateThemeTests {

        @Test
        @DisplayName("Should return 201 when MANAGER creates a theme")
        @WithMockConversationUser
        void shouldReturn201OnCreate() throws Exception {
            CreateThemeRequest request = CreateThemeRequest.builder()
                    .title("Daily Shopping").build();

            given(themeService.createTheme(any(), any())).willReturn(themeResponse);

            mockMvc.perform(post("/api/v1/manager/conversation/themes")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.title").value("Daily Shopping"));
        }

        @Test
        @DisplayName("Should return 400 when title is blank")
        @WithMockConversationUser
        void shouldReturn400WhenTitleBlank() throws Exception {
            CreateThemeRequest request = CreateThemeRequest.builder()
                    .title("").build(); // blank — @NotBlank violation

            mockMvc.perform(post("/api/v1/manager/conversation/themes")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 403 for LEARNER")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn403ForLearner() throws Exception {
            CreateThemeRequest request = CreateThemeRequest.builder()
                    .title("Test Theme").build();

            mockMvc.perform(post("/api/v1/manager/conversation/themes")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/manager/conversation/themes")
    class GetAllThemesTests {

        @Test
        @DisplayName("Should return 200 with paginated themes")
        @WithMockConversationUser
        void shouldReturn200WithThemes() throws Exception {
            given(themeService.getAllThemes(any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(themeSummary)));

            mockMvc.perform(get("/api/v1/manager/conversation/themes")
                            .param("page", "0").param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content[0].title").value("Daily Shopping"));
        }

        @Test
        @DisplayName("Should return 403 for LEARNER")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn403ForLearner() throws Exception {
            mockMvc.perform(get("/api/v1/manager/conversation/themes"))
                    .andExpect(status().isForbidden());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/manager/conversation/themes/{themeId}")
    class GetThemeByIdTests {

        @Test
        @DisplayName("Should return 200 with theme details")
        @WithMockConversationUser
        void shouldReturn200WithTheme() throws Exception {
            given(themeService.getTheme(themeId)).willReturn(themeResponse);

            mockMvc.perform(get("/api/v1/manager/conversation/themes/{themeId}", themeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(themeId.toString()));
        }

        @Test
        @DisplayName("Should return 404 when theme not found")
        @WithMockConversationUser
        void shouldReturn404WhenNotFound() throws Exception {
            given(themeService.getTheme(themeId))
                    .willThrow(new ResourceNotFoundException("ConversationTheme", themeId));

            mockMvc.perform(get("/api/v1/manager/conversation/themes/{themeId}", themeId))
                    .andExpect(status().isNotFound());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PUT /api/v1/manager/conversation/themes/{themeId}")
    class UpdateThemeTests {

        @Test
        @DisplayName("Should return 200 when theme updated")
        @WithMockConversationUser
        void shouldReturn200OnUpdate() throws Exception {
            UpdateThemeRequest request = UpdateThemeRequest.builder()
                    .title("Updated Theme").build();

            ConversationThemeResponse updated = ConversationThemeResponse.builder()
                    .id(themeId).title("Updated Theme").status(Course.ContentStatus.DRAFT).build();
            given(themeService.updateTheme(eq(themeId), any(), any())).willReturn(updated);

            mockMvc.perform(put("/api/v1/manager/conversation/themes/{themeId}", themeId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("Updated Theme"));
        }

        @Test
        @DisplayName("Should return 404 when theme not found")
        @WithMockConversationUser
        void shouldReturn404WhenNotFound() throws Exception {
            UpdateThemeRequest request = UpdateThemeRequest.builder()
                    .title("X").build();
            given(themeService.updateTheme(eq(themeId), any(), any()))
                    .willThrow(new ResourceNotFoundException("ConversationTheme", themeId));

            mockMvc.perform(put("/api/v1/manager/conversation/themes/{themeId}", themeId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PATCH /api/v1/manager/conversation/themes/{themeId}/publish")
    class PublishThemeTests {

        @Test
        @DisplayName("Should return 200 when theme published")
        @WithMockConversationUser
        void shouldReturn200OnPublish() throws Exception {
            ConversationThemeResponse published = ConversationThemeResponse.builder()
                    .id(themeId).title("Daily Shopping").status(Course.ContentStatus.PUBLISHED).build();
            given(themeService.updateStatus(eq(themeId), eq(Course.ContentStatus.PUBLISHED), any()))
                    .willReturn(published);

            mockMvc.perform(patch("/api/v1/manager/conversation/themes/{themeId}/publish", themeId)
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PATCH /api/v1/manager/conversation/themes/{themeId}/archive")
    class ArchiveThemeTests {

        @Test
        @DisplayName("Should return 200 when theme archived")
        @WithMockConversationUser
        void shouldReturn200OnArchive() throws Exception {
            ConversationThemeResponse archived = ConversationThemeResponse.builder()
                    .id(themeId).title("Daily Shopping").status(Course.ContentStatus.ARCHIVED).build();
            given(themeService.updateStatus(eq(themeId), eq(Course.ContentStatus.ARCHIVED), any()))
                    .willReturn(archived);

            mockMvc.perform(patch("/api/v1/manager/conversation/themes/{themeId}/archive", themeId)
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ARCHIVED"));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("DELETE /api/v1/manager/conversation/themes/{themeId}")
    class DeleteThemeTests {

        @Test
        @DisplayName("Should return 200 on soft-delete")
        @WithMockConversationUser
        void shouldReturn200OnDelete() throws Exception {
            doNothing().when(themeService).deleteTheme(eq(themeId), any());

            mockMvc.perform(delete("/api/v1/manager/conversation/themes/{themeId}", themeId)
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("Should return 404 when not found")
        @WithMockConversationUser
        void shouldReturn404WhenNotFound() throws Exception {
            doThrow(new ResourceNotFoundException("ConversationTheme", themeId))
                    .when(themeService).deleteTheme(eq(themeId), any());

            mockMvc.perform(delete("/api/v1/manager/conversation/themes/{themeId}", themeId)
                            .with(csrf()))
                    .andExpect(status().isNotFound());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/v1/manager/conversation/topics")
    class CreateTopicTests {

        @Test
        @DisplayName("Should return 201 when topic created")
        @WithMockConversationUser
        void shouldReturn201OnCreate() throws Exception {
            CreateTopicRequest request = CreateTopicRequest.builder()
                    .title("At the Convenience Store").build();
            given(topicService.createTopic(any(), any())).willReturn(topicResponse);

            mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.title").value("At the Convenience Store"));
        }

        @Test
        @DisplayName("Should return 400 when title blank")
        @WithMockConversationUser
        void shouldReturn400WhenTitleBlank() throws Exception {
            CreateTopicRequest request = CreateTopicRequest.builder().title("").build();

            mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 400 when difficultyScore out of range")
        @WithMockConversationUser
        void shouldReturn400WhenDifficultyScoreOutOfRange() throws Exception {
            // difficultyScore = 11 exceeds @Max(10)
            String body = "{\"title\":\"Test\",\"difficultyScore\":11}";

            mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 403 for LEARNER")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn403ForLearner() throws Exception {
            CreateTopicRequest request = CreateTopicRequest.builder().title("Test").build();

            mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/manager/conversation/topics")
    class GetAllTopicsTests {

        @Test
        @DisplayName("Should return 200 with paginated topics")
        @WithMockConversationUser
        void shouldReturn200WithTopics() throws Exception {
            given(topicService.getAllTopics(any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(topicSummary)));

            mockMvc.perform(get("/api/v1/manager/conversation/topics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].title").value("At the Convenience Store"));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/manager/conversation/themes/{themeId}/topics")
    class GetTopicsByThemeTests {

        @Test
        @DisplayName("Should return 200 with topics for theme")
        @WithMockConversationUser
        void shouldReturn200WithTopicsByTheme() throws Exception {
            given(topicService.getTopicsByTheme(eq(themeId), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(topicSummary)));

            mockMvc.perform(get("/api/v1/manager/conversation/themes/{themeId}/topics", themeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].title").value("At the Convenience Store"));
        }

        @Test
        @DisplayName("Should return empty page when theme has no topics")
        @WithMockConversationUser
        void shouldReturnEmptyPageWhenNoTopics() throws Exception {
            given(topicService.getTopicsByTheme(eq(themeId), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get("/api/v1/manager/conversation/themes/{themeId}/topics", themeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isEmpty());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/manager/conversation/topics/{topicId}")
    class GetTopicByIdTests {

        @Test
        @DisplayName("Should return 200 with topic")
        @WithMockConversationUser
        void shouldReturn200WithTopic() throws Exception {
            given(topicService.getTopic(topicId)).willReturn(topicResponse);

            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}", topicId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(topicId.toString()));
        }

        @Test
        @DisplayName("Should return 404 when topic not found")
        @WithMockConversationUser
        void shouldReturn404WhenNotFound() throws Exception {
            given(topicService.getTopic(topicId))
                    .willThrow(new ResourceNotFoundException("ConversationTopic", topicId));

            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}", topicId))
                    .andExpect(status().isNotFound());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PUT /api/v1/manager/conversation/topics/{topicId}")
    class UpdateTopicTests {

        @Test
        @DisplayName("Should return 200 on successful update")
        @WithMockConversationUser
        void shouldReturn200OnUpdate() throws Exception {
            UpdateTopicRequest request = UpdateTopicRequest.builder()
                    .title("Updated Topic").build();
            ConversationTopicResponse updated = ConversationTopicResponse.builder()
                    .id(topicId).title("Updated Topic").status(Course.ContentStatus.DRAFT).build();
            given(topicService.updateTopic(eq(topicId), any(), any())).willReturn(updated);

            mockMvc.perform(put("/api/v1/manager/conversation/topics/{topicId}", topicId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("Updated Topic"));
        }

        @Test
        @DisplayName("Should return 404 when topic not found")
        @WithMockConversationUser
        void shouldReturn404WhenNotFound() throws Exception {
            UpdateTopicRequest request = UpdateTopicRequest.builder().title("X").build();
            given(topicService.updateTopic(eq(topicId), any(), any()))
                    .willThrow(new ResourceNotFoundException("ConversationTopic", topicId));

            mockMvc.perform(put("/api/v1/manager/conversation/topics/{topicId}", topicId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PATCH /api/v1/manager/conversation/topics/{topicId}/publish")
    class PublishTopicTests {

        @Test
        @DisplayName("Should return 200 when topic published")
        @WithMockConversationUser
        void shouldReturn200OnPublish() throws Exception {
            ConversationTopicResponse published = ConversationTopicResponse.builder()
                    .id(topicId).title("Test").status(Course.ContentStatus.PUBLISHED).build();
            given(topicService.updateStatus(eq(topicId), eq(Course.ContentStatus.PUBLISHED), any()))
                    .willReturn(published);

            mockMvc.perform(patch("/api/v1/manager/conversation/topics/{topicId}/publish", topicId)
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PATCH /api/v1/manager/conversation/topics/{topicId}/archive")
    class ArchiveTopicTests {

        @Test
        @DisplayName("Should return 200 when topic archived")
        @WithMockConversationUser
        void shouldReturn200OnArchive() throws Exception {
            ConversationTopicResponse archived = ConversationTopicResponse.builder()
                    .id(topicId).title("Test").status(Course.ContentStatus.ARCHIVED).build();
            given(topicService.updateStatus(eq(topicId), eq(Course.ContentStatus.ARCHIVED), any()))
                    .willReturn(archived);

            mockMvc.perform(patch("/api/v1/manager/conversation/topics/{topicId}/archive", topicId)
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ARCHIVED"));
        }

        @Test
        @DisplayName("Should return 404 when topic not found")
        @WithMockConversationUser
        void shouldReturn404WhenNotFound() throws Exception {
            given(topicService.updateStatus(eq(topicId), eq(Course.ContentStatus.ARCHIVED), any()))
                    .willThrow(new ResourceNotFoundException("ConversationTopic", topicId));

            mockMvc.perform(patch("/api/v1/manager/conversation/topics/{topicId}/archive", topicId)
                            .with(csrf()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 403 for LEARNER")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn403ForLearner() throws Exception {
            mockMvc.perform(patch("/api/v1/manager/conversation/topics/{topicId}/archive", topicId)
                            .with(csrf()))
                    .andExpect(status().isForbidden());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("DELETE /api/v1/manager/conversation/topics/{topicId}")
    class DeleteTopicTests {

        @Test
        @DisplayName("Should return 200 on soft-delete")
        @WithMockConversationUser
        void shouldReturn200OnDelete() throws Exception {
            doNothing().when(topicService).deleteTopic(eq(topicId), any());

            mockMvc.perform(delete("/api/v1/manager/conversation/topics/{topicId}", topicId)
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("Should return 404 when topic not found")
        @WithMockConversationUser
        void shouldReturn404WhenNotFound() throws Exception {
            doThrow(new ResourceNotFoundException("ConversationTopic", topicId))
                    .when(topicService).deleteTopic(eq(topicId), any());

            mockMvc.perform(delete("/api/v1/manager/conversation/topics/{topicId}", topicId)
                            .with(csrf()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 403 for LEARNER")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn403ForLearner() throws Exception {
            mockMvc.perform(delete("/api/v1/manager/conversation/topics/{topicId}", topicId)
                            .with(csrf()))
                    .andExpect(status().isForbidden());
        }
    }
}
