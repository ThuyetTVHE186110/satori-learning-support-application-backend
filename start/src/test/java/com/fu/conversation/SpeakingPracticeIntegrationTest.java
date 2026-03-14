package com.fu.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.conversation.ai.ConversationGenerationService;
import com.fu.conversation.entity.ConversationTheme;
import com.fu.conversation.entity.ConversationTopic;
import com.fu.conversation.entity.UserConversationTopic;
import com.fu.conversation.repository.ConversationThemeRepository;
import com.fu.conversation.repository.ConversationTopicRepository;
import com.fu.conversation.repository.UserConversationTopicRepository;
import com.fu.course.entity.Course;
import com.fu.infrastructure.email.EmailService;
import com.fu.roleplay.ai.MissionEvaluatorAI;
import com.fu.roleplay.ai.RoleplayAIService;
import com.fu.roleplay.ai.SessionEvaluatorAI;
import com.fu.roleplay.dto.response.SessionEvaluationResponse;
import com.fu.roleplay.dto.entity.ConversationMessage;
import com.fu.conversation.entity.ConversationMission;
import com.fu.roleplay.dto.entity.ConversationSession;
import com.fu.roleplay.dto.entity.SessionMission;
import com.fu.roleplay.repository.ConversationMessageRepository;
import com.fu.conversation.repository.ConversationMissionRepository;
import com.fu.roleplay.repository.ConversationSessionRepository;
import com.fu.roleplay.repository.MessagePronunciationRepository;
import com.fu.roleplay.repository.SessionMissionRepository;
import com.fu.pronunciation.provider.PronunciationAssessmentProvider;
import com.fu.user.entity.AuthProvider;
import com.fu.user.entity.User;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import com.fu.user.repository.UserRepository;
import com.fu.user.repository.UserSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — Speaking Practice (Learner).
 * Covers test cases in SPEAKING_PRACTICE_TEST_PROCEDURES.md.
 */
