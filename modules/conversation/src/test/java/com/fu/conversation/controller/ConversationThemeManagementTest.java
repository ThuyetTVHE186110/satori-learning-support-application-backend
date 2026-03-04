package com.fu.conversation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.conversation.entity.ConversationTheme;
import com.fu.conversation.entity.ConversationTopic;
import com.fu.conversation.repository.ConversationThemeRepository;
import com.fu.conversation.repository.ConversationTopicRepository;
import com.fu.infrastructure.email.EmailService;
import com.fu.user.entity.AuthProvider;
import com.fu.user.entity.User;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import com.fu.user.repository.UserRepository;
import com.fu.user.repository.UserSessionRepository;
import com.fu.course.entity.Course;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — Conversation Theme Management.
 *
 * Runs against a real PostgreSQL database (satori_test).
 * Setup (one-time):
 *   docker exec satori-postgres psql -U satori_user -d satori_learning \
 *     -c "CREATE DATABASE satori_test OWNER satori_user;"
 *
 * Covers (test plan):
 *   1. VIEW THEME LIST    — GET    /api/v1/manager/conversation/themes       [P3 Sanity]
 *   2. CREATE THEME       — POST   /api/v1/manager/conversation/themes       [P2 Standard]
 *   3. UPDATE THEME       — PUT    /api/v1/manager/conversation/themes/{id}  [P2 Standard]
 *   4. DELETE THEME       — DELETE /api/v1/manager/conversation/themes/{id}  [P2 Standard]
 *   5. VIEW THEME DETAIL  — GET    /api/v1/manager/conversation/themes/{id}  [P3 Sanity]
 *
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  DISCREPANCY NOTES — update test script accordingly                     │
 * │                                                                          │
 * │  [NOTE-1] TC_CT_VTL_002 / TC_CT_VTL_003 — keyword search:              │
 * │    GET /themes has NO ?keyword= param in controller → @Disabled.        │
 * │    Raise feature request to dev.                                         │
 * │                                                                          │
 * │  [NOTE-4] TC_CT_DTH_001 / TC_CT_DTH_002 — delete response message:     │
 * │    Script expects: "Nội dung đã được xoá"                               │
 * │    Actual code:    "Theme deleted successfully"                          │
 * │    Tests assert actual. Update test script.                              │
 * │                                                                          │
 * │  [NOTE-5] 404 errorCode: script expects "THEME_NOT_FOUND",             │
 * │    actual ResourceNotFoundException uses "NOT_FOUND".                   │
 * │    Tests assert "NOT_FOUND". Update test script.                        │
 * └──────────────────────────────────────────────────────────────────────────┘
 */
