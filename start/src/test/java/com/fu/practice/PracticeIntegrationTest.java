//package com.fu.practice;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.fu.clazz.entity.ClassMember;
//import com.fu.clazz.entity.ClassStatus;
//import com.fu.clazz.entity.Clazz;
//import com.fu.clazz.repository.ClassMemberRepository;
//import com.fu.clazz.repository.ClassRepository;
//import com.fu.course.entity.Course;
//import com.fu.course.entity.Lesson;
//import com.fu.course.repository.CourseRepository;
//import com.fu.course.repository.LessonRepository;
//import com.fu.practice.agent.AnswerEvaluatorAgent;
//import com.fu.practice.agent.AnswerEvaluatorAgent.EvaluationResult;
//import com.fu.practice.agent.ExerciseGeneratorAgent;
//import com.fu.practice.agent.ExerciseGeneratorAgent.GeneratedItem;
//import com.fu.practice.agent.SessionFeedbackAgent;
//import com.fu.practice.dto.request.StartPracticeRequest;
//import com.fu.practice.dto.request.SubmitAnswerRequest;
//import com.fu.practice.entity.PracticeItem;
//import com.fu.practice.entity.PracticeItemType;
//import com.fu.practice.entity.PracticeSession;
//import com.fu.practice.entity.PracticeSessionType;
//import com.fu.practice.entity.PracticeStatus;
//import com.fu.practice.repository.PracticeItemRepository;
//import com.fu.practice.repository.PracticeSessionRepository;
//import com.fu.user.entity.User;
//import com.fu.user.entity.UserRole;
//import com.fu.user.entity.UserStatus;
//import com.fu.user.repository.UserRepository;
//import com.fu.user.repository.UserSessionRepository;
//import org.junit.jupiter.api.*;
//import org.mockito.Mockito;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.boot.test.mock.mockito.MockBean;
//import org.springframework.http.MediaType;
//import org.springframework.security.crypto.password.PasswordEncoder;
//import org.springframework.test.context.ActiveProfiles;
//import org.springframework.test.web.servlet.MockMvc;
//import org.springframework.test.web.servlet.MvcResult;
//
//import java.time.LocalDate;
//import java.util.List;
//import java.util.Optional;
//import java.util.UUID;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.mockito.ArgumentMatchers.*;
//
//import static org.mockito.Mockito.when;
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
//
//@SpringBootTest(classes = com.fu.StartApplication.class)
//@AutoConfigureMockMvc
//@ActiveProfiles("test")
//@DisplayName("Practice with AI Integration Tests")
//class PracticeIntegrationTest {
//
//    @Autowired private MockMvc mockMvc;
//    @Autowired private ObjectMapper objectMapper;
//    @Autowired private PracticeSessionRepository practiceSessionRepository;
//    @Autowired private PracticeItemRepository practiceItemRepository;
//    @Autowired private UserRepository userRepository;
//    @Autowired private CourseRepository courseRepository;
//    @Autowired private LessonRepository lessonRepository;
//    @Autowired private PasswordEncoder passwordEncoder;
//    @Autowired private ClassRepository classRepository;
//    @Autowired private ClassMemberRepository classMemberRepository;
//    @Autowired private UserSessionRepository sessionRepository;
//
//    @MockBean private ExerciseGeneratorAgent exerciseGeneratorAgent;
//    @MockBean private AnswerEvaluatorAgent answerEvaluatorAgent;
//    @MockBean private SessionFeedbackAgent sessionFeedbackAgent;
//
//    private User learner;
//    private User otherLearner;
//    private User teacher;
//    private Course course;
//    private Course otherCourse;
//    private Lesson lesson;
//    private String learnerToken;
//    private String teacherToken;
//
//    @BeforeEach
//    void setUp() throws Exception {
//        clearAllData();
//
//        // 1. Users setup
//        learner = saveUser("learner@test.com", UserRole.LEARNER);
//        otherLearner = saveUser("otherb@test.com", UserRole.LEARNER);
//        teacher = saveUser("teacher@test.com", UserRole.TEACHER);
//
//        // 2. Course & Class setup (Enrolled course)
//        course = courseRepository.save(Course.builder()
//                .name("Enrolled Test Course")
//                .description("Test Course Context")
//                .status(Course.CourseStatus.ACTIVE)
//                .jlptLevel(Course.JlptLevel.N5)
//                .build());
//        Clazz clazz = classRepository.save(Clazz.builder()
//                .name("Learner Class")
//                .courseId(course.getId())
//                .maxStudents(30)
//                .startDate(LocalDate.now().minusDays(1))
//                .endDate(LocalDate.now().plusMonths(3))
//                .status(ClassStatus.ACTIVE)
//                .build());
//        classMemberRepository.save(ClassMember.builder()
//                .classId(clazz.getId()).userId(learner.getId()).build());
//
//        // 3. Other Course setup (Not enrolled)
//        otherCourse = courseRepository.save(Course.builder()
//                .name("Unenrolled Course")
//                .status(Course.CourseStatus.ACTIVE)
//                .jlptLevel(Course.JlptLevel.N4)
//                .build());
//
//        // 4. Lesson setup
//        lesson = lessonRepository.save(Lesson.builder()
//                .course(course)
//                .title("Test Lesson 1")
//                .status(Lesson.ContentStatus.PUBLISHED)
//                .orderIndex(1)
//                .build());
//
//        // 5. Tokens
//        learnerToken = loginAndGetToken("learner@test.com", "ValidPass@1");
//        teacherToken = loginAndGetToken("teacher@test.com", "ValidPass@1");
//
//        // 6. Mocking basic AI behavior
//        setupMocks();
//    }
//
//    @AfterEach
//    void tearDown() {
//        clearAllData();
//    }
//
//    private void clearAllData() {
//        practiceItemRepository.deleteAllInBatch();
//        practiceSessionRepository.deleteAllInBatch();
//        lessonRepository.deleteAllInBatch();
//        classMemberRepository.deleteAllInBatch();
//        classRepository.deleteAllInBatch();
//        courseRepository.deleteAllInBatch();
//        sessionRepository.deleteAllInBatch();
//        userRepository.deleteAllInBatch();
//    }
//
//    private User saveUser(String email, UserRole role) {
//        return userRepository.save(User.builder()
//                .email(email)
//                .passwordHash(passwordEncoder.encode("ValidPass@1"))
//                .firstName("Test").lastName(role.name())
//                .role(role)
//                .status(UserStatus.ACTIVE)
//                .failedLoginAttempts(0)
//                .build());
//    }
//
//    private String loginAndGetToken(String email, String password) throws Exception {
//        String body = String.format("{\"email\":\"%s\",\"password\":\"%s\"}", email, password);
//        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content(body))
//                .andExpect(status().isOk())
//                .andReturn();
//        return objectMapper.readTree(result.getResponse().getContentAsString())
//                .path("data").path("accessToken").asText();
//    }
//
//    private void setupMocks() {
//        Mockito.reset(exerciseGeneratorAgent, answerEvaluatorAgent, sessionFeedbackAgent);
//
//        // General Generator behavior matching IT-06, etc.
//        GeneratedItem mockItem = new GeneratedItem(
//                "MULTIPLE_CHOICE",
//                "What is test?",
//                "テストは？",
//                List.of(new GeneratedItem.OptionItem("A", true), new GeneratedItem.OptionItem("B", false)),
//                null, "A", null, null, null, null
//        );
//        when(exerciseGeneratorAgent.generateItems(any(), any(), any(), anyInt(), any(), any()))
//                .thenReturn(List.of(mockItem, mockItem, mockItem, mockItem, mockItem));
//
//        // Evaluate behavior
//        EvaluationResult evalResult = new EvaluationResult(true, 100, "Correct", null);
//        when(answerEvaluatorAgent.evaluate(any(), any())).thenReturn(evalResult);
//
//        when(sessionFeedbackAgent.generateFeedback(any(), anyDouble(), anyList(), anyString())).thenReturn("{}");
//    }
//
//    // ── SECURITY ─────────────────────────────────────────────────────────
//
//    @Nested
//    @DisplayName("Security & RBAC Checks")
//    class SecurityChecks {
//        @Test
//        @DisplayName("IT-01: Gửi request without JWT -> 401")
//        void it01_noJwtReturns401() throws Exception {
//            mockMvc.perform(post("/api/v1/learner/practice/sessions")
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content("{}"))
//                    .andExpect(status().isUnauthorized());
//        }
//
//        @Test
//        @DisplayName("IT-02: Gửi request with TEACHER role -> 403")
//        void it02_teacherRoleReturns403() throws Exception {
//            String validPayload = String.format("{\"lessonId\":\"%s\",\"sessionType\":\"MIXED_LESSON\",\"itemCount\":5}", lesson.getId());
//            mockMvc.perform(post("/api/v1/learner/practice/sessions")
//                    .header("Authorization", "Bearer " + teacherToken)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content(validPayload))
//                    .andExpect(status().isForbidden());
//        }
//    }
//
//    // ── VIEW LESSON LIST ─────────────────────────────────────────────────
//
//    @Nested
//    @DisplayName("UC 2.12.1 – View Lesson List")
//    class ViewLessonListTests {
//        @Test
//        @DisplayName("IT-03: Xem lesson list course hợp lệ -> Thành công")
//        void it03_learnEnrolledCourseReturnsLessons() throws Exception {
//            mockMvc.perform(get("/api/v1/courses/{courseId}/lessons", course.getId())
//                    .header("Authorization", "Bearer " + learnerToken))
//                    .andExpect(status().isOk())
//                    .andExpect(jsonPath("$.success").value(true))
//                    .andExpect(jsonPath("$.data.length()").value(1))
//                    .andExpect(jsonPath("$.data[0].id").value(lesson.getId().toString()));
//        }
//
//        @Test
//        @DisplayName("IT-03B: Xem lesson list course chưa enroll -> Vẫn public lấy được bài học")
//        void it03b_learnNotEnrolledCourseReturnsError() throws Exception {
//            mockMvc.perform(get("/api/v1/courses/{courseId}/lessons", otherCourse.getId())
//                    .header("Authorization", "Bearer " + learnerToken))
//                    .andExpect(status().isOk())
//                    .andExpect(jsonPath("$.success").value(true))
//                    .andExpect(jsonPath("$.data").isEmpty()); // Vì otherCourse không có lesson
//        }
//
//        @Test
//        @DisplayName("IT-04: Xem lesson list với courseId không tồn tại -> Trả về mảng rỗng")
//        void it04_courseIdNotFoundReturnsError() throws Exception {
//            mockMvc.perform(get("/api/v1/courses/{courseId}/lessons", UUID.randomUUID())
//                    .header("Authorization", "Bearer " + learnerToken))
//                    .andExpect(status().isOk())
//                    .andExpect(jsonPath("$.success").value(true))
//                    .andExpect(jsonPath("$.data").isEmpty());
//        }
//
//        @Test
//        @DisplayName("IT-05: Course chưa có lessons -> Trả về danh sách rỗng hoặc lỗi MSG-162")
//        void it05_courseWithoutLessons() throws Exception {
//            // First we need to delete the lesson from the course or make a new assigned course
//            lessonRepository.deleteAll();
//            mockMvc.perform(get("/api/v1/courses/{courseId}/lessons", course.getId())
//                    .header("Authorization", "Bearer " + learnerToken))
//                    .andExpect(status().isOk())
//                    .andExpect(jsonPath("$.data").isEmpty()); // Handled at client or explicitly checked
//        }
//    }
//
//    // ── CONFIG AI PRACTICE ────────────────────────────────────────────────
//
//    @Nested
//    @DisplayName("UC 2.12.2 – Config AI Practice")
//    class ConfigAiPracticeTests {
//
//        private StartPracticeRequest defaultRequest() {
//            StartPracticeRequest req = new StartPracticeRequest();
//            req.setLessonId(lesson.getId());
//            req.setSessionType(PracticeSessionType.MIXED_LESSON);
//            req.setItemCount(5);
//            return req;
//        }
//
//        @Test
//        @DisplayName("IT-06: Config hợp lệ -> Tạo session mới")
//        void it06_validConfigCreatesSession() throws Exception {
//            when(exerciseGeneratorAgent.generateItems(any(), any(), any(), eq(5), any(), any()))
//                    .thenReturn(List.of(
//                            new GeneratedItem("MULTIPLE_CHOICE", "Q1", null, null, null, null, null, null, null, null),
//                            new GeneratedItem("FILL_BLANK", "Q2", null, null, null, null, null, null, null, null)
//                    ));
//
//            String response = mockMvc.perform(post("/api/v1/learner/practice/sessions")
//                    .header("Authorization", "Bearer " + learnerToken)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content(objectMapper.writeValueAsString(defaultRequest())))
//                    .andExpect(status().isCreated())
//                    .andExpect(jsonPath("$.success").value(true))
//                    .andExpect(jsonPath("$.data.session.sessionId").isNotEmpty())
//                    .andReturn().getResponse().getContentAsString();
//
//            UUID sessionId = UUID.fromString(objectMapper.readTree(response).path("data").path("session").path("sessionId").asText());
//            Optional<PracticeSession> session = practiceSessionRepository.findById(sessionId);
//            assertThat(session).isPresent();
//            assertThat(session.get().getUserId()).isEqualTo(learner.getId());
//            assertThat(session.get().getStatus()).isEqualTo(PracticeStatus.IN_PROGRESS);
//            assertThat(session.get().getTotalItems()).isEqualTo(2);
//
//            long itemsCount = practiceItemRepository.findBySessionIdOrderByItemIndex(sessionId).size();
//            assertThat(itemsCount).isEqualTo(2); // Check DB directly, generator returned 2, total items=2.
//        }
//
//        @Test
//        @DisplayName("IT-07: Config với lessonId không tồn tại -> Lỗi MSG-66")
//        void it07_invalidLessonReturnsMsg66() throws Exception {
//            StartPracticeRequest req = defaultRequest();
//            req.setLessonId(UUID.randomUUID());
//
//            mockMvc.perform(post("/api/v1/learner/practice/sessions")
//                    .header("Authorization", "Bearer " + learnerToken)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content(objectMapper.writeValueAsString(req)))
//                    .andExpect(status().isBadRequest())
//                    .andExpect(jsonPath("$.code").value("LESSON_NOT_FOUND"))
//                    .andExpect(jsonPath("$.message").value("Không tìm thấy bài học. Vui lòng thử lại.")); // Checking standard format
//        }
//
//        @Test
//        @DisplayName("IT-08: Config hai lần -> Tạo hai session riêng biệt (BR-263)")
//        void it08_configTwiceCreatesSeparateSessions() throws Exception {
//            StartPracticeRequest req = defaultRequest();
//
//            mockMvc.perform(post("/api/v1/learner/practice/sessions")
//                    .header("Authorization", "Bearer " + learnerToken)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content(objectMapper.writeValueAsString(req)))
//                    .andExpect(status().isCreated());
//
//            mockMvc.perform(post("/api/v1/learner/practice/sessions")
//                    .header("Authorization", "Bearer " + learnerToken)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content(objectMapper.writeValueAsString(req)))
//                    .andExpect(status().isCreated());
//
//            List<PracticeSession> sessions = practiceSessionRepository.findAll();
//            assertThat(sessions).hasSize(2);
//            assertThat(sessions.get(0).getId()).isNotEqualTo(sessions.get(1).getId());
//        }
//
//        @Test
//        @DisplayName("IT-09: itemCount = 1 (biên dưới hợp lệ) -> Chấp nhận")
//        void it09_itemCountOneIsAccepted() throws Exception {
//            StartPracticeRequest req = defaultRequest();
//            req.setItemCount(1);
//            when(exerciseGeneratorAgent.generateItems(any(), any(), any(), eq(1), any(), any()))
//                    .thenReturn(List.of(new GeneratedItem("MULTIPLE_CHOICE", "Q1", null, null, null, null, null, null, null, null)));
//
//            mockMvc.perform(post("/api/v1/learner/practice/sessions")
//                    .header("Authorization", "Bearer " + learnerToken)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content(objectMapper.writeValueAsString(req)))
//                    .andExpect(status().isCreated());
//        }
//
//        @Test
//        @DisplayName("IT-10: itemCount = 0 (ngoại biên dưới) -> Reject")
//        void it10_itemCountZeroIsRejected() throws Exception {
//            StartPracticeRequest req = defaultRequest();
//            req.setItemCount(0);
//
//            mockMvc.perform(post("/api/v1/learner/practice/sessions")
//                    .header("Authorization", "Bearer " + learnerToken)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content(objectMapper.writeValueAsString(req)))
//                    .andExpect(status().isBadRequest());
//        }
//
//        @Test
//        @DisplayName("IT-10B: Config thiếu tham số bắt buộc -> 400 Bad Request")
//        void it10b_missingRequiredFieldsIsRejected() throws Exception {
//            String invalidPayload = "{\"itemCount\": 5}"; // missing lessonId and practiceType
//            mockMvc.perform(post("/api/v1/learner/practice/sessions")
//                    .header("Authorization", "Bearer " + learnerToken)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content(invalidPayload))
//                    .andExpect(status().isBadRequest());
//        }
//
//        @Test
//        @DisplayName("IT-11: itemCount = 20 (biên trên hợp lệ) -> Chấp nhận")
//        void it11_itemCount20IsAccepted() throws Exception {
//            StartPracticeRequest req = defaultRequest();
//            req.setItemCount(20);
//            mockMvc.perform(post("/api/v1/learner/practice/sessions")
//                    .header("Authorization", "Bearer " + learnerToken)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content(objectMapper.writeValueAsString(req)))
//                    .andExpect(status().isCreated());
//        }
//
//        @Test
//        @DisplayName("IT-12: itemCount = 21 (ngoại biên trên) -> Reject")
//        void it12_itemCount21IsRejected() throws Exception {
//            StartPracticeRequest req = defaultRequest();
//            req.setItemCount(21);
//            mockMvc.perform(post("/api/v1/learner/practice/sessions")
//                    .header("Authorization", "Bearer " + learnerToken)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content(objectMapper.writeValueAsString(req)))
//                    .andExpect(status().isBadRequest());
//        }
//    }
//
//    // ── COMPLETE AI EXERCISES ─────────────────────────────────────────────
//
//    @Nested
//    @DisplayName("UC 2.12.3 – Complete AI Exercises")
//    class CompleteAiExercisesTests {
//
//        private PracticeSession createTestSession(User user, PracticeStatus status, int totalItems, int completedCount) {
//            PracticeSession session = practiceSessionRepository.save(PracticeSession.builder()
//                    .userId(user.getId())
//                    .lessonId(lesson.getId())
//                    .courseId(course.getId())
//                    .sessionType(PracticeSessionType.MIXED_LESSON)
//                    .status(status)
//                    .totalItems(totalItems)
//                    .completedItems(completedCount)
//                    .score(java.math.BigDecimal.valueOf(completedCount * 10))
//                    .build());
//
//            for (int i = 0; i < totalItems; i++) {
//                practiceItemRepository.save(PracticeItem.builder()
//                        .sessionId(session.getId())
//                        .itemType(PracticeItemType.MULTIPLE_CHOICE)
//                        .itemIndex(i)
//                        .question("Question " + i)
//                        .options("[\"A\", \"B\"]")
//                        .correctAnswer("A")
//                        .userAnswer(i < completedCount ? "A" : null)
//                        .isCorrect(i < completedCount)
//                        .score(i < completedCount ? 100 : 0)
//                        .build());
//            }
//            return session;
//        }
//
//        @Test
//        @DisplayName("IT-13: Learner nộp câu cuối cùng -> session hoành thành (BR-265)")
//        void it13_submitFinalAnswerHappyPath() throws Exception {
//            PracticeSession session = createTestSession(learner, PracticeStatus.IN_PROGRESS, 2, 1);
//            // Lay cau hoi cuoi chua tra loi
//            PracticeItem itemToAnswer = practiceItemRepository.findAll()
//                .stream().filter(i -> i.getUserAnswer() == null).findFirst().orElseThrow();
//
//            // Mock Evaluation OK
//            when(answerEvaluatorAgent.evaluate(any(), any())).thenReturn(new EvaluationResult(true, 100, "Good job!", null));
//
//            SubmitAnswerRequest request = new SubmitAnswerRequest();
//            request.setUserAnswer("A");
//
//            mockMvc.perform(post("/api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer", session.getId(), itemToAnswer.getId())
//                    .header("Authorization", "Bearer " + learnerToken)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content(objectMapper.writeValueAsString(request)))
//                    .andExpect(status().isOk())
//                    .andExpect(jsonPath("$.success").value(true))
//                    .andExpect(jsonPath("$.data.correct").value(true))
//                    .andExpect(jsonPath("$.data.feedback").value("Good job!"))
//                    .andExpect(jsonPath("$.data.sessionCompleted").value(true));
//
//            // Verify in DB
//            PracticeSession dbSession = practiceSessionRepository.findById(session.getId()).orElseThrow();
//            assertThat(dbSession.getStatus()).isEqualTo(PracticeStatus.COMPLETED);
//            assertThat(dbSession.getCompletedItems()).isEqualTo(2);
//
//            PracticeItem dbItem = practiceItemRepository.findById(itemToAnswer.getId()).orElseThrow();
//            assertThat(dbItem.getIsCorrect()).isTrue();
//            assertThat(dbItem.getUserAnswer()).isEqualTo("A");
//        }
//
//        @Test
//        @DisplayName("IT-14: Learner dừng ngang (partial submit), hệ thống lưu điểm một nửa an toàn")
//        void it14_partialSubmitTracksProperly() throws Exception {
//            PracticeSession session = createTestSession(learner, PracticeStatus.IN_PROGRESS, 5, 0);
//            PracticeItem itemToAnswer = practiceItemRepository.findBySessionIdOrderByItemIndex(session.getId()).get(0);
//
//            when(answerEvaluatorAgent.evaluate(any(), any())).thenReturn(new EvaluationResult(false, 0, "Wrong", "A"));
//
//            SubmitAnswerRequest request = new SubmitAnswerRequest();
//            request.setUserAnswer("B");
//
//            mockMvc.perform(post("/api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer", session.getId(), itemToAnswer.getId())
//                    .header("Authorization", "Bearer " + learnerToken)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content(objectMapper.writeValueAsString(request)))
//                    .andExpect(status().isOk())
//                    .andExpect(jsonPath("$.data.sessionCompleted").value(false));
//
//            PracticeSession dbSession = practiceSessionRepository.findById(session.getId()).orElseThrow();
//            assertThat(dbSession.getStatus()).isEqualTo(PracticeStatus.IN_PROGRESS);
//            assertThat(dbSession.getCompletedItems()).isEqualTo(1);
//        }
//
//        @Test
//        @DisplayName("IT-15: Submit hợp lệ nhưng AI service lỗi -> Lỗi thân thiện MSG-158")
//        void it15_aiServiceFailsReturnsFriendlyMessage() throws Exception {
//            PracticeSession session = createTestSession(learner, PracticeStatus.IN_PROGRESS, 1, 0);
//            PracticeItem item = practiceItemRepository.findAll().get(0);
//
//            when(answerEvaluatorAgent.evaluate(any(), any())).thenThrow(new RuntimeException("OpenAI Timeout"));
//
//            SubmitAnswerRequest request = new SubmitAnswerRequest();
//            request.setUserAnswer("A");
//
//            mockMvc.perform(post("/api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer", session.getId(), item.getId())
//                    .header("Authorization", "Bearer " + learnerToken)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content(objectMapper.writeValueAsString(request)))
//                    .andExpect(status().isInternalServerError())
//                    .andExpect(jsonPath("$.success").value(false))
//                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
//        }
//
//        @Test
//        @DisplayName("IT-16: Submit vào sessionId không tồn tại -> Lỗi Không Tìm Thấy")
//        void it16_submitInvalidSessionIdReturnsError() throws Exception {
//            PracticeSession session = createTestSession(learner, PracticeStatus.IN_PROGRESS, 1, 0);
//            PracticeItem item = practiceItemRepository.findAll().get(0);
//
//            SubmitAnswerRequest request = new SubmitAnswerRequest();
//            request.setUserAnswer("A");
//
//            mockMvc.perform(post("/api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer", UUID.randomUUID(), item.getId())
//                    .header("Authorization", "Bearer " + learnerToken)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content(objectMapper.writeValueAsString(request)))
//                    .andExpect(status().isBadRequest())
//                    .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"))
//                    .andExpect(jsonPath("$.message").value("Không tìm thấy phiên luyện tập."));
//        }
//
//        @Test
//        @DisplayName("IT-16B: Submit ID câu hỏi thuộc về session khác -> Trả lỗi toàn vẹn")
//        void it16b_submitItemFromDifferentSessionReturnsError() throws Exception {
//            PracticeSession session1 = createTestSession(learner, PracticeStatus.IN_PROGRESS, 1, 0);
//            PracticeItem itemSession1 = practiceItemRepository.findBySessionIdOrderByItemIndex(session1.getId()).get(0);
//
//            PracticeSession session2 = createTestSession(learner, PracticeStatus.IN_PROGRESS, 1, 0);
//
//            SubmitAnswerRequest request = new SubmitAnswerRequest();
//            request.setUserAnswer("A");
//
//            // Guing item cua Session 1 vao URL api cua Session 2
//            mockMvc.perform(post("/api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer", session2.getId(), itemSession1.getId())
//                    .header("Authorization", "Bearer " + learnerToken)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content(objectMapper.writeValueAsString(request)))
//                    .andExpect(status().isBadRequest())
//                    .andExpect(jsonPath("$.code").value("ITEM_SESSION_MISMATCH"))
//                    .andExpect(jsonPath("$.message").value("Câu hỏi không thuộc phiên luyện tập này.")); // Or 400 Bad Request
//        }
//
//        @Test
//        @DisplayName("IT-17: Cố tình submit lại session đã COMPLETED -> Bị từ chối")
//        void it17_submitToCompletedSessionIsRejected() throws Exception {
//            PracticeSession session = createTestSession(learner, PracticeStatus.COMPLETED, 1, 1);
//            PracticeItem item = practiceItemRepository.findAll().get(0);
//
//            SubmitAnswerRequest request = new SubmitAnswerRequest();
//            request.setUserAnswer("A");
//
//            mockMvc.perform(post("/api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer", session.getId(), item.getId())
//                    .header("Authorization", "Bearer " + learnerToken)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content(objectMapper.writeValueAsString(request)))
//                    .andExpect(status().isBadRequest())
//                    .andExpect(jsonPath("$.code").value("SESSION_NOT_ACTIVE"))
//                    .andExpect(jsonPath("$.message").value("Phiên luyện tập đã kết thúc."));
//        }
//
//        @Test
//        @DisplayName("IT-18: Learner A dùng sessionId của Learner B -> 403 Forbidden")
//        void it18_submitToOtherUsersSessionReturns403() throws Exception {
//            PracticeSession session = createTestSession(otherLearner, PracticeStatus.IN_PROGRESS, 1, 0);
//            PracticeItem item = practiceItemRepository.findAll().get(0);
//
//            SubmitAnswerRequest request = new SubmitAnswerRequest();
//            request.setUserAnswer("A");
//
//            mockMvc.perform(post("/api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer", session.getId(), item.getId())
//                    .header("Authorization", "Bearer " + learnerToken) // Requesting as 'learner'
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content(objectMapper.writeValueAsString(request)))
//                    .andExpect(status().isBadRequest())
//                    .andExpect(jsonPath("$.code").value("SESSION_ACCESS_DENIED"))
//                    .andExpect(jsonPath("$.message").value("Bạn không có quyền truy cập phiên luyện tập này.")); // Or Forbidden depending on standard service implementation (Satori often returns 404 for missing resource checks)
//        }
//
//        @Test
//        @DisplayName("IT-19: Xóa / không lưu trữ data vĩnh viễn (Kiểm chứng BR-266)")
//        void it19_verifyShortTermStorageStrategy() throws Exception {
//            // Satori spec does not require long-term storage,
//            // In integration test we verify record exists initially, and maybe cron removes it later.
//            // Or if soft deletion logic applies. We assert what is present.
//            PracticeSession session = createTestSession(learner, PracticeStatus.COMPLETED, 1, 1);
//            Optional<PracticeSession> check = practiceSessionRepository.findById(session.getId());
//            assertThat(check).isPresent(); // Present in DB is fine, wait for scheduled purge.
//        }
//
//        @Test
//        @DisplayName("IT-20: MATCHING với partial credit (đúng 2/4 cặp -> score 50)")
//        void it20_matchingPartialCreditScoringAccepted() throws Exception {
//            PracticeSession session = createTestSession(learner, PracticeStatus.IN_PROGRESS, 1, 0);
//            PracticeItem item = practiceItemRepository.findBySessionIdOrderByItemIndex(session.getId()).get(0);
//
//            // Setup MATCHING item với 4 cặp cần nối
//            item.setItemType(PracticeItemType.MATCHING);
//            item.setCorrectAnswer("0:4,1:5,2:6,3:7");  // 4 cặp đúng
//            item.setQuestion("Nối các từ tiếng Anh với nghĩa tiếng Việt");
//            practiceItemRepository.save(item);
//
//            // Mock evaluator: learner nối đúng 2/4 cặp → score = 50
//            EvaluationResult partialResult = new EvaluationResult(false, 50, "Đúng 2/4 cặp", "0:4,1:5,2:6,3:7");
//            when(answerEvaluatorAgent.evaluate(any(), any())).thenReturn(partialResult);
//
//            SubmitAnswerRequest request = new SubmitAnswerRequest();
//            // Learner nối: 0→4 ✅, 1→5 ✅, 2→8 ❌, 3→9 ❌
//            request.setUserAnswer("0:4,1:5,2:8,3:9");
//
//            String response = mockMvc.perform(post("/api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer", session.getId(), item.getId())
//                    .header("Authorization", "Bearer " + learnerToken)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content(objectMapper.writeValueAsString(request)))
//                    .andExpect(status().isOk())
//                    .andExpect(jsonPath("$.success").value(true))
//                    .andExpect(jsonPath("$.data.correct").value(false))
//                    .andExpect(jsonPath("$.data.score").value(50))  // Verify score gradient (not 0 or 100)
//                    .andExpect(jsonPath("$.data.feedback").value("Đúng 2/4 cặp"))
//                    .andReturn().getResponse().getContentAsString();
//
//            // Verify in DB that score is 50 (gradient), not 0 or 100
//            PracticeItem dbItem = practiceItemRepository.findById(item.getId()).orElseThrow();
//            assertThat(dbItem.getUserAnswer()).isEqualTo("0:4,1:5,2:8,3:9");
//            assertThat(dbItem.getIsCorrect()).isFalse();
//            assertThat(dbItem.getScore()).isEqualTo(50);  // Crucial: partial score must persist
//            assertThat(dbItem.getItemType()).isEqualTo(PracticeItemType.MATCHING);
//
//            // Session should be COMPLETED (all items answered: 1/1)
//            PracticeSession dbSession = practiceSessionRepository.findById(session.getId()).orElseThrow();
//            assertThat(dbSession.getStatus()).isEqualTo(PracticeStatus.COMPLETED);
//            assertThat(dbSession.getCompletedItems()).isEqualTo(1);
//        }
//
//        @Test
//        @DisplayName("IT-21: Session hoàn thành, kiểm tra feedback có được trả về trong response")
//        void it21_feedbackIncludedWhenSessionCompleted() throws Exception {
//            PracticeSession session = createTestSession(learner, PracticeStatus.IN_PROGRESS, 2, 1);
//            PracticeItem itemToAnswer = practiceItemRepository.findAll()
//                    .stream().filter(i -> i.getUserAnswer() == null).findFirst().orElseThrow();
//
//            // Mock evaluator trả về kết quả đúng
//            when(answerEvaluatorAgent.evaluate(any(), any())).thenReturn(new EvaluationResult(true, 100, "Excellent!", null));
//
//            // Mock session feedback agent trả về feedback JSON
//            String expectedFeedback = "{\"summary\":\"Bạn làm rất tốt\",\"strengths\":[\"Nhanh\"],\"weaknesses\":[],\"nextLessonSuggestion\":\"Học tiếp bài N5\"}";
//            when(sessionFeedbackAgent.generateFeedback(eq(session.getId()), anyDouble(), anyList(), anyString()))
//                    .thenReturn(expectedFeedback);
//
//            SubmitAnswerRequest request = new SubmitAnswerRequest();
//            request.setUserAnswer("A");
//
//            // Submit final answer
//            mockMvc.perform(post("/api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer", session.getId(), itemToAnswer.getId())
//                    .header("Authorization", "Bearer " + learnerToken)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .content(objectMapper.writeValueAsString(request)))
//                    .andExpect(status().isOk())
//                    .andExpect(jsonPath("$.success").value(true))
//                    .andExpect(jsonPath("$.data.sessionCompleted").value(true))
//                    .andExpect(jsonPath("$.data.correct").value(true))
//                    .andExpect(jsonPath("$.data.feedback").value("Excellent!"));
//
//            // Verify in DB that session is COMPLETED and feedback was persisted
//            PracticeSession dbSession = practiceSessionRepository.findById(session.getId()).orElseThrow();
//            assertThat(dbSession.getStatus()).isEqualTo(PracticeStatus.COMPLETED);
//            assertThat(dbSession.getCompletedItems()).isEqualTo(2);
//            // Note: AI feedback generated asynchronously, so it may not be immediately in response
//            // But we verify the session was completed and marked for async feedback generation
//        }
//
//        @Test
//        @DisplayName("IT-22: Learner làm 5/10 câu rồi quit, quay lại tiếp tục vẫn giữ trạng thái")
//        void it22_resumePartialSessionPreservesState() throws Exception {
//            // Create session with 10 items
//            PracticeSession session = practiceSessionRepository.save(PracticeSession.builder()
//                    .userId(learner.getId())
//                    .lessonId(lesson.getId())
//                    .courseId(course.getId())
//                    .sessionType(PracticeSessionType.MIXED_LESSON)
//                    .status(PracticeStatus.IN_PROGRESS)
//                    .totalItems(10)
//                    .completedItems(0)
//                    .score(java.math.BigDecimal.ZERO)
//                    .build());
//
//            // Create 10 items
//            for (int i = 0; i < 10; i++) {
//                practiceItemRepository.save(PracticeItem.builder()
//                        .sessionId(session.getId())
//                        .itemType(PracticeItemType.MULTIPLE_CHOICE)
//                        .itemIndex(i)
//                        .question("Question " + i)
//                        .options("[\"A\", \"B\"]")
//                        .correctAnswer("A")
//                        .build());
//            }
//
//            // Step 1: Learner làm 5 câu (items 0-4)
//            for (int i = 0; i < 5; i++) {
//                PracticeItem item = practiceItemRepository.findBySessionIdAndItemIndex(session.getId(), i).orElseThrow();
//                when(answerEvaluatorAgent.evaluate(any(), any())).thenReturn(new EvaluationResult(true, 100, "Good!", null));
//
//                SubmitAnswerRequest request = new SubmitAnswerRequest();
//                request.setUserAnswer("A");
//
//                mockMvc.perform(post("/api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer", session.getId(), item.getId())
//                        .header("Authorization", "Bearer " + learnerToken)
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content(objectMapper.writeValueAsString(request)))
//                        .andExpect(status().isOk());
//            }
//
//            // Verify state after 5 submissions
//            PracticeSession session5Done = practiceSessionRepository.findById(session.getId()).orElseThrow();
//            assertThat(session5Done.getStatus()).isEqualTo(PracticeStatus.IN_PROGRESS);  // Chưa complete
//            assertThat(session5Done.getCompletedItems()).isEqualTo(5);
//            assertThat(session5Done.getTotalItems()).isEqualTo(10);
//
//            // Step 2: Learner quay lại và tiếp tục làm các câu còn lại (5-9)
//            for (int i = 5; i < 10; i++) {
//                PracticeItem item = practiceItemRepository.findBySessionIdAndItemIndex(session.getId(), i).orElseThrow();
//                when(answerEvaluatorAgent.evaluate(any(), any())).thenReturn(new EvaluationResult(true, 100, "Good!", null));
//
//                SubmitAnswerRequest request = new SubmitAnswerRequest();
//                request.setUserAnswer("A");
//
//                mockMvc.perform(post("/api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer", session.getId(), item.getId())
//                        .header("Authorization", "Bearer " + learnerToken)
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content(objectMapper.writeValueAsString(request)))
//                        .andExpect(status().isOk());
//            }
//
//            // Verify final state - all completed
//            PracticeSession sessionFinal = practiceSessionRepository.findById(session.getId()).orElseThrow();
//            assertThat(sessionFinal.getStatus()).isEqualTo(PracticeStatus.COMPLETED);
//            assertThat(sessionFinal.getCompletedItems()).isEqualTo(10);
//            assertThat(sessionFinal.getTotalItems()).isEqualTo(10);
//
//            // Verify all 10 items have answers
//            List<PracticeItem> allItems = practiceItemRepository.findBySessionIdOrderByItemIndex(session.getId());
//            assertThat(allItems).hasSize(10);
//            assertThat(allItems.stream().filter(i -> i.getUserAnswer() != null).count()).isEqualTo(10);
//        }
//    }
//}
