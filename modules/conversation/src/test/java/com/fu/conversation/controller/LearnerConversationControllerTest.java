package com.fu.conversation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.config.ConversationModuleTestConfig;
import com.fu.conversation.dto.request.CreateUserConversationRequest;
import com.fu.conversation.dto.request.UpdateUserConversationRequest;
import com.fu.conversation.dto.response.ConversationResponse;
import com.fu.conversation.dto.response.ConversationSummaryResponse;
import com.fu.conversation.dto.response.ConversationTopicSummaryResponse;
import com.fu.conversation.dto.response.UserConversationResponse;
import com.fu.conversation.service.ConversationMissionService;
import com.fu.conversation.service.ConversationService;
import com.fu.conversation.service.ConversationTopicService;
import com.fu.conversation.service.UserConversationService;
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
    private ConversationTopicService topicService;

    @MockBean
    private ConversationService conversationService;

    @MockBean
    private UserConversationService userConversationService;

    // Required by ManagerConversationController (also loaded by component scan)
    @MockBean
    private ConversationMissionService missionService;

    private UUID topicId;
    private UUID conversationId;
    private UUID myConversationId;

    private ConversationTopicSummaryResponse topicSummary;
    private ConversationSummaryResponse conversationSummary;
    private ConversationResponse conversationResponse;
    private UserConversationResponse userConversationResponse;

    @BeforeEach
    void setUp() {
        topicId          = UUID.randomUUID();
        conversationId   = UUID.randomUUID();
        myConversationId = UUID.randomUUID();

        topicSummary = ConversationTopicSummaryResponse.builder()
                .id(topicId)
                .title("Daily Shopping")
                .status(Course.ContentStatus.PUBLISHED)
                .build();

        conversationSummary = ConversationSummaryResponse.builder()
                .id(conversationId)
                .topicId(topicId)
                .title("At the Convenience Store")
                .status(Course.ContentStatus.PUBLISHED)
                .build();

        conversationResponse = ConversationResponse.builder()
                .id(conversationId)
                .topicId(topicId)
                .title("At the Convenience Store")
                .status(Course.ContentStatus.PUBLISHED)
                .build();

        userConversationResponse = UserConversationResponse.builder()
                .id(myConversationId)
                .title("My Shopping Practice")
                .status(Course.ContentStatus.DRAFT)
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/learner/conversation/topics")
    class GetPublishedTopicsTests {

        @Test
        @DisplayName("Should return 200 with published topics for LEARNER")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200WithPublishedTopics() throws Exception {
            given(topicService.getPublishedTopics(any(), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(topicSummary)));

            mockMvc.perform(get("/api/v1/learner/conversation/topics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content[0].title").value("Daily Shopping"));
        }

        @Test
        @DisplayName("Should return 200 with empty page when no published topics")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200WithEmptyPage() throws Exception {
            given(topicService.getPublishedTopics(any(), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get("/api/v1/learner/conversation/topics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isEmpty());
        }

        @Test
        @DisplayName("Should return 403 for CONTENT_MANAGER")
        @WithMockConversationUser
        void shouldReturn403ForManager() throws Exception {
            mockMvc.perform(get("/api/v1/learner/conversation/topics"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Should accept optional JLPT level filter")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldAcceptJlptLevelFilter() throws Exception {
            given(topicService.getPublishedTopics(eq(Course.JlptLevel.N5), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(topicSummary)));

            mockMvc.perform(get("/api/v1/learner/conversation/topics")
                            .param("level", "N5"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should use searchPublishedTopics when keyword is provided")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldSearchWhenKeywordProvided() throws Exception {
            given(topicService.searchPublishedTopics(eq("shopping"), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(topicSummary)));

            mockMvc.perform(get("/api/v1/learner/conversation/topics")
                            .param("keyword", "shopping"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].title").value("Daily Shopping"));
        }

        @Test
        @DisplayName("Should ignore blank keyword and return published topics")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldIgnoreBlankKeyword() throws Exception {
            given(topicService.getPublishedTopics(any(), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(topicSummary)));

            mockMvc.perform(get("/api/v1/learner/conversation/topics")
                            .param("keyword", "  "))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].title").value("Daily Shopping"));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/learner/conversation/topics/{topicId}/conversations")
    class GetConversationsByTopicTests {

        @Test
        @DisplayName("Should return 200 with conversations for topic")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200WithConversations() throws Exception {
            given(conversationService.getPublishedConversationsByTopic(eq(topicId), any()))
                    .willReturn(List.of(conversationSummary));

            mockMvc.perform(get("/api/v1/learner/conversation/topics/{topicId}/conversations", topicId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(conversationId.toString()))
                    .andExpect(jsonPath("$.data[0].title").value("At the Convenience Store"));
        }

        @Test
        @DisplayName("Should return 200 with empty list when topic has no conversations")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200WithEmptyList() throws Exception {
            given(conversationService.getPublishedConversationsByTopic(eq(topicId), any()))
                    .willReturn(List.of());

            mockMvc.perform(get("/api/v1/learner/conversation/topics/{topicId}/conversations", topicId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("Should return 403 for CONTENT_MANAGER")
        @WithMockConversationUser
        void shouldReturn403ForManager() throws Exception {
            mockMvc.perform(get("/api/v1/learner/conversation/topics/{topicId}/conversations", topicId))
                    .andExpect(status().isForbidden());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/learner/conversation/conversations/{conversationId}")
    class GetPublishedConversationTests {

        @Test
        @DisplayName("Should return 200 with full conversation detail for LEARNER")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200WithConversationDetail() throws Exception {
            given(conversationService.getPublishedConversation(conversationId))
                    .willReturn(conversationResponse);

            mockMvc.perform(get("/api/v1/learner/conversation/conversations/{conversationId}", conversationId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(conversationId.toString()))
                    .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        }

        @Test
        @DisplayName("Should return 404 when conversation is not published or not found")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn404WhenNotPublished() throws Exception {
            given(conversationService.getPublishedConversation(conversationId))
                    .willThrow(new ResourceNotFoundException("Conversation", "id", conversationId.toString()));

            mockMvc.perform(get("/api/v1/learner/conversation/conversations/{conversationId}", conversationId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 403 for CONTENT_MANAGER")
        @WithMockConversationUser
        void shouldReturn403ForManager() throws Exception {
            mockMvc.perform(get("/api/v1/learner/conversation/conversations/{conversationId}", conversationId))
                    .andExpect(status().isForbidden());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/v1/learner/conversation/my-conversations")
    class CreateMyConversationTests {

        @Test
        @DisplayName("Should return 201 when LEARNER creates a personal conversation")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn201OnCreate() throws Exception {
            CreateUserConversationRequest request = CreateUserConversationRequest.builder()
                    .title("My Shopping Practice").build();
            given(userConversationService.createConversation(any(), any()))
                    .willReturn(userConversationResponse);

            mockMvc.perform(post("/api/v1/learner/conversation/my-conversations")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.title").value("My Shopping Practice"));
        }

        @Test
        @DisplayName("Should return 400 when title is missing")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn400WhenTitleMissing() throws Exception {
            String body = "{\"scenario\":\"Some scenario\"}";

            mockMvc.perform(post("/api/v1/learner/conversation/my-conversations")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 403 for CONTENT_MANAGER")
        @WithMockConversationUser
        void shouldReturn403ForManager() throws Exception {
            CreateUserConversationRequest request = CreateUserConversationRequest.builder()
                    .title("Test").build();

            mockMvc.perform(post("/api/v1/learner/conversation/my-conversations")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/learner/conversation/my-conversations")
    class GetMyConversationsTests {

        @Test
        @DisplayName("Should return 200 with paginated personal conversations")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200WithPersonalConversations() throws Exception {
            given(userConversationService.getMyConversations(any(), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(userConversationResponse)));

            mockMvc.perform(get("/api/v1/learner/conversation/my-conversations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].title").value("My Shopping Practice"));
        }

        @Test
        @DisplayName("Should return 200 with empty page when no personal conversations")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200WithEmptyPage() throws Exception {
            given(userConversationService.getMyConversations(any(), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get("/api/v1/learner/conversation/my-conversations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isEmpty());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/learner/conversation/my-conversations/{conversationId}")
    class GetMyConversationByIdTests {

        @Test
        @DisplayName("Should return 200 when conversation is owned by user")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200WhenOwned() throws Exception {
            given(userConversationService.getMyConversation(eq(myConversationId), any()))
                    .willReturn(userConversationResponse);

            mockMvc.perform(get("/api/v1/learner/conversation/my-conversations/{conversationId}", myConversationId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(myConversationId.toString()));
        }

        @Test
        @DisplayName("Should return 404 when conversation not found or not owned")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn404WhenNotOwned() throws Exception {
            given(userConversationService.getMyConversation(eq(myConversationId), any()))
                    .willThrow(new ResourceNotFoundException("UserConversation", "id", myConversationId.toString()));

            mockMvc.perform(get("/api/v1/learner/conversation/my-conversations/{conversationId}", myConversationId))
                    .andExpect(status().isNotFound());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PUT /api/v1/learner/conversation/my-conversations/{conversationId}")
    class UpdateMyConversationTests {

        @Test
        @DisplayName("Should return 200 on successful update")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200OnUpdate() throws Exception {
            UpdateUserConversationRequest request = UpdateUserConversationRequest.builder()
                    .title("Updated Title").build();
            UserConversationResponse updated = UserConversationResponse.builder()
                    .id(myConversationId).title("Updated Title").status(Course.ContentStatus.DRAFT).build();
            given(userConversationService.updateConversation(eq(myConversationId), any(), any()))
                    .willReturn(updated);

            mockMvc.perform(put("/api/v1/learner/conversation/my-conversations/{conversationId}", myConversationId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("Updated Title"));
        }

        @Test
        @DisplayName("Should return 404 when conversation not found or not owned")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn404WhenNotOwned() throws Exception {
            UpdateUserConversationRequest request = UpdateUserConversationRequest.builder()
                    .title("X").build();
            given(userConversationService.updateConversation(eq(myConversationId), any(), any()))
                    .willThrow(new ResourceNotFoundException("UserConversation", "id", myConversationId.toString()));

            mockMvc.perform(put("/api/v1/learner/conversation/my-conversations/{conversationId}", myConversationId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("DELETE /api/v1/learner/conversation/my-conversations/{conversationId}")
    class DeleteMyConversationTests {

        @Test
        @DisplayName("Should return 200 on delete")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200OnDelete() throws Exception {
            doNothing().when(userConversationService).deleteConversation(eq(myConversationId), any());

            mockMvc.perform(delete("/api/v1/learner/conversation/my-conversations/{conversationId}", myConversationId)
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("Should return 404 when conversation not found or not owned")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn404WhenNotOwned() throws Exception {
            doThrow(new ResourceNotFoundException("UserConversation", "id", myConversationId.toString()))
                    .when(userConversationService).deleteConversation(eq(myConversationId), any());

            mockMvc.perform(delete("/api/v1/learner/conversation/my-conversations/{conversationId}", myConversationId)
                            .with(csrf()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 403 for CONTENT_MANAGER")
        @WithMockConversationUser
        void shouldReturn403ForManager() throws Exception {
            mockMvc.perform(delete("/api/v1/learner/conversation/my-conversations/{conversationId}", myConversationId)
                            .with(csrf()))
                    .andExpect(status().isForbidden());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/v1/learner/conversation/my-conversations/{conversationId}/generate-context")
    class GenerateContextTests {

        @Test
        @DisplayName("Should return 200 with updated conversation containing AI context")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn200WithGeneratedContext() throws Exception {
            UserConversationResponse withContext = UserConversationResponse.builder()
                    .id(myConversationId)
                    .title("My Shopping Practice")
                    .aiGeneratedContext("{\"vocabularyHints\":[\"買い物\"]}")
                    .status(Course.ContentStatus.DRAFT)
                    .build();
            given(userConversationService.generateContext(eq(myConversationId), any()))
                    .willReturn(withContext);

            mockMvc.perform(post("/api/v1/learner/conversation/my-conversations/{conversationId}/generate-context",
                            myConversationId)
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.aiGeneratedContext").isNotEmpty());
        }

        @Test
        @DisplayName("Should return 400 when scenario is missing (BusinessException)")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn400WhenScenarioMissing() throws Exception {
            given(userConversationService.generateContext(eq(myConversationId), any()))
                    .willThrow(new BusinessException("MISSING_SCENARIO",
                            "Conversation must have a scenario before generating context"));

            mockMvc.perform(post("/api/v1/learner/conversation/my-conversations/{conversationId}/generate-context",
                            myConversationId)
                            .with(csrf()))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 404 when conversation not found")
        @WithMockConversationUser(roles = "LEARNER")
        void shouldReturn404WhenNotFound() throws Exception {
            given(userConversationService.generateContext(eq(myConversationId), any()))
                    .willThrow(new ResourceNotFoundException("UserConversation", "id", myConversationId.toString()));

            mockMvc.perform(post("/api/v1/learner/conversation/my-conversations/{conversationId}/generate-context",
                            myConversationId)
                            .with(csrf()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 403 for CONTENT_MANAGER")
        @WithMockConversationUser
        void shouldReturn403ForManager() throws Exception {
            mockMvc.perform(post("/api/v1/learner/conversation/my-conversations/{conversationId}/generate-context",
                            myConversationId)
                            .with(csrf()))
                    .andExpect(status().isForbidden());
        }
    }
}
