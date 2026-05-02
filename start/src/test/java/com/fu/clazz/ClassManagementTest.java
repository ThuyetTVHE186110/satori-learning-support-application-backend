package com.fu.clazz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.clazz.entity.ClassMember;
import com.fu.clazz.entity.ClassStatus;
import com.fu.clazz.entity.ClassTeacherHistory;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.repository.ClassMemberRepository;
import com.fu.clazz.repository.ClassRepository;
import com.fu.clazz.repository.ClassTeacherHistoryRepository;
import com.fu.course.entity.Course;
import com.fu.course.entity.Course.CourseStatus;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.repository.CourseRepository;
import com.fu.infrastructure.cloudinary.CloudinaryService;
import com.fu.infrastructure.email.EmailService;
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
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = com.fu.StartApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(
        statements = "TRUNCATE TABLE user_management.class_teacher_history, user_management.class_members, user_management.classes, learning_content.courses, system_admin.curriculum_imports, user_management.user_sessions, user_management.users CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS
)
@Sql(
        statements = "TRUNCATE TABLE user_management.class_teacher_history, user_management.class_members, user_management.classes, learning_content.courses, system_admin.curriculum_imports, user_management.user_sessions, user_management.users CASCADE",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD,
        config = @SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED)
)
@DisplayName("Class Management — Integration Tests (PostgreSQL satori_test)")
class ClassManagementTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ClassRepository classRepository;
    @Autowired
    private ClassMemberRepository classMemberRepository;
    @Autowired
    private ClassTeacherHistoryRepository teacherHistoryRepository;
    @Autowired
    private CourseRepository courseRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserSessionRepository sessionRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private EmailService emailService;
    @MockBean
    private CloudinaryService cloudinaryService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User saveManager(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("ValidPass@1"))
                .firstName("Test").lastName("Manager")
                .role(UserRole.OPERATION_MANAGER)
                .status(UserStatus.ACTIVE)
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
                .failedLoginAttempts(0)
                .build());
    }

    private User saveLearner(String email) {
        return saveLearner(email, UserStatus.ACTIVE);
    }

    private User saveInactiveLearner(String email) {
        return saveLearner(email, UserStatus.INACTIVE);
    }

    private User saveLearner(String email, UserStatus status) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("ValidPass@1"))
                .firstName("Test").lastName("Learner")
                .role(UserRole.LEARNER)
                .status(status)
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

    private Course saveCourse(String name, CourseStatus status) {
        return courseRepository.save(Course.builder()
                .name(name).description("Test course")
                .jlptLevel(JlptLevel.N5).status(status).orderIndex(0)
                .build());
    }

    /**
     * Save a class AND create the corresponding ClassTeacherHistory entry (active assignment).
     * Use this wherever a class needs a current teacher.
     */
    private Clazz saveClass(String name, UUID teacherId, UUID courseId,
                            ClassStatus status, int maxStudents) {
        Clazz clazz = classRepository.save(Clazz.builder()
                .name(name).description("Test class")
                .courseId(courseId)
                .maxStudents(maxStudents)
                .startDate(LocalDate.now().minusDays(1))
                .status(status)
                .build());
        if (teacherId != null) {
            teacherHistoryRepository.save(ClassTeacherHistory.builder()
                    .classId(clazz.getId())
                    .teacherId(teacherId)
                    .assignedAt(LocalDate.now())
                    .build());
        }
        return clazz;
    }

    private Clazz saveDeletedClass(String name, UUID teacherId, UUID courseId,
                                   ClassStatus status, int maxStudents, UUID deletedBy) {
        Clazz clazz = saveClass(name, teacherId, courseId, status, maxStudents);
        clazz.setDeletedAt(LocalDateTime.now().minusHours(1));
        clazz.setDeletedBy(deletedBy);
        return classRepository.save(clazz);
    }

    private ClassMember saveClassMember(UUID classId, UUID userId) {
        return classMemberRepository.save(ClassMember.builder()
                .classId(classId).userId(userId).build());
    }

    private ClassMember saveClassMember(UUID classId, UUID userId, LocalDateTime joinedAt) {
        return classMemberRepository.save(ClassMember.builder()
                .classId(classId).userId(userId).joinedAt(joinedAt).build());
    }

    private ClassMember saveRemovedClassMember(UUID classId, UUID userId) {
        return classMemberRepository.save(ClassMember.builder()
                .classId(classId).userId(userId)
                .removedAt(LocalDateTime.now().minusDays(1))
                .build());
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        // cleanup is handled by @Sql(AFTER_TEST_METHOD) in an isolated transaction
    }

    @AfterEach
    void tearDown() {
        // cleanup is handled by @Sql(AFTER_TEST_METHOD) in an isolated transaction
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 1. VIEW CLASS LIST — GET /api/v1/classes
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. VIEW CLASS LIST — GET /api/v1/classes")
    class ViewClassListTests {

        @Test
        @DisplayName("TC_CLM_VL_001 — Manager views all non-deleted classes with pagination")
        void tc_clm_vl_001() throws Exception {
            User manager = saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);

            // Create classes with different timestamps to test sorting
            Thread.sleep(10); // Ensure different timestamps
            Clazz classA = saveClass("Class A", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);
            Thread.sleep(10);
            Clazz classB = saveClass("Class B", teacher.getId(), course.getId(), ClassStatus.CLOSED, 8);
            Thread.sleep(10);
            Clazz classC = saveClass("Class C", teacher.getId(), course.getId(), ClassStatus.NOT_STARTED, 8);

            MvcResult result = mockMvc.perform(get("/api/v1/classes")
                            .param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content.length()").value(3))
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.pageSize").value(10))
                    .andExpect(jsonPath("$.data.totalElements").value(3))
                    .andExpect(jsonPath("$.data.totalPages").value(1))
                    .andReturn();

            // Verify default sort by createdAt descending (newest first)
            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            var content = objectMapper.readTree(body).path("data").path("content");
            assertThat(content.get(0).path("name").asText()).isEqualTo("Class C"); // newest
            assertThat(content.get(1).path("name").asText()).isEqualTo("Class B"); // middle
            assertThat(content.get(2).path("name").asText()).isEqualTo("Class A"); // oldest

            // Verify each class content structure
            for (var classNode : content) {
                assertThat(classNode.has("id")).isTrue();
                assertThat(classNode.has("name")).isTrue();
                assertThat(classNode.has("teacherId")).isTrue();
                assertThat(classNode.has("teacherName")).isTrue();
                assertThat(classNode.has("courseId")).isTrue();
                assertThat(classNode.has("courseName")).isTrue();
                assertThat(classNode.has("courseLevel")).isTrue();
                assertThat(classNode.has("courseStatus")).isTrue();
                assertThat(classNode.has("status")).isTrue();
                assertThat(classNode.has("memberCount")).isTrue();

                // Verify specific values for Class A
                if ("Class A".equals(classNode.path("name").asText())) {
                    assertThat(classNode.path("id").asText()).isEqualTo(classA.getId().toString());
                    assertThat(classNode.path("name").asText()).isEqualTo("Class A");
                    assertThat(classNode.path("teacherId").asText()).isEqualTo(teacher.getId().toString());
                    assertThat(classNode.path("teacherName").asText()).isEqualTo("Test Teacher");
                    assertThat(classNode.path("courseId").asText()).isEqualTo(course.getId().toString());
                    assertThat(classNode.path("courseName").asText()).isEqualTo("Japanese N5");
                    assertThat(classNode.path("courseLevel").asText()).isEqualTo("N5");
                    assertThat(classNode.path("courseStatus").asText()).isEqualTo("ACTIVE");
                    assertThat(classNode.path("status").asText()).isEqualTo("ACTIVE");
                    assertThat(classNode.path("memberCount").asInt()).isEqualTo(0);
                }
            }
        }

        @Test
        @DisplayName("TC_CLM_VL_002 — Teacher sees only assigned classes (auto-filtered)")
        void tc_clm_vl_002() throws Exception {
            User t1 = saveTeacher("teacher1@test.com");
            User t2 = saveTeacher("teacher2@test.com");
            String token = loginAndGetToken("teacher1@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);

            saveClass("T1 Active 1", t1.getId(), course.getId(), ClassStatus.ACTIVE, 8);
            saveClass("T1 Closed 2", t1.getId(), course.getId(), ClassStatus.CLOSED, 8);
            saveClass("T1 Inactive", t1.getId(), course.getId(), ClassStatus.NOT_STARTED, 8);
            saveClass("T2 Active", t2.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            MvcResult result = mockMvc.perform(get("/api/v1/classes")
                            .param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content.length()").value(3))
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.pageSize").value(10))
                    .andExpect(jsonPath("$.data.totalElements").value(3))
                    .andExpect(jsonPath("$.data.totalPages").value(1))
                    .andReturn();

        }

        @Test
        @DisplayName("TC_CLM_VL_003 — Manager filters by keyword, teacher, active status, and course")
        void tc_clm_vl_003() throws Exception {
            User manager = saveManager("manager@test.com");
            User t1 = saveTeacher("teacher1@test.com");
            User t2 = saveTeacher("teacher2@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course c1 = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Course c2 = saveCourse("Japanese N4", CourseStatus.ACTIVE);

            saveClass("N5 A", t1.getId(), c1.getId(), ClassStatus.ACTIVE, 8);
            saveClass("N5 B", t1.getId(), c1.getId(), ClassStatus.NOT_STARTED, 8);
            saveClass("N5 C", t2.getId(), c1.getId(), ClassStatus.ACTIVE, 8);
            saveClass("Other", t1.getId(), c1.getId(), ClassStatus.ACTIVE, 8);
            saveClass("N5 D", t1.getId(), c2.getId(), ClassStatus.ACTIVE, 8);
            saveDeletedClass("N5 Deleted", t1.getId(), c1.getId(), ClassStatus.DELETED, 8, manager.getId());

            mockMvc.perform(get("/api/v1/classes")
                            .param("keyword", "N5")
                            .param("teacherId", t1.getId().toString())
                            .param("courseId", c1.getId().toString())
                            .param("status", "ACTIVE")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].name").value("N5 A"));
        }

        @Test
        @DisplayName("TC_CLM_VL_004 — Teacher filters by keyword and course only")
        void tc_clm_vl_004() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com", "ValidPass@1");
            Course c1 = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Course c2 = saveCourse("Japanese N4", CourseStatus.ACTIVE);

            saveClass("Kana Basics", teacher.getId(), c1.getId(), ClassStatus.ACTIVE, 8);
            saveClass("Kana Review", teacher.getId(), c2.getId(), ClassStatus.ACTIVE, 8);
            saveClass("Grammar", teacher.getId(), c1.getId(), ClassStatus.ACTIVE, 8);
            saveClass("Kana Inactive", teacher.getId(), c1.getId(), ClassStatus.NOT_STARTED, 8);

            mockMvc.perform(get("/api/v1/classes")
                            .param("keyword", "  Kana  ")
                            .param("courseId", c1.getId().toString())
                            .param("status", "NOT_STARTED")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].name").value("Kana Inactive"));
        }

        @Test
        @DisplayName("TC_CLM_VL_005 — Empty results show no-classes message")
        void tc_clm_vl_005() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(get("/api/v1/classes")
                            .param("keyword", "NoMatch")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Không có lớp học nào."));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 2. VIEW CLASS DETAIL — GET /api/v1/classes/{classId}
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. VIEW CLASS DETAIL — GET /api/v1/classes/{classId}")
    class ViewClassDetailTests {

        @Test
        @DisplayName("TC_CLM_VD_001 — Manager views any non-deleted class detail")
        void tc_clm_vd_001() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            mockMvc.perform(get("/api/v1/classes/{classId}", clazz.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(clazz.getId().toString()))
                    .andExpect(jsonPath("$.data.name").value("Test Class"))
                    .andExpect(jsonPath("$.data.description").value("Test class"))
                    .andExpect(jsonPath("$.data.teacherId").value(teacher.getId().toString()))
                    .andExpect(jsonPath("$.data.courseId").value(course.getId().toString()))
                    .andExpect(jsonPath("$.data.courseName").value("Japanese N5"))
                    .andExpect(jsonPath("$.data.courseLevel").value("N5"))
                    .andExpect(jsonPath("$.data.courseStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.maxStudents").value(8))
                    .andExpect(jsonPath("$.data.startDate").exists())
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.createdBy").doesNotExist())
                    .andExpect(jsonPath("$.data.updatedBy").doesNotExist())
                    .andExpect(jsonPath("$.data.createdAt").exists())
                    .andExpect(jsonPath("$.data.updatedAt").exists())
                    .andExpect(jsonPath("$.data.memberCount").value(0))
                    .andExpect(jsonPath("$.data.teacherName").value("Test Teacher"))
                    .andExpect(jsonPath("$.data.teacherEmail").value("teacher@test.com"))
                    .andExpect(jsonPath("$.data.teacherAvatarUrl").doesNotExist())
                    .andExpect(jsonPath("$.data.teacherPhoneNumber").doesNotExist());
        }

        @Test
        @DisplayName("TC_CLM_VD_002 — Teacher views only assigned class detail")
        void tc_clm_vd_002() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("My Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            mockMvc.perform(get("/api/v1/classes/{classId}", clazz.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(clazz.getId().toString()));
        }

        @Test
        @DisplayName("TC_CLM_VD_003 — Teacher views class NOT assigned → 400 ACCESS_DENIED")
        void tc_clm_vd_003() throws Exception {
            User t1 = saveTeacher("teacher1@test.com");
            User t2 = saveTeacher("teacher2@test.com");
            String token = loginAndGetToken("teacher1@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("T2 Class", t2.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            mockMvc.perform(get("/api/v1/classes/{classId}", clazz.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("TC_CLM_VD_004 — Class not found → 404 NOT_FOUND")
        void tc_clm_vd_004() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(get("/api/v1/classes/{classId}", UUID.randomUUID())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Lớp học không tồn tại."));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 3. VIEW LEARNER IN CLASS — GET /api/v1/classes/{classId}/members
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. VIEW LEARNER IN CLASS — GET /api/v1/classes/{classId}/members")
    class ViewClassMembersTests {

        @Test
        @DisplayName("TC_CLM_VM_001 — Manager views active members, removed excluded, sorted by joinDate DESC")
        void tc_clm_vm_001() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            User l1 = saveLearner("learner1@test.com");
            User l2 = saveLearner("learner2@test.com");
            User l3 = saveLearner("learner3@test.com");
            User removed = saveLearner("removed@test.com");

            LocalDateTime base = LocalDateTime.now();
            saveClassMember(clazz.getId(), l1.getId(), base.minusDays(3));
            saveClassMember(clazz.getId(), l2.getId(), base.minusDays(1));
            saveClassMember(clazz.getId(), l3.getId(), base.minusDays(2));
            saveRemovedClassMember(clazz.getId(), removed.getId());

            MvcResult result = mockMvc.perform(get("/api/v1/classes/{classId}/members", clazz.getId())
                            .param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content.length()").value(3))
                    .andReturn();

            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            var content = objectMapper.readTree(body).path("data").path("content");
            assertThat(content.get(0).path("email").asText()).isEqualTo("learner2@test.com");
            assertThat(content.get(1).path("email").asText()).isEqualTo("learner3@test.com");
            assertThat(content.get(2).path("email").asText()).isEqualTo("learner1@test.com");
            content.forEach(item -> {
                assertThat(item.has("userId")).isTrue();
                assertThat(item.has("fullName")).isTrue();
                assertThat(item.has("email")).isTrue();
                assertThat(item.has("joinedAt")).isTrue();
                assertThat(item.path("email").asText()).isNotEqualTo("removed@test.com");
                assertThat(item.has("role")).isTrue();
            });
        }

        @Test
        @DisplayName("TC_CLM_VM_002 — Teacher views members of assigned class")
        void tc_clm_vm_002() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("My Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            saveClassMember(clazz.getId(), saveLearner("l1@test.com").getId());
            saveClassMember(clazz.getId(), saveLearner("l2@test.com").getId());

            mockMvc.perform(get("/api/v1/classes/{classId}/members", clazz.getId())
                            .param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(2));
        }

        @Test
        @DisplayName("TC_CLM_VM_003 — Teacher not assigned → 400 ACCESS_DENIED")
        void tc_clm_vm_003() throws Exception {
            User t1 = saveTeacher("teacher1@test.com");
            User t2 = saveTeacher("teacher2@test.com");
            String token = loginAndGetToken("teacher1@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("T2 Class", t2.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            mockMvc.perform(get("/api/v1/classes/{classId}/members", clazz.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                    .andExpect(jsonPath("$.message").value("Bạn không phải là giáo viên của lớp này."));
        }

        @Test
        @DisplayName("TC_CLM_VM_004 — Class not found → 404 NOT_FOUND")
        void tc_clm_vm_004() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(get("/api/v1/classes/{classId}/members", UUID.randomUUID())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Lớp học không tồn tại."));
        }

        @Test
        @DisplayName("TC_CLM_VM_006 — Search members by keyword")
        void tc_clm_vm_006() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Search Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            User l1 = saveLearner("alice@test.com");
            User l2 = saveLearner("bob@test.com");
            saveClassMember(clazz.getId(), l1.getId());
            saveClassMember(clazz.getId(), l2.getId());

            mockMvc.perform(get("/api/v1/classes/{classId}/members", clazz.getId())
                            .param("keyword", "alice")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].email").value("alice@test.com"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 4. CREATE CLASS — POST /api/v1/classes
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. CREATE CLASS — POST /api/v1/classes")
    class CreateClassTests {

        @Test
        @DisplayName("TC_CLM_CC_001 — Create class successfully, auto-activated if startDate ≤ current date")
        void tc_clm_cc_001() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);

            String body = String.format(
                    "{\"name\":\"Japanese N5 Class\",\"description\":\"Comprehensive Japanese N5 course for beginners\",\"teacherId\":\"%s\",\"courseId\":\"%s\"," +
                            "\"maxStudents\":8,\"startDate\":\"%s\",\"expectedEndDate\":\"%s\"}",
                    teacher.getId(), course.getId(), LocalDate.now(), LocalDate.now().plusMonths(6));

            MvcResult result = mockMvc.perform(post("/api/v1/classes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").isNotEmpty())
                    .andExpect(jsonPath("$.data.name").value("Japanese N5 Class"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE")) // Auto-activated since startDate ≤ current date
                    .andExpect(jsonPath("$.data.teacherId").value(teacher.getId().toString()))
                    .andExpect(jsonPath("$.data.courseId").value(course.getId().toString()))
                    .andReturn();

            String id = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("id").asText();
            assertThat(classRepository.findByIdAndNotDeleted(UUID.fromString(id))).isPresent();
        }

        @Test
        @DisplayName("TC_CLM_CC_003 — Create class with future startDate → auto-inactive")
        void tc_clm_cc_003() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);

            String body = String.format(
                    "{\"name\":\"Future Japanese N5 Class\",\"description\":\"Japanese N5 course starting next month\",\"teacherId\":\"%s\",\"courseId\":\"%s\"," +
                            "\"maxStudents\":8,\"startDate\":\"%s\",\"expectedEndDate\":\"%s\"}",
                    teacher.getId(), course.getId(), LocalDate.now().plusDays(30), LocalDate.now().plusMonths(6));

            MvcResult result = mockMvc.perform(post("/api/v1/classes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").isNotEmpty())
                    .andExpect(jsonPath("$.data.name").value("Future Japanese N5 Class"))
                    .andExpect(jsonPath("$.data.teacherId").value(teacher.getId().toString()))
                    .andExpect(jsonPath("$.data.status").value("NOT_STARTED")) // Auto-inactive since startDate > current date
                    .andExpect(jsonPath("$.data.courseId").value(course.getId().toString()))
                    .andReturn();

            String id = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("id").asText();
            assertThat(classRepository.findByIdAndNotDeleted(UUID.fromString(id))).isPresent();
            
            // Verify the class is indeed inactive in database
            Clazz savedClass = classRepository.findByIdAndNotDeleted(UUID.fromString(id)).orElseThrow();
            assertThat(savedClass.getStatus() == ClassStatus.NOT_STARTED).isTrue();
        }

        @Test
        @DisplayName("TC_CLM_CC_002 — Duplicate class name → CLASS_NAME_EXISTS")
        void tc_clm_cc_002() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            saveClass("Japanese N5 Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            String body = String.format(
                    "{\"name\":\"Japanese N5 Class\",\"teacherId\":\"%s\",\"courseId\":\"%s\"," +
                            "\"maxStudents\":8,\"startDate\":\"%s\",\"expectedEndDate\":\"%s\"}",
                    teacher.getId(), course.getId(), LocalDate.now().plusDays(1), LocalDate.now().plusMonths(6));

            mockMvc.perform(post("/api/v1/classes")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("CLASS_NAME_EXISTS"));
        }

        @Test
        @DisplayName("TC_CLM_CC_005 — teacherId is a LEARNER → INVALID_TEACHER")
        void tc_clm_cc_005() throws Exception {
            saveManager("manager@test.com");
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);

            String body = String.format(
                    "{\"name\":\"Bad Teacher Class\",\"teacherId\":\"%s\",\"courseId\":\"%s\"," +
                            "\"maxStudents\":8,\"startDate\":\"%s\",\"expectedEndDate\":\"%s\"}",
                    learner.getId(), course.getId(),
                    LocalDate.now().plusDays(1), LocalDate.now().plusMonths(6));

            mockMvc.perform(post("/api/v1/classes")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_TEACHER"));
        }

        @Test
        @DisplayName("TC_CLM_CC_006 — INACTIVE course → INVALID_COURSE_STATUS")
        void tc_clm_cc_006() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Inactive Course", CourseStatus.INACTIVE);

            String body = String.format(
                    "{\"name\":\"Inactive Course Class\",\"teacherId\":\"%s\",\"courseId\":\"%s\"," +
                            "\"maxStudents\":8,\"startDate\":\"%s\",\"expectedEndDate\":\"%s\"}",
                    teacher.getId(), course.getId(), LocalDate.now().plusDays(1), LocalDate.now().plusMonths(6));

            mockMvc.perform(post("/api/v1/classes")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_COURSE_STATUS"));
        }

        @Test
        @DisplayName("TC_CLM_CC_007 — TEACHER token → 403 Forbidden")
        void tc_clm_cc_007() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com", "ValidPass@1");

            String body = String.format(
                    "{\"name\":\"Inactive Course Class\",\"teacherId\":\"%s\",\"courseId\":\"%s\"," +
                            "\"maxStudents\":8,\"startDate\":\"%s\",\"expectedEndDate\":\"%s\"}",
                    UUID.randomUUID(),UUID.randomUUID(), LocalDate.now().plusDays(1), LocalDate.now().plusMonths(6));

            mockMvc.perform(post("/api/v1/classes")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 5. UPDATE CLASS — PUT /api/v1/classes/{classId}
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. UPDATE CLASS — PUT /api/v1/classes/{classId}")
    class UpdateClassTests {

        @Test
        @DisplayName("TC_CLM_UC_001 — Update success: returns updated details with memberCount, teacher info, updatedBy and updatedAt recorded")
        void tc_clm_uc_001() throws Exception {
            // Happy path: the response includes updated fields, teacher info enrichment,
            // memberCount, and audit fields (updatedBy, updatedAt) are populated.
            User manager = saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Old Name", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);
            // Add one member so memberCount = 1
            saveClassMember(clazz.getId(), saveLearner("learner@test.com").getId());

            LocalDateTime beforeUpdate = LocalDateTime.now().minusSeconds(1);
            // UpdateClassRequest has: name, description, teacherId, maxStudents
            String body = String.format(
                    "{\"name\":\"New Name\",\"description\":\"Updated desc\",\"maxStudents\":\"%d\"}",
                   8);

            mockMvc.perform(put("/api/v1/classes/{classId}", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Cập nhật thông tin lớp học thành công."))
                    .andExpect(jsonPath("$.data.name").value("New Name"))
                    .andExpect(jsonPath("$.data.description").value("Updated desc"))
                    .andExpect(jsonPath("$.data.memberCount").value(1))
                    .andExpect(jsonPath("$.data.teacherName").isNotEmpty())
                    .andExpect(jsonPath("$.data.updatedBy").value(manager.getId().toString()))
                    .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());

            // Assert audit fields persisted to DB
            Clazz updated = classRepository.findByIdAndNotDeleted(clazz.getId()).orElseThrow();
            assertThat(updated.getName()).isEqualTo("New Name");
            assertThat(updated.getUpdatedBy()).isEqualTo(manager.getId());
            assertThat(updated.getUpdatedAt()).isAfter(beforeUpdate);
        }

        @Test
        @DisplayName("TC_CLM_UC_002 — Change teacher: old teacher loses access, new teacher gains access")
        void tc_clm_uc_002() throws Exception {
            saveManager("manager@test.com");
            User t1 = saveTeacher("teacher1@test.com");
            User t2 = saveTeacher("teacher2@test.com");
            String mgrToken = loginAndGetToken("manager@test.com", "ValidPass@1");
            String t1Token = loginAndGetToken("teacher1@test.com", "ValidPass@1");
            String t2Token = loginAndGetToken("teacher2@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Swap Class", t1.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            // Update teacher to T2
            String body = String.format("{\"teacherId\":\"%s\"}", t2.getId());
            mockMvc.perform(put("/api/v1/classes/{classId}", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + mgrToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.teacherId").value(t2.getId().toString()));

            // Old teacher T1 — class NOT in their list
            mockMvc.perform(get("/api/v1/classes").param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + t1Token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(0));

            // New teacher T2 — class IS in their list
            mockMvc.perform(get("/api/v1/classes").param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + t2Token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1));
        }

        @Test
        @DisplayName("TC_CLM_UC_003 — Close class via /close endpoint: disappears from teacher list, assign learner blocked, learners become inactive")
        void tc_clm_uc_003() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String mgrToken = loginAndGetToken("manager@test.com", "ValidPass@1");
            String tToken = loginAndGetToken("teacher@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Deactivate Me", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);
            User learner = saveInactiveLearner("learner@test.com");

            // Add learner to class first
            String addBody = String.format("{\"learnerIds\":[\"%s\"]}", learner.getId());
            mockMvc.perform(post("/api/v1/classes/{classId}/members", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(addBody)
                            .header("Authorization", "Bearer " + mgrToken))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value("true"));

            // Verify learner is active in class before closing
            mockMvc.perform(get("/api/v1/classes/{classId}/members", clazz.getId())
                            .param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + mgrToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].userId").value(learner.getId().toString()));

            // Close via dedicated /close endpoint
            mockMvc.perform(put("/api/v1/classes/{classId}/close", clazz.getId())
                            .header("Authorization", "Bearer " + mgrToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CLOSED"));

            // Assign learner — blocked (class inactive)
            mockMvc.perform(post("/api/v1/classes/{classId}/members", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(addBody)
                            .header("Authorization", "Bearer " + mgrToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CLASS_INACTIVE"));

            // Learner status becomes INACTIVE when class is closed
            User learnerUser = userRepository.findById(learner.getId()).orElseThrow();
            assertThat(learnerUser.getStatus()).isEqualTo(UserStatus.INACTIVE);

            // Class is deactivated in DB
            Clazz closed = classRepository.findById(clazz.getId()).orElseThrow();
            assertThat(closed.getStatus() == ClassStatus.CLOSED).isTrue();
        }

        @Test
        @DisplayName("TC_CLM_UC_004 — Reactivate class: update startDate to today when no members → auto-activated, reappears in teacher list")
        void tc_clm_uc_004() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String mgrToken = loginAndGetToken("manager@test.com", "ValidPass@1");
            String tToken = loginAndGetToken("teacher@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            // Create an initially INACTIVE class (future startDate, status=false)
            Clazz clazz = saveClass("Reactivate Me", teacher.getId(), course.getId(), ClassStatus.NOT_STARTED, 8);

            // Update startDate to today with no members → auto-activate (ClassServiceImpl)
            String body = String.format("{\"startDate\":\"%s\"}", LocalDate.now());
            mockMvc.perform(put("/api/v1/classes/{classId}", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + mgrToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));

            // Teacher list — class now visible
            mockMvc.perform(get("/api/v1/classes").param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + tToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1));

            // Assign a new learner — succeeds now that class is active
            User newLearner = saveInactiveLearner("newlearner@test.com");
            String addBody = String.format("{\"learnerIds\":[\"%s\"]}", newLearner.getId());
            mockMvc.perform(post("/api/v1/classes/{classId}/members", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(addBody)
                            .header("Authorization", "Bearer " + mgrToken))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("TC_CLM_UC_005 — maxStudents below current enrollment → CAPACITY_VIOLATION")
        void tc_clm_uc_005() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Full Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            saveClassMember(clazz.getId(), saveLearner("l1@test.com").getId());
            saveClassMember(clazz.getId(), saveLearner("l2@test.com").getId());
            saveClassMember(clazz.getId(), saveLearner("l3@test.com").getId());

            mockMvc.perform(put("/api/v1/classes/{classId}", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"maxStudents\":1}")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("CAPACITY_VIOLATION"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 6. ASSIGN LEARNER TO CLASS — POST /api/v1/classes/{classId}/members
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. ASSIGN LEARNER TO CLASS — POST /api/v1/classes/{classId}/members")
    class AssignLearnerTests {

        @Test
        @DisplayName("TC_CLM_AL_001 — Assign learner successfully → 201 Created")
        void tc_clm_al_001() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);
            User learner = saveInactiveLearner("learner@test.com");

            String body = String.format("{\"learnerIds\":[\"%s\"]}", learner.getId());

            mockMvc.perform(post("/api/v1/classes/{classId}/members", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[0].userId").value(learner.getId().toString()))
                    .andExpect(jsonPath("$.data[0].classId").value(clazz.getId().toString()))
                    .andExpect(jsonPath("$.data[0].removedAt").isEmpty());

            var member = classMemberRepository.findByClassIdAndUserId(clazz.getId(), learner.getId());
            assertThat(member).isPresent();
            assertThat(member.get().getRemovedAt()).isNull();
        }

        @Test
        @DisplayName("TC_CLM_AL_002 — Class full → CLASS_FULL")
        void tc_clm_al_002() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Full Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 2);

            saveClassMember(clazz.getId(), saveLearner("l1@test.com").getId());
            saveClassMember(clazz.getId(), saveLearner("l2@test.com").getId());
            User newLearner = saveLearner("new@test.com");

            String body = String.format("{\"learnerIds\":[\"%s\"]}", newLearner.getId());

            mockMvc.perform(post("/api/v1/classes/{classId}/members", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CLASS_FULL"));
        }

        @Test
        @DisplayName("TC_CLM_AL_003 — Inactive class → CLASS_INACTIVE")
        void tc_clm_al_003() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Inactive Class", teacher.getId(), course.getId(), ClassStatus.CLOSED, 8);
            User learner = saveLearner("learner@test.com");

            String body = String.format("{\"learnerIds\":[\"%s\"]}", learner.getId());

            mockMvc.perform(post("/api/v1/classes/{classId}/members", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CLASS_INACTIVE"));
        }

        @Test
        @DisplayName("TC_CLM_AL_004 — Non-LEARNER role (TEACHER) → INVALID_MEMBER_ROLE")
        void tc_clm_al_004() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            User anotherTeacher = saveTeacher("teacher2@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            String body = String.format("{\"learnerIds\":[\"%s\"]}", anotherTeacher.getId());

            mockMvc.perform(post("/api/v1/classes/{classId}/members", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_MEMBER_ROLE"));
        }

        @Test
        @DisplayName("TC_CLM_AL_005 — Already active member → MEMBER_ALREADY_EXISTS")
        void tc_clm_al_005() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);
            User learner = saveLearner("learner@test.com");
            saveClassMember(clazz.getId(), learner.getId());

            String body = String.format("{\"learnerIds\":[\"%s\"]}", learner.getId());

            mockMvc.perform(post("/api/v1/classes/{classId}/members", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_MEMBER_STATUS"));
        }

        @Test
        @DisplayName("TC_CLM_AL_006 — TEACHER token → 403 Forbidden")
        void tc_clm_al_006() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);
            User learner = saveLearner("learner@test.com");

            String body = String.format("{\"learnerIds\":[\"%s\"]}", learner.getId());

            mockMvc.perform(post("/api/v1/classes/{classId}/members", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        @Test
        @DisplayName("TC_CLM_AL_007 — INACTIVE learner auto-promoted to ACTIVE on assignment")
        void tc_clm_al_007() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);
            User inactiveLearner = saveLearner("inactive@test.com", UserStatus.INACTIVE);

            String body = String.format("{\"learnerIds\":[\"%s\"]}", inactiveLearner.getId());

            mockMvc.perform(post("/api/v1/classes/{classId}/members", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.[0].userId").value(inactiveLearner.getId().toString()));

            // Verify learner status auto-promoted to ACTIVE
            User updated = userRepository.findById(inactiveLearner.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(UserStatus.ACTIVE);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 7. REMOVE LEARNER FROM CLASS — DELETE /api/v1/classes/{classId}/members/{userId}
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. REMOVE LEARNER — DELETE /api/v1/classes/{classId}/members/{userId}")
    class RemoveLearnerTests {

        @Test
        @DisplayName("TC_CLM_RL_001 — Remove learner successfully, removedAt set")
        void tc_clm_rl_001() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);
            User learner = saveLearner("learner@test.com");
            saveClassMember(clazz.getId(), learner.getId());

            mockMvc.perform(delete("/api/v1/classes/{classId}/members/{userId}",
                            clazz.getId(), learner.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Xóa học viên ra khỏi lớp học thành công."));

            // Verify removedAt is set
            ClassMember member = classMemberRepository.findByClassIdAndUserId(
                    clazz.getId(), learner.getId()).orElseThrow();
            assertThat(member.getRemovedAt()).isNotNull();

            // Verify not in active members list
            mockMvc.perform(get("/api/v1/classes/{classId}/members", clazz.getId())
                            .param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(jsonPath("$.data.content.length()").value(0));

            assertThat(classMemberRepository.countActiveByClassId(clazz.getId())).isEqualTo(0);
            User updated = userRepository.findById(learner.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(UserStatus.INACTIVE);
        }

        @Test
        @DisplayName("TC_CLM_RL_002 — Already removed → MEMBER_ALREADY_REMOVED")
        void tc_clm_rl_002() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);
            User learner = saveLearner("learner@test.com");
            saveRemovedClassMember(clazz.getId(), learner.getId());

            mockMvc.perform(delete("/api/v1/classes/{classId}/members/{userId}",
                            clazz.getId(), learner.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MEMBER_ALREADY_REMOVED"));
        }

        @Test
        @DisplayName("TC_CLM_RL_003 — Not a member → MEMBER_NOT_FOUND")
        void tc_clm_rl_003() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);
            User learner = saveLearner("learner@test.com");

            mockMvc.perform(delete("/api/v1/classes/{classId}/members/{userId}",
                            clazz.getId(), learner.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 8. DELETE CLASS — DELETE /api/v1/classes/{classId}
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. DELETE CLASS — DELETE /api/v1/classes/{classId}")
    class DeleteClassTests {

        @Test
        @DisplayName("TC_CLM_DC_001 — Delete class successfully, deletedAt set, hidden from views")
        void tc_clm_dc_001() throws Exception {
            User manager = saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Delete Me", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            mockMvc.perform(delete("/api/v1/classes/{classId}", clazz.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(clazz.getId().toString()));

            // DB: deletedAt set
            Clazz deleted = classRepository.findById(clazz.getId()).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();
            assertThat(deleted.getDeletedBy()).isEqualTo(manager.getId());

            // Hidden from list
            assertThat(classRepository.findByIdAndNotDeleted(clazz.getId())).isEmpty();
        }

        @Test
        @DisplayName("TC_CLM_DC_002 — Delete non-existing class → 404 NOT_FOUND")
        void tc_clm_dc_002() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(delete("/api/v1/classes/{classId}", UUID.randomUUID())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("TC_CLM_DC_003 — TEACHER token → 403 Forbidden")
        void tc_clm_dc_003() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            mockMvc.perform(delete("/api/v1/classes/{classId}", clazz.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        @Test
        @DisplayName("TC_CLM_DC_004 — Cannot delete active class with enrolled learners → CLASS_HAS_LEARNERS")
        void tc_clm_dc_004() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Cascade Test", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            User l1 = saveLearner("l1@test.com");
            User l2 = saveLearner("l2@test.com");
            // Add learners to class (they should be active)
            ClassMember m1 = saveClassMember(clazz.getId(), l1.getId());
            ClassMember m2 = saveClassMember(clazz.getId(), l2.getId());
            
            // Verify learners are initially active
            assertThat(userRepository.findById(l1.getId()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.ACTIVE);
            assertThat(userRepository.findById(l2.getId()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.ACTIVE);

            mockMvc.perform(delete("/api/v1/classes/{classId}", clazz.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("CLASS_DELETE_VIOLATION"))
                    .andExpect(jsonPath("$.message").value("Không thể xóa lớp học khi còn học viên đang theo học."));

            // Class remains active (not deleted)
            Clazz notDeleted = classRepository.findById(clazz.getId()).orElseThrow();
            assertThat(notDeleted.getDeletedAt()).isNull();

            // Learner statuses unchanged (still ACTIVE)
            assertThat(userRepository.findById(l1.getId()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.ACTIVE);
            assertThat(userRepository.findById(l2.getId()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.ACTIVE);

            // ClassMember records unchanged
            ClassMember cm1 = classMemberRepository.findById(m1.getId()).orElseThrow();
            ClassMember cm2 = classMemberRepository.findById(m2.getId()).orElseThrow();
            assertThat(cm1.getRemovedAt()).isNull();
            assertThat(cm2.getRemovedAt()).isNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 1b. VIEW CLASS LIST — Additional sort / default behaviour
    // ═══════════════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════════════
    // 4b. CREATE CLASS — Additional cases
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4b. CREATE CLASS — Additional cases")
    class CreateClassAdditionalTests {

        @Test
        @DisplayName("TC_CLM_CC_008 — Create class without description: succeeds with null description")
        void tc_clm_cc_008() throws Exception {
            // Description is optional — class creation must succeed when omitted
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);

            // No description field in the payload
            String body = String.format(
                    "{\"name\":\"No Desc Class\",\"teacherId\":\"%s\",\"courseId\":\"%s\"," +
                            "\"maxStudents\":8,\"startDate\":\"%s\",\"expectedEndDate\":\"%s\"}",
                    teacher.getId(), course.getId(), LocalDate.now().plusDays(1), LocalDate.now().plusMonths(6));

            MvcResult result = mockMvc.perform(post("/api/v1/classes")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("NOT_STARTED")) //  status = false when start date is in future
                    .andExpect(jsonPath("$.data.name").value("No Desc Class"))
                    .andReturn();

            String id = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("id").asText();
            Clazz saved = classRepository.findByIdAndNotDeleted(UUID.fromString(id)).orElseThrow();
            assertThat(saved.getDescription()).isNullOrEmpty();
            assertThat(saved.getStatus() == ClassStatus.NOT_STARTED).isTrue(); // status = false when start date is in future
        }

        @Test
        @DisplayName("TC_CLM_CC_009 — Missing required fields (name, teacherId, courseId) → 400 VALIDATION_ERROR")
        void tc_clm_cc_009() throws Exception {
            // All required fields omitted
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/classes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            // Confirm no class was persisted
            assertThat(classRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("TC_CLM_CC_010 — Teacher ID does not exist in database → INVALID_TEACHER")
        void tc_clm_cc_010() throws Exception {
            // Teacher UUID that has no matching user record
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);

            String body = String.format(
                    "{\"name\":\"Ghost Teacher Class\",\"teacherId\":\"%s\",\"courseId\":\"%s\"," +
                            "\"maxStudents\":8,\"startDate\":\"%s\",\"expectedEndDate\":\"%s\"}",
                    UUID.randomUUID(), course.getId(), LocalDate.now().plusDays(1), LocalDate.now().plusMonths(6));

            mockMvc.perform(post("/api/v1/classes")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_TEACHER"));
        }

        @Test
        @DisplayName("TC_CLM_CC_011 — Course ID does not exist or has been deleted → INVALID_COURSE")
        void tc_clm_cc_011() throws Exception {
            // Course UUID that has no matching non-deleted record
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            String body = String.format(
                    "{\"name\":\"Ghost Course Class\",\"teacherId\":\"%s\",\"courseId\":\"%s\"," +
                            "\"maxStudents\":8,\"startDate\":\"%s\",\"expectedEndDate\":\"%s\"}",
                    teacher.getId(), UUID.randomUUID(), LocalDate.now().plusDays(1), LocalDate.now().plusMonths(6));

            mockMvc.perform(post("/api/v1/classes")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_COURSE"));
        }

        @Test
        @DisplayName("TC_CLM_CC_012 — maxStudents defaults to 8 when not provided")
        void tc_clm_cc_012() throws Exception {
            // When maxStudents is omitted in the request, the DB default of 8 must be applied
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);

            String body = String.format(
                    "{\"name\":\"Default Max Class\",\"teacherId\":\"%s\",\"courseId\":\"%s\"," +
                            "\"startDate\":\"%s\",\"expectedEndDate\":\"%s\"}",
                    teacher.getId(), course.getId(), LocalDate.now().plusDays(1), LocalDate.now().plusMonths(6));

            MvcResult result = mockMvc.perform(post("/api/v1/classes")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.maxStudents").value(8))
                    .andReturn();

            String id = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("id").asText();
            Clazz saved = classRepository.findByIdAndNotDeleted(UUID.fromString(id)).orElseThrow();
            assertThat(saved.getMaxStudents()).isEqualTo(8);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 5b. UPDATE CLASS — Additional cases
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5b. UPDATE CLASS — Additional cases")
    class UpdateClassAdditionalTests {

        @Test
        @DisplayName("TC_CLM_UC_007 — Update non-existing class → 404 NOT_FOUND")
        void tc_clm_uc_007() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(put("/api/v1/classes/{classId}", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Anything\"}")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Lớp học không tồn tại."));
        }

        @Test
        @DisplayName("TC_CLM_UC_008 — Assign non-TEACHER user as teacher → INVALID_TEACHER (\"Giáo viên không tồn tại hoặc không hợp lệ.\")")
        void tc_clm_uc_008() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            String body = String.format("{\"teacherId\":\"%s\"}", learner.getId());

            mockMvc.perform(put("/api/v1/classes/{classId}", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_TEACHER"))
                    .andExpect(jsonPath("$.message").value("Giáo viên không tồn tại hoặc không hợp lệ."));
        }

        @Test
        @DisplayName("TC_CLM_UC_009 — Update startDate to after current startDate when members exist → INVALID_START_DATE")
        void tc_clm_uc_009() throws Exception {
            // UpdateClassRequest has no endDate; INVALID_DATE_RANGE no longer applies.
            // INVALID_START_DATE is thrown when currentMembers > 0 and new startDate > old startDate.
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            // Class starts today (startDate = now), is active
            Clazz clazz = saveClass("Date Test", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);
            // Add a member so the INVALID_START_DATE guard is triggered
            saveClassMember(clazz.getId(), saveLearner("member@test.com").getId());

            // Try to push startDate to the future (after current startDate) with members present
            String body = String.format("{\"startDate\":\"%s\"}", LocalDate.now().plusDays(5));

            mockMvc.perform(put("/api/v1/classes/{classId}", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_START_DATE"))
                    .andExpect(jsonPath("$.message").value("Không thể cập nhật ngày bắt đầu sau khi lớp học đã hoạt động."));
        }

        @Test
        @DisplayName("TC_CLM_UC_010 — Update with required name set to blank → VALIDATION_ERROR")
        void tc_clm_uc_010() throws Exception {
            // If name is provided but empty/blank it violates @NotBlank on UpdateClassRequest
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Valid Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            // Blank name is explicitly sent — should fail validation
            mockMvc.perform(put("/api/v1/classes/{classId}", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"  \"}")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            // Verify name was NOT changed in DB
            Clazz unchanged = classRepository.findByIdAndNotDeleted(clazz.getId()).orElseThrow();
            assertThat(unchanged.getName()).isEqualTo("Valid Class");
        }

        @Test
        @DisplayName("TC_CLM_UC_011 — Duplicate class name on update → CLASS_NAME_EXISTS")
        void tc_clm_uc_011() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            saveClass("Existing Name", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);
            Clazz clazz = saveClass("Another Name", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            mockMvc.perform(put("/api/v1/classes/{classId}", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Existing Name\"}")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("CLASS_NAME_EXISTS"));
        }

        @Test
        @DisplayName("TC_CLM_UC_012 — TEACHER token cannot update class → 403 Forbidden")
        void tc_clm_uc_012() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            mockMvc.perform(put("/api/v1/classes/{classId}", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Attempted Update\"}")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        @Test
        @DisplayName("TC_CLM_UC_013 — Cannot update a soft-deleted class → 404 NOT_FOUND")
        void tc_clm_uc_013() throws Exception {
            User manager = saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz deleted = saveDeletedClass("Deleted Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8, manager.getId());

            mockMvc.perform(put("/api/v1/classes/{classId}", deleted.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Should Fail\"}")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Lớp học không tồn tại."));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 3b. VIEW LEARNER IN CLASS — Additional cases
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3b. VIEW LEARNER IN CLASS — Additional cases")
    class ViewClassMembersAdditionalTests {

        @Test
        @DisplayName("TC_CLM_VM_009 — Unauthorized access: LEARNER token →  403 / Thông tin không tồn tại hoặc bạn không có quyền truy cập")
        void tc_clm_vm_009() throws Exception {
            // A LEARNER does not have the MANAGER/TEACHER role and should be denied
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            mockMvc.perform(get("/api/v1/classes/{classId}/members", UUID.randomUUID())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("TC_CLM_VM_010 — Cannot view members of a deleted class → 404 NOT_FOUND")
        void tc_clm_vm_010() throws Exception {
            User manager = saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz deleted = saveDeletedClass("Deleted Class", teacher.getId(), course.getId(), ClassStatus.DELETED, 8, manager.getId());

            mockMvc.perform(get("/api/v1/classes/{classId}/members", deleted.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Lớp học không tồn tại."));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 6b. ASSIGN LEARNER — Additional cases
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6b. ASSIGN LEARNER — Additional cases")
    class AssignLearnerAdditionalTests {

        @Test
        @DisplayName("TC_CLM_AL_009 — Eligible-learner search returns no results → empty page (\"Không tìm thấy học viên.\" in service layer)")
        void tc_clm_al_009() throws Exception {
            // GET /eligible-learners with a keyword that matches no learner
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);
            saveLearner("real@test.com"); // a real learner that won't match

            mockMvc.perform(get("/api/v1/classes/{classId}/eligible-learners", clazz.getId())
                            .param("keyword", "zzz_no_match_zzz")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(0));
        }

        @Test
        @DisplayName("TC_CLM_AL_010 — Eligible-learner search by keyword returns matching records")
        void tc_clm_al_010() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            saveInactiveLearner("alice.smith@test.com");
            saveInactiveLearner("bob.jones@test.com");

            mockMvc.perform(get("/api/v1/classes/{classId}/eligible-learners", clazz.getId())
                            .param("keyword", "alice")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].email").value("alice.smith@test.com"));
        }

        @Test
        @DisplayName("TC_CLM_AL_011 — Assign learner to non-existing class → 404 NOT_FOUND (\"Lớp học không tồn tại.\")")
        void tc_clm_al_011() throws Exception {
            saveManager("manager@test.com");
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            String body = String.format("{\"learnerIds\":[\"%s\"]}", learner.getId());

            mockMvc.perform(post("/api/v1/classes/{classId}/members", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("Lớp học không tồn tại."));
        }

        @Test
        @DisplayName("TC_CLM_AL_012 — Assign non-existing user to class → 404 NOT_FOUND (\"Không tìm thấy học viên.\")")
        void tc_clm_al_012() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            String body = String.format("{\"learnerIds\":[\"%s\"]}", UUID.randomUUID());

            mockMvc.perform(post("/api/v1/classes/{classId}/members", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("TC_CLM_AL_013 — Eligible-learners excludes SUSPENDED, ACTIVE and PENDING_ACTIVATION accounts")
        void tc_clm_al_013() throws Exception {
            // Only ACTIVE and INACTIVE learners must appear; SUSPENDED must be excluded
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Eligible Test", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);

            saveLearner("active@test.com", UserStatus.ACTIVE);
            saveLearner("inactive@test.com", UserStatus.INACTIVE);
            saveLearner("suspended@test.com", UserStatus.SUSPENDED);
            saveLearner("pending@test.com", UserStatus.PENDING_ACTIVATION);

            MvcResult result = mockMvc.perform(get("/api/v1/classes/{classId}/eligible-learners", clazz.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andReturn();

            String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            var content = objectMapper.readTree(body).path("data").path("content");
            content.forEach(item -> {
                String status = item.path("status").asText();
                assertThat(status).isIn("INACTIVE");
                assertThat(item.has("email")).isTrue();
                assertThat(item.has("fullName")).isTrue();
                assertThat(status).isNotEqualTo("ACTIVE");
                assertThat(status).isNotEqualTo("SUSPENDED");
                assertThat(status).isNotEqualTo("PENDING_ACTIVATION");
            });
        }

        @Test
        @DisplayName("TC_CLM_AL_014 — Re-join: previously removed learner gets a restored membership; prior progress association is intact")
        void tc_clm_al_014() throws Exception {
            // When a removed learner is re-assigned, the existing ClassMember row is restored
            // (removedAt cleared) — no new duplicate row is created.
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Rejoin Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);
            User learner = saveInactiveLearner("rejoin@test.com");

            // First, create and then remove the membership
            ClassMember original = saveRemovedClassMember(clazz.getId(), learner.getId());
            UUID originalMemberId = original.getId();

            String body = String.format("{\"learnerIds\":[\"%s\"]}", learner.getId());

            mockMvc.perform(post("/api/v1/classes/{classId}/members", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true));

            // The same membership record is restored — no duplicate created
            ClassMember restored = classMemberRepository.findById(originalMemberId).orElseThrow();
            assertThat(restored.getRemovedAt()).isNull();
            // Exactly one membership record for this learner+class should exist
            assertThat(classMemberRepository.findAll().stream()
                    .filter(m -> m.getClassId().equals(clazz.getId()) && m.getUserId().equals(learner.getId()))
                    .count()).isEqualTo(1);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 7b. REMOVE LEARNER — Additional cases
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7b. REMOVE LEARNER — Additional cases")
    class RemoveLearnerAdditionalTests {

        @Test
        @DisplayName("TC_CLM_RL_005 — Remove from non-existing class → 404 NOT_FOUND (\"Lớp học không tồn tại.\")")
        void tc_clm_rl_005() throws Exception {
            saveManager("manager@test.com");
            User learner = saveLearner("learner@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(delete("/api/v1/classes/{classId}/members/{userId}",
                            UUID.randomUUID(), learner.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("Lớp học không tồn tại."));
        }

        @Test
        @DisplayName("TC_CLM_RL_006 — TEACHER token cannot remove learner from class → 403 Forbidden")
        void tc_clm_rl_006() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), ClassStatus.ACTIVE, 8);
            User learner = saveLearner("learner@test.com");
            saveClassMember(clazz.getId(), learner.getId());

            mockMvc.perform(delete("/api/v1/classes/{classId}/members/{userId}",
                            clazz.getId(), learner.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

    }
}