@SpringBootTest(classes = com.fu.StartApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Speaking Practice — Integration Tests")
class SpeakingPracticeIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private ConversationThemeRepository themeRepository;
    @Autowired private ConversationTopicRepository topicRepository;
    @Autowired private UserConversationTopicRepository userTopicRepository;
    @Autowired private ConversationSessionRepository sessionRepository;
    @Autowired private ConversationMessageRepository messageRepository;
    @Autowired private ConversationMissionRepository missionRepository;
    @Autowired private SessionMissionRepository sessionMissionRepository;
    @Autowired private MessagePronunciationRepository pronunciationRepository;

    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean
    @SuppressWarnings("unused")
    private EmailService emailService;
    @MockBean private ConversationGenerationService conversationGenerationService;
    @MockBean private RoleplayAIService roleplayAIService;
    @MockBean private MissionEvaluatorAI missionEvaluatorAI;
    @MockBean private SessionEvaluatorAI sessionEvaluatorAI;
    @MockBean private PronunciationAssessmentProvider pronunciationAssessmentProvider;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User saveLearner(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("ValidPass@1"))
                .firstName("Test").lastName("Learner")
                .role(UserRole.LEARNER)
                .status(UserStatus.ACTIVE)
                .authProvider(AuthProvider.LOCAL)
                .failedLoginAttempts(0)
                .build());
    }

    @SuppressWarnings({"SameParameterValue", "UnusedReturnValue"})
    private User saveManager(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("ValidPass@1"))
                .firstName("Test").lastName("Manager")
                .role(UserRole.MANAGER)
                .status(UserStatus.ACTIVE)
                .authProvider(AuthProvider.LOCAL)
                .failedLoginAttempts(0)
                .build());
    }

    private String loginAndGetToken(String email) throws Exception {
        String body = String.format("{\"email\":\"%s\",\"password\":\"ValidPass@1\"}", email);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    private ConversationTheme saveTheme(String title, Course.ContentStatus status, int orderIndex) {
        return themeRepository.save(ConversationTheme.builder()
                .title(title)
                .status(status)
                .orderIndex(orderIndex)
                .build());
    }

    @SuppressWarnings({"SameParameterValue", "UnusedReturnValue"})
    private ConversationTheme saveThemeWithLevel(String title, Course.ContentStatus status, int orderIndex,
                                                Course.JlptLevel jlptLevel) {
        return themeRepository.save(ConversationTheme.builder()
                .title(title)
                .status(status)
                .orderIndex(orderIndex)
                .jlptLevel(jlptLevel)
                .build());
    }

    @SuppressWarnings({"SameParameterValue", "UnusedReturnValue"})
    private ConversationTheme saveSoftDeletedTheme(String title, UUID deletedBy) {
        ConversationTheme theme = themeRepository.save(ConversationTheme.builder()
                .title(title)
                .status(Course.ContentStatus.DRAFT)
                .orderIndex(0)
                .build());
        theme.setDeletedAt(LocalDateTime.now());
        theme.setDeletedBy(deletedBy);
        return themeRepository.save(theme);
    }

    private ConversationTopic saveTopic(UUID themeId, String title, Course.ContentStatus status, int orderIndex,
                                        boolean isRoleplay) {
        return topicRepository.save(ConversationTopic.builder()
                .themeId(themeId)
                .title(title)
                .status(status)
                .orderIndex(orderIndex)
                .roleplayContext("{\"aiRole\":\"shop clerk\",\"scenario\":\"buying items\"}")
                .jlptLevel(Course.JlptLevel.N5)
                .build());
    }

    @SuppressWarnings({"SameParameterValue", "UnusedReturnValue"})
    private ConversationTopic saveSoftDeletedTopic(UUID themeId, String title, UUID deletedBy) {
        ConversationTopic topic = topicRepository.save(ConversationTopic.builder()
                .themeId(themeId)
                .title(title)
                .status(Course.ContentStatus.PUBLISHED)
                .orderIndex(0)
                .build());
        topic.setDeletedAt(LocalDateTime.now());
        topic.setDeletedBy(deletedBy);
        return topicRepository.save(topic);
    }

    @SuppressWarnings({"SameParameterValue", "UnusedReturnValue"})
    private ConversationMission saveMission(UUID topicId, String title, int orderIndex) {
        return missionRepository.save(ConversationMission.builder()
                .topicId(topicId)
                .title(title)
                .orderIndex(orderIndex)
                .build());
    }

    private ConversationSession saveSession(UUID userId, UUID topicId) {
        return sessionRepository.save(ConversationSession.builder()
                .userId(userId)
                .topicId(topicId)
                .sessionType(ConversationSession.SessionType.ROLEPLAY)
                .status(ConversationSession.SessionStatus.ACTIVE)
                .context("{}")
                .conversationPhase("CONVERSATION")
                .build());
    }

    @SuppressWarnings("SameParameterValue")
    private ConversationSession saveSessionWithStatus(UUID userId, ConversationSession.SessionStatus status) {
        return sessionRepository.save(ConversationSession.builder()
                .userId(userId)
                .sessionType(ConversationSession.SessionType.ROLEPLAY)
                .status(status)
                .context("{}")
                .conversationPhase("CONVERSATION")
                .build());
    }

    @SuppressWarnings("UnusedReturnValue")
    private ConversationSession saveCompletedSession(UUID userId) {
        ConversationSession session = ConversationSession.builder()
                .userId(userId)
                .sessionType(ConversationSession.SessionType.ROLEPLAY)
                .status(ConversationSession.SessionStatus.COMPLETED)
                .context("{}")
                .conversationPhase("CONVERSATION")
                .build();
        session.setCompletedAt(LocalDateTime.now());
        return sessionRepository.save(session);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        clearAllData();
    }

    @AfterEach
    void tearDown() {
        clearAllData();
        sessionRepository.flush();
    }

    private void clearAllData() {
        pronunciationRepository.deleteAll();
        messageRepository.deleteAll();
        sessionMissionRepository.deleteAll();
        sessionRepository.deleteAll();
        missionRepository.deleteAll();
        userTopicRepository.deleteAll();
        topicRepository.deleteAll();
        themeRepository.deleteAll();
        userSessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1. View Conversation Theme List
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. View Theme List")
    class ViewThemeListTests {

        @Test
        @DisplayName("TC_SP_VTL_001 — published themes only, sorted by orderIndex")
        void tc_sp_vtl_001() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            saveTheme("Theme B", Course.ContentStatus.PUBLISHED, 2);
            saveTheme("Theme A", Course.ContentStatus.PUBLISHED, 1);
            saveTheme("Theme Draft", Course.ContentStatus.DRAFT, 0);
            saveSoftDeletedTheme("Theme Deleted", UUID.randomUUID());

            mockMvc.perform(get("/api/v1/learner/conversation/themes")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(2))
                    .andExpect(jsonPath("$.data.content[0].title").value("Theme A"))
                    .andExpect(jsonPath("$.data.content[1].title").value("Theme B"));
        }

        @Test
        @DisplayName("TC_SP_VTL_002 — keyword filter returns matching themes")
        void tc_sp_vtl_002() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            saveTheme("Shopping", Course.ContentStatus.PUBLISHED, 1);
            saveTheme("Travel", Course.ContentStatus.PUBLISHED, 2);

            mockMvc.perform(get("/api/v1/learner/conversation/themes")
                            .param("keyword", "shop")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].title").value("Shopping"));
        }

        @Test
        @DisplayName("TC_SP_VTL_003 — manager forbidden")
        void tc_sp_vtl_003() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com");

            mockMvc.perform(get("/api/v1/learner/conversation/themes")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("TC_SP_VTL_004 — no token returns 401")
        void tc_sp_vtl_004() throws Exception {
            mockMvc.perform(get("/api/v1/learner/conversation/themes"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("TC_SP_VTL_005 — filter by JLPT level")
        void tc_sp_vtl_005() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            saveThemeWithLevel("N5 Theme", Course.ContentStatus.PUBLISHED, 0, Course.JlptLevel.N5);
            saveThemeWithLevel("N4 Theme", Course.ContentStatus.PUBLISHED, 1, Course.JlptLevel.N4);

            mockMvc.perform(get("/api/v1/learner/conversation/themes")
                            .param("level", "N5")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].title").value("N5 Theme"));
        }

        @Test
        @DisplayName("TC_SP_VTL_006 — keyword blank falls back to list")
        void tc_sp_vtl_006() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            saveTheme("Theme A", Course.ContentStatus.PUBLISHED, 0);
            saveTheme("Theme B", Course.ContentStatus.PUBLISHED, 1);

            mockMvc.perform(get("/api/v1/learner/conversation/themes")
                            .param("keyword", "   ")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2));
        }

        @Test
        @DisplayName("TC_SP_VTL_007 — pagination returns correct page")
        void tc_sp_vtl_007() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            saveTheme("Theme A", Course.ContentStatus.PUBLISHED, 0);
            saveTheme("Theme B", Course.ContentStatus.PUBLISHED, 1);
            saveTheme("Theme C", Course.ContentStatus.PUBLISHED, 2);

            mockMvc.perform(get("/api/v1/learner/conversation/themes")
                            .param("page", "1")
                            .param("size", "2")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pageNumber").value(1))
                    .andExpect(jsonPath("$.data.pageSize").value(2))
                    .andExpect(jsonPath("$.data.totalElements").value(3))
                    .andExpect(jsonPath("$.data.content.length()").value(1));
        }

        @Test
        @DisplayName("TC_SP_VTL_008 — size clamp to 50")
        void tc_sp_vtl_008() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            for (int i = 0; i < 60; i++) {
                saveTheme("Theme " + i, Course.ContentStatus.PUBLISHED, i);
            }

            mockMvc.perform(get("/api/v1/learner/conversation/themes")
                            .param("size", "100")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pageSize").value(50))
                    .andExpect(jsonPath("$.data.content.length()").value(50));
        }

        @Test
        @DisplayName("TC_SP_VTL_009 — empty list returns 200 with no content")
        void tc_sp_vtl_009() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/conversation/themes")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(0))
                    .andExpect(jsonPath("$.data.content.length()").value(0));
        }

        @Test
        @DisplayName("TC_SP_VTH_019 — Theme nháp (DRAFT) hoặc lưu trữ (ARCHIVED) không được hiển thị cho Learner")
        void tc_sp_vth_019() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            saveTheme("Theme Published", Course.ContentStatus.PUBLISHED, 1);
            saveTheme("Theme Draft", Course.ContentStatus.DRAFT, 2);
            saveTheme("Theme Archived", Course.ContentStatus.ARCHIVED, 3);

            mockMvc.perform(get("/api/v1/learner/conversation/themes")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].title").value("Theme Published"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2. View Conversation Topic List
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. View Topic List")
    class ViewTopicListTests {

        @Test
        @DisplayName("TC_SP_VTP_001 — published topics only, ordered by orderIndex")
        void tc_sp_vtp_001() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);
            saveTopic(theme.getId(), "Topic B", Course.ContentStatus.PUBLISHED, 2, true);
            saveTopic(theme.getId(), "Topic A", Course.ContentStatus.PUBLISHED, 1, true);
            saveTopic(theme.getId(), "Topic Draft", Course.ContentStatus.DRAFT, 0, true);

            mockMvc.perform(get("/api/v1/learner/conversation/themes/{themeId}/topics", theme.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].title").value("Topic A"))
                    .andExpect(jsonPath("$.data[1].title").value("Topic B"));
        }

        @Test
        @DisplayName("TC_SP_VTP_002 — practice stats populated when session exists")
        void tc_sp_vtp_002() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);
            ConversationTopic topic = saveTopic(theme.getId(), "Topic A", Course.ContentStatus.PUBLISHED, 1, true);
            saveSession(learner.getId(), topic.getId());

            mockMvc.perform(get("/api/v1/learner/conversation/themes/{themeId}/topics", theme.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].practiced").value(true))
                    .andExpect(jsonPath("$.data[0].practiceCount").value(1));
        }

        @Test
        @DisplayName("TC_SP_VTP_003 — manager forbidden")
        void tc_sp_vtp_003() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);

            mockMvc.perform(get("/api/v1/learner/conversation/themes/{themeId}/topics", theme.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("TC_SP_VTP_004 — view published topic detail")
        void tc_sp_vtp_004() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);
            ConversationTopic topic = saveTopic(theme.getId(), "Topic A", Course.ContentStatus.PUBLISHED, 1, true);

            mockMvc.perform(get("/api/v1/learner/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(topic.getId().toString()))
                    .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        }

        @Test
        @DisplayName("TC_SP_VTP_005 — non-published topic detail returns 404")
        void tc_sp_vtp_005() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);
            ConversationTopic topic = saveTopic(theme.getId(), "Topic Draft", Course.ContentStatus.DRAFT, 1, true);

            mockMvc.perform(get("/api/v1/learner/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TC_SP_VTP_006 — no token returns 401")
        void tc_sp_vtp_006() throws Exception {
            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);

            mockMvc.perform(get("/api/v1/learner/conversation/themes/{themeId}/topics", theme.getId()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("TC_SP_VTP_007 — no practice stats when user has no sessions")
        void tc_sp_vtp_007() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);
            saveTopic(theme.getId(), "Topic A", Course.ContentStatus.PUBLISHED, 1, true);

            mockMvc.perform(get("/api/v1/learner/conversation/themes/{themeId}/topics", theme.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].practiced").value(false))
                    .andExpect(jsonPath("$.data[0].practiceCount").value(0));
        }

        @Test
        @DisplayName("TC_SP_VTP_008 — soft-deleted topic excluded")
        void tc_sp_vtp_008() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);
            saveTopic(theme.getId(), "Topic A", Course.ContentStatus.PUBLISHED, 1, true);
            saveSoftDeletedTopic(theme.getId(), "Deleted", learner.getId());

            mockMvc.perform(get("/api/v1/learner/conversation/themes/{themeId}/topics", theme.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].title").value("Topic A"));
        }

        @Test
        @DisplayName("TC_SP_VTP_009 — theme with no topics returns empty list")
        void tc_sp_vtp_009() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);

            mockMvc.perform(get("/api/v1/learner/conversation/themes/{themeId}/topics", theme.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("TC_SP_VTP_010 — non-existent theme returns empty list")
        void tc_sp_vtp_010() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/conversation/themes/{themeId}/topics", UUID.randomUUID())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("TC_SP_VTP_011 — practice stats map counts multiple sessions")
        void tc_sp_vtp_011() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);
            ConversationTopic topic = saveTopic(theme.getId(), "Topic A", Course.ContentStatus.PUBLISHED, 1, true);
            saveSession(learner.getId(), topic.getId());
            saveSession(learner.getId(), topic.getId());

            mockMvc.perform(get("/api/v1/learner/conversation/themes/{themeId}/topics", theme.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].practiceCount").value(2));
        }

        @Test
        @DisplayName("TC_SP_VTP_012 — topic summary payload includes required fields")
        void tc_sp_vtp_012() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);
            saveTopic(theme.getId(), "Topic A", Course.ContentStatus.PUBLISHED, 1, true);

            mockMvc.perform(get("/api/v1/learner/conversation/themes/{themeId}/topics", theme.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").exists())
                    .andExpect(jsonPath("$.data[0].title").value("Topic A"))
                    .andExpect(jsonPath("$.data[0].status").value("PUBLISHED"));
        }

        @Test
        @DisplayName("TC_SP_VTP_013 — lastPracticedAt populated when session exists")
        void tc_sp_vtp_013() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);
            ConversationTopic topic = saveTopic(theme.getId(), "Topic A", Course.ContentStatus.PUBLISHED, 1, true);
            saveSession(learner.getId(), topic.getId());

            mockMvc.perform(get("/api/v1/learner/conversation/themes/{themeId}/topics", theme.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].lastPracticedAt").isNotEmpty());
        }

        @Test
        @DisplayName("TC_SP_VTP_014 — Topic nháp (DRAFT) hoặc lưu trữ (ARCHIVED) không được hiển thị cho Learner")
        void tc_sp_vtp_014() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);
            saveTopic(theme.getId(), "Topic Published", Course.ContentStatus.PUBLISHED, 1, true);
            saveTopic(theme.getId(), "Topic Draft", Course.ContentStatus.DRAFT, 2, true);
            saveTopic(theme.getId(), "Topic Archived", Course.ContentStatus.ARCHIVED, 3, true);

            mockMvc.perform(get("/api/v1/learner/conversation/themes/{themeId}/topics", theme.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].title").value("Topic Published"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3. Practice Conversation with AI (Roleplay)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. Practice Conversation with AI")
    class PracticeConversationWithAiTests {

        @Test
        @DisplayName("TC_SP_AI_001 — start roleplay session success")
        void tc_sp_ai_001() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);
            ConversationTopic topic = saveTopic(theme.getId(), "Roleplay Topic", Course.ContentStatus.PUBLISHED, 1, true);
            saveMission(topic.getId(), "Mission 1", 0);

            when(roleplayAIService.buildSystemPrompt(any(), anyString(), anyString(), anyList()))
                    .thenReturn("prompt");
            when(roleplayAIService.generateOpeningMessage(anyString()))
                    .thenReturn("[{\"type\":\"MISSION_INTRO\",\"japanese_content\":\"Intro\",\"translation\":\"Intro\",\"romaji\":\"\"}," +
                            "{\"type\":\"SCENE_INTRO\",\"japanese_content\":\"Scene\",\"translation\":\"Scene\",\"romaji\":\"\"}," +
                            "{\"type\":\"DIALOGUE\",\"japanese_content\":\"Hello\",\"translation\":\"Hello\",\"romaji\":\"\"}]");

            String body = String.format("{\"topicId\":\"%s\"}", topic.getId());

            mockMvc.perform(post("/api/v1/learner/roleplay/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.messages.length()").value(3))
                    .andExpect(jsonPath("$.data.missions.length()").value(1));
        }

        @Test
        @DisplayName("TC_SP_AI_002 — start session with non-roleplay topic returns 404")
        void tc_sp_ai_002() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);
            ConversationTopic topic = saveTopic(theme.getId(), "Non Roleplay", Course.ContentStatus.PUBLISHED, 1, false);

            String body = String.format("{\"topicId\":\"%s\"}", topic.getId());

            mockMvc.perform(post("/api/v1/learner/roleplay/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TC_SP_AI_003 — send message success")
        void tc_sp_ai_003() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);
            ConversationTopic topic = saveTopic(theme.getId(), "Roleplay Topic", Course.ContentStatus.PUBLISHED, 1, true);
            saveMission(topic.getId(), "Mission 1", 0);

            when(roleplayAIService.buildSystemPrompt(any(), anyString(), anyString(), anyList()))
                    .thenReturn("prompt");
            when(roleplayAIService.generateOpeningMessage(anyString()))
                    .thenReturn("[{\"type\":\"MISSION_INTRO\",\"japanese_content\":\"Intro\",\"translation\":\"Intro\",\"romaji\":\"\"}]");
            when(roleplayAIService.getCachedSystemPrompt(any(UUID.class))).thenReturn("prompt");
            when(roleplayAIService.generateResponse(any(UUID.class), anyString(), anyList(), anyString()))
                    .thenReturn("[{\"type\":\"DIALOGUE\",\"japanese_content\":\"AI reply\",\"translation\":\"AI reply\",\"romaji\":\"\",\"corrections\":[]}]" );
            when(missionEvaluatorAI.evaluateProgress(anyList(), anyString(), anyList()))
                    .thenReturn(List.of());
            when(pronunciationAssessmentProvider.isAvailable()).thenReturn(false);

            String startBody = String.format("{\"topicId\":\"%s\"}", topic.getId());
            MvcResult startResult = mockMvc.perform(post("/api/v1/learner/roleplay/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(startBody)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andReturn();

            String sessionId = objectMapper.readTree(startResult.getResponse().getContentAsString())
                    .path("data").path("id").asText();

            String messageJson = "{\"content\":\"Hello\",\"hintRequest\":false}";

            mockMvc.perform(multipart("/api/v1/learner/roleplay/sessions/{sessionId}/messages", sessionId)
                            .file(new org.springframework.mock.web.MockMultipartFile(
                                    "message", "message", "application/json", messageJson.getBytes()))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.messages.length()").value(1));
        }

        @Test
        @DisplayName("TC_SP_AI_004 — complete session returns evaluation")
        void tc_sp_ai_004() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationSession session = saveSession(learner.getId(), null);
            messageRepository.save(ConversationMessage.builder()
                    .sessionId(session.getId())
                    .role(ConversationMessage.MessageRole.USER)
                    .content("Hello")
                    .sequenceNumber(1)
                    .build());
            sessionMissionRepository.save(SessionMission.builder()
                    .sessionId(session.getId())
                    .title("Mission 1")
                    .status(SessionMission.MissionStatus.IN_PROGRESS)
                    .progressPct(50)
                    .build());

            when(sessionEvaluatorAI.evaluateSession(anyList()))
                    .thenReturn(SessionEvaluationResponse.LanguageEvaluationDetail.builder()
                            .fluencyScore(java.math.BigDecimal.valueOf(70))
                            .accuracyScore(java.math.BigDecimal.valueOf(70))
                            .vocabularyScore(java.math.BigDecimal.valueOf(70))
                            .grammarScore(java.math.BigDecimal.valueOf(70))
                            .strengths(List.of("Good"))
                            .improvements(List.of("Improve"))
                            .summary("Summary")
                            .build());

            mockMvc.perform(post("/api/v1/learner/roleplay/sessions/{sessionId}/complete", session.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sessionId").value(session.getId().toString()))
                    .andExpect(jsonPath("$.data.languageEvaluation.summary").value("Summary"));
        }

        @Test
        @DisplayName("TC_SP_AI_005 — start session with draft topic returns 404")
        void tc_sp_ai_005() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);
            ConversationTopic topic = saveTopic(theme.getId(), "Draft", Course.ContentStatus.DRAFT, 1, true);

            String body = String.format("{\"topicId\":\"%s\"}", topic.getId());

            mockMvc.perform(post("/api/v1/learner/roleplay/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TC_SP_AI_006 — active session limit reached returns 400")
        void tc_sp_ai_006() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            saveSession(learner.getId(), null);
            saveSession(learner.getId(), null);
            saveSession(learner.getId(), null);

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);
            ConversationTopic topic = saveTopic(theme.getId(), "Roleplay", Course.ContentStatus.PUBLISHED, 1, true);

            String body = String.format("{\"topicId\":\"%s\"}", topic.getId());

            mockMvc.perform(post("/api/v1/learner/roleplay/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_SP_AI_007 — send message with blank content returns 400")
        void tc_sp_ai_007() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);
            ConversationTopic topic = saveTopic(theme.getId(), "Roleplay Topic", Course.ContentStatus.PUBLISHED, 1, true);
            saveMission(topic.getId(), "Mission 1", 0);

            when(roleplayAIService.buildSystemPrompt(any(), anyString(), anyString(), anyList()))
                    .thenReturn("prompt");
            when(roleplayAIService.generateOpeningMessage(anyString()))
                    .thenReturn("[{\"type\":\"MISSION_INTRO\",\"japanese_content\":\"Intro\",\"translation\":\"Intro\",\"romaji\":\"\"}]");

            String startBody = String.format("{\"topicId\":\"%s\"}", topic.getId());
            MvcResult startResult = mockMvc.perform(post("/api/v1/learner/roleplay/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(startBody)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andReturn();

            String sessionId = objectMapper.readTree(startResult.getResponse().getContentAsString())
                    .path("data").path("id").asText();

            String messageJson = "{\"content\":\"   \",\"hintRequest\":false}";

            mockMvc.perform(multipart("/api/v1/learner/roleplay/sessions/{sessionId}/messages", sessionId)
                            .file(new org.springframework.mock.web.MockMultipartFile(
                                    "message", "message", "application/json", messageJson.getBytes()))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_SP_AI_008 — send message to inactive session returns 400")
        void tc_sp_ai_008() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationSession session = saveSessionWithStatus(learner.getId(), ConversationSession.SessionStatus.COMPLETED);
            String messageJson = "{\"content\":\"Hello\",\"hintRequest\":false}";

            mockMvc.perform(multipart("/api/v1/learner/roleplay/sessions/{sessionId}/messages", session.getId())
                            .file(new org.springframework.mock.web.MockMultipartFile(
                                    "message", "message", "application/json", messageJson.getBytes()))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_SP_AI_009 — complete inactive session returns 400")
        void tc_sp_ai_009() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationSession session = saveSessionWithStatus(learner.getId(), ConversationSession.SessionStatus.COMPLETED);

            mockMvc.perform(post("/api/v1/learner/roleplay/sessions/{sessionId}/complete", session.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_SP_AI_010 — start session with missing topic returns 404")
        void tc_sp_ai_010() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            String body = String.format("{\"topicId\":\"%s\"}", UUID.randomUUID());

            mockMvc.perform(post("/api/v1/learner/roleplay/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TC_SP_AI_011 — start session caches system prompt")
        void tc_sp_ai_011() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);
            ConversationTopic topic = saveTopic(theme.getId(), "Roleplay Topic", Course.ContentStatus.PUBLISHED, 1, true);
            saveMission(topic.getId(), "Mission 1", 0);

            when(roleplayAIService.buildSystemPrompt(any(), anyString(), anyString(), anyList()))
                    .thenReturn("prompt");
            when(roleplayAIService.generateOpeningMessage(anyString()))
                    .thenReturn("[{\"type\":\"MISSION_INTRO\",\"japanese_content\":\"Intro\",\"translation\":\"Intro\",\"romaji\":\"\"}]");

            String body = String.format("{\"topicId\":\"%s\"}", topic.getId());

            mockMvc.perform(post("/api/v1/learner/roleplay/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated());

            verify(roleplayAIService).cacheSystemPrompt(any(UUID.class), eq("prompt"));
        }

        @Test
        @DisplayName("TC_SP_AI_012 — start session persists opening messages")
        void tc_sp_ai_012() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);
            ConversationTopic topic = saveTopic(theme.getId(), "Roleplay Topic", Course.ContentStatus.PUBLISHED, 1, true);
            saveMission(topic.getId(), "Mission 1", 0);

            when(roleplayAIService.buildSystemPrompt(any(), anyString(), anyString(), anyList()))
                    .thenReturn("prompt");
            when(roleplayAIService.generateOpeningMessage(anyString()))
                    .thenReturn("[{\"type\":\"MISSION_INTRO\",\"japanese_content\":\"Intro\",\"translation\":\"Intro\",\"romaji\":\"\"}," +
                            "{\"type\":\"SCENE_INTRO\",\"japanese_content\":\"Scene\",\"translation\":\"Scene\",\"romaji\":\"\"}]" );

            String body = String.format("{\"topicId\":\"%s\"}", topic.getId());

            MvcResult result = mockMvc.perform(post("/api/v1/learner/roleplay/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andReturn();

            String sessionId = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("id").asText();

            long storedMessages = messageRepository.countBySessionId(UUID.fromString(sessionId));
            assertThat(storedMessages).isEqualTo(2);
        }

        @Test
        @DisplayName("TC_SP_AI_013 — send message to non-existent session returns 404")
        void tc_sp_ai_013() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            String messageJson = "{\"content\":\"Hello\",\"hintRequest\":false}";

            mockMvc.perform(multipart("/api/v1/learner/roleplay/sessions/{sessionId}/messages", UUID.randomUUID())
                            .file(new org.springframework.mock.web.MockMultipartFile(
                                    "message", "message", "application/json", messageJson.getBytes()))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TC_SP_AI_014 — message limit exceeded returns 400")
        void tc_sp_ai_014() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationSession session = saveSession(learner.getId(), null);
            for (int i = 1; i <= 50; i++) {
                messageRepository.save(ConversationMessage.builder()
                        .sessionId(session.getId())
                        .role(ConversationMessage.MessageRole.USER)
                        .content("msg " + i)
                        .sequenceNumber(i)
                        .build());
            }

            String messageJson = "{\"content\":\"Hello\",\"hintRequest\":false}";

            mockMvc.perform(multipart("/api/v1/learner/roleplay/sessions/{sessionId}/messages", session.getId())
                            .file(new org.springframework.mock.web.MockMultipartFile(
                                    "message", "message", "application/json", messageJson.getBytes()))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_SP_AI_015 — audio too large returns 400")
        void tc_sp_ai_015() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationSession session = saveSession(learner.getId(), null);

            byte[] largeAudio = new byte[10 * 1024 * 1024 + 1];
            String messageJson = "{\"content\":\"Hello\",\"hintRequest\":false}";

            mockMvc.perform(multipart("/api/v1/learner/roleplay/sessions/{sessionId}/messages", session.getId())
                            .file(new org.springframework.mock.web.MockMultipartFile(
                                    "message", "message", "application/json", messageJson.getBytes()))
                            .file(new org.springframework.mock.web.MockMultipartFile(
                                    "audio", "audio.wav", "audio/wav", largeAudio))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_SP_AI_016 — invalid audio content type returns 400")
        void tc_sp_ai_016() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationSession session = saveSession(learner.getId(), null);

            byte[] audio = new byte[8];
            String messageJson = "{\"content\":\"Hello\",\"hintRequest\":false}";

            mockMvc.perform(multipart("/api/v1/learner/roleplay/sessions/{sessionId}/messages", session.getId())
                            .file(new org.springframework.mock.web.MockMultipartFile(
                                    "message", "message", "application/json", messageJson.getBytes()))
                            .file(new org.springframework.mock.web.MockMultipartFile(
                                    "audio", "audio.txt", "text/plain", audio))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_SP_AI_017 — pronunciation provider available with audio")
        void tc_sp_ai_017() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationSession session = saveSession(learner.getId(), null);

            when(roleplayAIService.getCachedSystemPrompt(any(UUID.class))).thenReturn("prompt");
            when(roleplayAIService.generateResponse(any(UUID.class), anyString(), anyList(), anyString()))
                    .thenReturn("[{\"type\":\"DIALOGUE\",\"japanese_content\":\"AI reply\",\"translation\":\"AI reply\",\"romaji\":\"\",\"corrections\":[]}]" );
            when(missionEvaluatorAI.evaluateProgress(anyList(), anyString(), anyList()))
                    .thenReturn(List.of());
            when(pronunciationAssessmentProvider.isAvailable()).thenReturn(true);

            byte[] audio = new byte[16];
            String messageJson = "{\"content\":\"Hello\",\"hintRequest\":false}";

            mockMvc.perform(multipart("/api/v1/learner/roleplay/sessions/{sessionId}/messages", session.getId())
                            .file(new org.springframework.mock.web.MockMultipartFile(
                                    "message", "message", "application/json", messageJson.getBytes()))
                            .file(new org.springframework.mock.web.MockMultipartFile(
                                    "audio", "audio.wav", "audio/wav", audio))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.messages.length()").value(1));
        }

        @Test
        @DisplayName("TC_SP_AI_018 — free-talk phase transition creates dynamic mission")
        void tc_sp_ai_018() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            when(roleplayAIService.buildSetupSystemPrompt(anyString())).thenReturn("setup");
            when(roleplayAIService.generateSetupOpening(anyString()))
                    .thenReturn("[{\"type\":\"DIALOGUE\",\"japanese_content\":\"Question\",\"translation\":\"Question\",\"romaji\":\"\",\"corrections\":[]}]" );

            String startBody = "{\"jlptLevel\":\"N5\",\"language\":\"vi\"}";
            MvcResult startResult = mockMvc.perform(post("/api/v1/learner/roleplay/free-talk/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(startBody)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andReturn();

            String sessionId = objectMapper.readTree(startResult.getResponse().getContentAsString())
                    .path("data").path("id").asText();

            when(roleplayAIService.getCachedSystemPrompt(any(UUID.class))).thenReturn("setup");
            when(roleplayAIService.generateResponse(any(UUID.class), anyString(), anyList(), anyString()))
                    .thenReturn("[{\"type\":\"PHASE_TRANSITION\",\"topicSummary\":\"Shopping\",\"missionSummary\":\"Mua hang\"}," +
                            "{\"type\":\"DIALOGUE\",\"japanese_content\":\"Hello\",\"translation\":\"Hello\",\"romaji\":\"\",\"corrections\":[]}]" );
            when(roleplayAIService.buildSystemPrompt(any(), anyString(), anyString(), anyList()))
                    .thenReturn("prompt2");
            when(missionEvaluatorAI.evaluateProgress(anyList(), anyString(), anyList()))
                    .thenReturn(List.of());

            String messageJson = "{\"content\":\"I want to practice shopping\",\"hintRequest\":false}";

            mockMvc.perform(multipart("/api/v1/learner/roleplay/sessions/{sessionId}/messages", sessionId)
                            .file(new org.springframework.mock.web.MockMultipartFile(
                                    "message", "message", "application/json", messageJson.getBytes()))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.missions.length()").value(1));
        }

        @Test
        @DisplayName("TC_SP_AI_019 — mission progress updates after send message")
        void tc_sp_ai_019() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationTheme theme = saveTheme("Theme", Course.ContentStatus.PUBLISHED, 0);
            ConversationTopic topic = saveTopic(theme.getId(), "Roleplay Topic", Course.ContentStatus.PUBLISHED, 1, true);
            saveMission(topic.getId(), "Mission 1", 0);

            when(roleplayAIService.buildSystemPrompt(any(), anyString(), anyString(), anyList()))
                    .thenReturn("prompt");
            when(roleplayAIService.generateOpeningMessage(anyString()))
                    .thenReturn("[{\"type\":\"MISSION_INTRO\",\"japanese_content\":\"Intro\",\"translation\":\"Intro\",\"romaji\":\"\"}]");
            when(roleplayAIService.getCachedSystemPrompt(any(UUID.class))).thenReturn("prompt");
            when(roleplayAIService.generateResponse(any(UUID.class), anyString(), anyList(), anyString()))
                    .thenReturn("[{\"type\":\"DIALOGUE\",\"japanese_content\":\"AI reply\",\"translation\":\"AI reply\",\"romaji\":\"\",\"corrections\":[]}]" );
            when(pronunciationAssessmentProvider.isAvailable()).thenReturn(false);

            String startBody = String.format("{\"topicId\":\"%s\"}", topic.getId());
            MvcResult startResult = mockMvc.perform(post("/api/v1/learner/roleplay/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(startBody)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andReturn();

            String sessionId = objectMapper.readTree(startResult.getResponse().getContentAsString())
                    .path("data").path("id").asText();
            UUID sessionUuid = UUID.fromString(sessionId);

            List<SessionMission> missions = sessionMissionRepository.findBySessionIdOrderByCreatedAtAsc(sessionUuid);
            String missionId = missions.getFirst().getId().toString();

            when(missionEvaluatorAI.evaluateProgress(anyList(), anyString(), anyList()))
                    .thenReturn(List.of(MissionEvaluatorAI.MissionProgress.builder()
                            .missionId(missionId)
                            .status("COMPLETED")
                            .progressPct(100)
                            .reasoning("Done")
                            .build()));

            String messageJson = "{\"content\":\"Hello\",\"hintRequest\":false}";

            mockMvc.perform(multipart("/api/v1/learner/roleplay/sessions/{sessionId}/messages", sessionId)
                            .file(new org.springframework.mock.web.MockMultipartFile(
                                    "message", "message", "application/json", messageJson.getBytes()))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            SessionMission updated = sessionMissionRepository.findBySessionIdOrderByCreatedAtAsc(sessionUuid).getFirst();
            assertThat(updated.getProgressPct()).isEqualTo(100);
            assertThat(updated.getStatus()).isEqualTo(SessionMission.MissionStatus.COMPLETED);
        }

        @Test
        @DisplayName("TC_SP_AI_020 — session history pagination")
        void tc_sp_ai_020() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            saveCompletedSession(learner.getId());
            saveCompletedSession(learner.getId());

            mockMvc.perform(get("/api/v1/learner/roleplay/sessions")
                            .param("page", "0")
                            .param("size", "1")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.content.length()").value(1));
        }

        @Test
        @DisplayName("TC_SP_AI_021 — abandon session sets status")
        void tc_sp_ai_021() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationSession session = saveSession(learner.getId(), null);

            mockMvc.perform(post("/api/v1/learner/roleplay/sessions/{sessionId}/abandon", session.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            ConversationSession updated = sessionRepository.findById(session.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ConversationSession.SessionStatus.ABANDONED);
        }

        @Test
        @DisplayName("TC_SP_AI_022 — get session returns messages and missions")
        void tc_sp_ai_022() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationSession session = saveSession(learner.getId(), null);
            messageRepository.save(ConversationMessage.builder()
                    .sessionId(session.getId())
                    .role(ConversationMessage.MessageRole.USER)
                    .content("Hello")
                    .sequenceNumber(1)
                    .build());
            sessionMissionRepository.save(SessionMission.builder()
                    .sessionId(session.getId())
                    .title("Mission 1")
                    .status(SessionMission.MissionStatus.IN_PROGRESS)
                    .progressPct(20)
                    .build());

            mockMvc.perform(get("/api/v1/learner/roleplay/sessions/{sessionId}", session.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.messages.length()").value(1))
                    .andExpect(jsonPath("$.data.missions.length()").value(1));
        }

        @Test
        @DisplayName("TC_SP_AI_023 — get session not found returns 404")
        void tc_sp_ai_023() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/roleplay/sessions/{sessionId}", UUID.randomUUID())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TC_SP_AI_024 — abandon non-existent session returns 404")
        void tc_sp_ai_024() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(post("/api/v1/learner/roleplay/sessions/{sessionId}/abandon", UUID.randomUUID())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TC_SP_AI_025 — abandon inactive session returns 400")
        void tc_sp_ai_025() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            ConversationSession session = saveSessionWithStatus(learner.getId(), ConversationSession.SessionStatus.COMPLETED);

            mockMvc.perform(post("/api/v1/learner/roleplay/sessions/{sessionId}/abandon", session.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. Create Custom Conversation Topic
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. Create Custom Conversation Topic")
    class CreateCustomConversationTopicTests {

        @Test
        @DisplayName("TC_SP_CT_001 — create personal topic")
        void tc_sp_ct_001() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            String body = "{\"title\":\"My Topic\",\"scenario\":\"Practice shopping\"}";

            mockMvc.perform(post("/api/v1/learner/conversation/my-topics")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.title").value("My Topic"));

            List<UserConversationTopic> topics = userTopicRepository.findByUserId(learner.getId(),
                    org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
            assertThat(topics).hasSize(1);
        }

        @Test
        @DisplayName("TC_SP_CT_002 — generate context with scenario")
        void tc_sp_ct_002() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            UserConversationTopic topic = userTopicRepository.save(UserConversationTopic.builder()
                    .userId(learner.getId())
                    .title("My Topic")
                    .scenario("Practice shopping")
                    .status(Course.ContentStatus.DRAFT)
                    .build());

            when(conversationGenerationService.generateUserTopicContext(anyString(), any()))
                    .thenReturn("{\"vocabularyHints\":[\"a\"]}");

            mockMvc.perform(post("/api/v1/learner/conversation/my-topics/{id}/generate-context", topic.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.aiGeneratedContext").isNotEmpty());
        }

        @Test
        @DisplayName("TC_SP_CT_003 — generate context without scenario returns 400")
        void tc_sp_ct_003() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            UserConversationTopic topic = userTopicRepository.save(UserConversationTopic.builder()
                    .userId(learner.getId())
                    .title("My Topic")
                    .scenario(null)
                    .status(Course.ContentStatus.DRAFT)
                    .build());

            mockMvc.perform(post("/api/v1/learner/conversation/my-topics/{id}/generate-context", topic.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_SP_CT_004 — start free-talk session")
        void tc_sp_ct_004() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            when(roleplayAIService.buildSetupSystemPrompt(anyString())).thenReturn("setup");
            when(roleplayAIService.generateSetupOpening(anyString()))
                    .thenReturn("[{\"type\":\"DIALOGUE\",\"japanese_content\":\"Question\",\"translation\":\"Question\",\"romaji\":\"\",\"corrections\":[]}]" );

            String body = "{\"jlptLevel\":\"N5\",\"language\":\"vi\"}";

            mockMvc.perform(post("/api/v1/learner/roleplay/free-talk/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.messages.length()").value(1));
        }

        @Test
        @DisplayName("TC_SP_CT_005 — create personal topic with blank title returns 400")
        void tc_sp_ct_005() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            String body = "{\"title\":\"  \",\"scenario\":\"Practice shopping\"}";

            mockMvc.perform(post("/api/v1/learner/conversation/my-topics")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_SP_CT_006 — no token returns 401")
        void tc_sp_ct_006() throws Exception {
            String body = "{\"title\":\"My Topic\",\"scenario\":\"Practice shopping\"}";

            mockMvc.perform(post("/api/v1/learner/conversation/my-topics")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("TC_SP_CT_007 — free-talk invalid jlpt level returns 400")
        void tc_sp_ct_007() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            String body = "{\"jlptLevel\":\"N9\",\"language\":\"vi\"}";

            mockMvc.perform(post("/api/v1/learner/roleplay/free-talk/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_SP_CT_008 — list my topics pagination")
        void tc_sp_ct_008() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            userTopicRepository.save(UserConversationTopic.builder()
                    .userId(learner.getId())
                    .title("Topic 1")
                    .scenario("Scenario 1")
                    .status(Course.ContentStatus.DRAFT)
                    .build());
            userTopicRepository.save(UserConversationTopic.builder()
                    .userId(learner.getId())
                    .title("Topic 2")
                    .scenario("Scenario 2")
                    .status(Course.ContentStatus.DRAFT)
                    .build());

            mockMvc.perform(get("/api/v1/learner/conversation/my-topics")
                            .param("page", "0")
                            .param("size", "1")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.content.length()").value(1));
        }

        @Test
        @DisplayName("TC_SP_CT_009 — get my topic by id")
        void tc_sp_ct_009() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            UserConversationTopic topic = userTopicRepository.save(UserConversationTopic.builder()
                    .userId(learner.getId())
                    .title("Topic 1")
                    .scenario("Scenario 1")
                    .status(Course.ContentStatus.DRAFT)
                    .build());

            mockMvc.perform(get("/api/v1/learner/conversation/my-topics/{id}", topic.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("Topic 1"));
        }

        @Test
        @DisplayName("TC_SP_CT_010 — update my topic")
        void tc_sp_ct_010() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            UserConversationTopic topic = userTopicRepository.save(UserConversationTopic.builder()
                    .userId(learner.getId())
                    .title("Topic 1")
                    .scenario("Scenario 1")
                    .status(Course.ContentStatus.DRAFT)
                    .build());

            String body = "{\"title\":\"Updated\",\"scenario\":\"New scenario\"}";

            mockMvc.perform(put("/api/v1/learner/conversation/my-topics/{id}", topic.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("Updated"));
        }

        @Test
        @DisplayName("TC_SP_CT_011 — delete my topic")
        void tc_sp_ct_011() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            UserConversationTopic topic = userTopicRepository.save(UserConversationTopic.builder()
                    .userId(learner.getId())
                    .title("Topic 1")
                    .scenario("Scenario 1")
                    .status(Course.ContentStatus.DRAFT)
                    .build());

            mockMvc.perform(delete("/api/v1/learner/conversation/my-topics/{id}", topic.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/v1/learner/conversation/my-topics/{id}", topic.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TC_SP_CT_012 — access another user's topic returns 404")
        void tc_sp_ct_012() throws Exception {
            User owner = saveLearner("owner@test.com");
            saveLearner("other@test.com");
            String otherToken = loginAndGetToken("other@test.com");

            UserConversationTopic topic = userTopicRepository.save(UserConversationTopic.builder()
                    .userId(owner.getId())
                    .title("Topic 1")
                    .scenario("Scenario 1")
                    .status(Course.ContentStatus.DRAFT)
                    .build());

            mockMvc.perform(get("/api/v1/learner/conversation/my-topics/{id}", topic.getId())
                            .header("Authorization", "Bearer " + otherToken))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TC_SP_CT_013 — generate context with blank scenario returns 400")
        void tc_sp_ct_013() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            UserConversationTopic topic = userTopicRepository.save(UserConversationTopic.builder()
                    .userId(learner.getId())
                    .title("Topic 1")
                    .scenario("   ")
                    .status(Course.ContentStatus.DRAFT)
                    .build());

            mockMvc.perform(post("/api/v1/learner/conversation/my-topics/{id}/generate-context", topic.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_SP_CT_014 — generate context persists aiGeneratedContext")
        void tc_sp_ct_014() throws Exception {
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            UserConversationTopic topic = userTopicRepository.save(UserConversationTopic.builder()
                    .userId(learner.getId())
                    .title("Topic 1")
                    .scenario("Scenario 1")
                    .status(Course.ContentStatus.DRAFT)
                    .build());

            when(conversationGenerationService.generateUserTopicContext(anyString(), any()))
                    .thenReturn("{\"vocabularyHints\":[\"a\"]}");

            mockMvc.perform(post("/api/v1/learner/conversation/my-topics/{id}/generate-context", topic.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            UserConversationTopic stored = userTopicRepository.findById(topic.getId()).orElseThrow();
            assertThat(stored.getAiGeneratedContext()).contains("vocabularyHints");
        }
    }
}