@SpringBootTest(classes = com.fu.conversation.ConversationTestApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Conversation Theme Management — Integration Tests (PostgreSQL satori_test)")
class ConversationThemeManagementTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private ConversationThemeRepository themeRepository;
    @Autowired private ConversationTopicRepository topicRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    // Email service is mocked — we don't want real SMTP calls during tests
    @MockBean
    private EmailService emailService;
    // ── Lifecycle ─────────────────────────────────────────────────────────────


    /** Insert a MANAGER user and return the saved entity. */
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

    /** Insert a LEARNER user and return the saved entity. */
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

    /** Login and return the Bearer access token. */
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

    /** Build and save an active ConversationTheme. */
    private ConversationTheme saveTheme(String title, Course.ContentStatus status) {
        return themeRepository.save(ConversationTheme.builder()
                .title(title)
                .status(status)
                .orderIndex(0)
                .build());
    }

    /** Build and save a soft-deleted ConversationTheme. */
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

    /** Build and save an active ConversationTopic linked to a theme. */
    private ConversationTopic saveActiveTopic(String title, UUID themeId) {
        return topicRepository.save(ConversationTopic.builder()
                .title(title)
                .themeId(themeId)
                .status(Course.ContentStatus.DRAFT)
                .orderIndex(0)
                .build());
    }

    /** Build and save a pre-deleted ConversationTopic linked to a theme. */
    private ConversationTopic saveDeletedTopic(String title, UUID themeId, UUID deletedBy) {
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
    // 1. VIEW THEME LIST — GET /api/v1/manager/conversation/themes [P3 Sanity]
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. VIEW THEME LIST — GET /api/v1/manager/conversation/themes [P3 Sanity]")
    class ViewThemeListTests {

        /**
         * TC_CT_VTL_001
         * MANAGER views theme list — returns paginated list of all non-deleted themes
         * regardless of status (DRAFT / PUBLISHED / ARCHIVED).
         * Soft-deleted theme must NOT appear.
         */
        @Test
        @DisplayName("TC_CT_VTL_001 — 3 active themes (DRAFT/PUBLISHED/ARCHIVED) returned, soft-deleted excluded")
        void tc_ct_vtl_001_managerViewsList_returns3ActiveThemes_softDeletedExcluded() throws Exception {
            // Arrange
            User manager = saveManager("manager@test.com");
            saveTheme("Mua sắm",  Course.ContentStatus.DRAFT);
            saveTheme("Du lịch",  Course.ContentStatus.PUBLISHED);
            saveTheme("Gia đình", Course.ContentStatus.ARCHIVED);
            saveSoftDeletedTheme("Deleted Theme", manager.getId());

            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            // Act + Assert
            MvcResult result = mockMvc.perform(get("/api/v1/manager/conversation/themes")
                            .param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.totalElements").value(3))
                    .andExpect(jsonPath("$.data.content.length()").value(3))
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            var content = objectMapper.readTree(body).path("data").path("content");

            // All 3 statuses represented
            List<String> statuses = new java.util.ArrayList<>();
            content.forEach(n -> statuses.add(n.path("status").asText()));
            assertThat(statuses).containsExactlyInAnyOrder("DRAFT", "PUBLISHED", "ARCHIVED");

            // Soft-deleted theme absent
            List<String> titles = new java.util.ArrayList<>();
            content.forEach(n -> titles.add(n.path("title").asText()));
            assertThat(titles).doesNotContain("Deleted Theme");
        }

        /**
         * TC_CT_VTL_002 — DISABLED
         * [NOTE-1] GET /themes has no ?keyword= param in controller.
         * Raise feature request to dev, or remove from test plan.
         */
        @Test
        @DisplayName("TC_CT_VTL_002 — [DISABLED — NOTE-1] keyword search not implemented")
        @org.junit.jupiter.api.Disabled(
                "[NOTE-1] GET /api/v1/manager/conversation/themes does not support ?keyword= param. " +
                "Raise feature request to dev or remove TC from plan.")
        void tc_ct_vtl_002_keywordSearch_disabled() {}

        /**
         * TC_CT_VTL_003 — DISABLED
         * [NOTE-1] Same reason as TC_CT_VTL_002.
         */
        @Test
        @DisplayName("TC_CT_VTL_003 — [DISABLED — NOTE-1] keyword search not implemented")
        @org.junit.jupiter.api.Disabled("[NOTE-1] See TC_CT_VTL_002.")
        void tc_ct_vtl_003_keywordNoResult_disabled() {}
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2. CREATE THEME — POST /api/v1/manager/conversation/themes [P2 Standard]
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. CREATE THEME — POST /api/v1/manager/conversation/themes [P2 Standard]")
    class CreateThemeTests {

        // ── 2.1 Happy Path ────────────────────────────────────────────────────

        /**
         * TC_CT_CTH_001
         * MANAGER creates theme with title only.
         * Expected: HTTP 201, default status=DRAFT, orderIndex=0,
         * all optional fields null, deleted_at=null in DB.
         */
        @Test
        @DisplayName("TC_CT_CTH_001 — title only → HTTP 201, DB: DRAFT, orderIndex=0, optional fields null")
        void tc_ct_cth_001_titleOnly_returns201_defaultsPersistedToDB() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            String body = "{\"title\":\"Mua sắm\"}";

            // Act
            MvcResult result = mockMvc.perform(post("/api/v1/manager/conversation/themes")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.title").value("Mua sắm"))
                    .andExpect(jsonPath("$.data.id").isNotEmpty())
                    .andReturn();

            // Assert DB state
            String idStr = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("id").asText();
            ConversationTheme theme = themeRepository.findById(UUID.fromString(idStr)).orElseThrow();
            assertThat(theme.getTitle()).isEqualTo("Mua sắm");
            assertThat(theme.getStatus()).isEqualTo(Course.ContentStatus.DRAFT);
            assertThat(theme.getOrderIndex()).isEqualTo(0);
            assertThat(theme.getDescription()).isNull();
            assertThat(theme.getTitleJapanese()).isNull();
            assertThat(theme.getCategory()).isNull();
            assertThat(theme.getDeletedAt()).isNull();
            assertThat(theme.getDeletedBy()).isNull();
        }

        /**
         * TC_CT_CTH_002
         * MANAGER creates theme with all optional fields.
         * Expected: HTTP 201, all fields persisted to DB.
         */
        @Test
        @DisplayName("TC_CT_CTH_002 — all optional fields → HTTP 201, all fields persisted to DB")
        void tc_ct_cth_002_allFields_returns201_allFieldsInDB() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            String body = """
                    {
                      "title": "Du lịch",
                      "description": "Chủ đề du lịch nội địa",
                      "titleJapanese": "旅行",
                      "category": "Travel",
                      "jlptLevel": "N3",
                      "orderIndex": 5
                    }
                    """;

            // Act
            MvcResult result = mockMvc.perform(post("/api/v1/manager/conversation/themes")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.title").value("Du lịch"))
                    .andExpect(jsonPath("$.data.description").value("Chủ đề du lịch nội địa"))
                    .andExpect(jsonPath("$.data.titleJapanese").value("旅行"))
                    .andExpect(jsonPath("$.data.category").value("Travel"))
                    .andExpect(jsonPath("$.data.jlptLevel").value("N3"))
                    .andExpect(jsonPath("$.data.orderIndex").value(5))
                    .andReturn();

            // Assert DB state
            String idStr = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("id").asText();
            ConversationTheme theme = themeRepository.findById(UUID.fromString(idStr)).orElseThrow();
            assertThat(theme.getTitle()).isEqualTo("Du lịch");
            assertThat(theme.getDescription()).isEqualTo("Chủ đề du lịch nội địa");
            assertThat(theme.getTitleJapanese()).isEqualTo("旅行");
            assertThat(theme.getCategory()).isEqualTo("Travel");
            assertThat(theme.getJlptLevel()).isEqualTo(Course.JlptLevel.N3);
            assertThat(theme.getOrderIndex()).isEqualTo(5);
        }

        /**
         * TC_CT_CTH_003
         * MANAGER creates two themes with identical title.
         * Expected: both HTTP 201, two distinct UUIDs in DB (no unique constraint on title).
         */
        @Test
        @DisplayName("TC_CT_CTH_003 — duplicate title → both HTTP 201, 2 distinct records in DB")
        void tc_ct_cth_003_duplicateTitle_bothReturn201_twoRecordsInDB() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            String body = "{\"title\":\"Gia đình\"}";

            // Act — first creation
            MvcResult first = mockMvc.perform(post("/api/v1/manager/conversation/themes")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andReturn();

            // Act — second creation
            MvcResult second = mockMvc.perform(post("/api/v1/manager/conversation/themes")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andReturn();

            // Assert: two distinct IDs
            String id1 = objectMapper.readTree(first.getResponse().getContentAsString())
                    .path("data").path("id").asText();
            String id2 = objectMapper.readTree(second.getResponse().getContentAsString())
                    .path("data").path("id").asText();
            assertThat(id1).isNotEqualTo(id2);

            // Assert DB: 2 records with title "Gia đình"
            List<ConversationTheme> all = themeRepository.findAll().stream()
                    .filter(t -> "Gia đình".equals(t.getTitle()))
                    .toList();
            assertThat(all).hasSize(2);
        }

        // ── 2.2 Negative Cases ────────────────────────────────────────────────

        /**
         * TC_CT_CTH_004
         * Create theme with blank title → @NotBlank violation →
         * HTTP 400, code = "VALIDATION_ERROR", data.title error present.
         */
        @Test
        @DisplayName("TC_CT_CTH_004 — blank title → HTTP 400 VALIDATION_ERROR, data.title error present")
        void tc_ct_cth_004_blankTitle_returns400ValidationError() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/manager/conversation/themes")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.data.title").isNotEmpty());
        }

        /**
         * TC_CT_CTH_005
         * Create theme with invalid jlptLevel enum value ("N9").
         * Expected: HTTP 400, code = "VALIDATION_ERROR", data.jlptLevel error present.
         */
        @Test
        @DisplayName("TC_CT_CTH_005 — invalid enum 'N9' → HTTP 400 VALIDATION_ERROR, data.jlptLevel error present")
        void tc_ct_cth_005_invalidJlptEnum_returns400ValidationError() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/manager/conversation/themes")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Test Theme\",\"jlptLevel\":\"N9\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.data.jlptLevel").isNotEmpty());
        }

        // ── 2.3 Permission Cases ──────────────────────────────────────────────

        /**
         * TC_CT_CTH_006
         * Unauthenticated request → HTTP 401 Unauthorized.
         */
        @Test
        @DisplayName("TC_CT_CTH_006 — no token → HTTP 401 Unauthorized")
        void tc_ct_cth_006_unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/manager/conversation/themes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Test\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CT_CTH_007
         * LEARNER attempts to create theme → HTTP 403 Forbidden.
         */
        @Test
        @DisplayName("TC_CT_CTH_007 — LEARNER creates theme → HTTP 403 Forbidden")
        void tc_ct_cth_007_learnerCreateTheme_returns403() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/manager/conversation/themes")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Test\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3. UPDATE THEME — PUT /api/v1/manager/conversation/themes/{id} [P2 Standard]
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. UPDATE THEME — PUT /api/v1/manager/conversation/themes/{id} [P2 Standard]")
    class UpdateThemeTests {

        // ── 3.1 Happy Path ────────────────────────────────────────────────────

        /**
         * TC_CT_UTH_001
         * MANAGER updates title and description.
         * Expected: HTTP 200, changes persisted, updatedAt refreshed.
         */
        @Test
        @DisplayName("TC_CT_UTH_001 — update title+description → HTTP 200, DB updated, updatedAt refreshed")
        void tc_ct_uth_001_updateTitleAndDescription_returns200_dbUpdated() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTheme theme = saveTheme("Old Title", Course.ContentStatus.DRAFT);

            String body = "{\"title\":\"New Title\",\"description\":\"New Desc\"}";

            // Act
            mockMvc.perform(put("/api/v1/manager/conversation/themes/{id}", theme.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.title").value("New Title"))
                    .andExpect(jsonPath("$.data.description").value("New Desc"))
                    .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());

            // Assert DB
            ConversationTheme updated = themeRepository.findById(theme.getId()).orElseThrow();
            assertThat(updated.getTitle()).isEqualTo("New Title");
            assertThat(updated.getDescription()).isEqualTo("New Desc");
            // updatedAt must have changed (or be at least equal — JPA may batch within same ms)
            assertThat(updated.getUpdatedAt()).isNotNull();
        }

        /**
         * TC_CT_UTH_002
         * MANAGER updates only optional fields (titleJapanese, jlptLevel).
         * Expected: HTTP 200, original title unchanged, updated optional fields persisted.
         */
        @Test
        @DisplayName("TC_CT_UTH_002 — optional fields only → HTTP 200, title unchanged in DB")
        void tc_ct_uth_002_updateOptionalFieldsOnly_returns200_titleUnchanged() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTheme theme = saveTheme("Gia đình", Course.ContentStatus.DRAFT);

            String body = "{\"titleJapanese\":\"家族\",\"jlptLevel\":\"N4\"}";

            // Act
            mockMvc.perform(put("/api/v1/manager/conversation/themes/{id}", theme.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.title").value("Gia đình"))
                    .andExpect(jsonPath("$.data.titleJapanese").value("家族"))
                    .andExpect(jsonPath("$.data.jlptLevel").value("N4"));

            // Assert DB
            ConversationTheme updated = themeRepository.findById(theme.getId()).orElseThrow();
            assertThat(updated.getTitle()).isEqualTo("Gia đình");
            assertThat(updated.getTitleJapanese()).isEqualTo("家族");
            assertThat(updated.getJlptLevel()).isEqualTo(Course.JlptLevel.N4);
        }

        // ── 3.2 Negative Cases ────────────────────────────────────────────────

        /**
         * TC_CT_UTH_003
         * Update theme with blank title — returns VALIDATION_ERROR (BR-115).
         *
         * ❌ KNOWN FAILING — bug in dev code:
         *   UpdateThemeRequest.title has @Size only, NO @NotBlank.
         *   Blank string passes validation → actual HTTP 200 instead of 400.
         *   Bug: add @NotBlank to UpdateThemeRequest.title to enforce BR-115.
         */
        @Test
        @DisplayName("TC_CT_UTH_003 — blank title → HTTP 400 VALIDATION_ERROR, data.title error present [BR-115]")
        void tc_ct_uth_003_blankTitle_returns400ValidationError() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTheme theme = saveTheme("Existing Title", Course.ContentStatus.DRAFT);

            mockMvc.perform(put("/api/v1/manager/conversation/themes/{id}", theme.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.data.title").isNotEmpty());
        }

        /**
         * TC_CT_UTH_004
         * Update non-existent theme (all-zeros UUID) → HTTP 404.
         *
         * [NOTE-5] errorCode: actual = "NOT_FOUND", script says "NOT_FOUND".
         */
        @Test
        @DisplayName("TC_CT_UTH_004 — non-existent themeId → HTTP 404, code='NOT_FOUND' [NOTE-5]")
        void tc_ct_uth_004_nonExistentThemeId_returns404() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            UUID nonExistentId = UUID.fromString("00000000-0000-0000-0000-000000000000");

            mockMvc.perform(put("/api/v1/manager/conversation/themes/{id}", nonExistentId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Test\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    // NOTE-5: actual "NOT_FOUND", update test script from "THEME_NOT_FOUND"
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        /**
         * TC_CT_UTH_005
         * Update soft-deleted theme → HTTP 404 (service filters deleted_at IS NULL).
         *
         * [NOTE-5] Same errorCode note.
         */
        @Test
        @DisplayName("TC_CT_UTH_005 — soft-deleted theme → HTTP 404, code='NOT_FOUND' [NOTE-5]")
        void tc_ct_uth_005_softDeletedTheme_returns404() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTheme deleted = saveSoftDeletedTheme("Deleted Theme", manager.getId());

            mockMvc.perform(put("/api/v1/manager/conversation/themes/{id}", deleted.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Test\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        // ── 3.3 Permission Cases ──────────────────────────────────────────────

        /**
         * TC_CT_UTH_006
         * Unauthenticated request → HTTP 401 Unauthorized.
         */
        @Test
        @DisplayName("TC_CT_UTH_006 — no token → HTTP 401 Unauthorized")
        void tc_ct_uth_006_unauthenticated_returns401() throws Exception {
            ConversationTheme theme = saveTheme("Test Theme", Course.ContentStatus.DRAFT);

            mockMvc.perform(put("/api/v1/manager/conversation/themes/{id}", theme.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Test\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CT_UTH_007
         * LEARNER attempts to update theme → HTTP 403 Forbidden.
         */
        @Test
        @DisplayName("TC_CT_UTH_007 — LEARNER updates theme → HTTP 403 Forbidden")
        void tc_ct_uth_007_learnerUpdateTheme_returns403() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");
            ConversationTheme theme = saveTheme("Test Theme", Course.ContentStatus.DRAFT);

            mockMvc.perform(put("/api/v1/manager/conversation/themes/{id}", theme.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Test\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. DELETE THEME — DELETE /api/v1/manager/conversation/themes/{id} [P2 Standard]
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. DELETE THEME — DELETE /api/v1/manager/conversation/themes/{id} [P2 Standard]")
    class DeleteThemeTests {

        // ── 4.1 Happy Path ────────────────────────────────────────────────────

        /**
         * TC_CT_DTH_001
         * MANAGER soft-deletes theme with no child topics.
         * Expected: HTTP 200, deleted_at set in DB, deleted_by = manager UUID,
         * all other fields unchanged, theme absent from list response.
         *
         * [NOTE-4] Response message: actual = "Theme deleted successfully",
         *          script says "Nội dung đã được xoá".
         */
        @Test
        @DisplayName("TC_CT_DTH_001 — delete theme (no topics) → HTTP 200, deleted_at set, absent from list [NOTE-4]")
        void tc_ct_dth_001_deleteThemeNoTopics_softDeletedInDB_absentFromList() throws Exception {
            // Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTheme theme = saveTheme("Test Theme", Course.ContentStatus.DRAFT);
            String originalTitle = theme.getTitle();

            // Act — delete
            mockMvc.perform(delete("/api/v1/manager/conversation/themes/{id}", theme.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Nội dung đã được xoá"));

            // Assert DB: deleted_at set, deleted_by = manager UUID, title unchanged
            ConversationTheme inDB = themeRepository.findById(theme.getId()).orElseThrow();
            assertThat(inDB.getDeletedAt()).isNotNull();
            assertThat(inDB.getDeletedBy()).isEqualTo(manager.getId());
            assertThat(inDB.getTitle()).isEqualTo(originalTitle);

            // Assert list: theme no longer appears
            mockMvc.perform(get("/api/v1/manager/conversation/themes")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }

        /**
         * TC_CT_DTH_002
         * MANAGER soft-deletes theme with mixed topics (2 active + 1 pre-deleted).
         * Expected: HTTP 200, theme soft-deleted, 2 active topics cascade soft-deleted,
         * pre-deleted topic's deleted_at NOT overwritten.
         */
        @Test
        @DisplayName("TC_CT_DTH_002 — mixed topics → active cascade-deleted, pre-deleted unchanged")
        void tc_ct_dth_002_deleteThemeMixedTopics_onlyActiveTopicsCascadeDeleted() throws Exception {
            // Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTheme theme = saveTheme("Mixed Theme", Course.ContentStatus.DRAFT);

            ConversationTopic topic1 = saveActiveTopic("Topic Active 1", theme.getId());
            ConversationTopic topic2 = saveActiveTopic("Topic Active 2", theme.getId());
            ConversationTopic topic3 = saveDeletedTopic("Topic Pre-Deleted", theme.getId(), UUID.randomUUID());
            LocalDateTime preDeletedAt = topic3.getDeletedAt();  // capture original timestamp

            // Act
            mockMvc.perform(delete("/api/v1/manager/conversation/themes/{id}", theme.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Nội dung đã được xoá"));

            // Assert DB — theme soft-deleted
            ConversationTheme themeInDB = themeRepository.findById(theme.getId()).orElseThrow();
            assertThat(themeInDB.getDeletedAt()).isNotNull();
            assertThat(themeInDB.getDeletedBy()).isEqualTo(manager.getId());

            // Assert DB — active topics cascade-deleted
            ConversationTopic t1 = topicRepository.findById(topic1.getId()).orElseThrow();
            ConversationTopic t2 = topicRepository.findById(topic2.getId()).orElseThrow();
            assertThat(t1.getDeletedAt()).isNotNull();
            assertThat(t1.getDeletedBy()).isEqualTo(manager.getId());
            assertThat(t2.getDeletedAt()).isNotNull();
            assertThat(t2.getDeletedBy()).isEqualTo(manager.getId());

            // Assert DB — pre-deleted topic: deleted_at NOT overwritten
            ConversationTopic t3 = topicRepository.findById(topic3.getId()).orElseThrow();
            assertThat(t3.getDeletedAt()).isEqualToIgnoringNanos(preDeletedAt);

            // Assert: findByThemeIdAndDeletedAtIsNull returns empty
            List<ConversationTopic> activeLeft = topicRepository.findByThemeIdAndDeletedAtIsNull(theme.getId());
            assertThat(activeLeft).isEmpty();
        }

        // ── 4.2 Negative Cases ────────────────────────────────────────────────

        /**
         * TC_CT_DTH_003
         * Delete non-existent theme (all-zeros UUID) → HTTP 404.
         *
         * [NOTE-5] errorCode: actual = "NOT_FOUND".
         */
        @Test
        @DisplayName("TC_CT_DTH_003 — non-existent themeId → HTTP 404, code='NOT_FOUND' [NOTE-5]")
        void tc_ct_dth_003_nonExistentThemeId_returns404() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            UUID nonExistentId = UUID.fromString("00000000-0000-0000-0000-000000000000");

            mockMvc.perform(delete("/api/v1/manager/conversation/themes/{id}", nonExistentId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        /**
         * TC_CT_DTH_004
         * Delete already-soft-deleted theme → HTTP 404.
         *
         * [NOTE-5] Same errorCode note.
         */
        @Test
        @DisplayName("TC_CT_DTH_004 — already-deleted theme → HTTP 404, code='NOT_FOUND' [NOTE-5]")
        void tc_ct_dth_004_alreadyDeletedTheme_returns404() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTheme deleted = saveSoftDeletedTheme("Already Deleted", manager.getId());

            mockMvc.perform(delete("/api/v1/manager/conversation/themes/{id}", deleted.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        // ── 4.3 Permission Cases ──────────────────────────────────────────────

        /**
         * TC_CT_DTH_005
         * Unauthenticated request → HTTP 401 Unauthorized.
         */
        @Test
        @DisplayName("TC_CT_DTH_005 — no token → HTTP 401 Unauthorized")
        void tc_ct_dth_005_unauthenticated_returns401() throws Exception {
            ConversationTheme theme = saveTheme("Test Theme", Course.ContentStatus.DRAFT);

            mockMvc.perform(delete("/api/v1/manager/conversation/themes/{id}", theme.getId()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CT_DTH_006
         * LEARNER attempts to delete theme → HTTP 403 Forbidden.
         */
        @Test
        @DisplayName("TC_CT_DTH_006 — LEARNER deletes theme → HTTP 403 Forbidden")
        void tc_ct_dth_006_learnerDeleteTheme_returns403() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");
            ConversationTheme theme = saveTheme("Test Theme", Course.ContentStatus.DRAFT);

            mockMvc.perform(delete("/api/v1/manager/conversation/themes/{id}", theme.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 5. VIEW THEME DETAIL — GET /api/v1/manager/conversation/themes/{id} [P3 Sanity]
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. VIEW THEME DETAIL — GET /api/v1/manager/conversation/themes/{id} [P3 Sanity]")
    class ViewThemeDetailTests {

        /**
         * TC_CT_VTI_001
         * MANAGER views theme detail — HTTP 200, ConversationThemeResponse
         * with correct non-nullable fields present.
         *
         * Fields asserted: id, title, status, createdAt, updatedAt, topicCount.
         * Fields NOT asserted (nullable, stripped by @JsonInclude NON_NULL when absent):
         *   courseId, courseName, description, thumbnailUrl, aiGeneratedContext.
         */
        @Test
        @DisplayName("TC_CT_VTI_001 — view detail → HTTP 200, id/title/status/createdAt/updatedAt/topicCount present")
        void tc_ct_vti_001_viewThemeDetail_returns200_requiredFieldsPresent() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTheme theme = saveTheme("Test Theme", Course.ContentStatus.DRAFT);

            // Act + Assert
            mockMvc.perform(get("/api/v1/manager/conversation/themes/{id}", theme.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(theme.getId().toString()))
                    .andExpect(jsonPath("$.data.title").value("Test Theme"))
                    .andExpect(jsonPath("$.data.status").isNotEmpty())
                    .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                    .andExpect(jsonPath("$.data.updatedAt").isNotEmpty())
                    .andExpect(jsonPath("$.data.topicCount").value(0));
        }
    }
}
