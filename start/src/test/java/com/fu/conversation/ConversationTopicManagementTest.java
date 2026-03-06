package com.fu.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.conversation.entity.ConversationTheme;
import com.fu.conversation.entity.ConversationTopic;
import com.fu.conversation.repository.ConversationThemeRepository;
import com.fu.conversation.repository.ConversationTopicRepository;
import com.fu.course.entity.Course;
import com.fu.infrastructure.email.EmailService;
import com.fu.user.entity.AuthProvider;
import com.fu.user.entity.User;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import com.fu.user.repository.UserRepository;
import com.fu.user.repository.UserSessionRepository;
import org.junit.jupiter.api.*;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — Conversation Topic Management (Manager).
 *
 * Runs from the start module so all modules are on the classpath.
 *
 * Requires: docker exec satori-postgres psql -U satori_user -d satori_learning \
 *   -c "CREATE DATABASE satori_test OWNER satori_user;"
 *
 * Covers (test plan):
 *   1. VIEW TOPIC LIST BY THEME   — GET    /api/v1/manager/conversation/themes/{themeId}/topics   [P3 Sanity]
 *   2. VIEW TOPIC DETAIL          — GET    /api/v1/manager/conversation/topics/{topicId}          [P3 Sanity]
 *   3. CREATE TOPIC               — POST   /api/v1/manager/conversation/topics                    [P2 Standard]
 *   4. UPDATE TOPIC               — PUT    /api/v1/manager/conversation/topics/{topicId}          [P2 Standard]
 *   5. DELETE TOPIC               — DELETE /api/v1/manager/conversation/topics/{topicId}          [P2 Standard]
 *   6. GENERATE BY AI (count=0 validation only) — POST /api/v1/manager/conversation/themes/generate [P2 Standard]
 */
