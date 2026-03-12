package com.fu.assignment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.assignment.entity.Assignment;
import com.fu.assignment.entity.AssignmentStatus;
import com.fu.assignment.entity.AssignmentType;
import com.fu.assignment.repository.AssignmentRepository;
import com.fu.classes.entity.Classs;
import com.fu.classes.repository.ClassRepository;
import com.fu.course.entity.Course;
import com.fu.course.repository.CourseRepository;
import com.fu.infrastructure.openai.OpenAIService;
import com.fu.user.entity.AuthProvider;
import com.fu.user.entity.User;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import com.fu.user.repository.UserRepository;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — Practice Task / Assignment Management.
 * Follows cases established in ASSIGNMENT_TEST_PROCEDURES.md.
 */
@SpringBootTest(classes = com.fu.StartApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Assignment Management — Integration Tests")
class AssignmentIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean
    private OpenAIService openAiService;

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private Course setupCourse() {
        return courseRepository.save(Course.builder()
                .name("Integration Course")
                .jlptLevel(Course.JlptLevel.N5)
                .status(Course.CourseStatus.ACTIVE)
                .build());
    }

    private Classs setupClass(User teacher, Course course) {
        return classRepository.save(Classs.builder()
                .course(course)
                .teacher(teacher)
                .name("Test Class 101")
                .status(Classs.ClassStatus.ACTIVE)
                .build());
    }

    @BeforeEach
    void setUp() {
        assignmentRepository.deleteAll();
        classRepository.deleteAll();
        courseRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1. TEACHER ASSIGNMENT MANAGEMENT
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1.1 View Practice Task List")
    class ViewTeacherAssignments {

        @Test
        @DisplayName("TC_AS_T_VL_001 — Lấy list thành công, trả đúng fields")
        void viewAssignmentsSuccess() throws Exception {
            User teacher = saveTeacher("teacher1@test.com");
            Course course = setupCourse();
            Classs classs = setupClass(teacher, course);

            assignmentRepository.save(Assignment.builder()
                    .title("Quiz 1")
                    .type(AssignmentType.QUIZ)
                    .status(AssignmentStatus.PUBLISHED)
                    .targetClass(classs)
                    .teacher(teacher)
                    .build());

            String token = loginAndGetToken(teacher.getEmail(), "ValidPass@1");

            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", classs.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }

        @Test
        @DisplayName("TC_AS_T_VL_002 — Class không tồn tại → 404")
        void classNotFound() throws Exception {
            User teacher = saveTeacher("teacher1@test.com");
            String token = loginAndGetToken(teacher.getEmail(), "ValidPass@1");

            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", UUID.randomUUID())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TC_AS_T_VL_004 — Teacher không phải owner của class → 403")
        void notClassOwner() throws Exception {
            User teacherOwner = saveTeacher("owner@test.com");
            User teacherOther = saveTeacher("other@test.com");
            Course course = setupCourse();
            Classs classs = setupClass(teacherOwner, course);

            String tokenOther = loginAndGetToken(teacherOther.getEmail(), "ValidPass@1");

            // Assuming system blocks viewing other teacher's class content
            mockMvc.perform(get("/api/v1/teacher/assignments/class/{classId}", classs.getId())
                            .header("Authorization", "Bearer " + tokenOther))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("1.3 Create Practice Task")
    class CreatePracticeTask {

        @Test
        @DisplayName("TC_AS_T_CR_001 — Tạo QUIZ thành công → status DRAFT")
        void createQuizSuccess() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            Classs classs = setupClass(teacher, setupCourse());
            String token = loginAndGetToken(teacher.getEmail(), "ValidPass@1");

            String jsonPayload = """
                {
                    "title": "New Quiz",
                    "dueDate": "%s",
                    "passingScore": 50
                }
            """.formatted(Instant.now().plus(7, ChronoUnit.DAYS).toString());

            // Notice we use SaveFullQuizRequest payload format expected by the controller
            // The JSON must match the backend DTO structure perfectly. Given complexity,
            // we will build basic payload assuming minimal requirements are title, dueDate.
            // Using multipart since endpoint consumes MULTIPART_FORM_DATA_VALUE
            org.springframework.mock.web.MockMultipartFile dataFile =
                    new org.springframework.mock.web.MockMultipartFile("data", "", 
                    "application/json", jsonPayload.getBytes());

            mockMvc.perform(multipart("/api/v1/teacher/assignments/class/{classId}/quiz", classs.getId())
                            .file(dataFile)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated()) // Or isOk() depending on ApiResponse wrapper
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.type").value("QUIZ"))
                    .andExpect(jsonPath("$.data.status").value("DRAFT"));
        }
    }
}
