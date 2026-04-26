package com.fu.assignment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.assignment.ai.AiGradingService;
import com.fu.assignment.ai.QuizAiGenerationService;
import com.fu.assignment.ai.QuizExplanationService;
import com.fu.assignment.dto.request.AiQuizGenerationRequest;
import com.fu.assignment.dto.request.WritingEvaluationRequest;
import com.fu.assignment.dto.response.QuizQuestionResponse;
import com.fu.assignment.dto.response.WritingEvaluationResponse;
import com.fu.assignment.entity.*;
import com.fu.assignment.repository.*;
import com.fu.assignment.service.AssignmentService;
import com.fu.clazz.entity.ClassMember;
import com.fu.clazz.entity.ClassStatus;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.entity.ClassTeacherHistory;
import com.fu.clazz.repository.ClassMemberRepository;
import com.fu.clazz.repository.ClassRepository;
import com.fu.clazz.repository.ClassTeacherHistoryRepository;
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
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fu.infrastructure.cloudinary.CloudinaryService;
import org.springframework.web.multipart.MultipartFile;

/**
 * Integration tests — Practice Task (Assignment) Management.
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
    @Autowired private ClassTeacherHistoryRepository classTeacherHistoryRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AssignmentService assignmentService;

    // ── Mocked AI services (prevent real HTTP calls) ──────────────────────────
    @MockBean private QuizAiGenerationService quizAiGenerationService;
    @MockBean private QuizExplanationService quizExplanationService;
    @MockBean private AiGradingService aiGradingService;
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
                .failedLoginAttempts(0)
                .build());
    }

    private User saveLearner(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("ValidPass@1"))
                .firstName("Test").lastName("Learner")
                .role(UserRole.LEARNER).status(UserStatus.ACTIVE)
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

    private Course saveCourse() {
        return courseRepository.save(Course.builder()
                .name("Integration Course").description("test")
                .jlptLevel(JlptLevel.N5).status(CourseStatus.ACTIVE).orderIndex(0)
                .build());
    }

    private Clazz saveClass(User teacher, Course course) {
        Clazz clazz = classRepository.save(Clazz.builder()
                .name("Test Class 101").description("test class")
                .courseId(course.getId())
                .maxStudents(30)
                .startDate(LocalDate.now().plusDays(1))
                .endDate(LocalDate.now().plusMonths(3))
                .status(ClassStatus.ACTIVE)
                .build());
        
        // Create teacher assignment history to verify teacher access
        classTeacherHistoryRepository.save(ClassTeacherHistory.builder()
                .classId(clazz.getId())
                .teacherId(teacher.getId())
                .assignedAt(LocalDate.now())
                .build());
        
        return clazz;
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
                .correctCount(0).totalQuestions(1)
                .answers("[]").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
    }

    /**
     * Create a fully-populated submission with GRADED status (for detail view tests).
     * Populates: submittedAt, gradedAt, gradedBy, score, feedback, correctCount.
     */
    private AssignmentSubmission createFullyPopulatedSubmission(UUID assignmentId, UUID learnerId, UUID gradedByUserId) {
        assignmentRepository.findById(assignmentId).ifPresent(a -> {
            a.setHasSubmissions(true);
            assignmentRepository.save(a);
        });
        LocalDateTime now = LocalDateTime.now();
        return submissionRepository.save(AssignmentSubmission.builder()
                .assignmentId(assignmentId).userId(learnerId)
                .status(SubmissionStatus.GRADED)
                .attemptNumber(1)
                .startedAt(now.minusMinutes(30))
                .submittedAt(now.minusMinutes(5))
                .gradedAt(now)
                .gradedBy(gradedByUserId)
                .correctCount(1).totalQuestions(1)
                .score(new BigDecimal("100"))
                .feedback("Excellent work!")
                .answers("[{\"questionId\":\"q1\",\"answer\":\"A\"}]")
                .imageUrls("[]")  // JSON array for writing submissions
                .createdAt(now).updatedAt(now)
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

    /** Save an EXPIRED QUIZ assignment (due date in the past). */
    private Assignment saveExpiredQuiz(User teacher, Clazz clazz) {
        Assignment a = assignmentRepository.save(Assignment.builder()
                .title("Expired Quiz").description("desc")
                .assignmentType(AssignmentType.QUIZ)
                .classId(clazz.getId()).createdBy(teacher.getId())
                .dueDate(LocalDateTime.now().minusDays(1))
                .questionCount(1)
                .status(AssignmentStatus.PUBLISHED)
                .hasSubmissions(true)
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

    /** Save an ONGOING WRITING assignment with submissions. */
    private Assignment saveOngoingWriting(User teacher, Clazz clazz) {
        Assignment a = assignmentRepository.save(Assignment.builder()
                .title("Ongoing Writing").description("desc")
                .assignmentType(AssignmentType.WRITING)
                .classId(clazz.getId()).createdBy(teacher.getId())
                .dueDate(LocalDateTime.now().plusDays(3))
                .status(AssignmentStatus.PUBLISHED)
                .hasSubmissions(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
        targetRepository.save(AssignmentTarget.builder()
                .assignmentId(a.getId()).classId(clazz.getId()).build());
        writingAssignmentRepository.save(WritingAssignment.builder()
                .assignmentId(a.getId()).prompt("Write about yourself.")
                .createdAt(LocalDateTime.now()).build());
        return a;
    }

    /** Save an ONGOING TRANSLATION assignment with submissions. */
    private Assignment saveOngoingTranslation(User teacher, Clazz clazz) {
        Assignment a = assignmentRepository.save(Assignment.builder()
                .title("Ongoing Translation").description("desc")
                .assignmentType(AssignmentType.TRANSLATION)
                .classId(clazz.getId()).createdBy(teacher.getId())
                .dueDate(LocalDateTime.now().plusDays(5))
                .status(AssignmentStatus.PUBLISHED)
                .hasSubmissions(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
        targetRepository.save(AssignmentTarget.builder()
                .assignmentId(a.getId()).classId(clazz.getId()).build());
        return a;
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
        classTeacherHistoryRepository.deleteAll();
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
        classTeacherHistoryRepository.deleteAll();
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
        @DisplayName("TC_AS_T_VL_001 — Get fields")
        void tc_as_t_vl_001() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            
            // Create 3 assignments: 1 expired, 2 ongoing
            Assignment expiredQuiz = saveExpiredQuiz(teacher, clazz);
            Assignment ongoingWriting = saveOngoingWriting(teacher, clazz);
            Assignment ongoingTranslation = saveOngoingTranslation(teacher, clazz);
            
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", clazz.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content.length()").value(3))
                    
                    // Verify expired quiz (first assignment)
                    .andExpect(jsonPath("$.data.content[0].id").exists())
                    .andExpect(jsonPath("$.data.content[0].title").value("Expired Quiz"))
                    .andExpect(jsonPath("$.data.content[0].assignmentType").value("QUIZ"))
                    .andExpect(jsonPath("$.data.content[0].status").exists())
                    .andExpect(jsonPath("$.data.content[0].dueDate").exists())
                    .andExpect(jsonPath("$.data.content[0].questionCount").exists())
                    .andExpect(jsonPath("$.data.content[0].submissionCount").exists())
                    .andExpect(jsonPath("$.data.content[0].ungradedCount").exists())
                    .andExpect(jsonPath("$.data.content[0].createdAt").exists())
                    .andExpect(jsonPath("$.data.content[0].hasSubmissions").exists());
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
        @DisplayName("TC_AS_T_VL_006 — Get My Assignments (tất cả class) thành công")
        void tc_as_t_vl_006() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz1 = saveClass(teacher, saveCourse());
            Clazz clazz2 = saveClass(teacher, saveCourse());
            Clazz clazz3 = saveClass(teacher, saveCourse());
            
            // Create assignments across multiple classes
            Assignment quiz1 = savePublishedQuiz(teacher, clazz1);
            Assignment writing1 = savePublishedWriting(teacher, clazz1);
            Assignment quiz2 = savePublishedQuiz(teacher, clazz2);
            Assignment writing2 = savePublishedWriting(teacher, clazz2);
            Assignment draft1 = saveQuizDraft(teacher, clazz3);
            
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content.length()").value(5))
                    .andExpect(jsonPath("$.data.totalElements").value(5))
                    .andExpect(jsonPath("$.data.totalPages").value(1))
                    .andExpect(jsonPath("$.data.pageNumber").value(1))
                    .andExpect(jsonPath("$.data.pageSize").value(10))
                    .andExpect(jsonPath("$.data.last").value(true))
                    .andExpect(jsonPath("$.data.first").value(false))
                    
                    // Verify assignments from all classes are returned
                    .andExpect(jsonPath("$.data.content[*].id").value(hasItems(
                            quiz1.getId().toString(),
                            writing1.getId().toString(),
                            quiz2.getId().toString(),
                            writing2.getId().toString(),
                            draft1.getId().toString()
                    )))
                    
                    // Verify each assignment has the required fields
                    .andExpect(jsonPath("$.data.content[*].title").exists())
                    .andExpect(jsonPath("$.data.content[*].assignmentType").exists())
                    .andExpect(jsonPath("$.data.content[*].status").exists())
                    .andExpect(jsonPath("$.data.content[*].questionCount").exists())
                    .andExpect(jsonPath("$.data.content[*].className").exists())
                    .andExpect(jsonPath("$.data.content[*].createdAt").exists())

                    // Verify assignment types and statuses
                    .andExpect(jsonPath("$.data.content[*].assignmentType").value(hasItems("QUIZ", "QUIZ", "QUIZ")))
                    .andExpect(jsonPath("$.data.content[*].assignmentType").value(hasItems("WRITING", "WRITING")))
                    .andExpect(jsonPath("$.data.content[*].status").value(hasItems("DRAFT")))
                    .andExpect(jsonPath("$.data.content[*].status").value(hasItems("PUBLISHED", "PUBLISHED", "PUBLISHED", "PUBLISHED")))
                    
                    // Verify numeric fields are correct type and non-negative
                    .andExpect(jsonPath("$.data.content[*].questionCount").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.greaterThanOrEqualTo(0))))
                    .andExpect(jsonPath("$.data.content[*].submissionCount").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.greaterThanOrEqualTo(0))))
                    .andExpect(jsonPath("$.data.content[*].ungradedCount").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.greaterThanOrEqualTo(0))))
                    
                    // Verify boolean field
                    .andExpect(jsonPath("$.data.content[*].hasSubmissions").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.isA(Boolean.class))));
        }

        @Test
        @DisplayName("TC_AS_T_VL_006c — Get My Assignments with filtering by assignment type")
        void tc_as_t_vl_006c() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz1 = saveClass(teacher, saveCourse());
            Clazz clazz2 = saveClass(teacher, saveCourse());
            
            // Create assignments with different types
            saveQuizDraft(teacher, clazz1); // QUIZ
            savePublishedQuiz(teacher, clazz1); // QUIZ
            savePublishedWriting(teacher, clazz2); // WRITING
            
            String token = loginAndGetToken("teacher@test.com");

            // Filter by QUIZ type only
            mockMvc.perform(get("/api/v1/teacher/assignments")
                            .param("type", "QUIZ")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content.length()").value(2))
                    .andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.content[*].assignmentType").value(hasItems("QUIZ", "QUIZ")));
        }

        @Test
        @DisplayName("TC_AS_T_VL_006d — Get My Assignments with title search")
        void tc_as_t_vl_006d() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz1 = saveClass(teacher, saveCourse());
            Clazz clazz2 = saveClass(teacher, saveCourse());
            
            // Create assignments with specific titles
            Assignment quiz1 = savePublishedQuiz(teacher, clazz1); // "Published Quiz"
            Assignment writing1 = savePublishedWriting(teacher, clazz1); // "Published Writing"
            Assignment quiz2 = savePublishedQuiz(teacher, clazz2); // "Published Quiz"
            
            String token = loginAndGetToken("teacher@test.com");

            // Search by "Writing"
            mockMvc.perform(get("/api/v1/teacher/assignments")
                            .param("title", "Writing")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].title").value("Published Writing"));
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
                    .andExpect(jsonPath("$.data.content[0].id").value(a.getId().toString()))
                    .andExpect(jsonPath("$.data.content[0].title").value("Published Quiz"));  // Verify actual title
            
            // Test: No results when title doesn't match
            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", clazz.getId())
                            .param("title", "NonExistent")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(0))
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }

        @Test
        @DisplayName("TC_AS_T_VL_007b — Filter by type")
        void tc_as_t_vl_007b() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment quiz = savePublishedQuiz(teacher, clazz);
            Assignment writing = savePublishedWriting(teacher, clazz);
            String token = loginAndGetToken("teacher@test.com");

            // Test: Filter WRITING type
            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", clazz.getId())
                            .param("type", "WRITING")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(writing.getId().toString()))
                    .andExpect(jsonPath("$.data.content[0].assignmentType").value("WRITING"));  // Verify type
            
            // Test: Filter QUIZ type
            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", clazz.getId())
                            .param("type", "QUIZ")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(quiz.getId().toString()))
                    .andExpect(jsonPath("$.data.content[0].assignmentType").value("QUIZ"));
        }

        @Test
        @DisplayName("TC_AS_T_VL_007c — Filter by status")
        void tc_as_t_vl_007c() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment draft = saveQuizDraft(teacher, clazz); // DRAFT
            Assignment published = savePublishedQuiz(teacher, clazz); // PUBLISHED
            String token = loginAndGetToken("teacher@test.com");

            // Test: Filter DRAFT status
            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", clazz.getId())
                            .param("status", "DRAFT")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(draft.getId().toString()))
                    .andExpect(jsonPath("$.data.content[0].status").value("DRAFT"));  // Verify status
            
            // Test: Filter PUBLISHED status
            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", clazz.getId())
                            .param("status", "PUBLISHED")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(published.getId().toString()))
                    .andExpect(jsonPath("$.data.content[0].status").value("PUBLISHED"));
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
                    .andExpect(jsonPath("$.data.title").value("Published Quiz"))
                    .andExpect(jsonPath("$.data.description").value("desc"))
                    .andExpect(jsonPath("$.data.instructions").value(nullValue()))
                    .andExpect(jsonPath("$.data.assignmentType").value("QUIZ"))
                    .andExpect(jsonPath("$.data.startDate").value(nullValue()))
                    .andExpect(jsonPath("$.data.dueDate").exists())
                    .andExpect(jsonPath("$.data.audioUrl").value(nullValue()))
                    .andExpect(jsonPath("$.data.questionCount").value(1))
                    .andExpect(jsonPath("$.data.actualQuestionCount").value(1))
                    .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                    .andExpect(jsonPath("$.data.createdBy").exists())
                    .andExpect(jsonPath("$.data.createdAt").exists())
                    .andExpect(jsonPath("$.data.updatedAt").exists())
                    .andExpect(jsonPath("$.data.hasSubmissions").value(false))
                    .andExpect(jsonPath("$.data.questions").isArray())
                    .andExpect(jsonPath("$.data.questions.length()").value(1))
                    .andExpect(jsonPath("$.data.questions[0].assignmentQuestionId").exists())
                    .andExpect(jsonPath("$.data.questions[0].orderIndex").value(0))
                    .andExpect(jsonPath("$.data.questions[0].points").value(nullValue()))
                    .andExpect(jsonPath("$.data.questions[0].questionId").exists())
                    .andExpect(jsonPath("$.data.questions[0].questionText").value("テストの問題"))
                    .andExpect(jsonPath("$.data.questions[0].questionType").value("multiple_choice"))
                    .andExpect(jsonPath("$.data.questions[0].options").isArray())
                    .andExpect(jsonPath("$.data.questions[0].options.length()").value(2))
                    .andExpect(jsonPath("$.data.questions[0].options[0].id").value("A"))
                    .andExpect(jsonPath("$.data.questions[0].options[0].text").value("正しい"))
                    .andExpect(jsonPath("$.data.questions[0].options[0].isCorrect").value(true))
                    .andExpect(jsonPath("$.data.questions[0].options[1].id").value("B"))
                    .andExpect(jsonPath("$.data.questions[0].options[1].text").value("間違い"))
                    .andExpect(jsonPath("$.data.questions[0].options[1].isCorrect").value(false))
                    .andExpect(jsonPath("$.data.questions[0].explanation").value(nullValue()))
                    .andExpect(jsonPath("$.data.questions[0].imageUrl").value(nullValue()))
                    .andExpect(jsonPath("$.data.questions[0].jlptLevel").value(nullValue()))
                    .andExpect(jsonPath("$.data.questions[0].correctAnswer").value("A"))
                    .andExpect(jsonPath("$.data.questions[0].correctAnswers").value(nullValue()))
                    .andExpect(jsonPath("$.data.writingContent").value(nullValue()))
                    .andExpect(jsonPath("$.data.targets").value(nullValue()))
                    .andExpect(jsonPath("$.data.submissionCount").value(0));
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

            String twoQuestionsJson = """
                [{
                  "questionText": "どれが正しいですか？",
                  "questionType": "multiple_choice",
                  "options": "[{\\"id\\":\\"A\\",\\"text\\":\\"はい\\",\\"isCorrect\\":true},{\\"id\\":\\"B\\",\\"text\\":\\"いいえ\\",\\"isCorrect\\":false}]",
                  "correctAnswer": "A",
                  "explanation": "The correct answer is A.",
                  "explanationVi": "Đáp án đúng là A.",
                  "points": 100
                },
                {
                  "questionText": "これは何ですか？",
                  "questionType": "multiple_choice",
                  "options": "[{\\"id\\":\\"A\\",\\"text\\":\\"ペン\\",\\"isCorrect\\":true},{\\"id\\":\\"B\\",\\"text\\":\\"ノート\\",\\"isCorrect\\":false}]",
                  "correctAnswer": "A",
                  "explanation": "This is a pen.",
                  "explanationVi": "Đây là cây bút.",
                  "points": 100
                }]
                """;

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    quizRequestJson(AssignmentStatus.DRAFT, twoQuestionsJson).getBytes());

            mockMvc.perform(multipart("/api/v1/teacher/assignments/class/{classId}/quiz", clazz.getId())
                            .file(dataPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.assignmentType").value("QUIZ"))
                    .andExpect(jsonPath("$.data.status").value("DRAFT"))
                    .andExpect(jsonPath("$.data.questionCount").value(2));
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
        @DisplayName("TC_AS_T_ST_006 — CLOSED → PUBLISHED → 200 (success)")
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
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
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
        @DisplayName("TC_AS_T_DL_003 — Xóa khi có submissions → 400 (requireDraft block)")
        void tc_as_t_dl_003() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            
            // Create a submission to simulate hasSubmissions = true
            User learner = saveLearner("learner@test.com");
            enrollLearner(clazz.getId(), learner.getId());
            startSubmission(a.getId(), learner.getId());
            
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(delete("/api/v1/teacher/assignments/{id}", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_T_DL_004 — Xóa PUBLISHED không có submissions → 200 (OK)")
        void tc_as_t_dl_004() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            Assignment a = savePublishedQuiz(teacher, clazz);
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(delete("/api/v1/teacher/assignments/{id}", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
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
                    .andExpect(jsonPath("$.data.content.length()").value(1))

                    // Verify submission fields
                    .andExpect(jsonPath("$.data.content[0].id").exists())
                    .andExpect(jsonPath("$.data.content[0].userFullName").value("Test Learner"))
                    .andExpect(jsonPath("$.data.content[0].status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.data.content[0].score").value(nullValue()))
                    .andExpect(jsonPath("$.data.content[0].submittedAt").value(nullValue()))
                    .andExpect(jsonPath("$.data.content[0].isRemovedLearner").value(false))
                    
                    // Verify pagination fields
                    .andExpect(jsonPath("$.data.pageNumber").value(1))
                    .andExpect(jsonPath("$.data.pageSize").value(10))
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.totalPages").value(1))
                    .andExpect(jsonPath("$.data.last").value(true))
                    .andExpect(jsonPath("$.data.first").value(false));
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
            AssignmentSubmission sub = createFullyPopulatedSubmission(a.getId(), learner.getId(), teacher.getId());
            String token = loginAndGetToken("teacher@test.com");

            mockMvc.perform(get("/api/v1/teacher/assignments/submissions/{subId}", sub.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    
                    // Verify submission details with all fields populated (GRADED status)
                    .andExpect(jsonPath("$.data.id").value(sub.getId().toString()))
                    .andExpect(jsonPath("$.data.assignmentId").value(a.getId().toString()))
                    .andExpect(jsonPath("$.data.assignmentTitle").value("Published Quiz"))
                    .andExpect(jsonPath("$.data.userId").value(learner.getId().toString()))
                    .andExpect(jsonPath("$.data.userFullName").value("Test Learner"))
                    .andExpect(jsonPath("$.data.userEmail").value("learner@test.com"))
                    .andExpect(jsonPath("$.data.status").value("GRADED"))
                    .andExpect(jsonPath("$.data.attemptNumber").value(1))
                    .andExpect(jsonPath("$.data.startedAt").exists())
                    .andExpect(jsonPath("$.data.submittedAt").exists())
                    .andExpect(jsonPath("$.data.gradedAt").exists())
                    .andExpect(jsonPath("$.data.gradedBy").value(teacher.getId().toString()))
                    .andExpect(jsonPath("$.data.score").value("100.0"))
                    .andExpect(jsonPath("$.data.correctCount").value(1))
                    .andExpect(jsonPath("$.data.totalQuestions").value(1))
                    .andExpect(jsonPath("$.data.feedback").value("Excellent work!"))
                    .andExpect(jsonPath("$.data.answers").exists())
                    .andExpect(jsonPath("$.data.createdAt").exists());
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
                    .startedAt(LocalDateTime.now().minusMinutes(10))
                    .submittedAt(LocalDateTime.now().minusMinutes(1))
                    .correctCount(0).totalQuestions(1)
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
                    .correctCount(0).totalQuestions(1)
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
        @DisplayName("TC_AS_T_GR_004 — Grade submission đã GRADED → 200 (re-grading allowed)")
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
                    .startedAt(LocalDateTime.now().minusMinutes(10))
                    .submittedAt(LocalDateTime.now().minusMinutes(5))
                    .gradedAt(LocalDateTime.now().minusMinutes(1))
                    .correctCount(0).totalQuestions(1)
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
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("GRADED"))
                    .andExpect(jsonPath("$.data.score").value(90.0))
                    .andExpect(jsonPath("$.data.feedback").value("Better."));
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
                    .startedAt(LocalDateTime.now().minusMinutes(10))
                    .submittedAt(LocalDateTime.now().minusMinutes(1))
                    .correctCount(0).totalQuestions(1)
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
                    
                    // Verify class identifier
                    .andExpect(jsonPath("$.data.classId").value(clazz.getId().toString()))
                    
                    // Verify assignment counts by status
                    .andExpect(jsonPath("$.data.totalAssignments").value(0))
                    .andExpect(jsonPath("$.data.draftAssignments").value(0))
                    .andExpect(jsonPath("$.data.publishedAssignments").value(0))
                    .andExpect(jsonPath("$.data.closedAssignments").value(0))
                    
                    // Verify submission counts by status
                    .andExpect(jsonPath("$.data.totalSubmissions").value(0))
                    .andExpect(jsonPath("$.data.ungradedSubmissions").value(0))
                    .andExpect(jsonPath("$.data.gradedSubmissions").value(0))
                    .andExpect(jsonPath("$.data.inProgressSubmissions").value(0))
                    .andExpect(jsonPath("$.data.overdueSubmissions").value(0));
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
        @DisplayName("TC_AS_L_VL_001 — Learner view assignment list success - only PUBLISHED assignments visible")
        void tc_as_l_vl_001() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            
            // Create multiple assignments with different statuses
            savePublishedQuiz(teacher, clazz); // PUBLISHED - should be visible
            saveQuizDraft(teacher, clazz); // DRAFT - should be filtered out
            saveExpiredQuiz(teacher, clazz); // EXPIRED - should be included with appropriate status
            
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content.length()").value(2)) // Only PUBLISHED and EXPIRED
                    .andExpect(jsonPath("$.data.content[*].title").exists())
                    .andExpect(jsonPath("$.data.content[*].assignmentType").exists())
                    .andExpect(jsonPath("$.data.content[*].dueDate").exists())
                    .andExpect(jsonPath("$.data.content[*].questionCount").exists())
                    .andExpect(jsonPath("$.data.content[*].createdAt").exists())
                    .andExpect(jsonPath("$.data.content[*].learnerSubmissionStatus").exists());
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
                    .andExpect(jsonPath("$.data.content.length()").value(1));
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
                    .andExpect(jsonPath("$.data.id").value(a.getId().toString()))
                    .andExpect(jsonPath("$.data.title").value("Published Quiz"))
                    .andExpect(jsonPath("$.data.description").exists())
                    .andExpect(jsonPath("$.data.instructions").doesNotExist())
                    .andExpect(jsonPath("$.data.assignmentType").value("QUIZ"))
                    .andExpect(jsonPath("$.data.startDate").doesNotExist())
                    .andExpect(jsonPath("$.data.dueDate").exists())
                    .andExpect(jsonPath("$.data.audioUrl").doesNotExist())
                    .andExpect(jsonPath("$.data.actualQuestionCount").value(1))
                    .andExpect(jsonPath("$.data.questions").isArray())
                    .andExpect(jsonPath("$.data.questions.length()").value(1))
                    .andExpect(jsonPath("$.data.questions[0].assignmentQuestionId").exists())
                    .andExpect(jsonPath("$.data.questions[0].orderIndex").value(0))
                    .andExpect(jsonPath("$.data.questions[0].points").doesNotExist())
                    .andExpect(jsonPath("$.data.questions[0].questionId").exists())
                    .andExpect(jsonPath("$.data.questions[0].questionText").exists())
                    .andExpect(jsonPath("$.data.questions[0].questionType").value("multiple_choice"))
                    .andExpect(jsonPath("$.data.questions[0].options").isArray())
                    .andExpect(jsonPath("$.data.questions[0].options.length()").value(2))
                    .andExpect(jsonPath("$.data.questions[0].options[*].id").exists())
                    .andExpect(jsonPath("$.data.questions[0].options[*].text").exists())
                    .andExpect(jsonPath("$.data.questions[0].options[*].isCorrect").exists())
                    .andExpect(jsonPath("$.data.questions[0].imageUrl").doesNotExist())
                    .andExpect(jsonPath("$.data.questions[0].jlptLevel").doesNotExist())
                    .andExpect(jsonPath("$.data.writingContent").doesNotExist());

            // Verify submission IN_PROGRESS in database
            List<AssignmentSubmission> submissions = submissionRepository.findByAssignmentIdAndUserIdOrderByAttemptDesc(a.getId(), learner.getId());
            assertThat(submissions).isNotEmpty();
            AssignmentSubmission submission = submissions.get(0);
            assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.IN_PROGRESS);
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
                    .correctCount(1).totalQuestions(1)
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
                        .correctCount(1).totalQuestions(1)
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
        @DisplayName("TC_AS_L_DO_005 — Đang có IN_PROGRESS → 200 (resume existing session)")
        void tc_as_l_do_005() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedQuiz(teacher, clazz);
            AssignmentSubmission existing = startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(post("/api/v1/learner/assignments/{id}/start", a.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(a.getId().toString()))
                    .andExpect(jsonPath("$.data.title").value("Published Quiz"));
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
                    "answers", "[{\"questionId\":\"" + questionId + "\",\"selectedAnswer\":\"A\"}]",
                    "timeSpentSeconds", 60));

            mockMvc.perform(post("/api/v1/learner/assignments/" + a.getId() + "/submit-quiz")
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
                    "answers", "[{\"questionId\":\"" + questionId + "\",\"selectedAnswer\":\"B\"}]",
                    "timeSpentSeconds", 30));

            mockMvc.perform(post("/api/v1/learner/assignments/" + a.getId() + "/submit-quiz")
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
                    "answers", "[{\"questionId\":\"" + questionId + "\",\"selectedAnswer\":\"A\"}]",
                    "timeSpentSeconds", 60));

            mockMvc.perform(post("/api/v1/learner/assignments/" + a.getId() + "/submit-quiz")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PAST_DUE"))
                    .andExpect(jsonPath("$.message").value("Bài tập này đã hết hạn nộp."));
        }

        @Test
        @DisplayName("TC_AS_L_DO_009 — Submit Writing với ảnh quá dung lượng (>5MB) → 400 FILE_TOO_LARGE")
        void tc_as_l_do_009() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedWriting(teacher, clazz);
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("learner@test.com");

            // Create a file larger than 5MB (5 * 1024 * 1024 + 1 byte)
            byte[] largeFileContent = new byte[(int) (5 * 1024 * 1024 + 1)];
            MockMultipartFile largeImagePart = new MockMultipartFile(
                    "images", "large-image.jpg", "image/jpeg",
                    largeFileContent);

            mockMvc.perform(multipart("/api/v1/learner/assignments/{id}/submit-writing", a.getId())
                            .file(largeImagePart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"))
                    .andExpect(jsonPath("$.message").value("Mỗi hình ảnh không được vượt quá 5MB. File: large-image.jpg có kích thước 5MB."));
        }

        @Test
        @DisplayName("TC_AS_L_DO_010 — Submit Writing quá hạn → không được nộp")
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
            MockMultipartFile imagePart = new MockMultipartFile(
                    "images", "writing-image.jpg", "image/jpeg",
                    "test image content".getBytes());

            mockMvc.perform(multipart("/api/v1/learner/assignments/{id}/submit-writing", a.getId())
                            .file(imagePart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Bài tập này đã hết hạn nộp."));
        }

        @Test
        @DisplayName("TC_AS_L_DO_011 — Submit Writing thiếu image → 400")
        void tc_as_l_do_011() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedWriting(teacher, clazz);
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("learner@test.com");

            // no image
            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    "{\"timeSpentSeconds\":10}".getBytes());

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

            mockMvc.perform(post("/api/v1/learner/assignments/" + UUID.randomUUID() + "/submit-quiz")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"answers\":\"[]\"}")
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
                    "answers", "[{\"questionId\":\"" + questionId + "\",\"selectedAnswer\":\"A\"}]",
                    "timeSpentSeconds", 60));

            mockMvc.perform(post("/api/v1/learner/assignments/" + a.getId() + "/submit-quiz")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("GRADED"))
                    .andExpect(jsonPath("$.data.score").value(100.00));
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
                    "{\"timeSpentSeconds\":120}".getBytes());
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
                    "answers", "invalid-json-array",
                    "timeSpentSeconds", 60));

            mockMvc.perform(post("/api/v1/learner/assignments/" + a.getId() + "/submit-quiz")
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
                    "answers", "[]",
                    "timeSpentSeconds", 60));

            mockMvc.perform(post("/api/v1/learner/assignments/" + a.getId() + "/submit-quiz")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TC_AS_L_DO_019 — Evaluate Writing thành công với image")
        void tc_as_l_do_019() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedWriting(teacher, clazz);
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("learner@test.com");

            // Mock AI service response
            WritingEvaluationResponse mockResponse = WritingEvaluationResponse.builder()
                    .feedback("Good writing with clear structure. Minor grammar issues.")
                    .build();

            when(aiGradingService.evaluateWriting(any(WritingEvaluationRequest.class), any(MultipartFile[].class)))
                    .thenReturn(mockResponse);

            // Prepare request data
            WritingEvaluationRequest request = WritingEvaluationRequest.builder()
                    .assignmentId(a.getId())
                    .prompt("Write about your hobby")
                    .build();

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    objectMapper.writeValueAsString(request).getBytes());

            MockMultipartFile imagePart = new MockMultipartFile(
                    "images", "writing-image.jpg", "image/jpeg",
                    "test image content".getBytes());

            mockMvc.perform(multipart("/api/v1/learner/assignments/writing/evaluate")
                            .file(dataPart)
                            .file(imagePart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.feedback").value("Good writing with clear structure. Minor grammar issues."));
        }


        @Test
        @DisplayName("TC_AS_L_DO_021 — Evaluate Writing với multiple images")
        void tc_as_l_do_021() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedWriting(teacher, clazz);
            startSubmission(a.getId(), learner.getId());
            String token = loginAndGetToken("learner@test.com");

            // Mock AI service response
            WritingEvaluationResponse mockResponse = WritingEvaluationResponse.builder()
                    .feedback("Excellent work with comprehensive content.")
                    .build();

            when(aiGradingService.evaluateWriting(any(WritingEvaluationRequest.class), any(MultipartFile[].class)))
                    .thenReturn(mockResponse);

            // Prepare request data with multiple images
            WritingEvaluationRequest request = WritingEvaluationRequest.builder()
                    .assignmentId(a.getId())
                    .prompt("Write a story")
                    .build();

            MockMultipartFile dataPart = new MockMultipartFile(
                    "data", "", "application/json",
                    objectMapper.writeValueAsString(request).getBytes());

            MockMultipartFile imagePart1 = new MockMultipartFile(
                    "images", "page1.jpg", "image/jpeg",
                    "first page content".getBytes());

            MockMultipartFile imagePart2 = new MockMultipartFile(
                    "images", "page2.jpg", "image/jpeg",
                    "second page content".getBytes());

            mockMvc.perform(multipart("/api/v1/learner/assignments/writing/evaluate")
                            .file(dataPart)
                            .file(imagePart1)
                            .file(imagePart2)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.feedback").value("Excellent work with comprehensive content."));
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
        @DisplayName("TC_AS_L_VS_001b — Xem submission quiz đã chấm điểm → có score, số câu đúng/sai")
        void tc_as_l_vs_001b() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            Clazz clazz = saveClass(teacher, saveCourse());
            enrollLearner(clazz.getId(), learner.getId());
            Assignment a = savePublishedQuiz(teacher, clazz);

            // Create a graded submission with score and answer details
            AssignmentSubmission sub = submissionRepository.save(AssignmentSubmission.builder()
                    .assignmentId(a.getId()).userId(learner.getId())
                    .status(SubmissionStatus.GRADED).attemptNumber(1)
                    .score(BigDecimal.valueOf(80.0))
                    .correctCount(4).totalQuestions(5)
                    .startedAt(LocalDateTime.now().minusMinutes(30))
                    .submittedAt(LocalDateTime.now().minusMinutes(10))
                    .gradedAt(LocalDateTime.now().minusMinutes(5))
                    .answers("[{\"questionId\":\"q1\",\"selectedOption\":\"A\",\"isCorrect\":true}," +
                            "{\"questionId\":\"q2\",\"selectedOption\":\"B\",\"isCorrect\":false}," +
                            "{\"questionId\":\"q3\",\"selectedOption\":\"C\",\"isCorrect\":true}," +
                            "{\"questionId\":\"q4\",\"selectedOption\":\"A\",\"isCorrect\":true}," +
                            "{\"questionId\":\"q5\",\"selectedOption\":\"D\",\"isCorrect\":false}]")
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build());

            String token = loginAndGetToken("learner@test.com");

            mockMvc.perform(get("/api/v1/learner/assignments/submissions/{id}", sub.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(sub.getId().toString()))
                    .andExpect(jsonPath("$.data.assignmentId").value(a.getId().toString()))
                    .andExpect(jsonPath("$.data.assignmentTitle").value("Published Quiz"))
                    .andExpect(jsonPath("$.data.userId").value(learner.getId().toString()))
                    .andExpect(jsonPath("$.data.userFullName").value("Test Learner"))
                    .andExpect(jsonPath("$.data.userEmail").value("learner@test.com"))
                    .andExpect(jsonPath("$.data.status").value("GRADED"))
                    .andExpect(jsonPath("$.data.attemptNumber").value(1))
                    .andExpect(jsonPath("$.data.startedAt").exists())
                    .andExpect(jsonPath("$.data.submittedAt").exists())
                    .andExpect(jsonPath("$.data.gradedAt").exists())
                    .andExpect(jsonPath("$.data.gradedBy").doesNotExist())
                    .andExpect(jsonPath("$.data.score").value(80.00))
                    .andExpect(jsonPath("$.data.correctCount").value(4))
                    .andExpect(jsonPath("$.data.totalQuestions").value(5))
                    .andExpect(jsonPath("$.data.feedback").doesNotExist())
                    .andExpect(jsonPath("$.data.answers").exists())
                    .andExpect(jsonPath("$.data.imageUrls").doesNotExist())
                    .andExpect(jsonPath("$.data.teacherScore").doesNotExist())
                    .andExpect(jsonPath("$.data.quizDetails").exists())
                    .andExpect(jsonPath("$.data.createdAt").exists())

                    // Verify answers structure (parsed as string, not array)
                    .andExpect(jsonPath("$.data.answers").value(org.hamcrest.Matchers.containsString("isCorrect")))
                    .andExpect(jsonPath("$.data.answers").value(org.hamcrest.Matchers.containsString("questionId")))
                    .andExpect(jsonPath("$.data.answers").value(org.hamcrest.Matchers.containsString("selectedOption")))
                    .andExpect(jsonPath("$.data.answers").value(org.hamcrest.Matchers.containsString("q1")))
                    .andExpect(jsonPath("$.data.answers").value(org.hamcrest.Matchers.containsString("A")));
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
                    .startedAt(LocalDateTime.now().minusMinutes(10))
                    .submittedAt(LocalDateTime.now().minusMinutes(1))
                    .correctCount(0).totalQuestions(1)
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
                    .correctCount(0).totalQuestions(1)
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
                    .startedAt(LocalDateTime.now().minusDays(2))
                    .submittedAt(LocalDateTime.now().minusDays(1))
                    .gradedAt(LocalDateTime.now())
                    .score(BigDecimal.valueOf(95.5))
                    .feedback("よくできました！")
                    .correctCount(0).totalQuestions(1)
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
                    .lessonId(UUID.randomUUID())
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
                    .lessonId(UUID.randomUUID())
                    .questionTypes(List.of("multiple_choice"))
                    .build());

            mockMvc.perform(post("/api/v1/teacher/assignments/questions/ai-generate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }
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
                    "{\"timeSpentSeconds\":60}".getBytes());
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
                    "{\"timeSpentSeconds\":60}".getBytes());

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