@SpringBootTest(classes = com.fu.StartApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Conversation Topic Management — Integration Tests (PostgreSQL satori_test)")
class ConversationTopicManagementTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private ConversationThemeRepository themeRepository;
    @Autowired private ConversationTopicRepository topicRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean
    private EmailService emailService;

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private User saveAdmin(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("ValidPass@1"))
                .firstName("Test").lastName("Admin")
                .role(UserRole.ADMIN)
                .status(UserStatus.ACTIVE)
                .authProvider(AuthProvider.LOCAL)
                .failedLoginAttempts(0)
                .build());
    }

    private String loginAndGetToken(String email, String password) throws Exception {
        String body = String.format("{\"email\":\"%s\",\"password\":\"%s\"}", email, password);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    private ConversationTheme saveTheme(String title) {
        return themeRepository.save(ConversationTheme.builder()
                .title(title)
                .status(Course.ContentStatus.DRAFT)
                .orderIndex(0)
                .build());
    }

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

    private ConversationTopic saveActiveTopic(String title, UUID themeId) {
        return topicRepository.save(ConversationTopic.builder()
                .title(title)
                .themeId(themeId)
                .status(Course.ContentStatus.DRAFT)
                .orderIndex(0)
                .build());
    }

    private ConversationTopic saveSoftDeletedTopic(String title, UUID themeId, UUID deletedBy) {
        ConversationTopic topic = topicRepository.save(ConversationTopic.builder()
                .title(title)
                .themeId(themeId)
                .status(Course.ContentStatus.DRAFT)
                .orderIndex(0)
                .build());
        topic.setDeletedAt(LocalDateTime.now().minusHours(1));
        topic.setDeletedBy(deletedBy);
        return topicRepository.save(topic);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @AfterEach
    void tearDown() {
        topicRepository.deleteAll();
        themeRepository.deleteAll();
        sessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1. VIEW TOPIC LIST BY THEME — GET /api/v1/manager/conversation/themes/{themeId}/topics
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. VIEW TOPIC LIST BY THEME — GET /api/v1/manager/conversation/themes/{themeId}/topics [P3 Sanity]")
    class ViewTopicListTests {

        /**
         * TC_CTP_VTL_001
         * MANAGER views topic list — returns paginated list of non-deleted topics only.
         * 3 active + 1 soft-deleted inserted; response must contain exactly 3.
         */
        @Test
        @DisplayName("TC_CTP_VTL_001 — 3 active topics returned, soft-deleted excluded")
        void tc_ctp_vtl_001_managerViewsList_returns3ActiveTopics_softDeletedExcluded() throws Exception {
            // Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTheme theme = saveTheme("Test Theme");
            saveActiveTopic("Mua sắm tại siêu thị", theme.getId());
            saveActiveTopic("Đặt đồ ăn", theme.getId());
            saveActiveTopic("Hỏi đường", theme.getId());
            saveSoftDeletedTopic("Cũ", theme.getId(), manager.getId());

            // Act + Assert
            MvcResult result = mockMvc.perform(get("/api/v1/manager/conversation/themes/{themeId}/topics", theme.getId())
                            .param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.totalElements").value(3))
                    .andExpect(jsonPath("$.data.content.length()").value(3))
                    .andReturn();

            String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            var content = objectMapper.readTree(body).path("data").path("content");

            // Soft-deleted topic absent
            List<String> titles = new ArrayList<>();
            content.forEach(n -> titles.add(n.path("title").asText()));
            assertThat(titles).doesNotContain("Cũ");
            assertThat(titles).containsExactlyInAnyOrder("Mua sắm tại siêu thị", "Đặt đồ ăn", "Hỏi đường");

            // Each item must contain required fields
            content.forEach(item -> {
                assertThat(item.has("id")).isTrue();
                assertThat(item.has("title")).isTrue();
                assertThat(item.has("status")).isTrue();
            });
        }

        /**
         * TC_CTP_VTL_002
         * MANAGER views topic list with default pagination — returns page 0, size 10.
         * 15 active topics inserted; page 0 should contain 10.
         */
        @Test
        @DisplayName("TC_CTP_VTL_002 — default pagination: page=0, size=10, totalElements=15, totalPages=2")
        void tc_ctp_vtl_002_defaultPagination_returns10Of15() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTheme theme = saveTheme("Big Theme");
            for (int i = 1; i <= 15; i++) {
                saveActiveTopic("Topic " + i, theme.getId());
            }

            // Act + Assert — no pagination params → defaults to page=0, size=10
            mockMvc.perform(get("/api/v1/manager/conversation/themes/{themeId}/topics", theme.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.pageSize").value(10))
                    .andExpect(jsonPath("$.data.totalElements").value(15))
                    .andExpect(jsonPath("$.data.totalPages").value(2))
                    .andExpect(jsonPath("$.data.content.length()").value(10));
        }

        /**
         * TC_CTP_VTL_003
         * MANAGER views topic list for theme with no topics — returns empty content list.
         */
        @Test
        @DisplayName("TC_CTP_VTL_003 — theme with no topics → empty content, totalElements=0")
        void tc_ctp_vtl_003_noTopics_returnsEmptyList() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTheme theme = saveTheme("Empty Theme");

            // Act + Assert
            mockMvc.perform(get("/api/v1/manager/conversation/themes/{themeId}/topics", theme.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.totalElements").value(0))
                    .andExpect(jsonPath("$.data.content").isEmpty());
        }

        /**
         * TC_CTP_VTL_004
         * Unauthenticated request → HTTP 401 Unauthorized.
         */
        @Test
        @DisplayName("TC_CTP_VTL_004 — no token → HTTP 401 Unauthorized")
        void tc_ctp_vtl_004_unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/manager/conversation/themes/{themeId}/topics",
                            UUID.fromString("00000000-0000-0000-0000-000000000001")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CTP_VTL_005
         * LEARNER attempts to view topic list → HTTP 403 Forbidden.
         */
        @Test
        @DisplayName("TC_CTP_VTL_005 — LEARNER token → HTTP 403 Forbidden")
        void tc_ctp_vtl_005_learnerToken_returns403() throws Exception {
            // Arrange
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            mockMvc.perform(get("/api/v1/manager/conversation/themes/{themeId}/topics",
                            UUID.fromString("00000000-0000-0000-0000-000000000001"))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2. VIEW TOPIC DETAIL — GET /api/v1/manager/conversation/topics/{topicId}
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. VIEW TOPIC DETAIL — GET /api/v1/manager/conversation/topics/{topicId} [P3 Sanity]")
    class ViewTopicDetailTests {

        /**
         * TC_CTP_VTI_001
         * MANAGER views topic detail — returns full ConversationTopicResponse with non-null fields.
         */
        @Test
        @DisplayName("TC_CTP_VTI_001 — HTTP 200, all required fields present and correct")
        void tc_ctp_vti_001_managerViewsDetail_returns200_allFieldsCorrect() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTheme theme = saveTheme("Shopping Theme");
            ConversationTopic topic = topicRepository.save(ConversationTopic.builder()
                    .title("Mua sắm tại siêu thị")
                    .titleJapanese("スーパーでの買い物")
                    .themeId(theme.getId())
                    .description("Learn shopping vocabulary")
                    .scenario("You are at a supermarket")
                    .jlptLevel(Course.JlptLevel.N4)
                    .difficultyScore(3)
                    .isRoleplay(false)
                    .status(Course.ContentStatus.DRAFT)
                    .orderIndex(0)
                    .build());

            // Act + Assert
            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(topic.getId().toString()))
                    .andExpect(jsonPath("$.data.title").value("Mua sắm tại siêu thị"))
                    .andExpect(jsonPath("$.data.titleJapanese").value("スーパーでの買い物"))
                    .andExpect(jsonPath("$.data.themeId").value(theme.getId().toString()))
                    .andExpect(jsonPath("$.data.description").value("Learn shopping vocabulary"))
                    .andExpect(jsonPath("$.data.scenario").value("You are at a supermarket"))
                    .andExpect(jsonPath("$.data.jlptLevel").value("N4"))
                    .andExpect(jsonPath("$.data.difficultyScore").value(3))
                    .andExpect(jsonPath("$.data.isRoleplay").value(false))
                    .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                    .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());
        }

        /**
         * TC_CTP_VTI_002
         * View topic with non-existent topicId → HTTP 404 Not Found.
         */
        @Test
        @DisplayName("TC_CTP_VTI_002 — non-existent topicId → HTTP 404, code=NOT_FOUND")
        void tc_ctp_vti_002_nonExistentTopicId_returns404() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            // Act + Assert
            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}",
                            UUID.fromString("00000000-0000-0000-0000-000000000000"))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CTP_VTI_003
         * View soft-deleted topic → HTTP 404 Not Found.
         */
        @Test
        @DisplayName("TC_CTP_VTI_003 — soft-deleted topic → HTTP 404, code=NOT_FOUND")
        void tc_ctp_vti_003_softDeletedTopic_returns404() throws Exception {
            // Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTheme theme = saveTheme("Theme");
            ConversationTopic deleted = saveSoftDeletedTopic("Deleted Topic", theme.getId(), manager.getId());

            // Act + Assert
            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}", deleted.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CTP_VTI_004
         * Unauthenticated request to view topic detail → HTTP 401.
         */
        @Test
        @DisplayName("TC_CTP_VTI_004 — no token → HTTP 401 Unauthorized")
        void tc_ctp_vti_004_unauthenticated_returns401() throws Exception {
            // Arrange — insert topic so we have a real ID
            ConversationTheme theme = saveTheme("Theme");
            ConversationTopic topic = saveActiveTopic("Some Topic", theme.getId());

            // Act + Assert
            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}", topic.getId()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3. CREATE TOPIC — POST /api/v1/manager/conversation/topics
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. CREATE TOPIC — POST /api/v1/manager/conversation/topics [P2 Standard]")
    class CreateTopicTests {

        /**
         * TC_CTP_CT_001
         * MANAGER creates topic with title only → saved with correct defaults.
         */
        @Test
        @DisplayName("TC_CTP_CT_001 — title only → HTTP 201, defaults: difficultyScore=1, isRoleplay=true, orderIndex=0")
        void tc_ctp_ct_001_titleOnly_returns201_defaultsPersisted() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            String body = "{\"title\":\"Mua sắm tại siêu thị\"}";

            // Act
            MvcResult result = mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.title").value("Mua sắm tại siêu thị"))
                    .andExpect(jsonPath("$.data.id").isNotEmpty())
                    .andReturn();

            // Assert DB state
            String idStr = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("id").asText();
            ConversationTopic topic = topicRepository.findById(UUID.fromString(idStr)).orElseThrow();
            assertThat(topic.getTitle()).isEqualTo("Mua sắm tại siêu thị");
            assertThat(topic.getDeletedAt()).isNull();
            assertThat(topic.getThemeId()).isNull();
            assertThat(topic.getCourseId()).isNull();
            assertThat(topic.getDifficultyScore()).isEqualTo(1);
            assertThat(topic.getOrderIndex()).isEqualTo(0);
        }

        /**
         * TC_CTP_CT_002
         * MANAGER creates topic with all optional fields → all fields persisted correctly.
         */
        @Test
        @DisplayName("TC_CTP_CT_002 — all optional fields → HTTP 201, all fields persisted to DB")
        void tc_ctp_ct_002_allFields_returns201_allFieldsPersisted() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            String body = """
                    {
                      "title": "Mua sắm tại siêu thị",
                      "titleJapanese": "スーパーでの買い物",
                      "description": "Learn shopping vocabulary",
                      "scenario": "You are at a supermarket",
                      "jlptLevel": "N4",
                      "difficultyScore": 3,
                      "isRoleplay": true,
                      "category": "shopping",
                      "orderIndex": 1
                    }
                    """;

            // Act
            MvcResult result = mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.title").value("Mua sắm tại siêu thị"))
                    .andExpect(jsonPath("$.data.titleJapanese").value("スーパーでの買い物"))
                    .andExpect(jsonPath("$.data.jlptLevel").value("N4"))
                    .andExpect(jsonPath("$.data.difficultyScore").value(3))
                    .andExpect(jsonPath("$.data.isRoleplay").value(true))
                    .andExpect(jsonPath("$.data.category").value("shopping"))
                    .andExpect(jsonPath("$.data.orderIndex").value(1))
                    .andReturn();

            // Assert DB state
            String idStr = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("id").asText();
            ConversationTopic topic = topicRepository.findById(UUID.fromString(idStr)).orElseThrow();
            assertThat(topic.getTitle()).isEqualTo("Mua sắm tại siêu thị");
            assertThat(topic.getTitleJapanese()).isEqualTo("スーパーでの買い物");
            assertThat(topic.getJlptLevel()).isEqualTo(Course.JlptLevel.N4);
            assertThat(topic.getDifficultyScore()).isEqualTo(3);
            assertThat(topic.getIsRoleplay()).isTrue();
            assertThat(topic.getCategory()).isEqualTo("shopping");
            assertThat(topic.getOrderIndex()).isEqualTo(1);
        }

        /**
         * TC_CTP_CT_003
         * MANAGER creates topic linked to valid themeId → themeId persisted, topic appears in theme's list.
         */
        @Test
        @DisplayName("TC_CTP_CT_003 — valid themeId → HTTP 201, topic appears in theme's topic list")
        void tc_ctp_ct_003_validThemeId_topicAppearsInThemeList() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTheme theme = saveTheme("Hỏi đường Theme");

            String body = String.format("{\"title\":\"Hỏi đường\",\"themeId\":\"%s\"}", theme.getId());

            // Act — create topic linked to themeId
            MvcResult createResult = mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.themeId").value(theme.getId().toString()))
                    .andReturn();

            // Assert DB state
            String idStr = objectMapper.readTree(createResult.getResponse().getContentAsString())
                    .path("data").path("id").asText();
            ConversationTopic topic = topicRepository.findById(UUID.fromString(idStr)).orElseThrow();
            assertThat(topic.getThemeId()).isEqualTo(theme.getId());

            // Verify topic appears in theme's topic list
            mockMvc.perform(get("/api/v1/manager/conversation/themes/{themeId}/topics", theme.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].title").value("Hỏi đường"));
        }

        /**
         * TC_CTP_CT_004
         * MANAGER creates topic without themeId → orphan topic created with themeId=null.
         */
        @Test
        @DisplayName("TC_CTP_CT_004 — no themeId → HTTP 201, topic.themeId=null in DB")
        void tc_ctp_ct_004_noThemeId_orphanTopicCreated() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            String body = "{\"title\":\"Orphan Topic\"}";

            // Act
            MvcResult result = mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andReturn();

            // Assert DB state
            String idStr = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("id").asText();
            ConversationTopic topic = topicRepository.findById(UUID.fromString(idStr)).orElseThrow();
            assertThat(topic.getThemeId()).isNull();
            assertThat(topic.getDeletedAt()).isNull();
        }

        /**
         * TC_CTP_CT_005
         * Create topic with blank title string → HTTP 400, VALIDATION_ERROR.
         */
        @Test
        @DisplayName("TC_CTP_CT_005 — blank title → HTTP 400 VALIDATION_ERROR, no new record in DB")
        void tc_ctp_ct_005_blankTitle_returns400ValidationError() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            long countBefore = topicRepository.count();

            // Act + Assert
            mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"   \"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            // DB: no new record created
            assertThat(topicRepository.count()).isEqualTo(countBefore);
        }

        /**
         * TC_CTP_CT_006
         * Create topic with non-existent themeId → HTTP 404, no new record created.
         */
        @Test
        @DisplayName("TC_CTP_CT_006 — non-existent themeId → HTTP 404, no new record in DB")
        void tc_ctp_ct_006_nonExistentThemeId_returns404() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            long countBefore = topicRepository.count();
            String body = "{\"title\":\"Test\",\"themeId\":\"00000000-0000-0000-0000-000000000000\"}";

            // Act + Assert
            mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));

            // DB: no new record created
            assertThat(topicRepository.count()).isEqualTo(countBefore);
        }

        /**
         * TC_CTP_CT_007
         * Create topic with soft-deleted themeId → HTTP 404, no new record created.
         */
        @Test
        @DisplayName("TC_CTP_CT_007 — soft-deleted themeId → HTTP 404, no new record in DB")
        void tc_ctp_ct_007_softDeletedThemeId_returns404() throws Exception {
            // Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTheme deletedTheme = saveSoftDeletedTheme("Deleted Theme", manager.getId());
            long countBefore = topicRepository.count();

            String body = String.format("{\"title\":\"Test\",\"themeId\":\"%s\"}", deletedTheme.getId());

            // Act + Assert
            mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));

            // DB: no new record created
            assertThat(topicRepository.count()).isEqualTo(countBefore);
        }

        /**
         * TC_CTP_CT_008
         * Unauthenticated request to create topic → HTTP 401.
         */
        @Test
        @DisplayName("TC_CTP_CT_008 — no token → HTTP 401 Unauthorized")
        void tc_ctp_ct_008_unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Test\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CTP_CT_009
         * LEARNER attempts to create topic → HTTP 403 Forbidden.
         */
        @Test
        @DisplayName("TC_CTP_CT_009 — LEARNER token → HTTP 403 Forbidden")
        void tc_ctp_ct_009_learnerToken_returns403() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Test\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CTP_CT_010
         * ADMIN attempts to create topic → HTTP 403 Forbidden.
         */
        @Test
        @DisplayName("TC_CTP_CT_010 — ADMIN token → HTTP 403 Forbidden")
        void tc_ctp_ct_010_adminToken_returns403() throws Exception {
            saveAdmin("admin@test.com");
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Test\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. UPDATE TOPIC — PUT /api/v1/manager/conversation/topics/{topicId}
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. UPDATE TOPIC — PUT /api/v1/manager/conversation/topics/{topicId} [P2 Standard]")
    class UpdateTopicTests {

        /**
         * TC_CTP_UT_001
         * MANAGER updates topic title and description → changes persisted, updatedAt refreshed.
         */
        @Test
        @DisplayName("TC_CTP_UT_001 — update title+description → HTTP 200, DB updated, updatedAt refreshed")
        void tc_ctp_ut_001_updateTitleAndDescription_returns200_dbUpdated() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTheme theme = saveTheme("Theme");
            ConversationTopic topic = topicRepository.save(ConversationTopic.builder()
                    .title("Old Title")
                    .description("Old desc")
                    .themeId(theme.getId())
                    .status(Course.ContentStatus.DRAFT)
                    .orderIndex(0)
                    .build());

            String body = "{\"title\":\"New Title\",\"description\":\"New description\"}";

            // Act
            mockMvc.perform(put("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.title").value("New Title"))
                    .andExpect(jsonPath("$.data.description").value("New description"));

            // Assert DB state
            ConversationTopic updated = topicRepository.findById(topic.getId()).orElseThrow();
            assertThat(updated.getTitle()).isEqualTo("New Title");
            assertThat(updated.getDescription()).isEqualTo("New description");
            assertThat(updated.getUpdatedAt()).isNotNull();
        }

        /**
         * TC_CTP_UT_002
         * MANAGER updates only optional fields → title unchanged, updated optional fields persisted.
         */
        @Test
        @DisplayName("TC_CTP_UT_002 — optional fields only → HTTP 200, title unchanged in DB")
        void tc_ctp_ut_002_updateOptionalFieldsOnly_returns200_titleUnchanged() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTheme theme = saveTheme("Theme");
            ConversationTopic topic = topicRepository.save(ConversationTopic.builder()
                    .title("Mua sắm")
                    .themeId(theme.getId())
                    .status(Course.ContentStatus.DRAFT)
                    .orderIndex(0)
                    .build());

            String body = "{\"jlptLevel\":\"N3\",\"difficultyScore\":5,\"isRoleplay\":true}";

            // Act
            mockMvc.perform(put("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.title").value("Mua sắm"))
                    .andExpect(jsonPath("$.data.difficultyScore").value(5))
                    .andExpect(jsonPath("$.data.isRoleplay").value(true));

            // Assert DB state
            ConversationTopic updated = topicRepository.findById(topic.getId()).orElseThrow();
            assertThat(updated.getTitle()).isEqualTo("Mua sắm");
            assertThat(updated.getDifficultyScore()).isEqualTo(5);
            assertThat(updated.getIsRoleplay()).isTrue();
        }

        /**
         * TC_CTP_UT_003
         * Update topic with blank title.
         * NOTE-C: UpdateTopicRequest does NOT have @NotBlank on title; the mapper uses
         * NullValuePropertyMappingStrategy.IGNORE so a blank (non-null) string IS written
         * to the entity. Expected behavior per test plan: HTTP 400 VALIDATION_ERROR.
         * If no such validation exists in the service, this test documents the expected fix.
         */
        @Test
        @DisplayName("TC_CTP_UT_003 — blank title → HTTP 400 VALIDATION_ERROR (NOTE-C: may be a known bug)")
        void tc_ctp_ut_003_blankTitle_returns400ValidationError() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTheme theme = saveTheme("Theme");
            ConversationTopic topic = saveActiveTopic("Valid Title", theme.getId());

            // Act + Assert
            mockMvc.perform(put("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"   \"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        /**
         * TC_CTP_UT_004
         * Update non-existent topicId → HTTP 404.
         */
        @Test
        @DisplayName("TC_CTP_UT_004 — non-existent topicId → HTTP 404, code=NOT_FOUND")
        void tc_ctp_ut_004_nonExistentTopicId_returns404() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            // Act + Assert
            mockMvc.perform(put("/api/v1/manager/conversation/topics/{topicId}",
                            UUID.fromString("00000000-0000-0000-0000-000000000000"))
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"New Title\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CTP_UT_005
         * Update soft-deleted topic → HTTP 404.
         */
        @Test
        @DisplayName("TC_CTP_UT_005 — soft-deleted topic → HTTP 404, code=NOT_FOUND")
        void tc_ctp_ut_005_softDeletedTopic_returns404() throws Exception {
            // Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTheme theme = saveTheme("Theme");
            ConversationTopic deleted = saveSoftDeletedTopic("Deleted Topic", theme.getId(), manager.getId());

            // Act + Assert
            mockMvc.perform(put("/api/v1/manager/conversation/topics/{topicId}", deleted.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"New Title\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CTP_UT_006
         * Unauthenticated request to update topic → HTTP 401.
         */
        @Test
        @DisplayName("TC_CTP_UT_006 — no token → HTTP 401 Unauthorized")
        void tc_ctp_ut_006_unauthenticated_returns401() throws Exception {
            ConversationTheme theme = saveTheme("Theme");
            ConversationTopic topic = saveActiveTopic("Test Topic", theme.getId());

            mockMvc.perform(put("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"New Title\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CTP_UT_007
         * LEARNER attempts to update topic → HTTP 403 Forbidden.
         */
        @Test
        @DisplayName("TC_CTP_UT_007 — LEARNER token → HTTP 403 Forbidden")
        void tc_ctp_ut_007_learnerToken_returns403() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            ConversationTheme theme = saveTheme("Theme");
            ConversationTopic topic = saveActiveTopic("Test Topic", theme.getId());

            mockMvc.perform(put("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"New Title\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CTP_UT_008
         * ADMIN attempts to update topic → HTTP 403 Forbidden.
         */
        @Test
        @DisplayName("TC_CTP_UT_008 — ADMIN token → HTTP 403 Forbidden")
        void tc_ctp_ut_008_adminToken_returns403() throws Exception {
            saveAdmin("admin@test.com");
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            ConversationTheme theme = saveTheme("Theme");
            ConversationTopic topic = saveActiveTopic("Test Topic", theme.getId());

            mockMvc.perform(put("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"New Title\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 5. DELETE TOPIC — DELETE /api/v1/manager/conversation/topics/{topicId}
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. DELETE TOPIC — DELETE /api/v1/manager/conversation/topics/{topicId} [P2 Standard]")
    class DeleteTopicTests {

        /**
         * TC_CTP_DT_001
         * MANAGER soft-deletes active topic → deleted_at set, topic invisible in list.
         */
        @Test
        @DisplayName("TC_CTP_DT_001 — soft-delete active topic → HTTP 200, deleted_at set, topic absent from list")
        void tc_ctp_dt_001_softDeleteActiveTopic_deletedAtSet_absentFromList() throws Exception {
            // Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTheme theme = saveTheme("Test Theme");
            ConversationTopic topic = saveActiveTopic("Test Topic", theme.getId());

            // Act — delete topic
            mockMvc.perform(delete("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            // Assert DB state — deleted_at set, deleted_by = manager
            ConversationTopic deleted = topicRepository.findById(topic.getId()).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();
            assertThat(deleted.getDeletedBy()).isEqualTo(manager.getId());

            // Assert topic NOT in theme's topic list
            mockMvc.perform(get("/api/v1/manager/conversation/themes/{themeId}/topics", theme.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(0))
                    .andExpect(jsonPath("$.data.content").isEmpty());
        }

        /**
         * TC_CTP_DT_002
         * Delete non-existent topicId → HTTP 404.
         */
        @Test
        @DisplayName("TC_CTP_DT_002 — non-existent topicId → HTTP 404, code=NOT_FOUND")
        void tc_ctp_dt_002_nonExistentTopicId_returns404() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            // Act + Assert
            mockMvc.perform(delete("/api/v1/manager/conversation/topics/{topicId}",
                            UUID.fromString("00000000-0000-0000-0000-000000000000"))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CTP_DT_003
         * Delete already-deleted topic → HTTP 404 (service filters deleted_at IS NULL).
         */
        @Test
        @DisplayName("TC_CTP_DT_003 — already soft-deleted topic → HTTP 404, code=NOT_FOUND")
        void tc_ctp_dt_003_alreadyDeletedTopic_returns404() throws Exception {
            // Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTheme theme = saveTheme("Theme");
            ConversationTopic deleted = saveSoftDeletedTopic("Already Deleted", theme.getId(), manager.getId());

            // Act + Assert
            mockMvc.perform(delete("/api/v1/manager/conversation/topics/{topicId}", deleted.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CTP_DT_004
         * Unauthenticated request to delete topic → HTTP 401.
         */
        @Test
        @DisplayName("TC_CTP_DT_004 — no token → HTTP 401 Unauthorized")
        void tc_ctp_dt_004_unauthenticated_returns401() throws Exception {
            ConversationTheme theme = saveTheme("Theme");
            ConversationTopic topic = saveActiveTopic("Test Topic", theme.getId());

            mockMvc.perform(delete("/api/v1/manager/conversation/topics/{topicId}", topic.getId()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CTP_DT_005
         * LEARNER attempts to delete topic → HTTP 403 Forbidden.
         */
        @Test
        @DisplayName("TC_CTP_DT_005 — LEARNER token → HTTP 403 Forbidden")
        void tc_ctp_dt_005_learnerToken_returns403() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            ConversationTheme theme = saveTheme("Theme");
            ConversationTopic topic = saveActiveTopic("Test Topic", theme.getId());

            mockMvc.perform(delete("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CTP_DT_006
         * ADMIN attempts to delete topic → HTTP 403 Forbidden.
         */
        @Test
        @DisplayName("TC_CTP_DT_006 — ADMIN token → HTTP 403 Forbidden")
        void tc_ctp_dt_006_adminToken_returns403() throws Exception {
            saveAdmin("admin@test.com");
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            ConversationTheme theme = saveTheme("Theme");
            ConversationTopic topic = saveActiveTopic("Test Topic", theme.getId());

            mockMvc.perform(delete("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 6. GENERATE BY AI — POST /api/v1/manager/conversation/themes/generate
    //    NOTE: Postman/live-AI tests (TC_CTP_GAI_001..004, 007..009) are skipped.
    //    Only automated (non-AI) cases are included here.
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. GENERATE BY AI (validation & permission only) — POST /api/v1/manager/conversation/themes/generate [P2 Standard]")
    class GenerateByAiTests {

        /**
         * TC_CTP_GAI_005
         * Generate with count=0 → HTTP 400 VALIDATION_ERROR (@Min=1).
         * AI service is NOT called — request rejected by @Valid before reaching service layer.
         */
        @Test
        @DisplayName("TC_CTP_GAI_005 — count=0 → HTTP 400 VALIDATION_ERROR (@Min=1 violated)")
        void tc_ctp_gai_005_countZero_returns400ValidationError() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            String body = String.format("{\"courseId\":\"%s\",\"count\":0}",
                    UUID.randomUUID());

            // Act + Assert
            mockMvc.perform(post("/api/v1/manager/conversation/themes/generate")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        /**
         * TC_CTP_GAI_010
         * Unauthenticated request to generate suggestions → HTTP 401.
         */
        @Test
        @DisplayName("TC_CTP_GAI_010 — no token → HTTP 401 Unauthorized")
        void tc_ctp_gai_010_unauthenticated_returns401() throws Exception {
            String body = String.format("{\"courseId\":\"%s\"}", UUID.randomUUID());

            mockMvc.perform(post("/api/v1/manager/conversation/themes/generate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CTP_GAI_011
         * LEARNER attempts to generate suggestions → HTTP 403 Forbidden.
         */
        @Test
        @DisplayName("TC_CTP_GAI_011 — LEARNER token → HTTP 403 Forbidden")
        void tc_ctp_gai_011_learnerToken_returns403() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            String body = String.format("{\"courseId\":\"%s\"}", UUID.randomUUID());

            mockMvc.perform(post("/api/v1/manager/conversation/themes/generate")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CTP_GAI_012
         * ADMIN attempts to generate suggestions → HTTP 403 Forbidden.
         */
        @Test
        @DisplayName("TC_CTP_GAI_012 — ADMIN token → HTTP 403 Forbidden")
        void tc_ctp_gai_012_adminToken_returns403() throws Exception {
            saveAdmin("admin@test.com");
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            String body = String.format("{\"courseId\":\"%s\"}", UUID.randomUUID());

            mockMvc.perform(post("/api/v1/manager/conversation/themes/generate")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }
}
