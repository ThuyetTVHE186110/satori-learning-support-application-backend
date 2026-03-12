package com.fu.clazz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.clazz.entity.ClassMember;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.repository.ClassMemberRepository;
import com.fu.clazz.repository.ClassRepository;
import com.fu.course.entity.Course;
import com.fu.course.entity.Course.CourseStatus;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.repository.CourseRepository;
import com.fu.infrastructure.cloudinary.CloudinaryService;
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
@DisplayName("Class Management — Integration Tests (PostgreSQL satori_test)")
class ClassManagementTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ClassRepository classRepository;
    @Autowired private ClassMemberRepository classMemberRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean private EmailService emailService;
    @MockBean private CloudinaryService cloudinaryService;

    // ── Helpers ───────────────────────────────────────────────────────────────

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
        return saveLearner(email, UserStatus.ACTIVE);
    }

    private User saveLearner(String email, UserStatus status) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("ValidPass@1"))
                .firstName("Test").lastName("Learner")
                .role(UserRole.LEARNER)
                .status(status)
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

    private Course saveCourse(String name, CourseStatus status) {
        return courseRepository.save(Course.builder()
                .name(name).description("Test course")
                .jlptLevel(JlptLevel.N5).status(status).orderIndex(0)
                .build());
    }

    private Clazz saveClass(String name, UUID teacherId, UUID courseId,
                            boolean isActive, int maxStudents) {
        return classRepository.save(Clazz.builder()
                .name(name).description("Test class")
                .teacherId(teacherId).courseId(courseId)
                .isActive(isActive).maxStudents(maxStudents)
                .startDate(LocalDate.now().plusDays(1))
                .endDate(LocalDate.now().plusMonths(3))
                .build());
    }

    private ClassMember saveClassMember(UUID classId, UUID userId) {
        return classMemberRepository.save(ClassMember.builder()
                .classId(classId).userId(userId).build());
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
        classMemberRepository.deleteAll();
        classRepository.deleteAll();
        courseRepository.deleteAll();
        sessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        classMemberRepository.deleteAll();
        classRepository.deleteAll();
        courseRepository.deleteAll();
        sessionRepository.deleteAll();
        userRepository.deleteAll();
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

            saveClass("Class A", teacher.getId(), course.getId(), true, 8);
            saveClass("Class B", teacher.getId(), course.getId(), true, 8);
            saveClass("Class C", teacher.getId(), course.getId(), false, 8);

            mockMvc.perform(get("/api/v1/classes")
                            .param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content.length()").value(3));
        }

        @Test
        @DisplayName("TC_CLM_VL_002 — Teacher sees only own active classes (auto-filtered)")
        void tc_clm_vl_002() throws Exception {
            User t1 = saveTeacher("teacher1@test.com");
            User t2 = saveTeacher("teacher2@test.com");
            String token = loginAndGetToken("teacher1@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);

            saveClass("T1 Active 1", t1.getId(), course.getId(), true, 8);
            saveClass("T1 Active 2", t1.getId(), course.getId(), true, 8);
            saveClass("T1 Inactive", t1.getId(), course.getId(), false, 8);
            saveClass("T2 Active", t2.getId(), course.getId(), true, 8);

            MvcResult result = mockMvc.perform(get("/api/v1/classes")
                            .param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content.length()").value(2))
                    .andReturn();

            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            var content = objectMapper.readTree(body).path("data").path("content");
            content.forEach(item -> {
                assertThat(item.path("name").asText()).startsWith("T1 Active");
            });
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
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), true, 8);

            mockMvc.perform(get("/api/v1/classes/{classId}", clazz.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(clazz.getId().toString()))
                    .andExpect(jsonPath("$.data.name").value("Test Class"))
                    .andExpect(jsonPath("$.data.teacherId").value(teacher.getId().toString()))
                    .andExpect(jsonPath("$.data.courseId").value(course.getId().toString()))
                    .andExpect(jsonPath("$.data.maxStudents").value(8))
                    .andExpect(jsonPath("$.data.isActive").value(true));
        }

        @Test
        @DisplayName("TC_CLM_VD_002 — Teacher views own assigned class detail")
        void tc_clm_vd_002() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("My Class", teacher.getId(), course.getId(), true, 8);

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
            Clazz clazz = saveClass("T2 Class", t2.getId(), course.getId(), true, 8);

            mockMvc.perform(get("/api/v1/classes/{classId}", clazz.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
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
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), true, 8);

            User l1 = saveLearner("learner1@test.com");
            User l2 = saveLearner("learner2@test.com");
            User l3 = saveLearner("learner3@test.com");
            User removed = saveLearner("removed@test.com");

            saveClassMember(clazz.getId(), l1.getId());
            saveClassMember(clazz.getId(), l2.getId());
            saveClassMember(clazz.getId(), l3.getId());
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
            content.forEach(item -> {
                assertThat(item.has("userId")).isTrue();
                assertThat(item.has("fullName")).isTrue();
                assertThat(item.has("email")).isTrue();
                assertThat(item.path("email").asText()).isNotEqualTo("removed@test.com");
            });
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 4. CREATE CLASS — POST /api/v1/classes
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. CREATE CLASS — POST /api/v1/classes")
    class CreateClassTests {

        @Test
        @DisplayName("TC_CLM_CC_001 — Create class successfully, isActive=true by default")
        void tc_clm_cc_001() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);

            String body = String.format(
                    "{\"name\":\"Japanese N5 Class\",\"teacherId\":\"%s\",\"courseId\":\"%s\"," +
                    "\"maxStudents\":8,\"startDate\":\"%s\",\"endDate\":\"%s\"}",
                    teacher.getId(), course.getId(),
                    LocalDate.now().plusDays(1), LocalDate.now().plusMonths(3));

            MvcResult result = mockMvc.perform(post("/api/v1/classes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").isNotEmpty())
                    .andExpect(jsonPath("$.data.name").value("Japanese N5 Class"))
                    .andExpect(jsonPath("$.data.isActive").value(true))
                    .andExpect(jsonPath("$.data.teacherId").value(teacher.getId().toString()))
                    .andExpect(jsonPath("$.data.courseId").value(course.getId().toString()))
                    .andReturn();

            String id = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("id").asText();
            assertThat(classRepository.findByIdAndNotDeleted(UUID.fromString(id))).isPresent();
        }

        @Test
        @DisplayName("TC_CLM_CC_002 — Duplicate class name → CLASS_NAME_EXISTS")
        void tc_clm_cc_002() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            saveClass("Japanese N5 Class", teacher.getId(), course.getId(), true, 8);

            String body = String.format(
                    "{\"name\":\"Japanese N5 Class\",\"teacherId\":\"%s\",\"courseId\":\"%s\"," +
                    "\"maxStudents\":8,\"startDate\":\"%s\",\"endDate\":\"%s\"}",
                    teacher.getId(), course.getId(),
                    LocalDate.now().plusDays(1), LocalDate.now().plusMonths(3));

            mockMvc.perform(post("/api/v1/classes")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("CLASS_NAME_EXISTS"));
        }

        @Test
        @DisplayName("TC_CLM_CC_003 — startDate in the past → INVALID_START_DATE")
        void tc_clm_cc_003() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);

            String body = String.format(
                    "{\"name\":\"Past Date Class\",\"teacherId\":\"%s\",\"courseId\":\"%s\"," +
                    "\"maxStudents\":8,\"startDate\":\"%s\",\"endDate\":\"%s\"}",
                    teacher.getId(), course.getId(),
                    LocalDate.now(), LocalDate.now().plusMonths(3));

            mockMvc.perform(post("/api/v1/classes")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_START_DATE"));
        }

        @Test
        @DisplayName("TC_CLM_CC_004 — endDate ≤ startDate → INVALID_DATE_RANGE")
        void tc_clm_cc_004() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);

            LocalDate tomorrow = LocalDate.now().plusDays(1);
            String body = String.format(
                    "{\"name\":\"Bad Range Class\",\"teacherId\":\"%s\",\"courseId\":\"%s\"," +
                    "\"maxStudents\":8,\"startDate\":\"%s\",\"endDate\":\"%s\"}",
                    teacher.getId(), course.getId(), tomorrow, tomorrow);

            mockMvc.perform(post("/api/v1/classes")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
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
                    "\"maxStudents\":8,\"startDate\":\"%s\",\"endDate\":\"%s\"}",
                    learner.getId(), course.getId(),
                    LocalDate.now().plusDays(1), LocalDate.now().plusMonths(3));

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
                    "\"maxStudents\":8,\"startDate\":\"%s\",\"endDate\":\"%s\"}",
                    teacher.getId(), course.getId(),
                    LocalDate.now().plusDays(1), LocalDate.now().plusMonths(3));

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

            String body = "{\"name\":\"Test\",\"teacherId\":\"" + UUID.randomUUID() +
                    "\",\"courseId\":\"" + UUID.randomUUID() + "\",\"maxStudents\":8}";

            mockMvc.perform(post("/api/v1/classes")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 5. UPDATE CLASS — PUT /api/v1/classes/{classId}
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. UPDATE CLASS — PUT /api/v1/classes/{classId}")
    class UpdateClassTests {

        @Test
        @DisplayName("TC_CLM_UC_001 — Partial update: name changed, other fields retained")
        void tc_clm_uc_001() throws Exception {
            User manager = saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Original Name", teacher.getId(), course.getId(), true, 8);

            String body = "{\"name\":\"Updated Class Name\"}";

            mockMvc.perform(put("/api/v1/classes/{classId}", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.name").value("Updated Class Name"))
                    .andExpect(jsonPath("$.data.description").value("Test class"))
                    .andExpect(jsonPath("$.data.updatedBy").value(manager.getId().toString()));
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
            Clazz clazz = saveClass("Swap Class", t1.getId(), course.getId(), true, 8);

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
        @DisplayName("TC_CLM_UC_003 — Deactivate class: disappears from teacher list, assign learner blocked")
        void tc_clm_uc_003() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String mgrToken = loginAndGetToken("manager@test.com", "ValidPass@1");
            String tToken = loginAndGetToken("teacher@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Deactivate Me", teacher.getId(), course.getId(), true, 8);
            User learner = saveLearner("learner@test.com");

            // Deactivate
            mockMvc.perform(put("/api/v1/classes/{classId}", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}")
                            .header("Authorization", "Bearer " + mgrToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isActive").value(false));

            // Teacher list — class not visible
            mockMvc.perform(get("/api/v1/classes").param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + tToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(0));

            // Assign learner — blocked
            String addBody = String.format("{\"userId\":\"%s\"}", learner.getId());
            mockMvc.perform(post("/api/v1/classes/{classId}/members", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(addBody)
                            .header("Authorization", "Bearer " + mgrToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CLASS_INACTIVE"));
        }

        @Test
        @DisplayName("TC_CLM_UC_004 — Reactivate class: reappears in teacher list, assign learner re-enabled")
        void tc_clm_uc_004() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String mgrToken = loginAndGetToken("manager@test.com", "ValidPass@1");
            String tToken = loginAndGetToken("teacher@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Reactivate Me", teacher.getId(), course.getId(), false, 8);
            User learner = saveLearner("learner@test.com");

            // Reactivate
            mockMvc.perform(put("/api/v1/classes/{classId}", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":true}")
                            .header("Authorization", "Bearer " + mgrToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isActive").value(true));

            // Teacher list — class visible
            mockMvc.perform(get("/api/v1/classes").param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + tToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1));

            // Assign learner — succeeds
            String addBody = String.format("{\"userId\":\"%s\"}", learner.getId());
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
            Clazz clazz = saveClass("Full Class", teacher.getId(), course.getId(), true, 8);

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
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), true, 8);
            User learner = saveLearner("learner@test.com");

            String body = String.format("{\"userId\":\"%s\"}", learner.getId());

            mockMvc.perform(post("/api/v1/classes/{classId}/members", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.userId").value(learner.getId().toString()))
                    .andExpect(jsonPath("$.data.classId").value(clazz.getId().toString()))
                    .andExpect(jsonPath("$.data.removedAt").isEmpty());

            assertThat(classMemberRepository.existsActiveByClassIdAndUserId(
                    clazz.getId(), learner.getId())).isTrue();
        }

        @Test
        @DisplayName("TC_CLM_AL_002 — Class full → CLASS_FULL")
        void tc_clm_al_002() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Full Class", teacher.getId(), course.getId(), true, 2);

            saveClassMember(clazz.getId(), saveLearner("l1@test.com").getId());
            saveClassMember(clazz.getId(), saveLearner("l2@test.com").getId());
            User newLearner = saveLearner("new@test.com");

            String body = String.format("{\"userId\":\"%s\"}", newLearner.getId());

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
            Clazz clazz = saveClass("Inactive Class", teacher.getId(), course.getId(), false, 8);
            User learner = saveLearner("learner@test.com");

            String body = String.format("{\"userId\":\"%s\"}", learner.getId());

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
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), true, 8);

            String body = String.format("{\"userId\":\"%s\"}", anotherTeacher.getId());

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
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), true, 8);
            User learner = saveLearner("learner@test.com");
            saveClassMember(clazz.getId(), learner.getId());

            String body = String.format("{\"userId\":\"%s\"}", learner.getId());

            mockMvc.perform(post("/api/v1/classes/{classId}/members", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MEMBER_ALREADY_EXISTS"));
        }

        @Test
        @DisplayName("TC_CLM_AL_006 — TEACHER token → 403 Forbidden")
        void tc_clm_al_006() throws Exception {
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("teacher@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), true, 8);
            User learner = saveLearner("learner@test.com");

            String body = String.format("{\"userId\":\"%s\"}", learner.getId());

            mockMvc.perform(post("/api/v1/classes/{classId}/members", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("TC_CLM_AL_007 — INACTIVE learner auto-promoted to ACTIVE on assignment")
        void tc_clm_al_007() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), true, 8);
            User inactiveLearner = saveLearner("inactive@test.com", UserStatus.INACTIVE);

            String body = String.format("{\"userId\":\"%s\"}", inactiveLearner.getId());

            mockMvc.perform(post("/api/v1/classes/{classId}/members", clazz.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.userId").value(inactiveLearner.getId().toString()));

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
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), true, 8);
            User learner = saveLearner("learner@test.com");
            saveClassMember(clazz.getId(), learner.getId());

            mockMvc.perform(delete("/api/v1/classes/{classId}/members/{userId}",
                            clazz.getId(), learner.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            // Verify removedAt is set
            ClassMember member = classMemberRepository.findByClassIdAndUserId(
                    clazz.getId(), learner.getId()).orElseThrow();
            assertThat(member.getRemovedAt()).isNotNull();

            // Verify not in active members list
            mockMvc.perform(get("/api/v1/classes/{classId}/members", clazz.getId())
                            .param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(jsonPath("$.data.content.length()").value(0));
        }

        @Test
        @DisplayName("TC_CLM_RL_002 — Already removed → MEMBER_ALREADY_REMOVED")
        void tc_clm_rl_002() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), true, 8);
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
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), true, 8);
            User learner = saveLearner("learner@test.com");

            mockMvc.perform(delete("/api/v1/classes/{classId}/members/{userId}",
                            clazz.getId(), learner.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
        }

        @Test
        @DisplayName("TC_CLM_RL_004 — Remove learner: user account status unchanged (still ACTIVE)")
        void tc_clm_rl_004() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), true, 8);
            User learner = saveLearner("learner@test.com");
            saveClassMember(clazz.getId(), learner.getId());

            mockMvc.perform(delete("/api/v1/classes/{classId}/members/{userId}",
                            clazz.getId(), learner.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            // ClassMember soft-deleted
            ClassMember member = classMemberRepository.findByClassIdAndUserId(
                    clazz.getId(), learner.getId()).orElseThrow();
            assertThat(member.getRemovedAt()).isNotNull();

            // User status unchanged — still ACTIVE
            User updated = userRepository.findById(learner.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        @DisplayName("TC_CLM_RL_005 — Remove from last class: status unchanged (no auto-demotion in current impl)")
        void tc_clm_rl_005() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Only Class", teacher.getId(), course.getId(), true, 8);
            User learner = saveLearner("learner@test.com");
            saveClassMember(clazz.getId(), learner.getId());

            mockMvc.perform(delete("/api/v1/classes/{classId}/members/{userId}",
                            clazz.getId(), learner.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            // Current behavior: status remains ACTIVE (no demotion logic)
            User updated = userRepository.findById(learner.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(UserStatus.INACTIVE);
        }

        @Test
        @DisplayName("TC_CLM_RL_006 — Remove from one class, still in another: status remains ACTIVE")
        void tc_clm_rl_006() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz classA = saveClass("Class A", teacher.getId(), course.getId(), true, 8);
            Clazz classB = saveClass("Class B", teacher.getId(), course.getId(), true, 8);
            User learner = saveLearner("learner@test.com");
            saveClassMember(classA.getId(), learner.getId());
            saveClassMember(classB.getId(), learner.getId());

            // Remove from class A only
            mockMvc.perform(delete("/api/v1/classes/{classId}/members/{userId}",
                            classA.getId(), learner.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            // Still active member of class B
            assertThat(classMemberRepository.existsActiveByClassIdAndUserId(
                    classB.getId(), learner.getId())).isTrue();

            // User status unchanged
            User updated = userRepository.findById(learner.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(UserStatus.ACTIVE);
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
            Clazz clazz = saveClass("Delete Me", teacher.getId(), course.getId(), true, 8);

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
            Clazz clazz = saveClass("Test Class", teacher.getId(), course.getId(), true, 8);

            mockMvc.perform(delete("/api/v1/classes/{classId}", clazz.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("TC_CLM_DC_004 — Delete class: ClassMember records NOT cascade-deleted, user statuses unchanged")
        void tc_clm_dc_004() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz clazz = saveClass("Cascade Test", teacher.getId(), course.getId(), true, 8);

            User l1 = saveLearner("l1@test.com");
            User l2 = saveLearner("l2@test.com");
            ClassMember m1 = saveClassMember(clazz.getId(), l1.getId());
            ClassMember m2 = saveClassMember(clazz.getId(), l2.getId());

            mockMvc.perform(delete("/api/v1/classes/{classId}", clazz.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            // ClassMember records still exist with removedAt = null (NOT cascade-deleted)
            ClassMember cm1 = classMemberRepository.findById(m1.getId()).orElseThrow();
            ClassMember cm2 = classMemberRepository.findById(m2.getId()).orElseThrow();
            assertThat(cm1.getRemovedAt()).isNull();
            assertThat(cm2.getRemovedAt()).isNull();

            // Teacher status unchanged
            assertThat(userRepository.findById(teacher.getId()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.ACTIVE);

            // Learner statuses unchanged
            assertThat(userRepository.findById(l1.getId()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.ACTIVE);
            assertThat(userRepository.findById(l2.getId()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        @DisplayName("TC_CLM_DC_005 — Delete class: learner auto-demotion NOT implemented (status unchanged)")
        void tc_clm_dc_005() throws Exception {
            saveManager("manager@test.com");
            User teacher = saveTeacher("teacher@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Japanese N5", CourseStatus.ACTIVE);
            Clazz targetClass = saveClass("Target Class", teacher.getId(), course.getId(), true, 8);
            Clazz otherClass = saveClass("Other Class", teacher.getId(), course.getId(), true, 8);

            User l1 = saveLearner("l1@test.com"); // only in target class
            User l2 = saveLearner("l2@test.com"); // in both classes
            saveClassMember(targetClass.getId(), l1.getId());
            saveClassMember(targetClass.getId(), l2.getId());
            saveClassMember(otherClass.getId(), l2.getId());

            mockMvc.perform(delete("/api/v1/classes/{classId}", targetClass.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            // Current behavior: no demotion logic — both learners remain ACTIVE
            assertThat(userRepository.findById(l1.getId()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.ACTIVE);
            assertThat(userRepository.findById(l2.getId()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.ACTIVE);
        }
    }
}
