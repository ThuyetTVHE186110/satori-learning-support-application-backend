package com.fu.assignment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.assignment.ai.QuizAiGenerationService;
import com.fu.assignment.ai.QuizExplanationService;
import com.fu.assignment.dto.request.AiQuizGenerationRequest;
import com.fu.assignment.dto.response.QuizQuestionResponse;
import com.fu.assignment.entity.*;
import com.fu.assignment.repository.*;
import com.fu.clazz.entity.ClassMember;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.repository.ClassMemberRepository;
import com.fu.clazz.repository.ClassRepository;
import com.fu.course.entity.Course;
import com.fu.course.entity.Course.CourseStatus;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.repository.CourseRepository;
import com.fu.user.entity.*;
import com.fu.user.repository.UserRepository;
import com.fu.user.repository.UserSessionRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — Practice Task (Assignment) Management.
 * Covers all test cases in ASSIGNMENT_TEST_PROCEDURES.md.
 *
 * Sections:
 *   1. TEACHER: View List, View Info, Create, Update, Status, Delete, View Results
 *   2. LEARNER:  View List, Do Task (start + submit), View Results
 *   3. AI:       Mocked OpenAI generate questions & explanation
 */
@SpringBootTest(classes = com.fu.StartApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Assignment Management — Integration Tests")
class AssignmentIntegrationTest {

    // ── Spring beans ──────────────────────────────────────────────────────────
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private AssignmentSubmissionRepository submissionRepository;
    @Autowired private AssignmentQuestionRepository assignmentQuestionRepository;
    @Autowired private AssignmentTargetRepository targetRepository;
    @Autowired private WritingAssignmentRepository writingAssignmentRepository;
    @Autowired private QuizQuestionRepository quizQuestionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private ClassMemberRepository classMemberRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    // ── Mocked AI services (prevent real HTTP calls) ──────────────────────────
    @MockBean private QuizAiGenerationService quizAiGenerationService;
    @MockBean private QuizExplanationService quizExplanationService;

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private User saveTeacher(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("ValidPass@1"))
                .firstName("Test").lastName("Teacher")
                .role(UserRole.TEACHER).status(UserStatus.ACTIVE)
                .authProvider(AuthProvider.LOCAL).failedLoginAttempts(0)
                .build());
    }

    private User saveLearner(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("ValidPass@1"))
                .firstName("Test").lastName("Learner")
                .role(UserRole.LEARNER).status(UserStatus.ACTIVE)
                .authProvider(AuthProvider.LOCAL).failedLoginAttempts(0)
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

    private Course saveCourse() {
        return courseRepository.save(Course.builder()
                .name("Integration Course").description("test")
                .jlptLevel(JlptLevel.N5).status(CourseStatus.ACTIVE).orderIndex(0)
                .build());
    }

    private Clazz saveClass(User teacher, Course course) {
        return classRepository.save(Clazz.builder()
                .name("Test Class 101").description("test class")
                .teacherId(teacher.getId()).courseId(course.getId())
                .isActive(true).maxStudents(30)
                .startDate(LocalDate.now().plusDays(1))
                .endDate(LocalDate.now().plusMonths(3))
                .build());
    }

    private void enrollLearner(UUID classId, UUID learnerId) {
        classMemberRepository.save(ClassMember.builder()
                .classId(classId).userId(learnerId).build());
    }

    /** Save a minimal QUIZ assignment (DRAFT, no questions). */
    private Assignment saveQuizDraft(User teacher, Clazz clazz) {
        Assignment a = assignmentRepository.save(Assignment.builder()
                .title("Quiz Assignment").description("desc")
                .assignmentType(AssignmentType.QUIZ)
                .classId(clazz.getId()).createdBy(teacher.getId())
                .dueDate(LocalDateTime.now().plusDays(7))
                .maxScore(100).passingScore(60)
                .allowRetry(false).maxRetries(1)
                .status(AssignmentStatus.DRAFT)
                .hasSubmissions(false)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
        targetRepository.save(AssignmentTarget.builder()
                .assignmentId(a.getId()).classId(clazz.getId()).build());
        return a;
    }

    /** Save a QUIZ assignment with one multiple-choice question (option A correct), PUBLISHED. */
    private Assignment savePublishedQuiz(User teacher, Clazz clazz) {
        Assignment a = assignmentRepository.save(Assignment.builder()
                .title("Published Quiz").description("desc")
                .assignmentType(AssignmentType.QUIZ)
                .classId(clazz.getId()).createdBy(teacher.getId())
                .dueDate(LocalDateTime.now().plusDays(7))
                .maxScore(100).passingScore(60)
                .allowRetry(false).maxRetries(1)
                .status(AssignmentStatus.PUBLISHED)
                .hasSubmissions(false)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
        targetRepository.save(AssignmentTarget.builder()
                .assignmentId(a.getId()).classId(clazz.getId()).build());
        QuizQuestion q = quizQuestionRepository.save(QuizQuestion.builder()
                .questionText("テストの問題").questionType("multiple_choice")
                .options("[{\"id\":\"A\",\"text\":\"正しい\",\"isCorrect\":true},{\"id\":\"B\",\"text\":\"間違い\",\"isCorrect\":false}]")
                .status(Course.ContentStatus.PUBLISHED)
                .aiGenerated(false).difficultyScore(1)
                .build());
        assignmentQuestionRepository.save(AssignmentQuestion.builder()
                .assignmentId(a.getId()).questionId(q.getId())
                .orderIndex(0).points(100).build());
        return a;
    }

    /** Save a PUBLISHED WRITING assignment. */
    private Assignment savePublishedWriting(User teacher, Clazz clazz) {
        Assignment a = assignmentRepository.save(Assignment.builder()
                .title("Published Writing").description("desc")
                .assignmentType(AssignmentType.WRITING)
                .classId(clazz.getId()).createdBy(teacher.getId())
                .dueDate(LocalDateTime.now().plusDays(7))
                .maxScore(100).passingScore(60)
                .allowRetry(false).maxRetries(1)
                .status(AssignmentStatus.PUBLISHED)
                .hasSubmissions(false)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
        targetRepository.save(AssignmentTarget.builder()
                .assignmentId(a.getId()).classId(clazz.getId()).build());
        writingAssignmentRepository.save(WritingAssignment.builder()
                .assignmentId(a.getId()).prompt("Write about yourself.")
                .createdAt(LocalDateTime.now()).build());
        return a;
    }

    /** Start an assignment for a learner directly via DB (bypasses controller). */
    private AssignmentSubmission startSubmission(UUID assignmentId, UUID learnerId) {
        assignmentRepository.findById(assignmentId).ifPresent(a -> {
            a.setHasSubmissions(true);
            assignmentRepository.save(a);
        });
        return submissionRepository.save(AssignmentSubmission.builder()
                .assignmentId(assignmentId).userId(learnerId)
                .status(SubmissionStatus.IN_PROGRESS)
                .attemptNumber(1).startedAt(LocalDateTime.now())
                .correctCount(0).totalQuestions(1).timeSpentSeconds(0)
                .answers("[]").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
    }

    /** Build JSON body for SaveFullQuizRequest (multipart "data" part). */
    private String quizRequestJson(AssignmentStatus status, String questionsJson) {
        String questions = (questionsJson != null) ? questionsJson : "[]";
        return """
            {
              "title": "Quiz Title",
              "dueDate": "%s",
              "passingScore": 60,
              "maxScore": 100,
              "allowRetry": false,
              "maxRetries": 1,
              "status": "%s",
              "questions": %s
            }
            """.formatted(LocalDateTime.now().plusDays(7), status.name(), questions);
    }

    /** One valid multiple-choice question JSON for questions[]. Option A is correct. */
    private String oneQuestionJson() {
        return """
            [{
              "questionText": "どれが正しいですか？",
              "questionType": "multiple_choice",
              "options": "[{\\"id\\":\\"A\\",\\"text\\":\\"はい\\",\\"isCorrect\\":true},{\\"id\\":\\"B\\",\\"text\\":\\"いいえ\\",\\"isCorrect\\":false}]",
              "points": 100
            }]
            """;
    }

    /** Build JSON body for SaveFullWritingRequest. */
    private String writingRequestJson(AssignmentStatus status) {
        return """
            {
              "title": "Writing Title",
              "dueDate": "%s",
              "passingScore": 60,
              "maxScore": 100,
              "status": "%s",
              "prompt": "Write something in Japanese."
            }
            """.formatted(LocalDateTime.now().plusDays(7), status.name());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    @BeforeEach
    void setUp() {
        submissionRepository.deleteAll();
        assignmentQuestionRepository.deleteAll();
        quizQuestionRepository.deleteAll();
        writingAssignmentRepository.deleteAll();
        targetRepository.deleteAll();
        assignmentRepository.deleteAll();
        classMemberRepository.deleteAll();
        classRepository.deleteAll();
        courseRepository.deleteAll();
        sessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        submissionRepository.deleteAll();
        assignmentQuestionRepository.deleteAll();
        quizQuestionRepository.deleteAll();
        writingAssignmentRepository.deleteAll();
        targetRepository.deleteAll();
        assignmentRepository.deleteAll();
        classMemberRepository.deleteAll();
        classRepository.deleteAll();
        courseRepository.deleteAll();
        sessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1. TEACHER SIDE
    // ═════════════════════════════════════════════════════════════════════════

    // ─────────────────────────────────────────────────────────────────────────
    // 1.1 View Practice Task List
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1.1 View Practice Task List")
    class ViewTeacherAssignments {

        @Test
        @DisplayName("TC_AS_T_VL_001 — Lấy list thành công, trả đúng fields")
        void tc_as_t_vl_001() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            savePublishedQuiz(teacher, clazz);
            String token = loginAndGetToken("teacher@test.com");
            
            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", clazz.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content[0].title").value("Published Quiz"))
                    .andExpect(jsonPath("$.data.content[0].assignmentType").value("QUIZ"));
        }

        @Test
        @DisplayName("TC_AS_T_VL_002 — classId không tồn tại → empty list (JOIN không tìm thấy record)")
        void tc_as_t_vl_002() throws Exception {
            saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", UUID.randomUUID())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content.length()").value(0));
        }

        @Test
        @DisplayName("TC_AS_T_VL_003 — Class tồn tại nhưng chưa có assignment → empty list")
        void tc_as_t_vl_003() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", clazz.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(0));
        }

        @Test
        @DisplayName("TC_AS_T_VL_004 — Teacher B xem class của Teacher A → 403")
        void tc_as_t_vl_004() throws Exception {
            User teacherA = saveTeacher("teacherA@test.com");
            saveTeacher("teacherB@test.com");
            Clazz clazzA = saveClass(teacherA, saveCourse());
            String tokenB = loginAndGetToken("teacherB@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", clazzA.getId())
                            .header("Authorization", "Bearer " + tokenB))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("TC_AS_T_VL_005 — Learner gọi endpoint Teacher → 403")
        void tc_as_t_vl_005() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1.2 View Practice Task Info
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1.2 View Practice Task Info")
    class ViewTeacherAssignmentInfo {

        @Test
        @DisplayName("TC_AS_T_VI_001 — Xem thành công, response đúng fields")
        void tc_as_t_vi_001() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/{id}", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(a.getId().toString()))
                    .andExpect(jsonPath("$.data.assignmentType").value("QUIZ"))
                    .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                    .andExpect(jsonPath("$.data.questions").isArray())
                    .andExpect(jsonPath("$.data.questions.length()").value(1));
        }

        @Test
        @DisplayName("TC_AS_T_VI_002 — Assignment không tồn tại → 404")
        void tc_as_t_vi_002() throws Exception {
            saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/{id}", UUID.randomUUID())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TC_AS_T_VI_004 — Teacher B xem assignment của Teacher A → 403")
        void tc_as_t_vi_004() throws Exception {
            User teacherA = saveTeacher("teacherA@test.com");
            saveTeacher("teacherB@test.com");
            Clazz clazzA = saveClass(teacherA, saveCourse());
            Assignment a = savePublishedQuiz(teacherA, clazzA);
            String tokenB = loginAndGetToken("teacherB@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/{id}", a.getId())
                            .header("Authorization", "Bearer " + tokenB))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("TC_AS_T_VI_005 — Learner gọi endpoint Teacher → 403")
        void tc_as_t_vi_005() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/{id}", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1.3 Create Practice Task
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1.3 Create Practice Task")
    class CreatePracticeTask {

        @Test
        @DisplayName("TC_AS_T_CR_001 — Tạo QUIZ thành công → status DRAFT")
        void tc_as_t_cr_001() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    quizRequestJson(AssignmentStatus.DRAFT, null).getBytes());

            mockMvc.perform(multipart("/api/v1/teacher/assignments/class/{classId}/quiz", clazz.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.assignmentType").value("QUIZ"))
                    .andExpect(jsonPath("$.data.status").value("DRAFT"));
        }

        @Test
        @DisplayName("TC_AS_T_CR_002a — Tạo WRITING thành công → status DRAFT")
        void tc_as_t_cr_002a() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(post("/api/v1/teacher/assignments/class/{classId}/writing", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(writingRequestJson(AssignmentStatus.DRAFT))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.assignmentType").value("WRITING"))
                    .andExpect(jsonPath("$.data.status").value("DRAFT"));
        }

        @Test
        @DisplayName("TC_AS_T_CR_002b — Tạo TRANSLATION thành công → status DRAFT")
        void tc_as_t_cr_002b() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            String token = loginAndGetToken("teacher@test.com");

            String body = """
                {
                  "title": "Translation Title",
                  "dueDate": "%s",
                  "passingScore": 60,
                  "maxScore": 100,
                  "status": "DRAFT",
                  "prompt": "Translate this sentence.",
                  "translationDirection": "JA_TO_VI"
                }
                """.formatted(LocalDateTime.now().plusDays(7));

            mockMvc.perform(post("/api/v1/teacher/assignments/class/{classId}/translation", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.assignmentType").value("TRANSLATION"))
                    .andExpect(jsonPath("$.data.status").value("DRAFT"));
        }

        @Test
        @DisplayName("TC_AS_T_CR_003 — Thiếu title → 400 validation error")
        void tc_as_t_cr_003() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    """
                    {
                      "dueDate": "%s",
                      "passingScore": 60,
                      "status": "DRAFT",
                      "questions": []
                    }
                    """.formatted(LocalDateTime.now().plusDays(7)).getBytes());

            mockMvc.perform(multipart("/api/v1/teacher/assignments/class/{classId}/quiz", clazz.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_CR_004 — dueDate đã quá hạn → 400 (@Future violation)")
        void tc_as_t_cr_004() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    """
                    {
                      "title": "Past Due Quiz",
                      "dueDate": "2020-01-01T00:00:00",
                      "passingScore": 60,
                      "status": "DRAFT",
                      "questions": []
                    }
                    """.getBytes());

            mockMvc.perform(multipart("/api/v1/teacher/assignments/class/{classId}/quiz", clazz.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_CR_005 — classId không tồn tại → 400 (CLASS_NOT_FOUND)")
        void tc_as_t_cr_005() throws Exception {
            saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    quizRequestJson(AssignmentStatus.DRAFT, null).getBytes());

            mockMvc.perform(multipart("/api/v1/teacher/assignments/class/{classId}/quiz", UUID.randomUUID())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_CR_006 — allowRetry=true, maxRetries omitted → default maxRetries=1")
        void tc_as_t_cr_006() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    """
                    {
                      "title": "Retry Quiz",
                      "dueDate": "%s",
                      "passingScore": 60,
                      "status": "DRAFT",
                      "allowRetry": true,
                      "questions": []
                    }
                    """.formatted(LocalDateTime.now().plusDays(7)).getBytes());

            mockMvc.perform(multipart("/api/v1/teacher/assignments/class/{classId}/quiz", clazz.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.allowRetry").value(true))
                    .andExpect(jsonPath("$.data.maxRetries").value(1));
        }

        @Test
        @DisplayName("TC_AS_T_CR_007 — Learner gọi endpoint Teacher create → 403")
        void tc_as_t_cr_007() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    quizRequestJson(AssignmentStatus.DRAFT, null).getBytes());

            mockMvc.perform(multipart("/api/v1/teacher/assignments/class/{classId}/quiz", clazz.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("TC_AS_T_CR_008 — Teacher B tạo vào class của Teacher A → 200 (no class-ownership guard, documents gap)")
        void tc_as_t_cr_008() throws Exception {
            User teacherA = saveTeacher("teacherA@test.com");
            saveTeacher("teacherB@test.com");
            Clazz clazzA = saveClass(teacherA, saveCourse());
            String tokenB = loginAndGetToken("teacherB@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    quizRequestJson(AssignmentStatus.DRAFT, null).getBytes());

            // NOTE: no class-ownership check on create — documents current behavior
            MvcResult result = mockMvc.perform(
                            multipart("/api/v1/teacher/assignments/class/{classId}/quiz", clazzA.getId())
                                    .file(dataPart)
                                    .header("Authorization", "Bearer " + tokenB))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1.4 Update Practice Task — metadata & content
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1.4 Update Practice Task")
    class UpdatePracticeTask {

        @Test
        @DisplayName("TC_AS_T_UP_001 — Update metadata thành công khi DRAFT")
        void tc_as_t_up_001() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = saveQuizDraft(teacher, clazz);
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    """
                    {
                      "title": "Updated Title",
                      "dueDate": "%s",
                      "passingScore": 50,
                      "status": "DRAFT",
                      "questions": []
                    }
                    """.formatted(LocalDateTime.now().plusDays(14)).getBytes());

            mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT,
                            "/api/v1/teacher/assignments/{id}/quiz", a.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("Updated Title"));
        }

        @Test
        @DisplayName("TC_AS_T_UP_002 — Update metadata thành công khi PUBLISHED")
        void tc_as_t_up_002() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    """
                    {
                      "title": "Updated Published Title",
                      "dueDate": "%s",
                      "passingScore": 60,
                      "status": "PUBLISHED",
                      "questions": []
                    }
                    """.formatted(LocalDateTime.now().plusDays(14)).getBytes());

            mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT,
                            "/api/v1/teacher/assignments/{id}/quiz", a.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("Updated Published Title"));
        }

        @Test
        @DisplayName("TC_AS_T_UP_003 — Update content (câu hỏi) khi chưa có submission → thành công")
        void tc_as_t_up_003() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = saveQuizDraft(teacher, clazz);
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    quizRequestJson(AssignmentStatus.DRAFT, oneQuestionJson()).getBytes());

            mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT,
                            "/api/v1/teacher/assignments/{id}/quiz", a.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.actualQuestionCount").value(1));
        }

        @Test
        @DisplayName("TC_AS_T_UP_004 — Update content khi hasSubmissions=true → 400 (HAS_SUBMISSIONS)")
        void tc_as_t_up_004() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            enrollLearner(clazz.getId(), learner.getId());
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    quizRequestJson(AssignmentStatus.PUBLISHED, oneQuestionJson()).getBytes());

            mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT,
                            "/api/v1/teacher/assignments/{id}/quiz", a.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_UP_005 — Rút ngắn dueDate khi hasSubmissions=true → 400")
        void tc_as_t_up_005() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            enrollLearner(clazz.getId(), learner.getId());
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    """
                    {
                      "title": "Published Quiz",
                      "dueDate": "%s",
                      "passingScore": 60,
                      "status": "PUBLISHED",
                      "questions": []
                    }
                    """.formatted(LocalDateTime.now().plusDays(3)).getBytes());

            mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT,
                            "/api/v1/teacher/assignments/{id}/quiz", a.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_UP_006 — Gia hạn dueDate khi hasSubmissions=true → thành công")
        void tc_as_t_up_006() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            enrollLearner(clazz.getId(), learner.getId());
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    """
                    {
                      "title": "Published Quiz",
                      "dueDate": "%s",
                      "passingScore": 60,
                      "status": "PUBLISHED",
                      "questions": []
                    }
                    """.formatted(LocalDateTime.now().plusDays(30)).getBytes());

            mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT,
                            "/api/v1/teacher/assignments/{id}/quiz", a.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("TC_AS_T_UP_007 — Tăng passingScore khi hasSubmissions=true → 400")
        void tc_as_t_up_007() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz); // passingScore=60
            enrollLearner(clazz.getId(), learner.getId());
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    """
                    {
                      "title": "Published Quiz",
                      "dueDate": "%s",
                      "passingScore": 80,
                      "status": "PUBLISHED",
                      "questions": []
                    }
                    """.formatted(LocalDateTime.now().plusDays(7)).getBytes());

            mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT,
                            "/api/v1/teacher/assignments/{id}/quiz", a.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1.5 Status Transitions
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1.5 Status Transitions")
    class StatusTransitions {

        @Test
        @DisplayName("TC_AS_T_ST_001 — DRAFT → PUBLISHED thành công (QUIZ có câu hỏi)")
        void tc_as_t_st_001() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = saveQuizDraft(teacher, clazz);
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    quizRequestJson(AssignmentStatus.PUBLISHED, oneQuestionJson()).getBytes());

            mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT,
                            "/api/v1/teacher/assignments/{id}/quiz", a.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        }

        @Test
        @DisplayName("TC_AS_T_ST_002 — DRAFT → PUBLISHED thiếu câu hỏi → 400 (NO_QUESTIONS)")
        void tc_as_t_st_002() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = saveQuizDraft(teacher, clazz);
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    quizRequestJson(AssignmentStatus.PUBLISHED, "[]").getBytes());

            mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT,
                            "/api/v1/teacher/assignments/{id}/quiz", a.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_ST_003 — DRAFT → PUBLISHED với dueDate quá hạn trong request → 400")
        void tc_as_t_st_003() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            saveQuizDraft(teacher, clazz);
            String token = loginAndGetToken("teacher@test.com");

            // Re-create the draft via controller so we can find its ID
            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    """
                    {
                      "title": "Quiz Title",
                      "dueDate": "2020-01-01T00:00:00",
                      "passingScore": 60,
                      "status": "PUBLISHED",
                      "questions": %s
                    }
                    """.formatted(oneQuestionJson()).getBytes());

            // Any assignment UUID — @Future validator fires before service logic
            mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT,
                            "/api/v1/teacher/assignments/{id}/quiz", UUID.randomUUID())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_ST_004 — PUBLISHED → DRAFT → 400 (INVALID_STATUS_TRANSITION)")
        void tc_as_t_st_004() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    quizRequestJson(AssignmentStatus.DRAFT, "[]").getBytes());

            mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT,
                            "/api/v1/teacher/assignments/{id}/quiz", a.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_ST_005 — PUBLISHED → CLOSED → thành công")
        void tc_as_t_st_005() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    """
                    {
                      "title": "Published Quiz",
                      "dueDate": "%s",
                      "passingScore": 60,
                      "status": "CLOSED",
                      "questions": []
                    }
                    """.formatted(LocalDateTime.now().plusDays(7)).getBytes());

            mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT,
                            "/api/v1/teacher/assignments/{id}/quiz", a.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CLOSED"));
        }

        @Test
        @DisplayName("TC_AS_T_ST_006 — CLOSED → PUBLISHED → 400 (terminal state)")
        void tc_as_t_st_006() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            a.setStatus(AssignmentStatus.CLOSED);
            assignmentRepository.save(a);
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    quizRequestJson(AssignmentStatus.PUBLISHED, oneQuestionJson()).getBytes());

            mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT,
                            "/api/v1/teacher/assignments/{id}/quiz", a.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1.6 Delete Practice Task
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1.6 Delete Practice Task")
    class DeletePracticeTask {

        @Test
        @DisplayName("TC_AS_T_DL_001 — Xóa DRAFT chưa có submission → soft delete thành công")
        void tc_as_t_dl_001() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = saveQuizDraft(teacher, clazz);
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(delete("/api/v1/teacher/assignments/{id}", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            assertThat(assignmentRepository.findByIdAndNotDeleted(a.getId())).isEmpty();
        }

        @Test
        @DisplayName("TC_AS_T_DL_002 — Xóa DRAFT có submission → GAP: xóa được, submission thành orphan")
        void tc_as_t_dl_002() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            a.setStatus(AssignmentStatus.DRAFT);
            a.setHasSubmissions(true);
            assignmentRepository.save(a);
            enrollLearner(clazz.getId(), learner.getId());
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("teacher@test.com");

            // BUG: no guard on hasSubmissions for DRAFT delete
            MvcResult result = mockMvc.perform(delete("/api/v1/teacher/assignments/{id}", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(assignmentRepository.findByIdAndNotDeleted(a.getId())).isEmpty();
            // Orphaned submission still in DB
            assertThat(submissionRepository.findByAssignmentIdAndUserIdOrderByAttemptDesc(
                    a.getId(), learner.getId())).isNotEmpty();
        }

        @Test
        @DisplayName("TC_AS_T_DL_003 — Xóa PUBLISHED → 400 (requireDraft block)")
        void tc_as_t_dl_003() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(delete("/api/v1/teacher/assignments/{id}", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_DL_004 — Xóa CLOSED → 400 (requireDraft block)")
        void tc_as_t_dl_004() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            a.setStatus(AssignmentStatus.CLOSED);
            assignmentRepository.save(a);
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(delete("/api/v1/teacher/assignments/{id}", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_DL_005 — Teacher B xóa assignment của Teacher A → 400 (ACCESS_DENIED)")
        void tc_as_t_dl_005() throws Exception {
            User teacherA = saveTeacher("teacherA@test.com");
            saveTeacher("teacherB@test.com");
            Clazz clazz = saveClass(teacherA, saveCourse());
            Assignment a = saveQuizDraft(teacherA, clazz);
            String tokenB = loginAndGetToken("teacherB@test.com");

            mockMvc.perform(delete("/api/v1/teacher/assignments/{id}", a.getId())
                            .header("Authorization", "Bearer " + tokenB))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_DL_006 — Learner gọi endpoint Teacher DELETE → 403")
        void tc_as_t_dl_006() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = saveQuizDraft(teacher, clazz);
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(delete("/api/v1/teacher/assignments/{id}", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1.7 View Practice Task Results (Teacher)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1.7 View Practice Task Results (Teacher)")
    class ViewTeacherSubmissions {

        @Test
        @DisplayName("TC_AS_T_VS_001 — Xem list submissions thành công")
        void tc_as_t_vs_001() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            enrollLearner(clazz.getId(), learner.getId());
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/{id}/submissions", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content.length()").value(1));
        }

        @Test
        @DisplayName("TC_AS_T_VS_002 — Assignment chưa có submission → empty list")
        void tc_as_t_vs_002() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/{id}/submissions", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(0));
        }

        @Test
        @DisplayName("TC_AS_T_VS_003 — Assignment ID không tồn tại → 404")
        void tc_as_t_vs_003() throws Exception {
            saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/{id}/submissions", UUID.randomUUID())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TC_AS_T_VS_004 — Xem 1 submission cụ thể → đúng fields")
        void tc_as_t_vs_004() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            enrollLearner(clazz.getId(), learner.getId());
            AssignmentSubmission sub = startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/submissions/{subId}", sub.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(sub.getId().toString()))
                    .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        }

        @Test
        @DisplayName("TC_AS_T_VS_005 — Submission không tồn tại → 404")
        void tc_as_t_vs_005() throws Exception {
            saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/submissions/{subId}", UUID.randomUUID())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TC_AS_T_VS_006 — Learner gọi endpoint Teacher submissions → 403")
        void tc_as_t_vs_006() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/{id}/submissions", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2. LEARNER SIDE
    // ═════════════════════════════════════════════════════════════════════════

    // ─────────────────────────────────────────────────────────────────────────
    // 2.1 View Assigned Practice Tasks (Learner)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("2.1 View Assigned Practice Tasks (Learner)")
    class LearnerViewAssignments {

        @Test
        @DisplayName("TC_AS_L_VL_001 — Xem list thành công, chỉ thấy assignment của class mình")
        void tc_as_l_vl_001() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            savePublishedQuiz(teacher, clazz);
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content.length()").value(1));
        }

        @Test
        @DisplayName("TC_AS_L_VL_002 — DRAFT assignment không hiện với Learner")
        void tc_as_l_vl_002() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            saveQuizDraft(teacher, clazz);
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(0));
        }

        @Test
        @DisplayName("TC_AS_L_VL_003 — Assignment CLOSED vẫn hiện (để xem lịch sử)")
        void tc_as_l_vl_003() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedQuiz(teacher, clazz);
            a.setStatus(AssignmentStatus.CLOSED);
            assignmentRepository.save(a);
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].status").value("CLOSED"));
        }

        @Test
        @DisplayName("TC_AS_L_VL_004 — Learner không thuộc class nào → empty list")
        void tc_as_l_vl_004() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(0));
        }

        @Test
        @DisplayName("TC_AS_L_VL_005 — Teacher gọi endpoint Learner → 403")
        void tc_as_l_vl_005() throws Exception {
            saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2.2 Do Practice Task (start + submit)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("2.2 Do Practice Task")
    class LearnerDoTask {

        @Test
        @DisplayName("TC_AS_L_DO_001 — Start thành công → submission IN_PROGRESS")
        void tc_as_l_do_001() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedQuiz(teacher, clazz);
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(post("/api/v1/learner/assignments/{id}/start", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.data.attemptNumber").value(1));
        }

        @Test
        @DisplayName("TC_AS_L_DO_002 — Assignment DRAFT → 400 (ASSIGNMENT_NOT_PUBLISHED)")
        void tc_as_l_do_002() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = saveQuizDraft(teacher, clazz);
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(post("/api/v1/learner/assignments/{id}/start", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_L_DO_003 — allowRetry=false, đã có 1 attempt GRADED → 400 (NO_RETRY)")
        void tc_as_l_do_003() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz); // allowRetry=false
            enrollLearner(clazz.getId(), learner.getId());
            submissionRepository.save(AssignmentSubmission.builder()
                    .assignmentId(a.getId()).userId(learner.getId())
                    .status(SubmissionStatus.GRADED).attemptNumber(1)
                    .startedAt(LocalDateTime.now().minusMinutes(10))
                    .submittedAt(LocalDateTime.now().minusMinutes(5))
                    .correctCount(1).totalQuestions(1).timeSpentSeconds(60)
                    .answers("[]").score(BigDecimal.valueOf(100))
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build());
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(post("/api/v1/learner/assignments/{id}/start", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_L_DO_004 — allowRetry=true, attemptCount >= maxRetries → 400 (MAX_RETRIES)")
        void tc_as_l_do_004() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            a.setAllowRetry(true);
            a.setMaxRetries(2);
            assignmentRepository.save(a);
            enrollLearner(clazz.getId(), learner.getId());
            for (int i = 1; i <= 2; i++) {
                submissionRepository.save(AssignmentSubmission.builder()
                        .assignmentId(a.getId()).userId(learner.getId())
                        .status(SubmissionStatus.GRADED).attemptNumber(i)
                        .startedAt(LocalDateTime.now().minusMinutes(20))
                        .submittedAt(LocalDateTime.now().minusMinutes(10))
                        .correctCount(1).totalQuestions(1).timeSpentSeconds(60)
                        .answers("[]").score(BigDecimal.valueOf(100))
                        .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                        .build());
            }
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(post("/api/v1/learner/assignments/{id}/start", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_L_DO_005 — Đang có IN_PROGRESS → 400 (ALREADY_IN_PROGRESS)")
        void tc_as_l_do_005() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedQuiz(teacher, clazz);
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(post("/api/v1/learner/assignments/{id}/start", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_L_DO_006 — Submit Quiz đúng hạn, đáp án đúng → GRADED, score=100")
        void tc_as_l_do_006() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedQuiz(teacher, clazz);
            AssignmentSubmission sub = startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("learner@test.com");

            UUID questionId = assignmentQuestionRepository
                    .findByAssignmentIdOrderByOrderIndex(a.getId()).getFirst().getQuestionId();

            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "submissionId", sub.getId().toString(),
                    "answers", "[{\"questionId\":\"" + questionId + "\",\"selectedAnswer\":\"A\"}]",
                    "timeSpentSeconds", 60));

            mockMvc.perform(post("/api/v1/learner/assignments/submit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("GRADED"))
                    .andExpect(jsonPath("$.data.score").value(100.00))
                    .andExpect(jsonPath("$.data.correctCount").value(1));
        }

        @Test
        @DisplayName("TC_AS_L_DO_007 — Submit Quiz đáp án sai → GRADED, score=0")
        void tc_as_l_do_007() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedQuiz(teacher, clazz);
            AssignmentSubmission sub = startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("learner@test.com");

            UUID questionId = assignmentQuestionRepository
                    .findByAssignmentIdOrderByOrderIndex(a.getId()).getFirst().getQuestionId();

            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "submissionId", sub.getId().toString(),
                    "answers", "[{\"questionId\":\"" + questionId + "\",\"selectedAnswer\":\"B\"}]",
                    "timeSpentSeconds", 30));

            mockMvc.perform(post("/api/v1/learner/assignments/submit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("GRADED"))
                    .andExpect(jsonPath("$.data.score").value(0.00))
                    .andExpect(jsonPath("$.data.correctCount").value(0));
        }

        @Test
        @DisplayName("TC_AS_L_DO_008 — Submit Quiz quá hạn → OVERDUE (assuming allowed but marked late)")
        void tc_as_l_do_008() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedQuiz(teacher, clazz);
            // Simulate assignment overdue
            a.setDueDate(LocalDateTime.now().minusHours(1));
            assignmentRepository.save(a);

            AssignmentSubmission sub = startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("learner@test.com");

            UUID questionId = assignmentQuestionRepository
                    .findByAssignmentIdOrderByOrderIndex(a.getId()).getFirst().getQuestionId();

            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "submissionId", sub.getId().toString(),
                    "answers", "[{\"questionId\":\"" + questionId + "\",\"selectedAnswer\":\"A\"}]",
                    "timeSpentSeconds", 60));

            // Assuming system allows late submission but marks it OVERDUE, or rejects (400)
            // Adjust expectation based on actual logic. Here we assume success with status OVERDUE
            mockMvc.perform(post("/api/v1/learner/assignments/submit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("OVERDUE"));
        }

        @Test
        @DisplayName("TC_AS_L_DO_009 — Submit Writing đúng hạn → SUBMITTED (chờ teacher chấm)")
        void tc_as_l_do_009() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedWriting(teacher, clazz);
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("learner@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    "{\"writtenAnswer\":\"私は学生です。\",\"timeSpentSeconds\":120}".getBytes());

            mockMvc.perform(multipart("/api/v1/learner/assignments/{id}/submit-writing", a.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                    .andExpect(jsonPath("$.data.writtenAnswer").value("私は学生です。"));
        }

        @Test
        @DisplayName("TC_AS_L_DO_010 — Submit Writing quá hạn → OVERDUE")
        void tc_as_l_do_010() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedWriting(teacher, clazz);
            // Simulate assignment overdue
            a.setDueDate(LocalDateTime.now().minusHours(1));
            assignmentRepository.save(a);

            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("learner@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    "{\"writtenAnswer\":\"Late answer.\",\"timeSpentSeconds\":120}".getBytes());

            mockMvc.perform(multipart("/api/v1/learner/assignments/{id}/submit-writing", a.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("OVERDUE"));
        }

        @Test
        @DisplayName("TC_AS_L_DO_011 — Submit Writing thiếu cả text lẫn image → 400")
        void tc_as_l_do_011() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedWriting(teacher, clazz);
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("learner@test.com");

            // writtenAnswer blank, no image
            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    "{\"writtenAnswer\":\"\",\"timeSpentSeconds\":10}".getBytes());

            mockMvc.perform(multipart("/api/v1/learner/assignments/{id}/submit-writing", a.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_L_DO_012 — Teacher gọi endpoint Learner submit → 403")
        void tc_as_l_do_012() throws Exception {
            saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(post("/api/v1/learner/assignments/submit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"submissionId\":\"" + UUID.randomUUID() + "\",\"answers\":\"[]\"}")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2.3 View Practice Task Results (Learner)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("2.3 View Practice Task Results (Learner)")
    class LearnerViewResults {

        @Test
        @DisplayName("TC_AS_L_VS_001 — Xem submission của chính mình → thành công")
        void tc_as_l_vs_001() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedQuiz(teacher, clazz);
            AssignmentSubmission sub = startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments/submissions/{id}", sub.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(sub.getId().toString()))
                    .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        }

        @Test
        @DisplayName("TC_AS_L_VS_002 — Learner B xem submission của Learner A → 400 (ACCESS_DENIED)")
        void tc_as_l_vs_002() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learnerA = saveLearner("learnerA@test.com");
            User learnerB = saveLearner("learnerB@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learnerA.getId());
            enrollLearner(clazz.getId(), learnerB.getId());
            Assignment a = savePublishedQuiz(teacher, clazz);
            AssignmentSubmission subA = startSubmission(a.getId(), learnerA.getId());
            String tokenB = loginAndGetToken("learnerB@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments/submissions/{id}", subA.getId())
                            .header("Authorization", "Bearer " + tokenB))
                    .andExpect(status().isBadRequest()); // BusinessException → 400
        }

        @Test
        @DisplayName("TC_AS_L_VS_003 — Submission SUBMITTED (chưa chấm) → status=SUBMITTED, score=null")
        void tc_as_l_vs_003() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedWriting(teacher, clazz);
            AssignmentSubmission sub = submissionRepository.save(AssignmentSubmission.builder()
                    .assignmentId(a.getId()).userId(learner.getId())
                    .status(SubmissionStatus.SUBMITTED).attemptNumber(1)
                    .writtenAnswer("私は学生です。")
                    .startedAt(LocalDateTime.now().minusMinutes(10))
                    .submittedAt(LocalDateTime.now().minusMinutes(1))
                    .correctCount(0).totalQuestions(1).timeSpentSeconds(600)
                    .answers("[]").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build());
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments/submissions/{id}", sub.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                    .andExpect(jsonPath("$.data.score").doesNotExist());
        }

        @Test
        @DisplayName("TC_AS_L_VS_004 — Submission OVERDUE → status=OVERDUE")
        void tc_as_l_vs_004() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedQuiz(teacher, clazz);
            AssignmentSubmission sub = submissionRepository.save(AssignmentSubmission.builder()
                    .assignmentId(a.getId()).userId(learner.getId())
                    .status(SubmissionStatus.OVERDUE).attemptNumber(1)
                    .startedAt(LocalDateTime.now().minusDays(8))
                    .submittedAt(LocalDateTime.now().minusDays(1))
                    .correctCount(0).totalQuestions(1).timeSpentSeconds(60)
                    .answers("[]").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build());
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments/submissions/{id}", sub.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("OVERDUE"));
        }

        @Test
        @DisplayName("TC_AS_L_VS_005 — Submission không tồn tại → 404")
        void tc_as_l_vs_005() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments/submissions/{id}", UUID.randomUUID())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3. AI INTEGRATION (MOCKED)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. AI Integration (Mocked)")
    class AiIntegrationTests {

        @Test
        @DisplayName("TC_AS_AI_001 — AI generate questions → returns mocked list")
        void tc_as_ai_001() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            String token = loginAndGetToken("teacher@test.com");

            when(quizAiGenerationService.generateQuestions(any(AiQuizGenerationRequest.class)))
                    .thenReturn(List.of(QuizQuestionResponse.builder()
                            .questionText("どれが正しいですか？")
                            .questionType("multiple_choice")
                            .build()));

            String body = objectMapper.writeValueAsString(AiQuizGenerationRequest.builder()
                    .teacherPrompt("N5 verb conjugation")
                    .classId(clazz.getId())
                    .questionCount(1)
                    .questionTypes(List.of("multiple_choice"))
                    .build());

            mockMvc.perform(post("/api/v1/teacher/assignments/questions/ai-generate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].questionText").value("どれが正しいですか？"));
        }

        @Test
        @DisplayName("TC_AS_AI_002 — AI generate explanation for existing question")
        void tc_as_ai_002() throws Exception {
            saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com");

            QuizQuestion q = quizQuestionRepository.save(QuizQuestion.builder()
                    .questionText("テスト問題").questionType("multiple_choice")
                    .options("[{\"id\":\"A\",\"text\":\"はい\",\"isCorrect\":true}]")
                    .status(Course.ContentStatus.PUBLISHED)
                    .aiGenerated(false).difficultyScore(1)
                    .build());

            when(quizExplanationService.generateExplanation(any(UUID.class), any(UUID.class)))
                    .thenReturn(q);

            mockMvc.perform(post("/api/v1/teacher/assignments/questions/{id}/ai-explain", q.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(q.getId().toString()));
        }

        @Test
        @DisplayName("TC_AS_AI_003 — AI generate questions thiếu teacherPrompt → 400 (validation)")
        void tc_as_ai_003() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            String token = loginAndGetToken("teacher@test.com");

            // Missing teacherPrompt — @NotNull triggers
            String body = objectMapper.writeValueAsString(AiQuizGenerationRequest.builder()
                    .classId(clazz.getId())
                    .questionCount(1)
                    .build());

            mockMvc.perform(post("/api/v1/teacher/assignments/questions/ai-generate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_AI_004 — Learner gọi endpoint ai-generate → 400")
        void tc_as_ai_004() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(post("/api/v1/teacher/assignments/questions/ai-generate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }
    }
}
