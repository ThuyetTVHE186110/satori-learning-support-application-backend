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
import com.fu.common.exception.BusinessException;
import com.fu.course.entity.Course;
import com.fu.course.entity.Course.CourseStatus;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.repository.CourseRepository;
import com.fu.common.exception.ResourceNotFoundException;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fu.infrastructure.cloudinary.CloudinaryService;

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
    @MockBean private CloudinaryService cloudinaryService;

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
                .questionCount(0)
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
                .questionCount(1)
                .status(AssignmentStatus.PUBLISHED)
                .hasSubmissions(false)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
        targetRepository.save(AssignmentTarget.builder()
                .assignmentId(a.getId()).classId(clazz.getId()).build());
        QuizQuestion q = quizQuestionRepository.save(QuizQuestion.builder()
                .questionText("テストの問題").questionType("multiple_choice")
                .options("[{\"id\":\"A\",\"text\":\"正しい\",\"isCorrect\":true},{\"id\":\"B\",\"text\":\"間違い\",\"isCorrect\":false}]")
                .correctAnswer("A")
                .aiGenerated(false)
                .build());
        assignmentQuestionRepository.save(AssignmentQuestion.builder()
                .assignmentId(a.getId()).questionId(q.getId())
                .orderIndex(0).build());
        return a;
    }

    /** Save a PUBLISHED WRITING assignment. */
    private Assignment savePublishedWriting(User teacher, Clazz clazz) {
        Assignment a = assignmentRepository.save(Assignment.builder()
                .title("Published Writing").description("desc")
                .assignmentType(AssignmentType.WRITING)
                .classId(clazz.getId()).createdBy(teacher.getId())
                .dueDate(LocalDateTime.now().plusDays(7))
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
              "correctAnswer": "A",
              "explanation": "The correct answer is A.",
              "explanationVi": "Đáp án đúng là A.",
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
                    .andExpect(status().isNotFound());
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
                    .andExpect(status().isBadRequest());
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

        @Test
        @DisplayName("TC_AS_T_VL_006 — Get My Assignments (tất cả class) thành công")
        void tc_as_t_vl_006() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz1 = saveClass(teacher, saveCourse());
            Clazz clazz2 = saveClass(teacher, saveCourse());
            savePublishedQuiz(teacher, clazz1);
            savePublishedWriting(teacher, clazz2);
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(2));
        }

        @Test
        @DisplayName("TC_AS_T_VL_007 — Filter by title")
        void tc_as_t_vl_007() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            savePublishedWriting(teacher, clazz); // another one
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", clazz.getId())
                            .param("title", "Published Quiz")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(a.getId().toString()));
        }

        @Test
        @DisplayName("TC_AS_T_VL_007b — Filter by type")
        void tc_as_t_vl_007b() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            savePublishedQuiz(teacher, clazz);
            Assignment a = savePublishedWriting(teacher, clazz);
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", clazz.getId())
                            .param("type", "WRITING")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(a.getId().toString()));
        }

        @Test
        @DisplayName("TC_AS_T_VL_007c — Filter by status")
        void tc_as_t_vl_007c() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = saveQuizDraft(teacher, clazz); // DRAFT
            savePublishedQuiz(teacher, clazz); // PUBLISHED
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", clazz.getId())
                            .param("status", "DRAFT")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(a.getId().toString()));
        }

        @Test
        @DisplayName("TC_AS_T_VL_007d — Pagination test (page/size)")
        void tc_as_t_vl_007d() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            savePublishedQuiz(teacher, clazz);
            savePublishedWriting(teacher, clazz);
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", clazz.getId())
                            .param("page", "1")
                            .param("size", "1")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.pageNumber").value(1))
                    .andExpect(jsonPath("$.data.pageSize").value(1))
                    .andExpect(jsonPath("$.data.totalElements").value(2));
        }

        @Test
        @DisplayName("TC_AS_T_VL_008 — Lỗi TypeMismatch khi truyền UUID sai format")
        void tc_as_t_vl_008() throws Exception {
            saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", "invalid-uuid")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_VL_009 — Lỗi ConstraintViolation khi page = -1")
        void tc_as_t_vl_009() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", clazz.getId())
                            .param("page", "-1")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_VL_010 — Lỗi ConstraintViolation khi size = 0")
        void tc_as_t_vl_010() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", clazz.getId())
                            .param("size", "0")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_VL_011 — Lỗi MethodArgumentTypeMismatch khi type sai Enum")
        void tc_as_t_vl_011() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", clazz.getId())
                            .param("type", "UNKNOWN_TYPE")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_VL_012 — Lỗi 401 Unauthorized do không truyền Header Authorization")
        void tc_as_t_vl_012() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());

            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", clazz.getId()))
                    .andExpect(status().isUnauthorized());
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
                    .andExpect(jsonPath("$.success").value(true)) // ApiResponse wrapper
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
                    .andExpect(status().isBadRequest());
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

        @Test
        @DisplayName("TC_AS_T_VI_006 — Lỗi TypeMismatch khi truyền UUID sai format")
        void tc_as_t_vi_006() throws Exception {
            saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/{id}", "invalid-uuid")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
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
                  "status": "DRAFT",
                  "prompt": "Translate this sentence."
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
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TC_AS_T_CR_006 — Tạo QUIZ thành công với instructions")
        void tc_as_t_cr_006() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    """
                    {
                      "title": "Quiz With Instructions",
                      "instructions": "Read carefully.",
                      "dueDate": "%s",
                      "status": "DRAFT",
                      "questions": []
                    }
                    """.formatted(LocalDateTime.now().plusDays(7)).getBytes());

            mockMvc.perform(multipart("/api/v1/teacher/assignments/class/{classId}/quiz", clazz.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.instructions").value("Read carefully."));
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
        @DisplayName("TC_AS_T_CR_008 — Teacher B tạo vào class của Teacher A → 403 (ACCESS_DENIED)")
        void tc_as_t_cr_008() throws Exception {
            User teacherA = saveTeacher("teacherA@test.com");
            saveTeacher("teacherB@test.com");
            Clazz clazzA = saveClass(teacherA, saveCourse());
            String tokenB = loginAndGetToken("teacherB@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    quizRequestJson(AssignmentStatus.DRAFT, null).getBytes());

            mockMvc.perform(
                            multipart("/api/v1/teacher/assignments/class/{classId}/quiz", clazzA.getId())
                                    .file(dataPart)
                                    .header("Authorization", "Bearer " + tokenB))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_CR_009 — Tạo QUIZ thành công đính kèm audio file")
        void tc_as_t_cr_009() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    quizRequestJson(AssignmentStatus.DRAFT, null).getBytes());
            MockMultipartFile audioPart = new MockMultipartFile(
                    "audioFile", "test.mp3", "audio/mpeg", "dummy audio content".getBytes());

            org.mockito.Mockito.when(cloudinaryService.uploadAudio(any(), any())).thenReturn("http://mock-audio-url.com/a.mp3");

            mockMvc.perform(multipart("/api/v1/teacher/assignments/class/{classId}/quiz", clazz.getId())
                            .file(dataPart)
                            .file(audioPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.assignmentType").value("QUIZ"));
        }

        @Test
        @DisplayName("TC_AS_T_CR_010 — Tạo WRITING thiếu title → validation error")
        void tc_as_t_cr_010() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            String token = loginAndGetToken("teacher@test.com");

            String body = """
                {
                  "dueDate": "%s",
                  "status": "DRAFT",
                  "prompt": "Translate this sentence."
                }
                """.formatted(LocalDateTime.now().plusDays(7));

            mockMvc.perform(post("/api/v1/teacher/assignments/class/{classId}/writing", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_CR_011 — Lỗi HttpMessageNotReadable do dueDate parse fail")
        void tc_as_t_cr_011() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            String token = loginAndGetToken("teacher@test.com");

            String body = """
                {
                  "title": "Bad Date Quiz",
                  "dueDate": "abc",
                  "status": "DRAFT",
                  "prompt": "Translate this sentence."
                }
                """;

            mockMvc.perform(post("/api/v1/teacher/assignments/class/{classId}/writing", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
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
        @DisplayName("TC_AS_T_UP_007 — Cập nhật instructions khi hasSubmissions=true → thành công")
        void tc_as_t_up_007() throws Exception {
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
                      "instructions": "Do not use dictionary.",
                      "dueDate": "%s",
                      "status": "PUBLISHED",
                      "questions": []
                    }
                    """.formatted(LocalDateTime.now().plusDays(7)).getBytes());

            mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT,
                            "/api/v1/teacher/assignments/{id}/quiz", a.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.instructions").value("Do not use dictionary."));
        }

        @Test
        @DisplayName("TC_AS_T_UP_008 — Update dueDate vào quá khứ → 400 (INVALID_DUE_DATE)")
        void tc_as_t_up_008() throws Exception {
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
                      "status": "PUBLISHED",
                      "questions": []
                    }
                    """.formatted(LocalDateTime.now().minusDays(1)).getBytes());

            mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT,
                            "/api/v1/teacher/assignments/{id}/quiz", a.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_UP_009 — Update WRITING thành công")
        void tc_as_t_up_009() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedWriting(teacher, clazz);
            String token = loginAndGetToken("teacher@test.com");

            String body = """
                {
                  "title": "Updated Writing",
                  "dueDate": "%s",
                  "status": "PUBLISHED",
                  "prompt": "Updated prompt."
                }
                """.formatted(LocalDateTime.now().plusDays(10));

            mockMvc.perform(put("/api/v1/teacher/assignments/{id}/writing", a.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("Updated Writing"));
        }

        @Test
        @DisplayName("TC_AS_T_UP_010 — Update TRANSLATION thành công")
        void tc_as_t_up_010() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedWriting(teacher, clazz);
            a.setAssignmentType(AssignmentType.TRANSLATION);
            assignmentRepository.save(a);
            String token = loginAndGetToken("teacher@test.com");

            String body = """
                {
                  "title": "Updated Translation",
                  "dueDate": "%s",
                  "status": "PUBLISHED",
                  "prompt": "Updated translation prompt.",
                  "sourceText": "Translate this."
                }
                """.formatted(LocalDateTime.now().plusDays(10));

            mockMvc.perform(put("/api/v1/teacher/assignments/{id}/translation", a.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("Updated Translation"));
        }

        @Test
        @DisplayName("TC_AS_T_UP_011 — Update writing prompt khi có submissions → 400 HAS_SUBMISSIONS")
        void tc_as_t_up_011() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedWriting(teacher, clazz);
            enrollLearner(clazz.getId(), learner.getId());
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("teacher@test.com");

            String body = """
                {
                  "title": "Published Writing",
                  "dueDate": "%s",
                  "status": "PUBLISHED",
                  "prompt": "Completely new prompt that is different."
                }
                """.formatted(LocalDateTime.now().plusDays(7));

            mockMvc.perform(put("/api/v1/teacher/assignments/{id}/writing", a.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
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

        @Test
        @DisplayName("TC_AS_T_VS_007 — Xem list submissions có phân trang")
        void tc_as_t_vs_007() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            enrollLearner(clazz.getId(), learner.getId());
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/{id}/submissions", a.getId())
                            .param("page", "1")
                            .param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1.8 Grade Writing Submissions (Teacher)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1.8 Grade Writing Submissions (Teacher)")
    class GradeWritingSubmissions {

        @Test
        @DisplayName("TC_AS_T_GR_001 — Teacher chấm điểm bài Writing hợp lệ → thành công, status GRADED")
        void tc_as_t_gr_001() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedWriting(teacher, clazz);
            AssignmentSubmission sub = submissionRepository.save(AssignmentSubmission.builder()
                    .assignmentId(a.getId()).userId(learner.getId())
                    .status(SubmissionStatus.SUBMITTED).attemptNumber(1)
                    .writtenAnswer("Tôi là sinh viên.")
                    .startedAt(LocalDateTime.now().minusMinutes(10))
                    .submittedAt(LocalDateTime.now().minusMinutes(1))
                    .correctCount(0).totalQuestions(1).timeSpentSeconds(600)
                    .answers("[]").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build());
            String token = loginAndGetToken("teacher@test.com");

            String body = """
                    {
                      "teacherScore": 85.5,
                      "feedback": "Bài viết rất tốt."
                    }
                    """;

            mockMvc.perform(post("/api/v1/teacher/assignments/submissions/{id}/grade-writing", sub.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("GRADED"))
                    .andExpect(jsonPath("$.data.score").value(85.5))
                    .andExpect(jsonPath("$.data.feedback").value("Bài viết rất tốt."));
        }
        
        @Test
        @DisplayName("TC_AS_T_GR_002 — Grade QUIZ submission → 400 (INVALID_TYPE)")
        void tc_as_t_gr_002() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedQuiz(teacher, clazz);
            AssignmentSubmission sub = submissionRepository.save(AssignmentSubmission.builder()
                    .assignmentId(a.getId()).userId(learner.getId())
                    .status(SubmissionStatus.SUBMITTED).attemptNumber(1)
                    .startedAt(LocalDateTime.now().minusMinutes(10))
                    .submittedAt(LocalDateTime.now().minusMinutes(1))
                    .correctCount(0).totalQuestions(1).timeSpentSeconds(600)
                    .answers("[]").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build());
            String token = loginAndGetToken("teacher@test.com");

            String body = """
                    {
                      "teacherScore": 85.5,
                      "feedback": "Feedback."
                    }
                    """;

            mockMvc.perform(post("/api/v1/teacher/assignments/submissions/{id}/grade-writing", sub.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_GR_003 — Grade submission IN_PROGRESS → 400 INVALID_STATUS")
        void tc_as_t_gr_003() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedWriting(teacher, clazz);
            AssignmentSubmission sub = startSubmission(a.getId(), learner.getId()); // IN_PROGRESS
            String token = loginAndGetToken("teacher@test.com");

            String body = """
                    { "teacherScore": 80.0, "feedback": "Good." }
                    """;

            mockMvc.perform(post("/api/v1/teacher/assignments/submissions/{id}/grade-writing", sub.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_GR_004 — Grade submission đã GRADED → 400 INVALID_STATUS")
        void tc_as_t_gr_004() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedWriting(teacher, clazz);
            AssignmentSubmission sub = submissionRepository.save(AssignmentSubmission.builder()
                    .assignmentId(a.getId()).userId(learner.getId())
                    .status(SubmissionStatus.GRADED).attemptNumber(1)
                    .score(BigDecimal.valueOf(80))
                    .writtenAnswer("Answer.")
                    .startedAt(LocalDateTime.now().minusMinutes(10))
                    .submittedAt(LocalDateTime.now().minusMinutes(5))
                    .gradedAt(LocalDateTime.now().minusMinutes(1))
                    .correctCount(0).totalQuestions(1).timeSpentSeconds(600)
                    .answers("[]").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build());
            String token = loginAndGetToken("teacher@test.com");

            String body = """
                    { "teacherScore": 90.0, "feedback": "Better." }
                    """;

            mockMvc.perform(post("/api/v1/teacher/assignments/submissions/{id}/grade-writing", sub.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_GR_005 — Teacher B chấm bài class Teacher A → 400 ACCESS_DENIED")
        void tc_as_t_gr_005() throws Exception {
            User teacherA = saveTeacher("teacherA@test.com");
            saveTeacher("teacherB@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacherA, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedWriting(teacherA, clazz);
            AssignmentSubmission sub = submissionRepository.save(AssignmentSubmission.builder()
                    .assignmentId(a.getId()).userId(learner.getId())
                    .status(SubmissionStatus.SUBMITTED).attemptNumber(1)
                    .writtenAnswer("test answer")
                    .startedAt(LocalDateTime.now().minusMinutes(10))
                    .submittedAt(LocalDateTime.now().minusMinutes(1))
                    .correctCount(0).totalQuestions(1).timeSpentSeconds(600)
                    .answers("[]").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build());
            String tokenB = loginAndGetToken("teacherB@test.com");

            String body = """
                    { "teacherScore": 70, "feedback": "OK." }
                    """;

            mockMvc.perform(post("/api/v1/teacher/assignments/submissions/{id}/grade-writing", sub.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + tokenB))
                    .andExpect(status().isBadRequest());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1.9 Class Statistics (Teacher)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1.9 Class Statistics (Teacher)")
    class ClassStatisticsTeacher {
        
        @Test
        @DisplayName("TC_AS_T_STAT_001 — Get statistics thành công")
        void tc_as_t_stat_001() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}/statistics", clazz.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalAssignments").exists());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1.10 Clone Practice Task (Teacher)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1.10 Clone Practice Task")
    class ClonePracticeTask {

        @Test
        @DisplayName("TC_AS_T_CL_001 — Clone assignment sang class khác thành công")
        void tc_as_t_cl_001() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz sourceClass = saveClass(teacher, saveCourse());
            Clazz destClass = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, sourceClass);
            String token = loginAndGetToken("teacher@test.com");

            String body = """
                {
                  "targetClassId": "%s",
                  "newDueDate": "%s",
                  "newTitle": "Cloned Quiz"
                }
                """.formatted(destClass.getId(), LocalDateTime.now().plusDays(10));

            mockMvc.perform(post("/api/v1/teacher/assignments/{id}/clone", a.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("Cloned Quiz"))
                    .andExpect(jsonPath("$.data.status").value("DRAFT"));
        }

        @Test
        @DisplayName("TC_AS_T_CL_002 — Clone sang class không tồn tại → 404")
        void tc_as_t_cl_002() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            String token = loginAndGetToken("teacher@test.com");

            String body = """
                { "targetClassId": "%s", "newDueDate": "%s" }
                """.formatted(UUID.randomUUID(), LocalDateTime.now().plusDays(10));

            mockMvc.perform(post("/api/v1/teacher/assignments/{id}/clone", a.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TC_AS_T_CL_003 — Clone WRITING assignment → thành công, status DRAFT")
        void tc_as_t_cl_003() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz sourceClass = saveClass(teacher, saveCourse());
            Clazz destClass = saveClass(teacher, saveCourse());
            Assignment a = savePublishedWriting(teacher, sourceClass);
            String token = loginAndGetToken("teacher@test.com");

            String body = """
                { "targetClassId": "%s", "newDueDate": "%s", "newTitle": "Cloned Writing" }
                """.formatted(destClass.getId(), LocalDateTime.now().plusDays(10));

            mockMvc.perform(post("/api/v1/teacher/assignments/{id}/clone", a.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("Cloned Writing"))
                    .andExpect(jsonPath("$.data.assignmentType").value("WRITING"))
                    .andExpect(jsonPath("$.data.status").value("DRAFT"));
        }

        @Test
        @DisplayName("TC_AS_T_CL_004 — Teacher B clone assignment Teacher A → 400 ACCESS_DENIED")
        void tc_as_t_cl_004() throws Exception {
            User teacherA = saveTeacher("teacherA@test.com");
            saveTeacher("teacherB@test.com");
            Clazz clazz = saveClass(teacherA, saveCourse());
            Assignment a = savePublishedQuiz(teacherA, clazz);
            String tokenB = loginAndGetToken("teacherB@test.com");

            String body = """
                { "targetClassId": "%s", "newDueDate": "%s" }
                """.formatted(clazz.getId(), LocalDateTime.now().plusDays(10));

            mockMvc.perform(post("/api/v1/teacher/assignments/{id}/clone", a.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + tokenB))
                    .andExpect(status().isBadRequest());
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
                    .andExpect(jsonPath("$.data.content").isArray()) // ApiResponse PageResponse
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

        @Test
        @DisplayName("TC_AS_L_VL_006 — Filter by title")
        void tc_as_l_vl_006() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedQuiz(teacher, clazz);
            savePublishedWriting(teacher, clazz); // another one
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments")
                            .param("title", "Published Quiz")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(a.getId().toString()));
        }

        @Test
        @DisplayName("TC_AS_L_VL_006b — Filter by type")
        void tc_as_l_vl_006b() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            savePublishedQuiz(teacher, clazz);
            Assignment a = savePublishedWriting(teacher, clazz);
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments")
                            .param("type", "WRITING")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(a.getId().toString()));
        }

        @Test
        @DisplayName("TC_AS_L_VL_006c — Pagination test (page/size)")
        void tc_as_l_vl_006c() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            savePublishedQuiz(teacher, clazz);
            savePublishedWriting(teacher, clazz);
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments")
                            .param("page", "1")
                            .param("size", "1")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.pageNumber").value(1))
                    .andExpect(jsonPath("$.data.pageSize").value(1))
                    .andExpect(jsonPath("$.data.totalElements").value(2));
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
        @DisplayName("TC_AS_L_DO_004 — Đã có >=2 attempts → 400 (NO_RETRY)")
        void tc_as_l_do_004() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            enrollLearner(clazz.getId(), learner.getId());
            for (int i = 1; i <= 2; i++) {
                submissionRepository.save(AssignmentSubmission.builder()
                        .assignmentId(a.getId()).userId(learner.getId())
                        .status(SubmissionStatus.GRADED).attemptNumber(i)
                        .startedAt(LocalDateTime.now().minusMinutes(20))
                        .submittedAt(LocalDateTime.now().minusMinutes(10))
                        .correctCount(1).totalQuestions(1).timeSpentSeconds(60)
                        .answers("[]").score(BigDecimal.valueOf(10))
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
        @DisplayName("TC_AS_L_DO_006 — Submit Quiz đúng hạn, đáp án đúng → GRADED, score=10")
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
                    .andExpect(jsonPath("$.data.score").value(10.00))
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
            startSubmission(a.getId(), learner.getId());
            // Simulate assignment overdue
            a.setDueDate(LocalDateTime.now().minusHours(1));
            assignmentRepository.save(a);
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

        @Test
        @DisplayName("TC_AS_L_DO_013 — Start assignment khi đang CLOSED → 400 (ASSIGNMENT_NOT_PUBLISHED)")
        void tc_as_l_do_013() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedQuiz(teacher, clazz);
            a.setStatus(AssignmentStatus.CLOSED);
            assignmentRepository.save(a);
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(post("/api/v1/learner/assignments/{id}/start", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_L_DO_018 — Start assignment quá due date → 400 PAST_DUE")
        void tc_as_l_do_018() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedQuiz(teacher, clazz);
            a.setDueDate(LocalDateTime.now().minusHours(1));
            assignmentRepository.save(a);
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(post("/api/v1/learner/assignments/{id}/start", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_L_DO_014 — Submit assignment khi đang CLOSED → vẫn chấm GRADED")
        void tc_as_l_do_014() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedQuiz(teacher, clazz);
            AssignmentSubmission sub = startSubmission(a.getId(), learner.getId());
            a.setStatus(AssignmentStatus.CLOSED);
            assignmentRepository.save(a);
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
                    .andExpect(jsonPath("$.data.score").value(10.00));
        }

        @Test
        @DisplayName("TC_AS_L_DO_015 — Submit WRITING assignment đính kèm ảnh → SUBMITTED")
        void tc_as_l_do_015() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedWriting(teacher, clazz);
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("learner@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    "{\"writtenAnswer\":\"私の答え\",\"timeSpentSeconds\":120}".getBytes());
            MockMultipartFile imagePart = new MockMultipartFile(
                    "images", "test.png", "image/png", "dummy image".getBytes());

            org.mockito.Mockito.when(cloudinaryService.uploadImage(any(), any())).thenReturn("http://mock-image-url.com/img.png");

            mockMvc.perform(multipart("/api/v1/learner/assignments/{id}/submit-writing", a.getId())
                            .file(dataPart)
                            .file(imagePart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SUBMITTED"));
        }

        @Test
        @DisplayName("TC_AS_L_DO_016 — Submit QUIZ answers bị lỗi JSON format → 400")
        void tc_as_l_do_016() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedQuiz(teacher, clazz);
            AssignmentSubmission sub = startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("learner@test.com");

            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "submissionId", sub.getId().toString(),
                    "answers", "invalid-json-array",
                    "timeSpentSeconds", 60));

            mockMvc.perform(post("/api/v1/learner/assignments/submit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_L_DO_017 — Learner submits quiz for WRITING assignment → 400")
        void tc_as_l_do_017() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedWriting(teacher, clazz);
            AssignmentSubmission sub = startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("learner@test.com");

            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "submissionId", sub.getId().toString(),
                    "answers", "[]",
                    "timeSpentSeconds", 60));

            mockMvc.perform(post("/api/v1/learner/assignments/submit")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
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

        @Test
        @DisplayName("TC_AS_L_VS_006 — Submission GRADED (đã chấm) → status=GRADED, có score & feedback")
        void tc_as_l_vs_006() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedWriting(teacher, clazz);
            AssignmentSubmission sub = submissionRepository.save(AssignmentSubmission.builder()
                    .assignmentId(a.getId()).userId(learner.getId())
                    .status(SubmissionStatus.GRADED).attemptNumber(1)
                    .writtenAnswer("私の答えです。")
                    .startedAt(LocalDateTime.now().minusDays(2))
                    .submittedAt(LocalDateTime.now().minusDays(1))
                    .gradedAt(LocalDateTime.now())
                    .score(BigDecimal.valueOf(95.5))
                    .feedback("よくできました！")
                    .correctCount(0).totalQuestions(1).timeSpentSeconds(300)
                    .answers("[]").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build());
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments/submissions/{id}", sub.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("GRADED"))
                    .andExpect(jsonPath("$.data.score").value(95.5))
                    .andExpect(jsonPath("$.data.feedback").value("よくできました！"));
        }

        @Test
        @DisplayName("TC_AS_L_VS_007 — Get My Submissions History thành công")
        void tc_as_l_vs_007() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedQuiz(teacher, clazz);
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments/submissions")
                            .param("page", "1")
                            .param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2.4 View Practice Task Info (Learner)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("2.4 View Practice Task Info (Learner)")
    class LearnerViewAssignmentInfo {

        @Test
        @DisplayName("TC_AS_L_VI_001 — Get Assignment Details thành công")
        void tc_as_l_vi_001() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedQuiz(teacher, clazz);
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments/{id}", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(a.getId().toString()))
                    .andExpect(jsonPath("$.data.questions[0].correctAnswer").doesNotExist());
        }

        @Test
        @DisplayName("TC_AS_L_VI_002 — UUID sai định dạng → 400")
        void tc_as_l_vi_002() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments/{id}", "invalid-uuid")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_L_VI_003 — Learner xem DRAFT assignment → 400 ASSIGNMENT_NOT_AVAILABLE")
        void tc_as_l_vi_003() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = saveQuizDraft(teacher, clazz);
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments/{id}", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_L_VI_004 — Learner không thuộc class → 400 ACCESS_DENIED")
        void tc_as_l_vi_004() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            // KHÔNG enroll learner
            Assignment a = savePublishedQuiz(teacher, clazz);
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments/{id}", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
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
                    .aiGenerated(false)
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
        @DisplayName("TC_AS_AI_004 — Learner gọi endpoint ai-generate → 403")
        void tc_as_ai_004() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(post("/api/v1/teacher/assignments/questions/ai-generate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_AI_005 — Không có token → 401")
        void tc_as_ai_005() throws Exception {
            mockMvc.perform(post("/api/v1/teacher/assignments/questions/ai-generate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("TC_AS_AI_006 — Explain question không tồn tại → 404")
        void tc_as_ai_006() throws Exception {
            saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com");

            // Mock the service to throw ResourceNotFoundException, simulating the real service behavior
            when(quizExplanationService.generateExplanation(any(UUID.class), any(UUID.class)))
                    .thenThrow(new ResourceNotFoundException("Quiz question not found"));

            mockMvc.perform(post("/api/v1/teacher/assignments/questions/{id}/ai-explain",
                            UUID.randomUUID())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }
    }
    @Test
    @DisplayName("TC_AS_AI_007 — ai-explain lưu explanation vào DB")
    void tc_as_ai_007() throws Exception {
        saveTeacher("teacher@test.com");
        String token = loginAndGetToken("teacher@test.com");

        QuizQuestion q = quizQuestionRepository.save(QuizQuestion.builder()
                .questionText("テスト問題")
                .questionType("multiple_choice")
                .options("[{\"id\":\"A\",\"text\":\"はい\",\"isCorrect\":true}]")
                .correctAnswer("A")
                .explanation(null)
                .aiGenerated(false)
                .build());

        when(quizExplanationService.generateExplanation(eq(q.getId()), any(UUID.class)))
                .thenAnswer(invocation -> {
                    q.setExplanation("Đáp án đúng là A vì...");
                    return quizQuestionRepository.save(q);
                });

        mockMvc.perform(post("/api/v1/teacher/assignments/questions/{id}/ai-explain", q.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.explanation").isNotEmpty());

        QuizQuestion updated = quizQuestionRepository.findById(q.getId()).orElseThrow();
        assertThat(updated.getExplanation()).isNotBlank();
    }

    @Test
    @DisplayName("TC_AS_AI_008 — AI explain draft")
    void tc_as_ai_008() throws Exception {
        saveTeacher("teacher@test.com");
        String token = loginAndGetToken("teacher@test.com");
        
        when(quizExplanationService.generateExplanationFromDraft(any()))
                .thenReturn(new com.fu.assignment.dto.response.AiExplanationResponse("Draft explanation text..."));

        String body = """
            {
              "questionText": "テスト",
              "questionType": "multiple_choice",
              "options": "A, B",
              "correctAnswer": "A"
            }
            """;

        mockMvc.perform(post("/api/v1/teacher/assignments/questions/ai-explain-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.explanation").value("Draft explanation text..."));
    }

    @Test
    @DisplayName("TC_AS_AI_009 — AI provider throws exception → 400")
    void tc_as_ai_009() throws Exception {
        saveTeacher("teacher@test.com");
        String token = loginAndGetToken("teacher@test.com");

        when(quizExplanationService.generateExplanationFromDraft(any()))
                .thenThrow(new BusinessException("AI_RESPONSE_ERROR", "Failed to call AI: timeout"));

        String body = """
        {
          "questionText": "テスト",
          "questionType": "multiple_choice",
          "options": "A, B",
          "correctAnswer": "A"
        }
        """;

        mockMvc.perform(post("/api/v1/teacher/assignments/questions/ai-explain-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("TC_AS_AI_010 — AI generate: service throw BusinessException → 400")
    void tc_as_ai_010() throws Exception {
        User teacher = saveTeacher("teacher@test.com");
        Clazz clazz = saveClass(teacher, saveCourse());
        String token = loginAndGetToken("teacher@test.com");

        when(quizAiGenerationService.generateQuestions(any(AiQuizGenerationRequest.class)))
                .thenThrow(new com.fu.common.exception.BusinessException(
                        "AI_PARSE_ERROR", "Failed to parse AI response."));

        String body = objectMapper.writeValueAsString(AiQuizGenerationRequest.builder()
                .teacherPrompt("N5 vocab")
                .classId(clazz.getId())
                .questionCount(1)
                .questionTypes(List.of("multiple_choice"))
                .build());

        mockMvc.perform(post("/api/v1/teacher/assignments/questions/ai-generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("TC_AS_AI_011 — AI generate trả về empty list → 200 với data rỗng")
    void tc_as_ai_011() throws Exception {
        User teacher = saveTeacher("teacher@test.com");
        Clazz clazz = saveClass(teacher, saveCourse());
        String token = loginAndGetToken("teacher@test.com");

        when(quizAiGenerationService.generateQuestions(any(AiQuizGenerationRequest.class)))
                .thenReturn(List.of());

        String body = objectMapper.writeValueAsString(AiQuizGenerationRequest.builder()
                .teacherPrompt("N5 vocab")
                .classId(clazz.getId())
                .questionCount(1)
                .questionTypes(List.of("multiple_choice"))
                .build());

        mockMvc.perform(post("/api/v1/teacher/assignments/questions/ai-generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. CLOUDINARY INTEGRATION (MOCKED)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. Cloudinary Integration (Mocked)")
    class CloudinaryIntegrationTests {

        @Test
        @DisplayName("TC_AS_CLOUD_001 — Cloudinary upload audio fail → trả lỗi hệ thống")
        void tc_as_cloud_001() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    quizRequestJson(AssignmentStatus.DRAFT, null).getBytes());
            MockMultipartFile audioPart = new MockMultipartFile(
                    "audioFile", "test.mp3", "audio/mpeg", "dummy audio".getBytes());

            when(cloudinaryService.uploadAudio(any(), any()))
                    .thenThrow(new RuntimeException("Cloudinary connection timeout"));

            mockMvc.perform(multipart("/api/v1/teacher/assignments/class/{classId}/quiz", clazz.getId())
                            .file(dataPart)
                            .file(audioPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isInternalServerError());
        }

        @Test
        @DisplayName("TC_AS_CLOUD_002 — Cloudinary upload image fail khi submit writing → lỗi UPLOAD_FAILED")
        void tc_as_cloud_002() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedWriting(teacher, clazz);
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("learner@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    "{\"writtenAnswer\":\"テスト\",\"timeSpentSeconds\":60}".getBytes());
            MockMultipartFile imagePart = new MockMultipartFile(
                    "images", "test.png", "image/png", "dummy image".getBytes());

            when(cloudinaryService.uploadImage(any(), any()))
                    .thenThrow(new RuntimeException("Cloudinary upload failed"));

            mockMvc.perform(multipart("/api/v1/learner/assignments/{id}/submit-writing", a.getId())
                            .file(dataPart)
                            .file(imagePart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest()); // BusinessException UPLOAD_FAILED
        }

        @Test
        @DisplayName("TC_AS_CLOUD_003 — Submit writing quá 10 ảnh → 400 TOO_MANY_IMAGES")
        void tc_as_cloud_003() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedWriting(teacher, clazz);
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("learner@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    "{\"writtenAnswer\":\"テスト\",\"timeSpentSeconds\":60}".getBytes());

            var builder = multipart("/api/v1/learner/assignments/{id}/submit-writing", a.getId())
                    .file(dataPart);

            // Add 11 images → exceeds MAX_SUBMISSION_IMAGES (10)
            for (int i = 0; i < 11; i++) {
                builder.file(new MockMultipartFile(
                        "images", "img" + i + ".png", "image/png", "dummy".getBytes()));
            }

            mockMvc.perform(builder.header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_CLOUD_004 — Update audio khi assignment đã có submissions → 400 HAS_SUBMISSIONS")
        void tc_as_cloud_004() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            enrollLearner(clazz.getId(), learner.getId());
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("teacher@test.com");

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    quizRequestJson(AssignmentStatus.PUBLISHED, null).getBytes());
            MockMultipartFile audioPart = new MockMultipartFile(
                    "audioFile", "new.mp3", "audio/mpeg", "new audio".getBytes());

            mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT,
                            "/api/v1/teacher/assignments/{id}/quiz", a.getId())
                            .file(dataPart)
                            .file(audioPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }
    }
}
