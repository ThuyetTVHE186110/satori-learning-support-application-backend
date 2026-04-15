package com.fu.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.clazz.entity.ClassMember;
import com.fu.clazz.entity.ClassStatus;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.repository.ClassMemberRepository;
import com.fu.clazz.repository.ClassRepository;
import com.fu.conversation.dto.request.SendChatMessageRequest;
import com.fu.conversation.dto.request.StartChatSessionRequest;
import com.fu.conversation.entity.ChatMessage;
import com.fu.conversation.entity.ChatSession;
import com.fu.conversation.repository.ChatMessageRepository;
import com.fu.conversation.repository.ChatSessionRepository;
import com.fu.conversation.service.ChatRateLimiter;
import com.fu.course.entity.Course;
import com.fu.course.entity.Lesson;
import com.fu.course.repository.CourseRepository;
import com.fu.course.repository.LessonRepository;
import com.fu.curriculumimport.service.embedding.EmbeddingService;
import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.user.entity.User;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import com.fu.user.repository.UserRepository;
import com.fu.user.repository.UserSessionRepository;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = com.fu.StartApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Curriculum Chat Integration Tests")
class CurriculumChatIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ChatSessionRepository chatSessionRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ClassRepository classRepository;
    @Autowired private ClassMemberRepository classMemberRepository;
    @Autowired private UserSessionRepository sessionRepository;

    @MockBean private EmbeddingService embeddingService;
    @MockBean private ChatRateLimiter chatRateLimiter;
    @MockBean private OpenAiChatModel chatModel;

    private User learner;
    private Course course;
    private Lesson lesson;
    private String learnerToken;

    @BeforeEach
    void setUp() throws Exception {
        clearAllData();

        // Create fresh test data
        learner = saveLearner("learner@test.com");
        course = courseRepository.save(Course.builder()
                .name("Test Course")
                .description("Test Description")
                .status(Course.CourseStatus.ACTIVE)
                .jlptLevel(Course.JlptLevel.N1)
                .build());
        Clazz clazz = classRepository.save(Clazz.builder()
                .name("Test Class 101").description("test class")
                .courseId(course.getId())
                .maxStudents(30)
                .startDate(LocalDate.now().plusDays(1))
                .endDate(LocalDate.now().plusMonths(3))
                .status(ClassStatus.ACTIVE)
                .build());
        classMemberRepository.save(ClassMember.builder()
                .classId(clazz.getId()).userId(learner.getId()).build());
        lesson = lessonRepository.save(Lesson.builder()
                .course(course)
                .title("Test Lesson")
                .status(Lesson.ContentStatus.PUBLISHED)
                .orderIndex(1)
                .build());
        learnerToken = loginAndGetToken("learner@test.com", "ValidPass@1");
        
        // Setup mock behaviors
        setupEmbeddingServiceMock();
        setupChatRateLimiterMock();
        setupChatModelMock("This is a deterministic AI mock response.");
    }

    @AfterEach
    void tearDown() {
        clearAllData();
    }

    private void clearAllData() {
        chatMessageRepository.deleteAllInBatch();
        chatSessionRepository.deleteAllInBatch();
        lessonRepository.deleteAllInBatch();
        classMemberRepository.deleteAllInBatch();
        classRepository.deleteAllInBatch();
        courseRepository.deleteAllInBatch();
        sessionRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private void setupEmbeddingServiceMock() {
        Mockito.reset(embeddingService);
        when(embeddingService.retrieveContext(any(String.class), any()))
                .thenReturn(RagContext.empty());
        when(embeddingService.retrieveContext(any(String.class)))
                .thenReturn(RagContext.empty());
    }

    private void setupChatRateLimiterMock() {
        Mockito.reset(chatRateLimiter);
        doNothing().when(chatRateLimiter).checkRateLimit(any(UUID.class));
    }

    private void setupChatModelMock(String responseMessage) {
        Mockito.reset(chatModel);
        Generation generation = new Generation(new AssistantMessage(responseMessage));
        ChatResponse chatResponse = new ChatResponse(List.of(generation));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
    }

    private User saveLearner(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("ValidPass@1"))
                .firstName("Test").lastName("Learner")
                .role(UserRole.LEARNER)
                .status(UserStatus.ACTIVE)
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

    private ChatSession createChatSession(User user, int messageCount, int userMessageCount, String context) {
        return chatSessionRepository.save(ChatSession.builder()
                .userId(user.getId())
                .sessionType("CURRICULUM_QA")
                .status("ACTIVE")
                .context("{\"courseId\":\"" + course.getId() + "\"}")
                .messageCount(messageCount)
                .userMessageCount(userMessageCount)
                .hintsUsed(0)
                .correctionsCount(0)
                .build());
    }

    private ChatSession createDefaultChatSession() {
        return createChatSession(learner, 0, 0, null);
    }

    @Nested
    @DisplayName("TC-1: Create Chat Session")
    class TestCreateChatSession {
        @Test
        @DisplayName("Should create session successfully with JWT")
        void tc_cs_001_createSessionSuccessfully() throws Exception {
            StartChatSessionRequest request = new StartChatSessionRequest();
            request.setCourseId(course.getId());
            request.setLessonId(lesson.getId());
            request.setJlptLevel("N1");
            request.setTitle("Test Chat Session");

            String response = mockMvc.perform(post("/api/v1/learner/curriculum-chat/sessions")
                    .header("Authorization", "Bearer " + learnerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.sessionId").isNotEmpty())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            UUID sessionId = UUID.fromString(objectMapper.readTree(response)
                    .path("data").path("sessionId").asText());
            Optional<ChatSession> session = chatSessionRepository.findById(sessionId);
            assertThat(session).isPresent();
            assertThat(session.get().getUserId()).isEqualTo(learner.getId());
            assertThat(session.get().getStatus()).isEqualTo("ACTIVE");
            assertThat(session.get().getSessionType()).isEqualTo("CURRICULUM_QA");
        }
    }

    @Nested
    @DisplayName("TC-2: Send Message to Chat")
    class TestSendMessage {
        @Test
        @DisplayName("Should send message and receive AI response")
        void tc_msg_001_sendMessageSuccessfully() throws Exception {
            ChatSession session = createChatSession(learner, 0, 0, "{\"courseId\":\"" + course.getId() + "\"}");

            SendChatMessageRequest request = new SendChatMessageRequest();
            request.setMessage("この文法は何ですか？");

            mockMvc.perform(post("/api/v1/learner/curriculum-chat/sessions/{sessionId}/messages", session.getId())
                    .header("Authorization", "Bearer " + learnerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").value("This is a deterministic AI mock response."));

            // Verify two-point assertion: Database persistence updated explicitly
            ChatSession updatedSession = chatSessionRepository.findById(session.getId()).orElseThrow();
            assertThat(updatedSession.getMessageCount()).isEqualTo(2); // 1 user + 1 assistant
            assertThat(updatedSession.getUserMessageCount()).isEqualTo(1);
            
            List<ChatMessage> persistedMessages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId(), PageRequest.of(0, 10)).getContent();
            assertThat(persistedMessages).hasSize(2);
            assertThat(persistedMessages.get(0).getRole()).isEqualTo("USER");
            assertThat(persistedMessages.get(0).getContent()).isEqualTo("この文法は何ですか？");
            assertThat(persistedMessages.get(1).getRole()).isEqualTo("ASSISTANT");
            assertThat(persistedMessages.get(1).getContent()).isEqualTo("This is a deterministic AI mock response.");
        }
    }

    @Nested
    @DisplayName("TC-3: Get Chat History")
    class TestGetChatHistory {
        @Test
        @DisplayName("Should retrieve chat history with pagination")
        void tc_hist_001_getChatHistorySuccessfully() throws Exception {
            ChatSession session = createDefaultChatSession();

            chatMessageRepository.save(ChatMessage.builder()
                    .sessionId(session.getId())
                    .role("USER")
                    .content("これは何ですか？")
                    .sequenceNumber(1)
                    .build());
            chatMessageRepository.save(ChatMessage.builder()
                    .sessionId(session.getId())
                    .role("ASSISTANT")
                    .content("これは日本語です。")
                    .sequenceNumber(2)
                    .build());

            mockMvc.perform(get("/api/v1/learner/curriculum-chat/sessions/{sessionId}/messages", session.getId())
                    .header("Authorization", "Bearer " + learnerToken)
                    .param("page", "0")
                    .param("size", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].content").value("これは何ですか？"))
                    .andExpect(jsonPath("$.data[1].content").value("これは日本語です。"));
        }
    }

    @Nested
    @DisplayName("TC-4: List Chat Sessions")
    class TestListSessions {
        @Test
        @DisplayName("Should list all sessions for learner with pagination")
        void tc_list_001_listSessionsSuccessfully() throws Exception {
            createChatSession(learner, 1, 0, null);
            createChatSession(learner, 1, 0, null);

            mockMvc.perform(get("/api/v1/learner/curriculum-chat/sessions")
                    .header("Authorization", "Bearer " + learnerToken)
                    .param("page", "0")
                    .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content.length()").value(2));
        }
    }

    @Nested
    @DisplayName("TC-6: RBAC - Unauthorized Access")
    class TestRBAC {
        @Test
        @DisplayName("Should reject request without JWT token")
        void tc_rbac_001_requiresAuthentication() throws Exception {
            mockMvc.perform(get("/api/v1/learner/curriculum-chat/sessions"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Should reject request with non-LEARNER role")
        void tc_rbac_002_teacherCannotAccess() throws Exception {
            userRepository.save(User.builder()
                    .email("teacher@test.com")
                    .passwordHash(passwordEncoder.encode("ValidPass@1"))
                    .firstName("Test").lastName("Teacher")
                    .role(UserRole.TEACHER)
                    .status(UserStatus.ACTIVE)
                    .failedLoginAttempts(0)
                    .build());
            String teacherToken = loginAndGetToken("teacher@test.com", "ValidPass@1");
            mockMvc.perform(get("/api/v1/learner/curriculum-chat/sessions")
                    .header("Authorization", "Bearer " + teacherToken))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("TC-7: Session Not Found")
    class TestErrorHandling {
        @Test
        @DisplayName("Should return 404 when session not found")
        void tc_err_001_sessionNotFound() throws Exception {
            UUID invalidSessionId = UUID.randomUUID();
            mockMvc.perform(get("/api/v1/learner/curriculum-chat/sessions/{sessionId}/messages", invalidSessionId)
                    .header("Authorization", "Bearer " + learnerToken))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should handle AI service timeout and return MSG-158")
        void tc_err_002_aiServiceTimeoutReturnsMsg158() throws Exception {
            ChatSession session = createDefaultChatSession();

            // Mock an exception thrown from the AI model
            when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("OpenAI API Timeout Error"));

            SendChatMessageRequest request = new SendChatMessageRequest();
            request.setMessage("Test message");

            mockMvc.perform(post("/api/v1/learner/curriculum-chat/sessions/{sessionId}/messages", session.getId())
                    .header("Authorization", "Bearer " + learnerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("CHAT_ERROR"))
                    .andExpect(jsonPath("$.message").value("Hệ thống AI đang gặp sự cố, vui lòng thử lại sau."));

            // Verify NO message saved
            List<ChatMessage> messages = chatMessageRepository.findAll();
            assertThat(messages).isEmpty();

            // Verify session state unchanged
            ChatSession updatedSession = chatSessionRepository.findById(session.getId()).orElseThrow();
            assertThat(updatedSession.getMessageCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should handle KB unavailable and return MSG-159")
        void tc_err_003_kbUnavailableReturnsMsg159() throws Exception {
            ChatSession session = createDefaultChatSession();

            // Stub EmbeddingService to throw exception
            when(embeddingService.retrieveContext(any(String.class), any()))
                    .thenThrow(new RuntimeException("Pinecone Error"));

            SendChatMessageRequest request = new SendChatMessageRequest();
            request.setMessage("Test message");

            mockMvc.perform(post("/api/v1/learner/curriculum-chat/sessions/{sessionId}/messages", session.getId())
                    .header("Authorization", "Bearer " + learnerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("CHAT_ERROR"))
                    .andExpect(jsonPath("$.message").value("Hệ thống AI đang gặp sự cố, vui lòng thử lại sau."));

            List<ChatMessage> messages = chatMessageRepository.findAll();
            assertThat(messages).isEmpty();

            ChatSession updatedSession = chatSessionRepository.findById(session.getId()).orElseThrow();
            assertThat(updatedSession.getMessageCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("A1: No Relevant Knowledge - Fallback Response")
    class TestAlternativeA1 {
        @Test
        @DisplayName("Should return fallback response when no relevant knowledge found")
        void tc_alt_001_noRelevantKnowledgeReturnsFallback() throws Exception {
            ChatSession session = createDefaultChatSession();

            SendChatMessageRequest request = new SendChatMessageRequest();
            request.setMessage("Hôm nay trời thế nào?");

            setupChatModelMock("I am afraid I do not know this from the knowledge base.");

            mockMvc.perform(post("/api/v1/learner/curriculum-chat/sessions/{sessionId}/messages", session.getId())
                    .header("Authorization", "Bearer " + learnerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").value("I am afraid I do not know this from the knowledge base."));

            List<ChatMessage> messages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId(), PageRequest.of(0, 50)).getContent();
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).getRole()).isEqualTo("USER");
            assertThat(messages.get(1).getRole()).isEqualTo("ASSISTANT");
            assertThat(messages.get(1).getContent()).isEqualTo("I am afraid I do not know this from the knowledge base.");
        }
    }

    @Nested
    @DisplayName("A2: Out-of-Scope Question")
    class TestAlternativeA2 {
        @Test
        @DisplayName("Should return guidance response for out-of-scope question")
        void tc_alt_002_outOfScopeQuestionReturnsGuidance() throws Exception {
            ChatSession session = createDefaultChatSession();

            SendChatMessageRequest request = new SendChatMessageRequest();
            request.setMessage("Hôm nay trời đẹp quá");
            
            setupChatModelMock("Guidance response for out of scope.");

            mockMvc.perform(post("/api/v1/learner/curriculum-chat/sessions/{sessionId}/messages", session.getId())
                    .header("Authorization", "Bearer " + learnerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").value("Guidance response for out of scope."));

            List<ChatMessage> messages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId(), PageRequest.of(0, 50)).getContent();
            assertThat(messages).hasSize(2);
            assertThat(messages.getFirst().getContent()).isEqualTo("Hôm nay trời đẹp quá");
            assertThat(messages.get(1).getContent()).isEqualTo("Guidance response for out of scope.");
        }
    }

    @Nested
    @DisplayName("BR-252 Extended: Chat History Structure")
    class TestHistoryStructure {
        @Test
        @DisplayName("Should store chat history with correct structure in database")
        void tc_hist_002_chatHistoryStoredWithCorrectStructure() throws Exception {
            ChatSession session = createDefaultChatSession();

            SendChatMessageRequest request = new SendChatMessageRequest();
            request.setMessage("これは何ですか？");

            mockMvc.perform(post("/api/v1/learner/curriculum-chat/sessions/{sessionId}/messages", session.getId())
                    .header("Authorization", "Bearer " + learnerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            List<ChatMessage> messages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId(), PageRequest.of(0, 50)).getContent();
            assertThat(messages).hasSize(2);

            ChatMessage userMsg = messages.getFirst();
            assertThat(userMsg.getSessionId()).isEqualTo(session.getId());
            assertThat(userMsg.getRole()).isEqualTo("USER");
            assertThat(userMsg.getContent()).isEqualTo("これは何ですか？");
            assertThat(userMsg.getSequenceNumber()).isEqualTo(1);
            assertThat(userMsg.getCreatedAt()).isNotNull();

            ChatMessage assistantMsg = messages.get(1);
            assertThat(assistantMsg.getSessionId()).isEqualTo(session.getId());
            assertThat(assistantMsg.getRole()).isEqualTo("ASSISTANT");
            assertThat(assistantMsg.getContent()).isNotBlank();
            assertThat(assistantMsg.getSequenceNumber()).isEqualTo(2);
            assertThat(assistantMsg.getCreatedAt()).isNotNull();
            assertThat(assistantMsg.getCreatedAt()).isAfterOrEqualTo(userMsg.getCreatedAt());
        }
    }

    @Nested
    @DisplayName("BR-253: Input Validation")
    class TestInputValidation {
        @Test
        @DisplayName("Should reject empty message")
        void tc_val_001_rejectEmptyMessage() throws Exception {
            ChatSession session = createDefaultChatSession();

            SendChatMessageRequest request = new SendChatMessageRequest();
            request.setMessage("");

            mockMvc.perform(post("/api/v1/learner/curriculum-chat/sessions/{sessionId}/messages", session.getId())
                    .header("Authorization", "Bearer " + learnerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));

            List<ChatMessage> messages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId(), PageRequest.of(0, 50)).getContent();
            assertThat(messages).isEmpty();

            ChatSession updatedSession = chatSessionRepository.findById(session.getId()).orElseThrow();
            assertThat(updatedSession.getMessageCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("BR-256: Sessions Ordered DESC")
    class TestSessionOrdering {
        @Test
        @DisplayName("Should list sessions in descending order by updated_at")
        void tc_list_002_sessionsOrderedDescByUpdatedAt() throws Exception {
            ChatSession session1 = createChatSession(learner, 1, 1, null);
            Thread.sleep(50);

            ChatSession session2 = createChatSession(learner, 1, 1, null);
            Thread.sleep(50);

            ChatSession session3 = createChatSession(learner, 1, 1, null);

            String responseBody = mockMvc.perform(get("/api/v1/learner/curriculum-chat/sessions")
                    .header("Authorization", "Bearer " + learnerToken)
                    .param("page", "0")
                    .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content.length()").value(3))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            List<UUID> sessionIds = objectMapper.readTree(responseBody)
                    .path("data").path("content")
                    .findValues("sessionId")
                    .stream()
                    .map(node -> UUID.fromString(node.asText()))
                    .collect(Collectors.toList());

            assertThat(sessionIds).containsExactly(
                    session3.getId(),
                    session2.getId(),
                    session1.getId()
            );
        }
    }

    @Nested
    @DisplayName("BR-257: Continue Conversation")
    class TestContinueConversation {
        @Test
        @DisplayName("Should allow adding message to existing session")
        void tc_cont_001_continueExistingConversation() throws Exception {
            ChatSession session = createChatSession(learner, 2, 1, null);

            chatMessageRepository.save(ChatMessage.builder()
                    .sessionId(session.getId())
                    .role("USER")
                    .content("Previous question")
                    .sequenceNumber(1)
                    .build());
            chatMessageRepository.save(ChatMessage.builder()
                    .sessionId(session.getId())
                    .role("ASSISTANT")
                    .content("Previous answer")
                    .sequenceNumber(2)
                    .build());

            SendChatMessageRequest newMessage = new SendChatMessageRequest();
            newMessage.setMessage("Follow-up question");

            setupChatModelMock("Follow up AI response");

            mockMvc.perform(post("/api/v1/learner/curriculum-chat/sessions/{sessionId}/messages", session.getId())
                    .header("Authorization", "Bearer " + learnerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(newMessage)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            List<ChatMessage> allMessages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId(), PageRequest.of(0, 100)).getContent();
            assertThat(allMessages).hasSize(4);
            assertThat(allMessages.get(2).getContent()).isEqualTo("Follow-up question");
            assertThat(allMessages.get(3).getRole()).isEqualTo("ASSISTANT");

            ChatSession updatedSession = chatSessionRepository.findById(session.getId()).orElseThrow();
            assertThat(updatedSession.getMessageCount()).isEqualTo(4);
            assertThat(updatedSession.getUserMessageCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("TC-10: User Access Control")
    class TestAccessControl {

        @Test
        @DisplayName("Should prevent learner from accessing other learner's session")
        void tc_access_001_cannotAccessOtherUserSession() throws Exception {
            User otherLearner = saveLearner("other@test.com");
            ChatSession session = createChatSession(otherLearner, 0, 0, null);

            mockMvc.perform(get("/api/v1/learner/curriculum-chat/sessions/{sessionId}/messages", session.getId())
                    .header("Authorization", "Bearer " + learnerToken))
                    .andExpect(status().isNotFound()); // Expect 404 per Satori's generic ResourceNotOwned exception handling
        }
    }
}
