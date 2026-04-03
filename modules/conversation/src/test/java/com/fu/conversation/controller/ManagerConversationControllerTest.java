package com.fu.conversation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.config.ConversationModuleTestConfig;
import com.fu.conversation.dto.request.CreateConversationRequest;
import com.fu.conversation.dto.request.CreateMissionRequest;
import com.fu.conversation.dto.request.CreateTopicRequest;
import com.fu.conversation.dto.request.GenerateTopicRequest;
import com.fu.conversation.dto.request.UpdateTopicRequest;
import com.fu.conversation.dto.response.ConversationMissionResponse;
import com.fu.conversation.dto.response.ConversationResponse;
import com.fu.conversation.dto.response.ConversationTopicResponse;
import com.fu.conversation.dto.response.ConversationTopicSummaryResponse;
import com.fu.conversation.dto.response.GeneratedTopicSuggestionResponse;
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
    private ConversationTopicService topicService;

    @MockBean
    private ConversationService conversationService;

    @MockBean
    private ConversationMissionService missionService;

    // Required by LearnerConversationController (also loaded by component scan)
    @MockBean
    private UserConversationService userConversationService;

    private UUID topicId;
    private UUID conversationId;
    private UUID missionId;

    private ConversationTopicResponse topicResponse;
    private ConversationTopicSummaryResponse topicSummary;
    private ConversationResponse conversationResponse;
    private ConversationMissionResponse missionResponse;

    @BeforeEach
    void setUp() {
        topicId        = UUID.randomUUID();
        conversationId = UUID.randomUUID();
        missionId      = UUID.randomUUID();

        topicResponse = ConversationTopicResponse.builder()
                .id(topicId)
                .title("Daily Life")
                .status(Course.ContentStatus.DRAFT)
                .build();

        topicSummary = ConversationTopicSummaryResponse.builder()
                .id(topicId)
                .title("Daily Life")
                .build();

        conversationResponse = ConversationResponse.builder()
                .id(conversationId)
                .topicId(topicId)
                .title("At the Convenience Store")
                .status(Course.ContentStatus.DRAFT)
                .build();

        missionResponse = ConversationMissionResponse.builder()
                .id(missionId)
                .conversationId(conversationId)
                .title("Buy a snack")
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/v1/manager/conversation/topics/generate")
    class GenerateTopicsTests {

        @Test
        @DisplayName("Should return 200 with suggestions for CONTENT_MANAGER")
        @WithMockConversationUser
        void shouldReturn200WithSuggestions() throws Exception {
            GenerateTopicRequest request = GenerateTopicRequest.builder().count(2).build();
            GeneratedTopicSuggestionResponse suggestion = GeneratedTopicSuggestionResponse.builder()
                    .title("Shopping").build();
            given(topicService.generateTopicSuggestions(any())).willReturn(List.of(suggestion));

            mockMvc.perform(post("/api/v1/manager/conversation/topics/generate")
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
            GenerateTopicRequest request = GenerateTopicRequest.builder().count(2).build();

            mockMvc.perform(post("/api/v1/manager/conversation/topics/generate")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/v1/manager/conversation/topics")
    class CreateTopicTests {

        @Test
        @DisplayName("Should return 201 with created topic for CONTENT_MANAGER")
        @WithMockConversationUser
        void shouldReturn201ForManager() throws Exception {
            CreateTopicRequest request = CreateTopicRequest.builder()
                    .title("Daily Life").build();
            given(topicService.createTopic(any(), any())).willReturn(topicResponse);

            mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(topicId.toString()))
                    .andExpect(jsonPath("$.data.title").value("Daily Life"));
        }

        @Test
        @DisplayName("Should return 400 when title is missing")
        @WithMockConversationUser
        void shouldReturn400WhenTitleMissing() throws Exception {
            String body = "{}";

            mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/manager/conversation/topics")
    class GetAllTopicsTests {

        @Test
        @DisplayName("Should return paginated topics for CONTENT_MANAGER")
        @WithMockConversationUser
        void shouldReturnPaginatedTopics() throws Exception {
            given(topicService.getAllTopics(any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(topicSummary)));

            mockMvc.perform(get("/api/v1/manager/conversation/topics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].id").value(topicId.toString()));
        }

        @Test
        @DisplayName("Should delegate to searchTopics when keyword provided")
        @WithMockConversationUser
        void shouldDelegateToSearchWhenKeywordProvided() throws Exception {
            given(topicService.searchTopics(eq("shop"), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(topicSummary)));

            mockMvc.perform(get("/api/v1/manager/conversation/topics").param("keyword", "shop"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].id").value(topicId.toString()));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/manager/conversation/topics/{topicId}")
    class GetTopicTests {

        @Test
        @DisplayName("Should return topic when found")
        @WithMockConversationUser
        void shouldReturnTopicWhenFound() throws Exception {
            given(topicService.getTopic(topicId)).willReturn(topicResponse);

            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}", topicId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(topicId.toString()));
        }

        @Test
        @DisplayName("Should return 404 when topic not found")
        @WithMockConversationUser
        void shouldReturn404WhenTopicNotFound() throws Exception {
            given(topicService.getTopic(topicId))
                    .willThrow(new ResourceNotFoundException("ConversationTopic", "id", topicId.toString()));

            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}", topicId))
                    .andExpect(status().isNotFound());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PUT /api/v1/manager/conversation/topics/{topicId}")
    class UpdateTopicTests {

        @Test
        @DisplayName("Should return 200 with updated topic")
        @WithMockConversationUser
        void shouldReturn200WithUpdatedTopic() throws Exception {
            UpdateTopicRequest request = UpdateTopicRequest.builder().title("Updated").build();
            given(topicService.updateTopic(eq(topicId), any(), any())).willReturn(topicResponse);

            mockMvc.perform(put("/api/v1/manager/conversation/topics/{topicId}", topicId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(topicId.toString()));
        }

        @Test
        @DisplayName("Should return 404 when topic not found")
        @WithMockConversationUser
        void shouldReturn404WhenTopicNotFound() throws Exception {
            UpdateTopicRequest request = UpdateTopicRequest.builder().title("Updated").build();
            given(topicService.updateTopic(eq(topicId), any(), any()))
                    .willThrow(new ResourceNotFoundException("ConversationTopic", "id", topicId.toString()));

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
        @DisplayName("Should return 200 with published topic")
        @WithMockConversationUser
        void shouldReturn200WithPublishedTopic() throws Exception {
            ConversationTopicResponse published = ConversationTopicResponse.builder()
                    .id(topicId).status(Course.ContentStatus.PUBLISHED).title("Daily Life").build();
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
    @DisplayName("DELETE /api/v1/manager/conversation/topics/{topicId}")
    class DeleteTopicTests {

        @Test
        @DisplayName("Should return 200 when topic deleted")
        @WithMockConversationUser
        void shouldReturn200WhenTopicDeleted() throws Exception {
            doNothing().when(topicService).deleteTopic(eq(topicId), any());

            mockMvc.perform(delete("/api/v1/manager/conversation/topics/{topicId}", topicId)
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("Should return 404 when topic not found")
        @WithMockConversationUser
        void shouldReturn404WhenTopicNotFound() throws Exception {
            doThrow(new ResourceNotFoundException("ConversationTopic", "id", topicId.toString()))
                    .when(topicService).deleteTopic(eq(topicId), any());

            mockMvc.perform(delete("/api/v1/manager/conversation/topics/{topicId}", topicId)
                            .with(csrf()))
                    .andExpect(status().isNotFound());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/v1/manager/conversation/conversations")
    class CreateConversationTests {

        @Test
        @DisplayName("Should return 201 with created conversation")
        @WithMockConversationUser
        void shouldReturn201WithCreatedConversation() throws Exception {
            CreateConversationRequest request = CreateConversationRequest.builder()
                    .topicId(topicId).title("At the Store").build();
            given(conversationService.createConversation(any(), any())).willReturn(conversationResponse);

            mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(conversationId.toString()));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/v1/manager/conversation/missions")
    class CreateMissionTests {

        @Test
        @DisplayName("Should return 201 with created mission")
        @WithMockConversationUser
        void shouldReturn201WithCreatedMission() throws Exception {
            CreateMissionRequest request = CreateMissionRequest.builder()
                    .conversationId(conversationId)
                    .title("Buy a snack")
                    .build();
            given(missionService.createMission(any(), any())).willReturn(missionResponse);

            mockMvc.perform(post("/api/v1/manager/conversation/missions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.conversationId").value(conversationId.toString()));
        }

        @Test
        @DisplayName("Should return 400 when conversationId is missing")
        @WithMockConversationUser
        void shouldReturn400WhenConversationIdMissing() throws Exception {
            String body = "{\"title\": \"Buy a snack\"}";

            mockMvc.perform(post("/api/v1/manager/conversation/missions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/manager/conversation/conversations/{conversationId}/missions")
    class GetMissionsByConversationTests {

        @Test
        @DisplayName("Should return list of missions for a conversation")
        @WithMockConversationUser
        void shouldReturnMissionsForConversation() throws Exception {
            given(missionService.getMissionsByConversation(conversationId))
                    .willReturn(List.of(missionResponse));

            mockMvc.perform(get("/api/v1/manager/conversation/conversations/{conversationId}/missions",
                            conversationId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(missionId.toString()));
        }

        @Test
        @DisplayName("Should return empty list when conversation has no missions")
        @WithMockConversationUser
        void shouldReturnEmptyListWhenNoMissions() throws Exception {
            given(missionService.getMissionsByConversation(conversationId))
                    .willReturn(List.of());

            mockMvc.perform(get("/api/v1/manager/conversation/conversations/{conversationId}/missions",
                            conversationId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }
}
