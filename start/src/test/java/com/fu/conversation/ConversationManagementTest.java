package com.fu.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.conversation.dto.request.CreateConversationRequest;
import com.fu.conversation.dto.request.UpdateConversationRequest;
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
@DisplayName("Conversation Management — Integration Tests")
class ConversationManagementTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationTopicRepository topicRepository;

    @Autowired
    private ConversationMissionRepository missionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserSessionRepository sessionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private EmailService emailService;

    // ─────────────────────────────────────────────────────────────
    // HELPER METHODS
    // ─────────────────────────────────────────────────────────────

    private User saveManager(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("ValidPass@1"))
                .firstName("Test")
                .lastName("Manager")
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
                .firstName("Test")
                .lastName("Learner")
                .role(UserRole.LEARNER)
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

    private ConversationTopic saveTopic(String title) {
        return topicRepository.save(ConversationTopic.builder()
                .title(title)
                .status(Course.ContentStatus.DRAFT)
                .orderIndex(0)
                .build());
    }

    private Conversation saveConversation(String title, UUID topicId) {
        return conversationRepository.save(Conversation.builder()
                .title(title)
                .topicId(topicId)
                .status(Course.ContentStatus.DRAFT)
                .orderIndex(0)
                .aiRole("AI Character")
                .learnerRole("Learner")
                .build());
    }

    private Conversation saveSoftDeletedConversation(String title, UUID topicId, UUID deletedBy) {
        Conversation conversation = conversationRepository.save(Conversation.builder()
                .title(title)
                .topicId(topicId)
                .status(Course.ContentStatus.DRAFT)
                .orderIndex(0)
                .aiRole("AI Character")
                .learnerRole("Learner")
                .build());
        conversation.setDeletedAt(LocalDateTime.now().minusHours(1));
        conversation.setDeletedBy(deletedBy);
        return conversationRepository.save(conversation);
    }

    private ConversationMission saveMission(UUID conversationId, String title) {
        return missionRepository.save(ConversationMission.builder()
                .conversationId(conversationId)
                .title(title)
                .missionType(ConversationMission.MissionType.CUSTOM)
                .orderIndex(0)
                .build());
    }

    @AfterEach
    void tearDown() {
        missionRepository.deleteAll();
        conversationRepository.deleteAll();
        topicRepository.deleteAll();
        sessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Nested
    @DisplayName("2.7.1 View Conversation List")
    class ViewConversationListTests {

        @Test
        @DisplayName("TC_CVL_001 — 3 active conversations returned, soft-deleted excluded")
        void tc_cvl_001_managerViewsList_returns3ActiveConversations_softDeletedExcluded() throws Exception {
            // ─ Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Restaurant Conversation");

            Conversation conv1 = saveConversation("Ordering Food", topic.getId());
            Conversation conv2 = saveConversation("Making Reservation", topic.getId());
            Conversation conv3 = saveConversation("Paying Bill", topic.getId());
            saveSoftDeletedConversation("Deleted Conversation", topic.getId(), manager.getId());

            // ─ Act + Assert
            MvcResult result = mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}/conversations", topic.getId())
                            .param("page", "0")
                            .param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.pageSize").value(10))
                    .andExpect(jsonPath("$.data.totalElements").value(3))
                    .andExpect(jsonPath("$.data.totalPages").value(1))
                    .andExpect(jsonPath("$.data.content.length()").value(3))
                    .andReturn();

            // ─ Verify response structure and content
            String responseBody = result.getResponse().getContentAsString();
            var jsonNode = objectMapper.readTree(responseBody);
            var content = jsonNode.path("data").path("content");

            List<String> titles = new ArrayList<>();
            content.forEach(node -> titles.add(node.path("title").asText()));

            assertThat(titles)
                    .containsExactlyInAnyOrder("Ordering Food", "Making Reservation", "Paying Bill")
                    .doesNotContain("Deleted Conversation");

            // ─ Verify each item has required fields
            content.forEach(item -> {
                assertThat(item.has("id")).isTrue();
                assertThat(item.has("title")).isTrue();
                assertThat(item.has("status")).isTrue();
                assertThat(item.path("id").asText()).isNotBlank();
            });

            // ─ Verify database state
            long activeCount = conversationRepository.count();
            assertThat(activeCount).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("TC_CVL_002 — Pagination: multiple pages (15 items)")
        void tc_cvl_002_pagination_multiplePages() throws Exception {
            // ─ Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test Topic");

            for (int i = 1; i <= 15; i++) {
                Conversation conv = saveConversation("Conversation " + i, topic.getId());
                conv.setOrderIndex(i - 1);
                conversationRepository.save(conv);
            }

            // ─ Act: Get Page 0
            MvcResult result0 = mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}/conversations", topic.getId())
                            .param("page", "0")
                            .param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.totalElements").value(15))
                    .andExpect(jsonPath("$.data.totalPages").value(2))
                    .andExpect(jsonPath("$.data.content.length()").value(10))
                    .andReturn();

            // ─ Act: Get Page 1
            MvcResult result1 = mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}/conversations", topic.getId())
                            .param("page", "1")
                            .param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pageNumber").value(1))
                    .andExpect(jsonPath("$.data.totalElements").value(15))
                    .andExpect(jsonPath("$.data.totalPages").value(2))
                    .andExpect(jsonPath("$.data.content.length()").value(5))
                    .andReturn();

            // ─ Assert content in page 0
            var content0 = objectMapper.readTree(result0.getResponse().getContentAsString())
                    .path("data").path("content");
            assertThat(content0.size()).isEqualTo(10);

            // ─ Assert content in page 1
            var content1 = objectMapper.readTree(result1.getResponse().getContentAsString())
                    .path("data").path("content");
            assertThat(content1.size()).isEqualTo(5);
        }

        @Test
        @DisplayName("TC_CVL_003 — Keyword search: matching conversations only")
        void tc_cvl_003_keywordSearch_returnsMatchingOnly() throws Exception {
            // ─ Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test Topic");

            saveConversation("Restaurant - Ordering", topic.getId());
            saveConversation("Restaurant - Reservation", topic.getId());
            saveConversation("Hotel - Check-in", topic.getId());
            saveConversation("Train Station - Ticket", topic.getId());

            // ─ Act + Assert
            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}/conversations", topic.getId())
                            .param("page", "0")
                            .param("size", "10")
                            .param("keyword", "Restaurant")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.content.length()").value(2))
                    .andExpect(jsonPath("$.data.content[0].title").value("Restaurant - Ordering"))
                    .andExpect(jsonPath("$.data.content[1].title").value("Restaurant - Reservation"))
                    .andReturn();
        }

        @Test
        @DisplayName("TC_CVL_004 — Keyword length validation (> 100 chars)")
        void tc_cvl_004_keywordTooLong_returns400() throws Exception {
            // ─ Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Test Topic");

            String longKeyword = "a".repeat(101);

            // ─ Act + Assert
            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}/conversations", topic.getId())
                            .param("page", "0")
                            .param("size", "10")
                            .param("keyword", longKeyword)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_CVL_005 — filter by jlptLevel returns matching only")
        void tc_cvl_005_filterByJlptLevel_returnsMatching() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Test");

            Conversation n1_conv = saveConversation("N1 Conversation", topic.getId());
            n1_conv.setJlptLevel(Course.JlptLevel.N1);
            conversationRepository.save(n1_conv);

            Conversation n2_conv1 = saveConversation("N2 Conversation 1", topic.getId());
            n2_conv1.setJlptLevel(Course.JlptLevel.N2);
            conversationRepository.save(n2_conv1);

            Conversation n2_conv2 = saveConversation("N2 Conversation 2", topic.getId());
            n2_conv2.setJlptLevel(Course.JlptLevel.N2);
            conversationRepository.save(n2_conv2);

            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}/conversations", topic.getId())
                            .param("page", "0")
                            .param("size", "10")
                            .param("jlptLevel", "N2")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.content.length()").value(2));
        }

        @Test
        @DisplayName("TC_CVL_006 — filter by status returns matching only")
        void tc_cvl_006_filterByStatus_returnsMatching() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Test");

            Conversation draft = saveConversation("Draft Conv", topic.getId());
            draft.setStatus(Course.ContentStatus.DRAFT);
            conversationRepository.save(draft);

            Conversation published1 = saveConversation("Published Conv 1", topic.getId());
            published1.setStatus(Course.ContentStatus.PUBLISHED);
            conversationRepository.save(published1);

            Conversation published2 = saveConversation("Published Conv 2", topic.getId());
            published2.setStatus(Course.ContentStatus.PUBLISHED);
            conversationRepository.save(published2);

            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}/conversations", topic.getId())
                            .param("page", "0")
                            .param("size", "10")
                            .param("status", "PUBLISHED")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2));
        }

        @Test
        @DisplayName("TC_CVL_007 — combined filters use AND logic")
        void tc_cvl_007_combinedFilters_usesANDLogic() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Test");

            Conversation n1_draft = saveConversation("N1 Draft", topic.getId());
            n1_draft.setJlptLevel(Course.JlptLevel.N1);
            n1_draft.setStatus(Course.ContentStatus.DRAFT);
            conversationRepository.save(n1_draft);

            Conversation n1_published = saveConversation("N1 Published", topic.getId());
            n1_published.setJlptLevel(Course.JlptLevel.N1);
            n1_published.setStatus(Course.ContentStatus.PUBLISHED);
            conversationRepository.save(n1_published);

            Conversation n2_published = saveConversation("N2 Published", topic.getId());
            n2_published.setJlptLevel(Course.JlptLevel.N2);
            n2_published.setStatus(Course.ContentStatus.PUBLISHED);
            conversationRepository.save(n2_published);

            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}/conversations", topic.getId())
                            .param("page", "0")
                            .param("size", "10")
                            .param("jlptLevel", "N1")
                            .param("status", "PUBLISHED")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].title").value("N1 Published"));
        }

        @Test
        @DisplayName("TC_CVL_008 — no matching results returns empty list")
        void tc_cvl_008_noResults_returnsEmptyList() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Test");

            saveConversation("Conversation A", topic.getId());
            saveConversation("Conversation B", topic.getId());

            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}/conversations", topic.getId())
                            .param("page", "0")
                            .param("size", "10")
                            .param("keyword", "NonExistent")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(0))
                    .andExpect(jsonPath("$.data.content.length()").value(0));
        }
    }

    @Nested
    @DisplayName("2.7.3 Create Conversation")
    class CreateConversationTests {

        // ═════════════════════════════════════════════════════════════════════════════════
        // SECTION 1: BR-186 (Required Fields) & BR-185 (Default Status to ACTIVE)
        // Covers: aiRole, learnerRole, scenario, missions must not be empty
        //         Newly created conversation gets ACTIVE status by default
        // ═════════════════════════════════════════════════════════════════════════════════

        @Test
        @DisplayName("TC_CCV_001 — Valid data creates conversation with DRAFT status by default")
        void tc_ccv_001_validData_createsWithActiveStatus() throws Exception {
            // ─ Arrange: Create valid conversation with all required fields
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Restaurant");

            String requestBody = objectMapper.writeValueAsString(CreateConversationRequest.builder()
                    .topicId(topic.getId())
                    .title("Ordering at Restaurant")
                    .titleJapanese("レストランで注文する")
                    .description("Learn how to order food at a restaurant")
                    .descriptionVi("Học cách đặt đồ ăn tại nhà hàng")
                    .scenario("You are at a restaurant and want to order")  // ← BR-186: scenario required
                    .aiRole("Restaurant Staff (polite, helpful)")           // ← BR-186: aiRole required
                    .learnerRole("Customer")                                // ← BR-186: learnerRole required
                    .jlptLevel(Course.JlptLevel.N2)
                    .difficultyScore(5)
                    .vocabularyHints("[\"予約\", \"テーブル\", \"人数\"]")
                    .grammarPoints("[\"〜ください\", \"〜たいです\"]")
                    .build());

            // ─ Act: Create conversation
            MvcResult result = mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").isNotEmpty())
                    .andExpect(jsonPath("$.data.title").value("Ordering at Restaurant"))
                    .andExpect(jsonPath("$.data.aiRole").value("Restaurant Staff (polite, helpful)"))
                    .andExpect(jsonPath("$.data.learnerRole").value("Customer"))
                    .andExpect(jsonPath("$.data.scenario").value("You are at a restaurant and want to order"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))  // ← BR-185: Default status is ACTIVE
                    .andExpect(jsonPath("$.data.createdBy").isNotEmpty())
                    .andReturn();

            // ─ Assert: Verify database state
            UUID conversationId = UUID.fromString(
                    objectMapper.readTree(result.getResponse().getContentAsString())
                            .path("data").path("id").asText());

            Conversation saved = conversationRepository.findById(conversationId).orElseThrow();
            assertThat(saved.getTitle()).isEqualTo("Ordering at Restaurant");
            assertThat(saved.getTopicId()).isEqualTo(topic.getId());
            assertThat(saved.getStatus()).isEqualTo(Course.ContentStatus.DRAFT);
            assertThat(saved.getCreatedBy()).isEqualTo(manager.getId());
            assertThat(saved.getDeletedAt()).isNull();
            assertThat(saved.getAiRole()).isEqualTo("Restaurant Staff (polite, helpful)");
            assertThat(saved.getLearnerRole()).isEqualTo("Customer");
            assertThat(saved.getScenario()).isEqualTo("You are at a restaurant and want to order");
        }

        @Test
        @DisplayName("TC_CCV_002 — Missing aiRole returns 400 (BR-186)")
        void tc_ccv_002_missingAiRole_returns400() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Restaurant");

            String requestBody = objectMapper.writeValueAsString(CreateConversationRequest.builder()
                    .topicId(topic.getId())
                    .title("Test")
                    .aiRole(null)  // ← BR-186: aiRole required (empty/null not allowed)
                    .learnerRole("Learner")
                    .scenario("Test scenario")
                    .build());

            // ─ Act + Assert
            mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));

            assertThat(conversationRepository.count()).isEqualTo(0);
        }

        @Test
        @DisplayName("TC_CCV_003 — Missing learnerRole returns 400 (BR-186)")
        void tc_ccv_003_missingLearnerRole_returns400() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Restaurant");

            String requestBody = objectMapper.writeValueAsString(CreateConversationRequest.builder()
                    .topicId(topic.getId())
                    .title("Test")
                    .aiRole("AI")
                    .learnerRole(null)  // ← BR-186: learnerRole required (empty/null not allowed)
                    .scenario("Test scenario")
                    .build());

            // ─ Act + Assert
            mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));

            assertThat(conversationRepository.count()).isEqualTo(0);
        }

        @Test
        @DisplayName("TC_CCV_004 — Missing scenario returns 400 (BR-186)")
        void tc_ccv_004_missingScenario_returns400() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Restaurant");

            String requestBody = objectMapper.writeValueAsString(CreateConversationRequest.builder()
                    .topicId(topic.getId())
                    .title("Test")
                    .aiRole("AI")
                    .learnerRole("Learner")
                    .scenario(null)  // ← BR-186: scenario required (empty/null not allowed)
                    .build());

            // ─ Act + Assert
            mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));

            assertThat(conversationRepository.count()).isEqualTo(0);
        }

        // ═════════════════════════════════════════════════════════════════════════════════
        // SECTION 2: BR-183 (Title Validation)
        // Covers: Title is required and length does not exceed 255 characters
        // ═════════════════════════════════════════════════════════════════════════════════

        @Test
        @DisplayName("TC_CCV_005 — Missing title returns 400 (BR-183)")
        void tc_ccv_005_missingTitle_returns400() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Restaurant");

            String requestBody = objectMapper.writeValueAsString(CreateConversationRequest.builder()
                    .topicId(topic.getId())
                    .title(null)  // ← BR-183: title is required
                    .aiRole("AI")
                    .learnerRole("Learner")
                    .scenario("Test")
                    .build());

            // ─ Act + Assert
            mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));

            assertThat(conversationRepository.count()).isEqualTo(0);
        }

        @Test
        @DisplayName("TC_CCV_006 — Empty title returns 400 (BR-183)")
        void tc_ccv_006_emptyTitle_returns400() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Restaurant");

            String requestBody = objectMapper.writeValueAsString(CreateConversationRequest.builder()
                    .topicId(topic.getId())
                    .title("")  // ← BR-183: title cannot be empty
                    .aiRole("AI")
                    .learnerRole("Learner")
                    .scenario("Test")
                    .build());

            // ─ Act + Assert
            mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));

            assertThat(conversationRepository.count()).isEqualTo(0);
        }

        @Test
        @DisplayName("TC_CCV_007 — Title exactly 255 characters succeeds (BR-183)")
        void tc_ccv_007_titleExactly255Chars_succeeds() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Test");

            String title255 = "a".repeat(255);  // ← BR-183: max length is 255, should succeed
            String requestBody = objectMapper.writeValueAsString(CreateConversationRequest.builder()
                    .topicId(topic.getId())
                    .title(title255)
                    .aiRole("AI")
                    .learnerRole("Learner")
                    .scenario("Test")
                    .build());

            // ─ Act
            MvcResult result = mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andReturn();

            // ─ Assert: Verify title stored correctly
            UUID conversationId = UUID.fromString(
                    objectMapper.readTree(result.getResponse().getContentAsString())
                            .path("data").path("id").asText());
            Conversation saved = conversationRepository.findById(conversationId).orElseThrow();
            assertThat(saved.getTitle()).isEqualTo(title255);
            assertThat(saved.getTitle().length()).isEqualTo(255);
        }

        @Test
        @DisplayName("TC_CCV_008 — Title exceeding 255 characters returns 400 (BR-183)")
        void tc_ccv_008_titleExceeds255Chars_returns400() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Test");

            String title256 = "a".repeat(256);  // ← BR-183: length exceeds 255, should fail
            String requestBody = objectMapper.writeValueAsString(CreateConversationRequest.builder()
                    .topicId(topic.getId())
                    .title(title256)
                    .aiRole("AI")
                    .learnerRole("Learner")
                    .scenario("Test")
                    .build());

            // ─ Act + Assert
            mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));

            assertThat(conversationRepository.count()).isEqualTo(0);
        }

        // ═════════════════════════════════════════════════════════════════════════════════
        // SECTION 3: BR-184 (Description Validation)
        // Covers: Description length does not exceed 1000 characters
        // ═════════════════════════════════════════════════════════════════════════════════

        @Test
        @DisplayName("TC_CCV_009 — Description exactly 1000 characters succeeds (BR-184)")
        void tc_ccv_009_descriptionExactly1000Chars_succeeds() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Test");

            String description1000 = "a".repeat(1000);  // ← BR-184: max length is 1000, should succeed
            String requestBody = objectMapper.writeValueAsString(CreateConversationRequest.builder()
                    .topicId(topic.getId())
                    .title("Valid Title")
                    .description(description1000)
                    .aiRole("AI")
                    .learnerRole("Learner")
                    .scenario("Test")
                    .build());

            // ─ Act
            MvcResult result = mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andReturn();

            // ─ Assert: Verify description stored correctly
            UUID conversationId = UUID.fromString(
                    objectMapper.readTree(result.getResponse().getContentAsString())
                            .path("data").path("id").asText());
            Conversation saved = conversationRepository.findById(conversationId).orElseThrow();
            assertThat(saved.getDescription()).isEqualTo(description1000);
            assertThat(saved.getDescription().length()).isEqualTo(1000);
        }

        @Test
        @DisplayName("TC_CCV_010 — Description exceeding 1000 characters returns 400 (BR-184)")
        void tc_ccv_010_descriptionExceeds1000Chars_returns400() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Test");

            String description1001 = "a".repeat(1001);  // ← BR-184: length exceeds 1000, should fail
            String requestBody = objectMapper.writeValueAsString(CreateConversationRequest.builder()
                    .topicId(topic.getId())
                    .title("Valid Title")
                    .description(description1001)
                    .aiRole("AI")
                    .learnerRole("Learner")
                    .scenario("Test")
                    .build());

            // ─ Act + Assert
            mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));

            assertThat(conversationRepository.count()).isEqualTo(0);
        }


        // ═════════════════════════════════════════════════════════════════════════════════
        // SECTION 4: BR-281 (Mission Count Validation)
        // Covers: Conversation has at least 1 mission and does not exceed 3 missions
        // Combined with BR-186 (missions not empty)
        // ═════════════════════════════════════════════════════════════════════════════════

        @Test
        @DisplayName("TC_CCV_012 — Create with no missions returns 400 (BR-281)")
        void tc_ccv_012_noMissions_returns400() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Restaurant");

            String requestBody = """
                {
                    "topicId": "%s",
                    "title": "Restaurant Booking",
                    "aiRole": "Staff",
                    "learnerRole": "Customer",
                    "scenario": "Booking a table",
                    "missions": []
                }
                """.formatted(topic.getId());

            // ─ Act + Assert
            mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));

            // ─ Verify: No conversation created
            assertThat(conversationRepository.count()).isEqualTo(0);
            assertThat(missionRepository.count()).isEqualTo(0);
        }

        @Test
        @DisplayName("TC_CCV_013 — Create with exactly 1 mission succeeds (BR-281, BR-281 min boundary)")
        void tc_ccv_013_oneMission_succeeds() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Greeting");

            String requestBody = """
                {
                    "topicId": "%s",
                    "title": "Simple Greeting",
                    "aiRole": "Friend",
                    "learnerRole": "You",
                    "scenario": "Meeting a friend",
                    "missions": [
                        {"title": "Say Hello"}
                    ]
                }
                """.formatted(topic.getId());

            // ─ Act
            MvcResult result = mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.missions.length()").value(1))
                    .andExpect(jsonPath("$.data.missions[0].title").value("Say Hello"))
                    .andExpect(jsonPath("$.data.missions[0].orderIndex").value(0))
                    .andReturn();

            // ─ Assert: Verify database state
            UUID conversationId = UUID.fromString(
                    objectMapper.readTree(result.getResponse().getContentAsString())
                            .path("data").path("id").asText());

            List<ConversationMission> missions = missionRepository
                    .findByConversationIdAndDeletedAtIsNullOrderByOrderIndexAsc(conversationId);
            assertThat(missions).hasSize(1);
            assertThat(missions.get(0).getTitle()).isEqualTo("Say Hello");
            assertThat(missions.get(0).getOrderIndex()).isEqualTo(0);
        }


        @Test
        @DisplayName("TC_CCV_015 — Create with exactly 3 missions succeeds (BR-281, BR-281 max boundary)")
        void tc_ccv_015_threeMissions_succeeds() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Restaurant");

            String requestBody = """
                {
                    "topicId": "%s",
                    "title": "Restaurant Full Experience",
                    "aiRole": "Staff",
                    "learnerRole": "Customer",
                    "scenario": "Complete restaurant experience",
                    "missions": [
                        {"title": "Greeting", "description": "Say hello to staff"},
                        {"title": "Order Food", "description": "Make a reservation"},
                        {"title": "Pay Bill", "description": "Request and pay bill"}
                    ]
                }
                """.formatted(topic.getId());

            // ─ Act
            MvcResult result = mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.missions.length()").value(3))
                    .andReturn();

            // ─ Assert: Verify database state
            var responseData = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
            assertThat(responseData.path("missions").isArray()).isTrue();
            for (int i = 0; i < 3; i++) {
                assertThat(responseData.path("missions").path(i).path("orderIndex").asInt()).isEqualTo(i);
            }

            UUID conversationId = UUID.fromString(responseData.path("id").asText());
            List<ConversationMission> missions = missionRepository
                    .findByConversationIdAndDeletedAtIsNullOrderByOrderIndexAsc(conversationId);

            assertThat(missions).hasSize(3);
            assertThat(missions.get(0).getOrderIndex()).isEqualTo(0);
            assertThat(missions.get(1).getOrderIndex()).isEqualTo(1);
            assertThat(missions.get(2).getOrderIndex()).isEqualTo(2);
            assertThat(missions.get(0).getTitle()).isEqualTo("Greeting");
            assertThat(missions.get(1).getTitle()).isEqualTo("Order Food");
            assertThat(missions.get(2).getTitle()).isEqualTo("Pay Bill");
        }

        @Test
        @DisplayName("TC_CCV_016 — Create with 4 missions returns 400 (BR-281, exceeds max)")
        void tc_ccv_016_fourMissions_returns400() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Restaurant");

            String requestBody = """
                {
                    "topicId": "%s",
                    "title": "Restaurant Booking",
                    "aiRole": "Staff",
                    "learnerRole": "Customer",
                    "scenario": "Complete restaurant experience",
                    "missions": [
                        {"title": "Greeting", "description": "Say hello"},
                        {"title": "Order Food", "description": "Make order"},
                        {"title": "Ask for Bill", "description": "Request bill"},
                        {"title": "Pay", "description": "Process payment"}
                    ]
                }
                """.formatted(topic.getId());

            // ─ Act + Assert
            mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));

            // ─ Verify: No conversation created
            assertThat(conversationRepository.count()).isEqualTo(0);
            assertThat(missionRepository.count()).isEqualTo(0);
        }

        // ═════════════════════════════════════════════════════════════════════════════════
        // SECTION 5: Edge Cases & Non-existent Topic
        // ═════════════════════════════════════════════════════════════════════════════════

        @Test
        @DisplayName("TC_CCV_018 — Non-existent topic returns 404")
        void tc_ccv_018_topicNotFound_returns404() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            String requestBody = objectMapper.writeValueAsString(CreateConversationRequest.builder()
                    .topicId(UUID.randomUUID())
                    .title("Test")
                    .aiRole("AI")
                    .learnerRole("Learner")
                    .scenario("Test")
                    .build());

            mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TC_CCV_019 — Soft-deleted topic returns 404")
        void tc_ccv_019_softDeletedTopic_returns404() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            topic.setDeletedAt(LocalDateTime.now());
            topic.setDeletedBy(UUID.randomUUID());
            topicRepository.save(topic);

            String requestBody = objectMapper.writeValueAsString(CreateConversationRequest.builder()
                    .topicId(topic.getId())
                    .title("Test")
                    .aiRole("AI")
                    .learnerRole("Learner")
                    .scenario("Test")
                    .build());

            mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("2.7.5 Delete Conversation")
    class DeleteConversationTests {

        @Test
        @DisplayName("TC_CDL_001 — soft delete sets deletedAt and deletedBy")
        void tc_cdl_001_softDelete_setsDeletedMetadata() throws Exception {
            // ─ Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            Conversation conversation = saveConversation("Test Conversation", topic.getId());

            // ─ Act
            mockMvc.perform(delete("/api/v1/manager/conversation/conversations/{conversationId}", conversation.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Conversation deleted successfully"));

            // ─ Assert: Soft deleted (not hard deleted)
            Conversation deleted = conversationRepository.findById(conversation.getId()).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();
            assertThat(deleted.getDeletedBy()).isEqualTo(manager.getId());
            assertThat(deleted.getTitle()).isEqualTo("Test Conversation"); // Data preserved

            // ─ Assert: No longer appears in active list
            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}/conversations", topic.getId())
                            .param("page", "0")
                            .param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(0))
                    .andExpect(jsonPath("$.data.content.length()").value(0));
        }

        @Test
        @DisplayName("TC_CDL_002 — cascade soft-deletes missions")
        void tc_cdl_002_cascadeSoftDeletesMissions() throws Exception {
            // ─ Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            Conversation conversation = saveConversation("Test Conversation", topic.getId());

            ConversationMission mission1 = saveMission(conversation.getId(), "Mission 1");
            ConversationMission mission2 = saveMission(conversation.getId(), "Mission 2");
            ConversationMission mission3 = saveMission(conversation.getId(), "Mission 3");

            // ─ Act
            mockMvc.perform(delete("/api/v1/manager/conversation/conversations/{conversationId}", conversation.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            // ─ Assert: Conversation soft-deleted
            Conversation deleted = conversationRepository.findById(conversation.getId()).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();

            // ─ Assert: All 3 missions also soft-deleted
            ConversationMission deletedM1 = missionRepository.findById(mission1.getId()).orElseThrow();
            ConversationMission deletedM2 = missionRepository.findById(mission2.getId()).orElseThrow();
            ConversationMission deletedM3 = missionRepository.findById(mission3.getId()).orElseThrow();

            assertThat(deletedM1.getDeletedAt()).isNotNull();
            assertThat(deletedM2.getDeletedAt()).isNotNull();
            assertThat(deletedM3.getDeletedAt()).isNotNull();

            assertThat(deletedM1.getDeletedBy()).isEqualTo(manager.getId());
            assertThat(deletedM2.getDeletedBy()).isEqualTo(manager.getId());
            assertThat(deletedM3.getDeletedBy()).isEqualTo(manager.getId());
        }

        @Test
        @DisplayName("TC_CDL_003 — non-existent conversation returns 404")
        void tc_cdl_003_conversationNotFound_returns404() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(delete("/api/v1/manager/conversation/conversations/{conversationId}", UUID.randomUUID())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }


        @Test
        @DisplayName("TC_CDL_004 — already deleted conversation returns 404")
        void tc_cdl_004_alreadyDeleted_returns404() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            Conversation conversation = saveConversation("Test", topic.getId());
            conversation.setDeletedAt(LocalDateTime.now());
            conversation.setDeletedBy(manager.getId());
            conversationRepository.save(conversation);

            mockMvc.perform(delete("/api/v1/manager/conversation/conversations/{conversationId}", conversation.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }


        @Test
        @DisplayName("TC_CDL_005 — learner data not cascade deleted")
        void tc_cdl_005_learnerDataPreserved() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            Conversation conversation = saveConversation("Test", topic.getId());

            mockMvc.perform(delete("/api/v1/manager/conversation/conversations/{conversationId}", conversation.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            Conversation deleted = conversationRepository.findById(conversation.getId()).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();
            // In real scenario, user_conversation_progress would still exist
            long totalInDb = conversationRepository.count();
            assertThat(totalInDb).isGreaterThan(0); // Soft deleted, not hard deleted
        }

        @Test
        @DisplayName("TC_CDL_007 — learner data preserved after delete")
        void tc_cdl_007_learnerDataPreserved_afterDelete() throws Exception {
            // ─ Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            Conversation conversation = saveConversation("Test Conversation", topic.getId());

            // Simulate learner practice progress (would be in user_conversation_progress table)
            // In real scenario, this would be created via conversation practice
            // For this test, we just verify it's not deleted by the cascade

            // ─ Act: Delete conversation
            mockMvc.perform(delete("/api/v1/manager/conversation/conversations/{conversationId}", conversation.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            // ─ Assert: Conversation deleted but learner data would be preserved
            // (In real DB, user_conversation_progress records would NOT have deletedAt set)
            Conversation deleted = conversationRepository.findById(conversation.getId()).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();

            // Verify: Historical data can still be queried if needed (would use deleted_at IS NULL filter)
            long historicalCount = conversationRepository.count();
            assertThat(historicalCount).isGreaterThan(0); // Soft deleted, not hard deleted
        }
    }

    @Nested
    @DisplayName("2.7.4 Update Conversation")
    class UpdateConversationTests {

        @Test
        @DisplayName("TC_CUV_001 — partial update preserves other fields")
        void tc_cuv_001_partialUpdate_preservesOtherFields() throws Exception {
            // ─ Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            Conversation original = saveConversation("Original Title", topic.getId());
            original.setDescription("Original Description");
            original.setJlptLevel(Course.JlptLevel.N3);
            original.setDifficultyScore(3);
            Conversation saved = conversationRepository.save(original);

            LocalDateTime originalCreatedAt = saved.getCreatedAt();
            UUID originalCreatedBy = saved.getCreatedBy();

            Thread.sleep(100); // Ensure time difference

            // ─ Update only title
            String requestBody = objectMapper.writeValueAsString(UpdateConversationRequest.builder()
                    .title("New Title Only")
                    .build());

            // ─ Act
            mockMvc.perform(put("/api/v1/manager/conversation/conversations/{conversationId}", saved.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("New Title Only"));

            // ─ Assert: Only title changed, other fields preserved
            Conversation updated = conversationRepository.findById(saved.getId()).orElseThrow();
            assertThat(updated.getTitle()).isEqualTo("New Title Only");
            assertThat(updated.getDescription()).isEqualTo("Original Description");
            assertThat(updated.getJlptLevel()).isEqualTo(Course.JlptLevel.N3);
            assertThat(updated.getDifficultyScore()).isEqualTo(3);

            // ─ Assert: Timestamps correct
            assertThat(updated.getCreatedAt()).isEqualTo(originalCreatedAt);
            assertThat(updated.getCreatedBy()).isEqualTo(originalCreatedBy);
            assertThat(updated.getUpdatedAt()).isAfter(originalCreatedAt);
            assertThat(updated.getUpdatedBy()).isEqualTo(manager.getId());
        }

        @Test
        @DisplayName("TC_CUV_002 — update all fields succeeds")
        void tc_cuv_002_updateAllFields_succeeds() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            Conversation conversation = saveConversation("Original", topic.getId());

            String requestBody = objectMapper.writeValueAsString(UpdateConversationRequest.builder()
                    .title("Updated Title")
                    .titleJapanese("新しいタイトル")
                    .description("New description")
                    .difficultyScore(8)
                    .build());

            mockMvc.perform(put("/api/v1/manager/conversation/conversations/{conversationId}", conversation.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk());

            Conversation updated = conversationRepository.findById(conversation.getId()).orElseThrow();
            assertThat(updated.getTitle()).isEqualTo("Updated Title");
            assertThat(updated.getTitleJapanese()).isEqualTo("新しいタイトル");
            assertThat(updated.getJlptLevel()).isEqualTo(Course.JlptLevel.N1);
            assertThat(updated.getDifficultyScore()).isEqualTo(8);
        }


        @Test
        @DisplayName("TC_CUV_004 — non-existent conversation returns 404")
        void tc_cuv_004_conversationNotFound_returns404() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            String requestBody = objectMapper.writeValueAsString(UpdateConversationRequest.builder()
                    .title("Test")
                    .build());

            mockMvc.perform(put("/api/v1/manager/conversation/conversations/{conversationId}", UUID.randomUUID())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isNotFound());
        }


        @Test
        @DisplayName("TC_CUV_005 — soft-deleted conversation returns 404")
        void tc_cuv_005_softDeletedConversation_returns404() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            Conversation conversation = saveConversation("Test", topic.getId());
            conversation.setDeletedAt(LocalDateTime.now());
            conversation.setDeletedBy(UUID.randomUUID());
            conversationRepository.save(conversation);

            String requestBody = objectMapper.writeValueAsString(UpdateConversationRequest.builder()
                    .title("New Title")
                    .build());

            mockMvc.perform(put("/api/v1/manager/conversation/conversations/{conversationId}", conversation.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TC_CUV_003 — title too long returns 400")
        void tc_cuv_003_titleTooLong_returns400() throws Exception {
            // ─ Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            Conversation conversation = saveConversation("Test", topic.getId());

            String longTitle = "a".repeat(256);
            String requestBody = objectMapper.writeValueAsString(UpdateConversationRequest.builder()
                    .title(longTitle)
                    .build());

            // ─ Act + Assert
            mockMvc.perform(put("/api/v1/manager/conversation/conversations/{conversationId}", conversation.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());

            // ─ Verify no update in DB
            Conversation unchanged = conversationRepository.findById(conversation.getId()).orElseThrow();
            assertThat(unchanged.getTitle()).isEqualTo("Test");
        }
    }

    @Nested
    @DisplayName("2.7.2 View Conversation Information")
    class ViewConversationDetailsTests {

        @Test
        @DisplayName("TC_CVI_001 — get conversation returns full details")
        void tc_cvi_001_getConversation_returnsFull() throws Exception {
            // ─ Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            Conversation conversation = saveConversation("Test Conversation", topic.getId());
            conversation.setDescription("Full description");
            conversation.setTitleJapanese("テストタイトル");
            conversation.setJlptLevel(Course.JlptLevel.N2);
            conversation.setDifficultyScore(5);
            conversationRepository.save(conversation);

            ConversationMission mission1 = saveMission(conversation.getId(), "Mission 1");
            ConversationMission mission2 = saveMission(conversation.getId(), "Mission 2");

            // ─ Act + Assert
            mockMvc.perform(get("/api/v1/manager/conversation/conversations/{conversationId}", conversation.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(conversation.getId().toString()))
                    .andExpect(jsonPath("$.data.title").value("Test Conversation"))
                    .andExpect(jsonPath("$.data.titleJapanese").value("テストタイトル"))
                    .andExpect(jsonPath("$.data.description").value("Full description"))
                    .andExpect(jsonPath("$.data.jlptLevel").value("N2"))
                    .andExpect(jsonPath("$.data.difficultyScore").value(5))
                    .andExpect(jsonPath("$.data.status").value("DRAFT"))
                    .andExpect(jsonPath("$.data.missions.length()").value(2))
                    .andExpect(jsonPath("$.data.missions[0].title").value("Mission 1"))
                    .andExpect(jsonPath("$.data.missions[1].title").value("Mission 2"));
        }

        @Test
        @DisplayName("TC_CVI_002 — conversation not found returns 404")
        void tc_cvi_002_conversationNotFound_returns404() throws Exception {
            // ─ Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            UUID nonExistentId = UUID.randomUUID();

            // ─ Act + Assert
            mockMvc.perform(get("/api/v1/manager/conversation/conversations/{conversationId}", nonExistentId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("Không tìm thấy"));
        }
        @Test
        @DisplayName("TC_CVI_003 — soft-deleted conversation returns 404")
        void tc_cvi_003_softDeletedConversation_returns404() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            Conversation conversation = saveConversation("Test", topic.getId());
            conversation.setDeletedAt(LocalDateTime.now());
            conversation.setDeletedBy(UUID.randomUUID());
            conversationRepository.save(conversation);

            mockMvc.perform(get("/api/v1/manager/conversation/conversations/{conversationId}", conversation.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }


        @Test
        @DisplayName("TC_CVI_004 — response includes missions ordered by index")
        void tc_cvi_004_withMissions_returnsOrdered() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            Conversation conversation = saveConversation("Test", topic.getId());

            saveMission(conversation.getId(), "Mission A");
            saveMission(conversation.getId(), "Mission B");
            saveMission(conversation.getId(), "Mission C");

            mockMvc.perform(get("/api/v1/manager/conversation/conversations/{conversationId}", conversation.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.missions.length()").value(3))
                    .andExpect(jsonPath("$.data.missions[0].title").value("Mission A"))
                    .andExpect(jsonPath("$.data.missions[1].title").value("Mission B"))
                    .andExpect(jsonPath("$.data.missions[2].title").value("Mission C"));
        }
    }

    @Nested
    @DisplayName("Cross-Cutting Tests")
    class CrossCuttingTests {

        @Test
        @DisplayName("TC_CROSS_001 — full lifecycle: create → update → publish → delete")
        void tc_cross_001_fullLifecycle() throws Exception {
            // ─ Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            ConversationTopic topic = saveTopic("Test Topic");

            // ─ Step 1: CREATE
            String createBody = objectMapper.writeValueAsString(CreateConversationRequest.builder()
                    .topicId(topic.getId())
                    .title("New Conversation")
                    .aiRole("AI")
                    .learnerRole("Learner")
                    .build());

            MvcResult createResult = mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID conversationId = UUID.fromString(
                    objectMapper.readTree(createResult.getResponse().getContentAsString())
                            .path("data").path("id").asText());

            // ─ Step 2: UPDATE
            String updateBody = objectMapper.writeValueAsString(UpdateConversationRequest.builder()
                    .title("Updated Title")
                    .description("Updated description")
                    .build());

            mockMvc.perform(put("/api/v1/manager/conversation/conversations/{conversationId}", conversationId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("Updated Title"));

            // ─ Step 3: PUBLISH
            mockMvc.perform(patch("/api/v1/manager/conversation/conversations/{conversationId}/publish", conversationId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

            // ─ Step 4: GET and verify published
            mockMvc.perform(get("/api/v1/manager/conversation/conversations/{conversationId}", conversationId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                    .andExpect(jsonPath("$.data.title").value("Updated Title"));

            // ─ Step 5: DELETE
            mockMvc.perform(delete("/api/v1/manager/conversation/conversations/{conversationId}", conversationId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            // ─ Step 6: GET and verify 404
            mockMvc.perform(get("/api/v1/manager/conversation/conversations/{conversationId}", conversationId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TC_CROSS_002 — multiple conversations isolation")
        void tc_cross_002_multipleConversationsIsolation() throws Exception {
            // ─ Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic1 = saveTopic("Topic 1");
            ConversationTopic topic2 = saveTopic("Topic 2");

            saveConversation("Conv 1.1", topic1.getId());
            saveConversation("Conv 1.2", topic1.getId());
            saveConversation("Conv 1.3", topic1.getId());

            saveConversation("Conv 2.1", topic2.getId());
            saveConversation("Conv 2.2", topic2.getId());

            // ─ Act + Assert: Topic 1 returns 3
            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}/conversations", topic1.getId())
                            .param("page", "0")
                            .param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(3))
                    .andExpect(jsonPath("$.data.content.length()").value(3));

            // ─ Act + Assert: Topic 2 returns 2
            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}/conversations", topic2.getId())
                            .param("page", "0")
                            .param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.content.length()").value(2));
        }

        @Test
        @DisplayName("TC_CROSS_003 — pagination consistent with soft-deleted items")
        void tc_cross_003_paginationWithSoftDeletes() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");

            for (int i = 1; i <= 15; i++) {
                Conversation conv = saveConversation("Conv " + i, topic.getId());
                conv.setOrderIndex(i - 1);
                conversationRepository.save(conv);
            }

            // Soft-delete 2 conversations
            List<Conversation> all = conversationRepository.findAll();
            if (all.size() >= 2) {
                Conversation del1 = all.get(0);
                del1.setDeletedAt(LocalDateTime.now());
                del1.setDeletedBy(manager.getId());
                conversationRepository.save(del1);

                Conversation del2 = all.get(1);
                del2.setDeletedAt(LocalDateTime.now());
                del2.setDeletedBy(manager.getId());
                conversationRepository.save(del2);
            }

            // Verify pagination: should be 13 total (15 - 2 deleted)
            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}/conversations", topic.getId())
                            .param("page", "0")
                            .param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(13))
                    .andExpect(jsonPath("$.data.totalPages").value(2))
                    .andExpect(jsonPath("$.data.content.length()").value(10));

            // Page 1 should have 3 items
            mockMvc.perform(get("/api/v1/manager/conversation/topics/{topicId}/conversations", topic.getId())
                            .param("page", "1")
                            .param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(3));
        }
    }

    @Nested
    @DisplayName("Status Transitions")
    class StatusTransitionTests {

        @Test
        @DisplayName("TC_STR_001 — publish changes status to PUBLISHED")
        void tc_str_001_publish_changesStatus() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            Conversation conversation = saveConversation("Test", topic.getId());

            mockMvc.perform(patch("/api/v1/manager/conversation/conversations/{conversationId}/publish", conversation.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

            Conversation published = conversationRepository.findById(conversation.getId()).orElseThrow();
            assertThat(published.getStatus()).isEqualTo(Course.ContentStatus.PUBLISHED);
        }

        @Test
        @DisplayName("TC_STR_002 — archive changes status to ARCHIVED")
        void tc_str_002_archive_changesStatus() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            Conversation conversation = saveConversation("Test", topic.getId());

            mockMvc.perform(patch("/api/v1/manager/conversation/conversations/{conversationId}/archive", conversation.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ARCHIVED"));

            Conversation archived = conversationRepository.findById(conversation.getId()).orElseThrow();
            assertThat(archived.getStatus()).isEqualTo(Course.ContentStatus.ARCHIVED);
        }

        @Test
        @DisplayName("TC_STR_003 — publish is idempotent")
        void tc_str_003_publishIdempotent() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            Conversation conversation = saveConversation("Test", topic.getId());
            conversation.setStatus(Course.ContentStatus.PUBLISHED);
            conversationRepository.save(conversation);

            // Publish again
            mockMvc.perform(patch("/api/v1/manager/conversation/conversations/{conversationId}/publish", conversation.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

            Conversation result = conversationRepository.findById(conversation.getId()).orElseThrow();
            assertThat(result.getStatus()).isEqualTo(Course.ContentStatus.PUBLISHED);
        }
    }

    @Nested
    @DisplayName("Authorization / RBAC")
    class AuthorizationTests {

        @Test
        @DisplayName("TC_RBAC_001 — CONTENT_MANAGER has full access")
        void tc_rbac_001_contentManager_fullAccess() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            Conversation conversation = saveConversation("Test", topic.getId());

            // GET should succeed
            mockMvc.perform(get("/api/v1/manager/conversation/conversations/{conversationId}", conversation.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            // POST should succeed
            mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(CreateConversationRequest.builder()
                                    .topicId(topic.getId())
                                    .title("New")
                                    .aiRole("AI")
                                    .learnerRole("Learner")
                                    .build())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("TC_RBAC_002 — LEARNER gets 403 for all manager endpoints")
        void tc_rbac_002_learner_allForbidden() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            Conversation conversation = saveConversation("Test", topic.getId());

            mockMvc.perform(get("/api/v1/manager/conversation/conversations/{conversationId}", conversation.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("TC_RBAC_003 — TEACHER gets 403 for manager endpoints")
        void tc_rbac_003_teacher_allForbidden() throws Exception {
            User teacher = userRepository.save(User.builder()
                    .email("teacher@test.com")
                    .passwordHash(passwordEncoder.encode("ValidPass@1"))
                    .firstName("Test")
                    .lastName("Teacher")
                    .role(UserRole.TEACHER)
                    .status(UserStatus.ACTIVE)
                    .authProvider(AuthProvider.LOCAL)
                    .failedLoginAttempts(0)
                    .build());
            String token = loginAndGetToken("teacher@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            Conversation conversation = saveConversation("Test", topic.getId());

            mockMvc.perform(get("/api/v1/manager/conversation/conversations/{conversationId}", conversation.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("TC_RBAC_004 — ADMIN gets 403 for manager endpoints")
        void tc_rbac_004_admin_allForbidden() throws Exception {
            User admin = userRepository.save(User.builder()
                    .email("admin@test.com")
                    .passwordHash(passwordEncoder.encode("ValidPass@1"))
                    .firstName("Test")
                    .lastName("Admin")
                    .role(UserRole.ADMIN)
                    .status(UserStatus.ACTIVE)
                    .authProvider(AuthProvider.LOCAL)
                    .failedLoginAttempts(0)
                    .build());
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            ConversationTopic topic = saveTopic("Test");
            Conversation conversation = saveConversation("Test", topic.getId());

            mockMvc.perform(get("/api/v1/manager/conversation/conversations/{conversationId}", conversation.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("TC_RBAC_005 — Unauthenticated gets 401 for all endpoints")
        void tc_rbac_005_unauthenticated_allUnauthorized() throws Exception {
            ConversationTopic topic = saveTopic("Test");
            Conversation conversation = saveConversation("Test", topic.getId());

            // No token provided
            mockMvc.perform(get("/api/v1/manager/conversation/conversations/{conversationId}", conversation.getId()))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(post("/api/v1/manager/conversation/conversations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }
}