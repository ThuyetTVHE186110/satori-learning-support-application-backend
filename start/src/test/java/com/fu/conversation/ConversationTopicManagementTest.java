package com.fu.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.conversation.entity.Conversation;
import com.fu.conversation.entity.ConversationTopic;
import com.fu.conversation.entity.ConversationMission;
import com.fu.conversation.repository.ConversationRepository;
import com.fu.conversation.repository.ConversationTopicRepository;
import com.fu.conversation.repository.ConversationMissionRepository;
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

@SpringBootTest(classes = com.fu.StartApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Conversation Topic Management — Integration Tests ")
class ConversationTopicManagementTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private ConversationTopicRepository topicRepository;
    @Autowired private ConversationRepository conversationRepository;
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
                .role(UserRole.CONTENT_MANAGER)
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

    private User saveTeacher(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("ValidPass@1"))
                .firstName("Test").lastName("Teacher")
                .role(UserRole.TEACHER)
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

    private ConversationTopic saveActiveTopic(String title) {
        return topicRepository.save(ConversationTopic.builder()
                .title(title)
                .status(Course.ContentStatus.DRAFT)
                .orderIndex(0)
                .build());
    }

    private ConversationTopic saveSoftDeletedTopic(String title, UUID deletedBy) {
        ConversationTopic topic = topicRepository.save(ConversationTopic.builder()
                .title(title)
                .status(Course.ContentStatus.DRAFT)
                .orderIndex(0)
                .build());
        topic.setDeletedAt(LocalDateTime.now().minusHours(1));
        topic.setDeletedBy(deletedBy);
        return topicRepository.save(topic);
    }

    private Conversation saveConversation(String title, UUID topicId) {
        return conversationRepository.save(Conversation.builder()
                .title(title)
                .topicId(topicId)
                .status(Course.ContentStatus.DRAFT)
                .orderIndex(0)
                .build());
    }

    @AfterEach
    void tearDown() {
        conversationRepository.deleteAll();
        topicRepository.deleteAll();
        sessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Nested
    @DisplayName("1. VIEW TOPIC LIST — GET /api/v1/manager/conversation/topics [P3 Sanity]")
    class ViewTopicListTests {

        /**
         * TC_CTP_VTL_001
         * MANAGER views topic list — returns paginated list of all non-deleted topics.
         */
        @Test
        @DisplayName("TC_CTP_VTL_001 — 3 active topics returned, soft-deleted excluded")
        void tc_ctp_vtl_001_managerViewsList_returns3ActiveTopics_softDeletedExcluded() throws Exception {
            // Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            saveActiveTopic("Mua sắm tại siêu thị");
            saveActiveTopic("Đặt đồ ăn");
            saveActiveTopic("Hỏi đường");
            saveSoftDeletedTopic("Cũ", manager.getId());

            // Act + Assert
            MvcResult result = mockMvc.perform(get("/api/v1/manager/conversation/topics")
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

            // Verify soft-deleted topic absent
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
         * MANAGER searches by keyword (case-insensitive).
         */
        @Test
        @DisplayName("TC_CTP_VTL_002 — keyword search returns matching topics only")
        void tc_ctp_vtl_002_keywordSearch_returnsMatchingTopicsOnly() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            saveActiveTopic("Mua sắm tại siêu thị");
            saveActiveTopic("Đặt đồ ăn");
            saveActiveTopic("Hỏi đường");

            // Act + Assert
            MvcResult result = mockMvc.perform(get("/api/v1/manager/conversation/topics")
                            .param("page", "0").param("size", "10")
                            .param("keyword", "mua")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andReturn();

            String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            var content = objectMapper.readTree(body).path("data").path("content");
            assertThat(content.get(0).path("title").asText()).isEqualTo("Mua sắm tại siêu thị");
        }

        /**
         * TC_CTP_VTL_003
         * Keyword search with no results.
         */
        @Test
        @DisplayName("TC_CTP_VTL_003 — keyword no match → empty content")
        void tc_ctp_vtl_003_keywordNoResult_returnsEmptyList() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            saveActiveTopic("Mua sắm");

            // Act + Assert
            mockMvc.perform(get("/api/v1/manager/conversation/topics")
                            .param("page", "0").param("size", "10")
                            .param("keyword", "bệnh viện")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.totalElements").value(0))
                    .andExpect(jsonPath("$.data.content").isEmpty());
        }

        /**
         * TC_CTP_VTL_004
         * Pagination: default size=10, show page 0 and page 1.
         */
        @Test
        @DisplayName("TC_CTP_VTL_004 — pagination 15 items: page 0 has 10, page 1 has 5")
        void tc_ctp_vtl_004_pagination_returns10Of15OnPage0() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            for (int i = 1; i <= 15; i++) {
                saveActiveTopic("Topic " + i);
            }

            // Act + Assert — page 0
            mockMvc.perform(get("/api/v1/manager/conversation/topics")
                            .param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.pageSize").value(10))
                    .andExpect(jsonPath("$.data.totalElements").value(15))
                    .andExpect(jsonPath("$.data.totalPages").value(2))
                    .andExpect(jsonPath("$.data.content.length()").value(10));

            // Act + Assert — page 1
            mockMvc.perform(get("/api/v1/manager/conversation/topics")
                            .param("page", "1").param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pageNumber").value(1))
                    .andExpect(jsonPath("$.data.content.length()").value(5));
        }

        /**
         * TC_CTP_VTL_005
         * Keyword exceeds max 100 chars.
         */
        @Test
        @DisplayName("TC_CTP_VTL_005 — keyword > 100 chars → validation error")
        void tc_ctp_vtl_005_keywordTooLong_returns400() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            String longKeyword = "a".repeat(101);

            // Act + Assert
            mockMvc.perform(get("/api/v1/manager/conversation/topics")
                            .param("keyword", longKeyword)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("2. VIEW TOPIC DETAIL — GET /api/v1/manager/conversation/topics/{topicId} [P3 Sanity]")
    class ViewTopicDetailTests {

        /**
         * TC_CTP_VTD_001
         * Get active topic — returns full response with all fields.
         */
        @Test
        @DisplayName("TC_CTP_VTD_001 — get active topic → full response with new fields")
        void tc_ctp_vtd_001_getActiveTopic_returnsFull() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = topicRepository.save(ConversationTopic.builder()
                    .title("Shopping")
                    .titleJapanese("買い物")
                    .description("Shopping scenarios")
                    .descriptionVi("Các tình huống mua sắm")
                    .category("Daily Life")
                    .jlptLevel(Course.JlptLevel.N3)
                    .status(Course.ContentStatus.DRAFT)
                    .orderIndex(0)
                    .build());

            // Act + Assert
            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(topic.getId().toString()))
                    .andExpect(jsonPath("$.data.title").value("Shopping"))
                    .andExpect(jsonPath("$.data.titleJapanese").value("買い物"))
                    .andExpect(jsonPath("$.data.description").value("Shopping scenarios"))
                    .andExpect(jsonPath("$.data.descriptionVi").value("Các tình huống mua sắm"))
                    .andExpect(jsonPath("$.data.category").value("Daily Life"))
                    .andExpect(jsonPath("$.data.jlptLevel").value("N3"))
                    .andExpect(jsonPath("$.data.status").value("DRAFT"));
        }

        /**
         * TC_CTP_VTD_002
         * Get deleted topic → 404 NotFound.
         */
        @Test
        @DisplayName("TC_CTP_VTD_002 — get deleted topic → 404 NotFound")
        void tc_ctp_vtd_002_getDeletedTopic_returns404() throws Exception {
            // Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveSoftDeletedTopic("Deleted", manager.getId());

            // Act + Assert
            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CTP_VTD_003
         * Get non-existent topic.
         */
        @Test
        @DisplayName("TC_CTP_VTD_003 — get non-existent topic → 404 NotFound")
        void tc_ctp_vtd_003_getNonExistent_returns404() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            UUID nonExistentId = UUID.randomUUID();

            // Act + Assert
            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}", nonExistentId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("3. CREATE TOPIC — POST /api/v1/manager/conversation/topics [P2 Standard]")
    class CreateTopicTests {

        /**
         * TC_CTP_CTH_001
         * Create topic with title only → defaults applied.
         */
        @Test
        @DisplayName("TC_CTP_CTH_001 — title only → HTTP 201, defaults applied")
        void tc_ctp_cth_001_titleOnly_returns201() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            String body = "{\"title\":\"Mua sắm\"}";

            // Act
            MvcResult result = mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.title").value("Mua sắm"))
                    .andExpect(jsonPath("$.data.id").isNotEmpty())
                    .andExpect(jsonPath("$.data.status").value("DRAFT"))
                    .andReturn();

            // Assert DB state
            String idStr = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("id").asText();
            ConversationTopic topic = topicRepository.findById(UUID.fromString(idStr)).orElseThrow();
            assertThat(topic.getTitle()).isEqualTo("Mua sắm");
            assertThat(topic.getStatus()).isEqualTo(Course.ContentStatus.DRAFT);
            assertThat(topic.getOrderIndex()).isEqualTo(0);
            assertThat(topic.getDeletedAt()).isNull();
            assertThat(topic.getDeletedBy()).isNull();
        }

        /**
         * TC_CTP_CTH_002
         * Create topic with all fields.
         */
        @Test
        @DisplayName("TC_CTP_CTH_002 — all fields → HTTP 201, all persisted")
        void tc_ctp_cth_002_allFields_returns201() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            String body = """
                {
                  "title": "Shopping",
                  "titleJapanese": "買い物",
                  "description": "Shopping at supermarket",
                  "descriptionVi": "Mua sắm tại siêu thị",
                  "category": "Daily Life",
                  "jlptLevel": "N3",
                  "orderIndex": 5
                }
                """;

            // Act + Assert
            MvcResult result = mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.title").value("Shopping"))
                    .andExpect(jsonPath("$.data.titleJapanese").value("買い物"))
                    .andExpect(jsonPath("$.data.description").value("Shopping at supermarket"))
                    .andExpect(jsonPath("$.data.descriptionVi").value("Mua sắm tại siêu thị"))
                    .andExpect(jsonPath("$.data.category").value("Daily Life"))
                    .andExpect(jsonPath("$.data.jlptLevel").value("N3"))
                    .andExpect(jsonPath("$.data.orderIndex").value(5))
                    .andReturn();

            String idStr = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("id").asText();
            ConversationTopic topic = topicRepository.findById(UUID.fromString(idStr)).orElseThrow();
            assertThat(topic.getTitle()).isEqualTo("Shopping");
            assertThat(topic.getTitleJapanese()).isEqualTo("買い物");
            assertThat(topic.getOrderIndex()).isEqualTo(5);
        }

        /**
         * TC_CTP_CTH_003
         * Title blank → validation error.
         */
        @Test
        @DisplayName("TC_CTP_CTH_003 — blank title → 400 validation error")
        void tc_ctp_cth_003_titleBlank_returns400() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            String body = "{\"title\":\"\"}";

            // Act + Assert
            mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CTP_CTH_004
         * Title > 255 chars → validation error.
         */
        @Test
        @DisplayName("TC_CTP_CTH_004 — title > 255 → 400 validation error")
        void tc_ctp_cth_004_titleTooLong_returns400() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            String longTitle = "a".repeat(256);
            String body = String.format("{\"title\":\"%s\"}", longTitle);

            // Act + Assert
            mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        /**
         * TC_CTP_CTH_005
         * Description > 1000 chars → validation error.
         */
        @Test
        @DisplayName("TC_CTP_CTH_005 — description > 1000 → 400 validation error")
        void tc_ctp_cth_005_descriptionTooLong_returns400() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            String longDesc = "a".repeat(1001);
            String body = String.format("{\"title\":\"Test\",\"description\":\"%s\"}", longDesc);

            // Act + Assert
            mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("4. UPDATE TOPIC — PUT /api/v1/manager/conversation/topics/{topicId} [P2 Standard]")
    class UpdateTopicTests {

        /**
         * TC_CTP_UTH_001
         * Update title only → only title changed.
         */
        @Test
        @DisplayName("TC_CTP_UTH_001 — update title only → only title changed")
        void tc_ctp_uth_001_updateTitleOnly_onlyTitleChanged() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = topicRepository.save(ConversationTopic.builder()
                    .title("Old Title")
                    .description("Old Description")
                    .status(Course.ContentStatus.DRAFT)
                    .orderIndex(0)
                    .build());

            String body = "{\"title\":\"New Title\"}";

            // Act + Assert
            mockMvc.perform(put("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("New Title"))
                    .andExpect(jsonPath("$.data.description").value("Old Description"));

            // Verify DB
            ConversationTopic updated = topicRepository.findById(topic.getId()).orElseThrow();
            assertThat(updated.getTitle()).isEqualTo("New Title");
            assertThat(updated.getDescription()).isEqualTo("Old Description");
        }

        /**
         * TC_CTP_UTH_002
         * Update all fields.
         */
        @Test
        @DisplayName("TC_CTP_UTH_002 — update all fields → all updated")
        void tc_ctp_uth_002_updateAllFields_allUpdated() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveActiveTopic("Old");

            String body = """
                {
                  "title": "New Title",
                  "description": "New Description",
                  "titleJapanese": "新しいタイトル",
                  "descriptionVi": "Mô tả mới",
                  "category": "New Category"
                }
                """;

            // Act + Assert
            mockMvc.perform(put("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("New Title"))
                    .andExpect(jsonPath("$.data.description").value("New Description"))
                    .andExpect(jsonPath("$.data.titleJapanese").value("新しいタイトル"));
        }

        /**
         * TC_CTP_UTH_003
         * Update deleted topic → 404.
         */
        @Test
        @DisplayName("TC_CTP_UTH_003 — update deleted topic → 404 NotFound")
        void tc_ctp_uth_003_updateDeletedTopic_returns404() throws Exception {
            // Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveSoftDeletedTopic("Deleted", manager.getId());

            String body = "{\"title\":\"New Title\"}";

            // Act + Assert
            mockMvc.perform(put("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound());
        }

        /**
         * TC_CTP_UTH_005
         * Update title blank.
         */
        @Test
        @DisplayName("TC_CTP_UTH_005 — update to blank title → 400")
        void tc_ctp_uth_005_updateToBlankTitle_returns400() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveActiveTopic("Test");

            String body = "{\"title\":\"\"}";

            // Act + Assert
            mockMvc.perform(put("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        /**
         * TC_CTP_UTH_006
         * Verify createdAt/createdBy not changed.
         */
        @Test
        @DisplayName("TC_CTP_UTH_006 — createdAt/createdBy not changed on update")
        void tc_ctp_uth_006_createdAtNotChanged() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = topicRepository.save(ConversationTopic.builder()
                    .title("Original")
                    .status(Course.ContentStatus.DRAFT)
                    .orderIndex(0)
                    .build());

            LocalDateTime createdAt = topic.getCreatedAt();
            UUID createdBy = topic.getCreatedBy();

            // Wait a bit to ensure timestamp difference
            Thread.sleep(100);

            String body = "{\"title\":\"Updated\"}";

            // Act
            mockMvc.perform(put("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());

            // Assert: createdAt not changed, only updatedAt changed
            ConversationTopic updated = topicRepository.findById(topic.getId()).orElseThrow();
            assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
            assertThat(updated.getCreatedBy()).isEqualTo(createdBy);
            assertThat(updated.getUpdatedAt()).isAfter(createdAt);
        }
    }

    @Nested
    @DisplayName("5. DELETE TOPIC — DELETE /api/v1/manager/conversation/topics/{topicId} [P2 Standard]")
    class DeleteTopicTests {

        /**
         * TC_CTP_DTH_001
         * Delete active topic → soft-deleted.
         */
        @Test
        @DisplayName("TC_CTP_DTH_001 — delete active topic → soft-deleted in DB")
        void tc_ctp_dth_001_deleteActiveTopic_softDeleted() throws Exception {
            // Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveActiveTopic("Test");

            // Act
            mockMvc.perform(delete("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            // Assert: topic soft-deleted in DB
            ConversationTopic deleted = topicRepository.findById(topic.getId()).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();
            assertThat(deleted.getDeletedBy()).isEqualTo(manager.getId());
        }

        /**
         * TC_CTP_DTH_002
         * Delete topic cascades soft-delete to conversations.
         */
        @Test
        @DisplayName("TC_CTP_DTH_002 — delete topic cascades soft-delete to 3 conversations")
        void tc_ctp_dth_002_deleteTopicCascadesConversations() throws Exception {
            // Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveActiveTopic("Test Topic");

            Conversation conv1 = saveConversation("Conv 1", topic.getId());
            Conversation conv2 = saveConversation("Conv 2", topic.getId());
            Conversation conv3 = saveConversation("Conv 3", topic.getId());

            // Act
            mockMvc.perform(delete("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            // Assert: topic soft-deleted
            ConversationTopic deleted = topicRepository.findById(topic.getId()).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();

            // Assert: all conversations soft-deleted
            Conversation deletedConv1 = conversationRepository.findById(conv1.getId()).orElseThrow();
            Conversation deletedConv2 = conversationRepository.findById(conv2.getId()).orElseThrow();
            Conversation deletedConv3 = conversationRepository.findById(conv3.getId()).orElseThrow();

            assertThat(deletedConv1.getDeletedAt()).isNotNull();
            assertThat(deletedConv2.getDeletedAt()).isNotNull();
            assertThat(deletedConv3.getDeletedAt()).isNotNull();
        }

        /**
         * TC_CTP_DTH_003
         * Delete deleted topic → 404.
         */
        @Test
        @DisplayName("TC_CTP_DTH_003 — delete already-deleted topic → 404 NotFound")
        void tc_ctp_dth_003_deleteDeletedTopic_returns404() throws Exception {
            // Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveSoftDeletedTopic("Deleted", manager.getId());

            // Act + Assert
            mockMvc.perform(delete("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        /**
         * TC_CTP_DTH_004
         * Delete non-existent topic → 404.
         */
        @Test
        @DisplayName("TC_CTP_DTH_004 — delete non-existent topic → 404 NotFound")
        void tc_ctp_dth_004_deleteNonExistent_returns404() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            UUID nonExistentId = UUID.randomUUID();

            // Act + Assert
            mockMvc.perform(delete("/api/v1/manager/conversation/topics/{topicId}", nonExistentId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        /**
         * TC_CTP_DTH_005
         * Deleted topic no longer in list.
         */
        @Test
        @DisplayName("TC_CTP_DTH_005 — deleted topic not in list")
        void tc_ctp_dth_005_deletedTopicNotInList() throws Exception {
            // Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveActiveTopic("Test");

            // Act: Delete
            mockMvc.perform(delete("/api/v1/manager/conversation/topics/{topicId}", topic.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            // Assert: not in list
            mockMvc.perform(get("/api/v1/manager/conversation/topics")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(0))
                    .andExpect(jsonPath("$.data.content").isEmpty());
        }
    }

    @Nested
    @DisplayName("6. RBAC TESTS — @PreAuthorize Class-Level Guard Verification")
    class RBACTests {

        /**
         * TC_CTP_RBAC_001
         * GET request without token → 401 Unauthorized.
         * Verifies class-level @PreAuthorize guard covers all endpoints.
         */
        @Test
        @DisplayName("TC_CTP_RBAC_001 — GET without token → 401 Unauthorized")
        void tc_ctp_rbac_001_getWithoutToken_returns401() throws Exception {
            // Act + Assert
            mockMvc.perform(get("/api/v1/manager/conversation/topics"))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * TC_CTP_RBAC_002
         * GET request with LEARNER role → 403 Forbidden.
         * Class-level @PreAuthorize blocks non-CONTENT_MANAGER roles.
         */
        @Test
        @DisplayName("TC_CTP_RBAC_002 — GET with LEARNER role → 403 Forbidden")
        void tc_ctp_rbac_002_getWithLearner_returns403() throws Exception {
            // Arrange
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            // Act + Assert
            mockMvc.perform(get("/api/v1/manager/conversation/topics")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }

        /**
         * TC_CTP_RBAC_003
         * POST request with TEACHER role → 403 Forbidden.
         * Demonstrates @PreAuthorize guard works for all HTTP methods.
         */
        @Test
        @DisplayName("TC_CTP_RBAC_003 — POST with TEACHER role → 403 Forbidden")
        void tc_ctp_rbac_003_postWithTeacher_returns403() throws Exception {
            // Arrange
            saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com", "ValidPass@1");
            String body = "{\"title\":\"Test\"}";

            // Act + Assert
            mockMvc.perform(post("/api/v1/manager/conversation/topics")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
        }

        /**
         * TC_CTP_RBAC_004
         * DELETE request with ADMIN role → 403 Forbidden.
         * Even elevated roles like ADMIN cannot access CONTENT_MANAGER endpoints.
         */
        @Test
        @DisplayName("TC_CTP_RBAC_004 — DELETE with ADMIN role → 403 Forbidden")
        void tc_ctp_rbac_004_deleteWithAdmin_returns403() throws Exception {
            // Arrange
            User admin = saveAdmin("admin@test.com");
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");
            UUID dummyTopicId = UUID.randomUUID();

            // Act + Assert
            mockMvc.perform(delete("/api/v1/manager/conversation/topics/{topicId}", dummyTopicId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }
}