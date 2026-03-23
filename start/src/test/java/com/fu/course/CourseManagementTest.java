package com.fu.course;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.course.entity.Course;
import com.fu.course.entity.Course.CourseStatus;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.entity.Lesson;
import com.fu.course.repository.CourseRepository;
import com.fu.course.repository.LessonRepository;
import com.fu.infrastructure.cloudinary.CloudinaryService;
import com.fu.infrastructure.email.EmailService;
import com.fu.user.entity.AuthProvider;
import com.fu.user.entity.User;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import com.fu.user.repository.UserRepository;
import com.fu.user.repository.UserSessionRepository;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
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

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — Course Management (Manager).
 *
 * Runs from the start module so all modules are on the classpath.
 *
 * Requires: docker exec satori-postgres psql -U satori_user -d satori_learning \
 *   -c "CREATE DATABASE satori_test OWNER satori_user;"
 *
 * Covers (test plan):
 *   1. VIEW COURSE LIST   — GET    /api/v1/manager/courses
 *   2. CREATE COURSE      — POST   /api/v1/manager/courses
 *   3. VIEW COURSE DETAIL — GET    /api/v1/manager/courses/{courseId}
 *   4. UPDATE COURSE      — PUT    /api/v1/manager/courses/{courseId}
 *   5. DELETE COURSE      — DELETE /api/v1/manager/courses/{courseId}
 *   6. ACTIVATE/DEACTIVATE— PATCH  /api/v1/manager/courses/{courseId}/activate|deactivate
 */
@SpringBootTest(classes = com.fu.StartApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Course Management — Integration Tests (PostgreSQL satori_test)")
class CourseManagementTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private CourseRepository courseRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    // Email service is mocked — we don't want real SMTP calls during tests
    @MockBean
    private EmailService emailService;

    // CloudinaryService is mocked to avoid real Cloudinary API calls
    @MockBean
    private CloudinaryService cloudinaryService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Insert a MANAGER user and return the saved entity. */
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

    /** Insert a LEARNER user and return the saved entity. */
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

    /** Login and return the Bearer access token. */
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

    /** Build and save a Course with the given parameters. */
    private Course saveCourse(String name, JlptLevel level, CourseStatus status) {
        return courseRepository.save(Course.builder()
                .name(name)
                .description("Test description")
                .jlptLevel(level)
                .status(status)
                .orderIndex(0)
                .build());
    }

    /** Build and save a Course with thumbnail. */
    private Course saveCourseWithThumbnail(String name, JlptLevel level, CourseStatus status, String thumbnailUrl) {
        return courseRepository.save(Course.builder()
                .name(name)
                .description("Test description")
                .jlptLevel(level)
                .status(status)
                .thumbnailUrl(thumbnailUrl)
                .orderIndex(0)
                .build());
    }

    /** Build a valid image MockMultipartFile. */
    private MockMultipartFile validImageFile() {
        byte[] content = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}; // JPEG magic bytes
        return new MockMultipartFile("thumbnail", "test.jpg", "image/jpeg", content);
    }

    /** Build a MockMultipartFile with GIF content type (unsupported). */
    private MockMultipartFile gifImageFile() {
        byte[] content = new byte[]{0x47, 0x49, 0x46}; // GIF magic bytes
        return new MockMultipartFile("thumbnail", "test.gif", "image/gif", content);
    }

    /** Build a MockMultipartFile that is too large (> 5MB). */
    private MockMultipartFile oversizedImageFile() {
        byte[] content = new byte[6 * 1024 * 1024]; // 6MB
        return new MockMultipartFile("thumbnail", "large.jpg", "image/jpeg", content);
    }

    /**
     * Build a multipart request JSON part for course creation.
     * name, level, description — matches CreateCourseRequest.
     */
    private MockMultipartFile courseRequestPart(String name, String level, String description) throws Exception {
        String json = String.format(
                "{\"name\":\"%s\",\"jlptLevel\":\"%s\",\"description\":\"%s\"}",
                name, level, description);
        return new MockMultipartFile("request", "", "application/json", json.getBytes());
    }

    /**
     * Build a multipart request JSON part for course update.
     * name, description — matches UpdateCourseRequest.
     */
    private MockMultipartFile updateRequestPart(String name, String description) throws Exception {
        String json = String.format("{\"name\":\"%s\",\"description\":\"%s\"}", name, description);
        return new MockMultipartFile("request", "", "application/json", json.getBytes());
    }

    /** Save a Lesson linked to a course. */
    private Lesson saveLesson(Course course, String title) {
        return lessonRepository.save(Lesson.builder()
                .course(course)
                .title(title)
                .lessonType(Lesson.LessonType.VOCABULARY)
                .status(Lesson.ContentStatus.DRAFT)
                .orderIndex(0)
                .build());
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        Mockito.reset(cloudinaryService);
        // Clean up any leftover data from a previously-failed test
        lessonRepository.deleteAll();
        courseRepository.deleteAll();
        sessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        lessonRepository.deleteAll();
        courseRepository.deleteAll();
        sessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1. VIEW COURSE LIST — GET /api/v1/manager/courses
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. VIEW COURSE LIST — GET /api/v1/manager/courses")
    class ViewCourseListTests {

        // ── 1.1 Happy Path ────────────────────────────────────────────────────

        /**
         * TC_CM_VL_001
         * View course list successfully — returns paginated list with all fields.
         * 3 courses (mix of ACTIVE, INACTIVE) inserted — response must contain all.
         */
        @Test
        @DisplayName("TC_CM_VL_001 — paginated list returned, all fields present, totalElements ≥ 3")
        void tc_cm_vl_001_viewCourseList_returnsPaginatedList() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            saveCourse("Tiếng Nhật N5", JlptLevel.N5, CourseStatus.ACTIVE);
            saveCourse("Tiếng Nhật N4", JlptLevel.N4, CourseStatus.INACTIVE);
            saveCourse("Tiếng Nhật N3", JlptLevel.N3, CourseStatus.ACTIVE);

            // Act + Assert
            MvcResult result = mockMvc.perform(get("/api/v1/manager/courses")
                            .param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.totalElements").value(3))
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.pageSize").value(10))
                    .andExpect(jsonPath("$.data.first").value(true))
                    .andExpect(jsonPath("$.data.last").value(true))
                    .andReturn();

            String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            var content = objectMapper.readTree(body).path("data").path("content");

            // Every item must contain required fields
            content.forEach(item -> {
                assertThat(item.has("id")).isTrue();
                assertThat(item.has("name")).isTrue();
                assertThat(item.has("jlptLevel")).isTrue();
                assertThat(item.has("status")).isTrue();
                assertThat(item.has("createdAt")).isTrue();
            });
        }

        // ── 1.2 Search & Filter ────────────────────────────────────────────────

        /**
         * TC_CM_VL_002
         * Search by keyword matches course name (LIKE %keyword%) — non-matching excluded.
         * keyword=N5 → only "Tiếng Nhật N5" returned.
         */
        @Test
        @DisplayName("TC_CM_VL_002 — keyword=N5 → only 'Tiếng Nhật N5' returned, others excluded")
        void tc_cm_vl_002_keywordSearch_returnsMatchingCourseOnly() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            saveCourse("Tiếng Nhật N5", JlptLevel.N5, CourseStatus.ACTIVE);
            saveCourse("Tiếng Nhật N4", JlptLevel.N4, CourseStatus.ACTIVE);
            saveCourse("Unrelated Course", JlptLevel.N3, CourseStatus.ACTIVE);

            // Act + Assert
            MvcResult result = mockMvc.perform(get("/api/v1/manager/courses")
                            .param("page", "0").param("size", "10")
                            .param("keyword", "N5")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.pageSize").value(10))
                    .andExpect(jsonPath("$.data.first").value(true))
                    .andExpect(jsonPath("$.data.last").value(true))
                    .andReturn();

            String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            var firstItem = objectMapper.readTree(body).path("data").path("content").get(0);
            assertThat(firstItem.path("name").asText()).isEqualTo("Tiếng Nhật N5");
        }

        /**
         * TC_CM_VL_003
         * Filter by level=N5 returns only N5 courses — N3 course excluded.
         */
        @Test
        @DisplayName("TC_CM_VL_003 — level=N5 → 2 N5 courses returned, N3 course excluded")
        void tc_cm_vl_003_filterByLevel_returnsOnlyN5Courses() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            saveCourse("Course A", JlptLevel.N5, CourseStatus.ACTIVE);
            saveCourse("Course B", JlptLevel.N5, CourseStatus.INACTIVE);
            saveCourse("Course C", JlptLevel.N3, CourseStatus.ACTIVE);

            // Act + Assert
            MvcResult result = mockMvc.perform(get("/api/v1/manager/courses")
                            .param("page", "0").param("size", "10")
                            .param("level", "N5")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.pageSize").value(10))
                    .andExpect(jsonPath("$.data.first").value(true))
                    .andExpect(jsonPath("$.data.last").value(true))
                    .andReturn();

            String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            var content = objectMapper.readTree(body).path("data").path("content");
            content.forEach(item ->
                assertThat(item.path("jlptLevel").asText()).isEqualTo("N5")
            );

            // Course C (N3) must not appear
            var names = new java.util.ArrayList<String>();
            content.forEach(n -> names.add(n.path("name").asText()));
            assertThat(names).doesNotContain("Course C");
        }

        /**
         * TC_CM_VL_004
         * Combined filter (keyword + level + status) returns only matching courses.
         */
        @Test
        @DisplayName("TC_CM_VL_004 — combined filter (keyword='Nhật', level=N5, status=ACTIVE) → 'Nhật N5 Cơ Bản' returned")
        void tc_cm_vl_004_combinedFilter_returnsOnlyMatchingCourses() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            // Insert 5 courses
            saveCourse("Nhật N5 Cơ Bản", JlptLevel.N5, CourseStatus.ACTIVE);     // MATCH
            saveCourse("Nhật N5 Nâng Cao", JlptLevel.N5, CourseStatus.INACTIVE); // status mismatch
            saveCourse("Nhật N4 Cơ Bản", JlptLevel.N4, CourseStatus.ACTIVE);     // level mismatch
            saveCourse("Unrelated", JlptLevel.N5, CourseStatus.ACTIVE);           // keyword mismatch
            saveCourse("N5 Grammar", JlptLevel.N5, CourseStatus.ACTIVE);          // keyword mismatch

            // Act + Assert
            MvcResult result = mockMvc.perform(get("/api/v1/manager/courses")
                            .param("page", "0").param("size", "10")
                            .param("keyword", "Nhật")
                            .param("level", "N5")
                            .param("status", "ACTIVE")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.pageSize").value(10))
                    .andExpect(jsonPath("$.data.first").value(true))
                    .andExpect(jsonPath("$.data.last").value(true))
                    .andReturn();

            String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            var content = objectMapper.readTree(body).path("data").path("content");
            assertThat(content.get(0).path("name").asText()).isEqualTo("Nhật N5 Cơ Bản");
        }

        /**
         * TC_CM_VL_005
         * Invalid enum value for level param returns 400 — MethodArgumentTypeMismatchException.
         */
        @Test
        @DisplayName("TC_CM_VL_005 — level=N9 invalid enum → HTTP 400")
        void tc_cm_vl_005_invalidLevelEnum_returns400() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            // Act + Assert
            mockMvc.perform(get("/api/v1/manager/courses")
                            .param("level", "N9")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        // ── 1.3 Authorization ─────────────────────────────────────────────────

        /**
         * TC_CM_VL_006
         * Access without token returns 401.
         */
        @Test
        @DisplayName("TC_CM_VL_006 — no token → HTTP 401 Unauthorized")
        void tc_cm_vl_006_noToken_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/manager/courses"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CM_VL_007
         * Access with LEARNER role token returns 403 — RBAC enforced at SecurityConfig.
         */
        @Test
        @DisplayName("TC_CM_VL_007 — LEARNER token → HTTP 403 Forbidden")
        void tc_cm_vl_007_learnerToken_returns403() throws Exception {
            // Arrange
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            // Act + Assert
            mockMvc.perform(get("/api/v1/manager/courses")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2. CREATE COURSE — POST /api/v1/manager/courses
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. CREATE COURSE — POST /api/v1/manager/courses")
    class CreateCourseTests {

        // ── 2.1 Happy Path ────────────────────────────────────────────────────

        /**
         * TC_CM_CR_001
         * Create course without thumbnail — course saved with null thumbnailUrl, Cloudinary not called.
         */
        @Test
        @DisplayName("TC_CM_CR_001 — no thumbnail → HTTP 201, thumbnailUrl=null in DB, Cloudinary not called")
        void tc_cm_cr_001_createWithoutThumbnail_returns201_thumbnailUrlNull() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            MockMultipartFile requestPart = courseRequestPart("No Thumb Course", "N4", "Desc");

            // Act
            MvcResult result = mockMvc.perform(multipart("/api/v1/manager/courses")
                            .file(requestPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").isNotEmpty())
                    .andExpect(jsonPath("$.data.name").value("No Thumb Course"))
                    .andExpect(jsonPath("$.data.jlptLevel").value("N4"))
                    .andExpect(jsonPath("$.data.status").value("INACTIVE"))
                    .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                    .andExpect(jsonPath("$.data.updatedAt").isNotEmpty())
                    .andReturn();

            // Assert DB
            String idStr = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("id").asText();
            Course course = courseRepository.findByIdAndNotDeleted(UUID.fromString(idStr)).orElseThrow();
            assertThat(course.getThumbnailUrl()).isNull();
            assertThat(course.getDeletedAt()).isNull();

            // Assert Cloudinary not called
            verify(cloudinaryService, never()).uploadImage(any(), anyString());
        }

        /**
         * TC_CM_CR_001b
         * Create course with thumbnail — course saved with thumbnail URL from Cloudinary, returns Vietnamese success message.
         */
        @Test
        @DisplayName("TC_CM_CR_001b — with thumbnail → HTTP 201, Vietnamese success message, thumbnail URL stored")
        void tc_cm_cr_001b_createWithThumbnail_returns201_vietnameseMessage_thumbnailUrlStored() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            String mockThumbnailUrl = "https://res.cloudinary.com/demo/image/upload/v123/courses/thumbnails/test.jpg";
            Mockito.when(cloudinaryService.uploadImage(any(), anyString())).thenReturn(mockThumbnailUrl);

            MockMultipartFile requestPart = courseRequestPart("Course With Thumb", "N5", "Test description");

            // Act
            MvcResult result = mockMvc.perform(multipart("/api/v1/manager/courses")
                            .file(requestPart)
                            .file(validImageFile())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Tạo khóa học thành công."))
                    .andExpect(jsonPath("$.data.id").isNotEmpty())
                    .andExpect(jsonPath("$.data.name").value("Course With Thumb"))
                    .andExpect(jsonPath("$.data.jlptLevel").value("N5"))
                    .andExpect(jsonPath("$.data.status").value("INACTIVE"))
                    .andExpect(jsonPath("$.data.thumbnailUrl").value(mockThumbnailUrl))
                    .andReturn();

            // Assert DB
            String idStr = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("id").asText();
            Course course = courseRepository.findByIdAndNotDeleted(UUID.fromString(idStr)).orElseThrow();
            assertThat(course.getThumbnailUrl()).isEqualTo(mockThumbnailUrl);
            assertThat(course.getDeletedAt()).isNull();

            // Assert Cloudinary called
            verify(cloudinaryService).uploadImage(any(), anyString());
        }

        // ── 2.2 Validation Errors ─────────────────────────────────────────────

        /**
         * TC_CM_CR_002
         * Create course with empty name — validation error returned.
         */
        @Test
        @DisplayName("TC_CM_CR_002 — empty name → HTTP 400 validation error")
        void tc_cm_cr_002_emptyName_returns400ValidationError() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            MockMultipartFile requestPart = new MockMultipartFile(
                    "request", "", "application/json",
                    "{\"name\":\"\",\"jlptLevel\":\"N5\",\"description\":\"Desc\"}".getBytes());

            // Act + Assert
            mockMvc.perform(multipart("/api/v1/manager/courses")
                            .file(requestPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CM_CR_003
         * Create course with duplicate name — business validation error, no new record created.
         */
        @Test
        @DisplayName("TC_CM_CR_003 — duplicate name → HTTP 400/409, no new record in DB")
        void tc_cm_cr_003_duplicateName_returns400_noNewRecord() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            saveCourse("Duplicate Course", JlptLevel.N5, CourseStatus.ACTIVE);

            MockMultipartFile requestPart = courseRequestPart("Duplicate Course", "N5", "Desc");

            // Act + Assert
            mockMvc.perform(multipart("/api/v1/manager/courses")
                            .file(requestPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("Khóa học với tên 'Duplicate Course' đã tồn tại"));

            // DB: still only 1 course with this name
            long count = courseRepository.findAll().stream()
                    .filter(c -> "Duplicate Course".equalsIgnoreCase(c.getName()) && c.getDeletedAt() == null)
                    .count();
            assertThat(count).isEqualTo(1);
        }

        /**
         * TC_CM_CR_003b
         * Create course with description exceeding 5000 characters — validation error returned.
         */
        @Test
        @DisplayName("TC_CM_CR_003b — description > 5000 chars → HTTP 400 validation error")
        void tc_cm_cr_003b_descriptionTooLong_returns400ValidationError() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            // Create description with 5001 characters
            StringBuilder longDesc = new StringBuilder();
            for (int i = 0; i < 5001; i++) {
                longDesc.append("a");
            }

            MockMultipartFile requestPart = new MockMultipartFile(
                    "request", "", "application/json",
                    String.format("{\"name\":\"Test Course\",\"jlptLevel\":\"N5\",\"description\":\"%s\"}", longDesc.toString()).getBytes());

            // Act + Assert
            mockMvc.perform(multipart("/api/v1/manager/courses")
                            .file(requestPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("Mô tả không được vượt quá 5000 ký tự"));

            // DB: no course created
            long count = courseRepository.findAll().stream()
                    .filter(c -> "Test Course".equalsIgnoreCase(c.getName()) && c.getDeletedAt() == null)
                    .count();
            assertThat(count).isEqualTo(0);
        }

        /**
         * TC_CM_CR_003c
         * Create course with invalid JLPT level — validation error returned.
         */
        @Test
        @DisplayName("TC_CM_CR_003c — invalid JLPT level → HTTP 400 validation error")
        void tc_cm_cr_003c_invalidJlptLevel_returns400ValidationError() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            MockMultipartFile requestPart = new MockMultipartFile(
                    "request", "", "application/json",
                    "{\"name\":\"Test Course\",\"jlptLevel\":\"N9\",\"description\":\"Valid description\"}".getBytes());

            // Act + Assert
            mockMvc.perform(multipart("/api/v1/manager/courses")
                            .file(requestPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("JLPT level không hợp lệ 'N9'. Các giá trị được chấp nhận: [N5, N4, N3, N2, N1]"));

            // DB: no course created
            long count = courseRepository.findAll().stream()
                    .filter(c -> "Test Course".equalsIgnoreCase(c.getName()) && c.getDeletedAt() == null)
                    .count();
            assertThat(count).isEqualTo(0);
        }

        // ── 2.3 Cloudinary — MockMvc ──────────────────────────────────────────

        /**
         * TC_CM_CR_004
         * Cloudinary upload failure (runtime) causes @Transactional rollback — course NOT saved to DB.
         */
        @Test
        @DisplayName("TC_CM_CR_004 — Cloudinary upload fails → HTTP 400, course NOT in DB (rollback)")
        void tc_cm_cr_004_cloudinaryUploadFails_returns400_courseNotSaved() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            Mockito.when(cloudinaryService.uploadImage(any(), anyString()))
                    .thenThrow(new com.fu.common.exception.BusinessException("UPLOAD_FAILED", "Failed to upload image: network error"));

            MockMultipartFile requestPart = courseRequestPart("Test Course", "N5", "Desc");

            // Act + Assert
            mockMvc.perform(multipart("/api/v1/manager/courses")
                            .file(requestPart)
                            .file(validImageFile())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("UPLOAD_FAILED"))
                    .andExpect(jsonPath("$.message").value("Có lỗi xảy ra khi up ảnh, vui lòng thử lại sau."));

            // DB state: course NOT created (rollback)
            long count = courseRepository.findAll().stream()
                    .filter(c -> "Test Course".equalsIgnoreCase(c.getName()))
                    .count();
            assertThat(count).isEqualTo(0);
        }

        /**
         * TC_CM_CR_005
         * Create course with unsupported file format — validateImageFile() throws INVALID_FILE_TYPE,
         * Cloudinary never called.
         */
        @Test
        @DisplayName("TC_CM_CR_005 — GIF file format → HTTP 400 INVALID_FILE_TYPE, Cloudinary not called")
        void tc_cm_cr_005_unsupportedFileFormat_returns400_cloudinaryNotCalled() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            MockMultipartFile requestPart = courseRequestPart("Test Course", "N5", "Desc");

            // Act + Assert
            mockMvc.perform(multipart("/api/v1/manager/courses")
                            .file(requestPart)
                            .file(gifImageFile())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_FILE_TYPE"))
                    .andExpect(jsonPath("$.message").value("Chỉ cho phép các định dạng ảnh JPEG, PNG và WebP."));

            // Cloudinary never called
            verify(cloudinaryService, never()).uploadImage(any(), anyString());
        }

        /**
         * TC_CM_CR_006
         * Create course with file exceeding 5MB — validateImageFile() throws FILE_TOO_LARGE,
         * Cloudinary never called.
         */
        @Test
        @DisplayName("TC_CM_CR_006 — file > 5MB → HTTP 400 FILE_TOO_LARGE, Cloudinary not called")
        void tc_cm_cr_006_fileTooLarge_returns400_cloudinaryNotCalled() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            MockMultipartFile requestPart = courseRequestPart("Test Course", "N5", "Desc");

            // Act + Assert
            mockMvc.perform(multipart("/api/v1/manager/courses")
                            .file(requestPart)
                            .file(oversizedImageFile())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
                    .andExpect(jsonPath("$.message").value("File vượt quá kích thước tối đa 5MB, vui lòng chọn file khác"));

            // Cloudinary never called
            verify(cloudinaryService, never()).uploadImage(any(), anyString());
        }


        // ── 2.4 Authorization ─────────────────────────────────────────────────

        /**
         * TC_CM_CR_008
         * Create course with LEARNER token returns 403.
         */
        @Test
        @DisplayName("TC_CM_CR_008 — LEARNER token → HTTP 403 Forbidden")
        void tc_cm_cr_008_learnerToken_returns403() throws Exception {
            // Arrange
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            MockMultipartFile requestPart = courseRequestPart("Test Course", "N5", "Desc");

            // Act + Assert
            mockMvc.perform(multipart("/api/v1/manager/courses")
                            .file(requestPart)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3. VIEW COURSE DETAIL — GET /api/v1/manager/courses/{courseId}
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. VIEW COURSE DETAIL — GET /api/v1/manager/courses/{courseId}")
    class ViewCourseDetailTests {

        // ── 3.1 Happy Path ────────────────────────────────────────────────────


        /**
         * TC_CM_VD_001
         * View course detail returns comprehensive information including lesson and enrollment counts.
         */
        @Test
        @DisplayName("TC_CM_VD_001 — comprehensive course info → HTTP 200, all required fields including counts")
        void tc_cm_vd_001b_viewCourseDetail_returns200_comprehensiveInfo() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            
            // Create course with thumbnail
            Course course = saveCourseWithThumbnail("Comprehensive Course", JlptLevel.N4, CourseStatus.ACTIVE,
                    "https://example.com/course-thumb.jpg");
            
            // Add some lessons to the course
            saveLesson(course, "Lesson 1 - Vocabulary");
            saveLesson(course, "Lesson 2 - Grammar");
            saveLesson(course, "Lesson 3 - Reading");
            
            // Create some learners and enroll them (simulating enrollment)
            User learner1 = saveLearner("learner1@test.com");
            User learner2 = saveLearner("learner2@test.com");

            // Act + Assert
            MvcResult result = mockMvc.perform(get("/api/v1/manager/courses/{courseId}", course.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(course.getId().toString()))
                    .andExpect(jsonPath("$.data.name").value("Comprehensive Course"))
                    .andExpect(jsonPath("$.data.thumbnailUrl").value("https://example.com/course-thumb.jpg"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.description").value("Test description"))
                    .andExpect(jsonPath("$.data.jlptLevel").value("N4"))
                    .andExpect(jsonPath("$.data.lessonCount").value(3))
                    .andExpect(jsonPath("$.data.studentCount").exists())
                    .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                    .andExpect(jsonPath("$.data.updatedAt").isNotEmpty())
                    .andReturn();
        }

        // ── 3.2 Negative Cases ────────────────────────────────────────────────

        /**
         * TC_CM_VD_002
         * View detail of non-existent courseId returns not found.
         */
        @Test
        @DisplayName("TC_CM_VD_002 — non-existent courseId → HTTP 404 not found")
        void tc_cm_vd_002_nonExistentCourseId_returns404() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            // Act + Assert
            mockMvc.perform(get("/api/v1/manager/courses/{courseId}",
                            UUID.fromString("00000000-0000-0000-0000-000000000000"))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CM_VD_003
         * View detail of soft-deleted course returns not found — findByIdAndNotDeleted() filters deleted_at IS NULL.
         */
        @Test
        @DisplayName("TC_CM_VD_003 — soft-deleted course → HTTP 404 not found (deleted_at IS NULL filter)")
        void tc_cm_vd_003_softDeletedCourse_returns404() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("To Be Deleted", JlptLevel.N5, CourseStatus.ACTIVE);

            // Soft-delete via API
            mockMvc.perform(delete("/api/v1/manager/courses/{courseId}", course.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            // Act + Assert — GET after delete must return not found
            mockMvc.perform(get("/api/v1/manager/courses/{courseId}", course.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. UPDATE COURSE — PUT /api/v1/manager/courses/{courseId}
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. UPDATE COURSE — PUT /api/v1/manager/courses/{courseId}")
    class UpdateCourseTests {

        // ── 4.1 Happy Path ────────────────────────────────────────────────────

        /**
         * TC_CM_UP_001
         * Update name and description without new thumbnail — thumbnailUrl preserved in DB.
         */
        @Test
        @DisplayName("TC_CM_UP_001 — update name+description, no thumbnail → HTTP 200, thumbnailUrl preserved")
        void tc_cm_up_001_updateNameAndDescription_thumbnailPreserved() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourseWithThumbnail("Old Name", JlptLevel.N5, CourseStatus.INACTIVE,
                    "https://old.url/thumb.jpg");

            MockMultipartFile requestPart = updateRequestPart("New Name", "New Desc");

            // Act
            mockMvc.perform(multipart("/api/v1/manager/courses/{courseId}", course.getId())
                            .file(requestPart)
                            .with(req -> { req.setMethod("PUT"); return req; })
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.name").value("New Name"))
                    .andExpect(jsonPath("$.data.description").value("New Desc"))
                    .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());

            // Assert DB
            Course updated = courseRepository.findByIdAndNotDeleted(course.getId()).orElseThrow();
            assertThat(updated.getName()).isEqualTo("New Name");
            assertThat(updated.getDescription()).isEqualTo("New Desc");
            assertThat(updated.getThumbnailUrl()).isEqualTo("https://old.url/thumb.jpg");
            assertThat(updated.getUpdatedAt()).isNotNull();
        }

        /**
         * TC_CM_UP_001b
         * Update course with new thumbnail — Cloudinary returns new URL and DB saves new thumbnail URL.
         */
        @Test
        @DisplayName("TC_CM_UP_001b — update with new thumbnail → HTTP 200, Cloudinary returns new URL, DB saves new thumbnail")
        void tc_cm_up_001b_updateWithNewThumbnail_returns200_newThumbnailUrlStored() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            
            String oldThumbnailUrl = "https://old.url/thumb.jpg";
            String newThumbnailUrl = "https://res.cloudinary.com/demo/image/upload/v456/courses/thumbnails/new-thumb.jpg";
            
            Course course = saveCourseWithThumbnail("Course Name", JlptLevel.N5, CourseStatus.ACTIVE, oldThumbnailUrl);
            
            // Mock Cloudinary to return new URL
            Mockito.when(cloudinaryService.uploadImage(any(), anyString())).thenReturn(newThumbnailUrl);
            Mockito.when(cloudinaryService.extractPublicId(oldThumbnailUrl)).thenReturn("courses/thumbnails/thumb");
            
            MockMultipartFile requestPart = updateRequestPart("Updated Name", "Updated description");

            // Act
            MvcResult result = mockMvc.perform(multipart("/api/v1/manager/courses/{courseId}", course.getId())
                            .file(requestPart)
                            .file(validImageFile())
                            .with(req -> { req.setMethod("PUT"); return req; })
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.name").value("Updated Name"))
                    .andExpect(jsonPath("$.data.description").value("Updated description"))
                    .andExpect(jsonPath("$.data.thumbnailUrl").value(newThumbnailUrl))
                    .andExpect(jsonPath("$.data.updatedAt").isNotEmpty())
                    .andReturn();

            // Assert DB
            Course updated = courseRepository.findByIdAndNotDeleted(course.getId()).orElseThrow();
            assertThat(updated.getName()).isEqualTo("Updated Name");
            assertThat(updated.getDescription()).isEqualTo("Updated description");
            assertThat(updated.getThumbnailUrl()).isEqualTo(newThumbnailUrl);
            assertThat(updated.getUpdatedAt()).isNotNull();

            // Assert Cloudinary was called for upload and delete
            verify(cloudinaryService).uploadImage(any(), anyString());
            verify(cloudinaryService).deleteImage("courses/thumbnails/thumb");
        }

        // ── 4.2 Cloudinary — MockMvc ──────────────────────────────────────────

        /**
         * TC_CM_UP_002
         * Upload new thumbnail fails (runtime) — course NOT updated, old thumbnailUrl preserved, rollback.
         */
        @Test
        @DisplayName("TC_CM_UP_002 — Cloudinary upload fails → HTTP 400, course unchanged (rollback)")
        void tc_cm_up_002_cloudinaryUploadFails_returns400_courseNotUpdated() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourseWithThumbnail("Original", JlptLevel.N5, CourseStatus.INACTIVE,
                    "https://old.url/thumb.jpg");

            Mockito.when(cloudinaryService.uploadImage(any(), anyString()))
                    .thenThrow(new com.fu.common.exception.BusinessException("UPLOAD_FAILED", "Failed to upload image: network error"));

            MockMultipartFile requestPart = updateRequestPart("Original", "Desc");

            // Act + Assert
            mockMvc.perform(multipart("/api/v1/manager/courses/{courseId}", course.getId())
                            .file(requestPart)
                            .file(validImageFile())
                            .with(req -> { req.setMethod("PUT"); return req; })
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("UPLOAD_FAILED"))
                    .andExpect(jsonPath("$.message").value("Có lỗi xảy ra khi up ảnh, vui lòng thử lại sau."));

            // DB state: course unchanged
            Course unchanged = courseRepository.findByIdAndNotDeleted(course.getId()).orElseThrow();
            assertThat(unchanged.getName()).isEqualTo("Original");
            assertThat(unchanged.getThumbnailUrl()).isEqualTo("https://old.url/thumb.jpg");
        }

        /**
         * TC_CM_UP_003
         * Update thumbnail with unsupported file format — INVALID_FILE_TYPE, Cloudinary never called.
         */
        @Test
        @DisplayName("TC_CM_UP_003 — GIF format → HTTP 400 INVALID_FILE_TYPE, Cloudinary not called, DB unchanged")
        void tc_cm_up_003_unsupportedFileFormat_returns400_cloudinaryNotCalled() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourseWithThumbnail("Original", JlptLevel.N5, CourseStatus.INACTIVE,
                    "https://old.url/thumb.jpg");

            MockMultipartFile requestPart = updateRequestPart("Original", "Desc");

            // Act + Assert
            mockMvc.perform(multipart("/api/v1/manager/courses/{courseId}", course.getId())
                            .file(requestPart)
                            .file(gifImageFile())
                            .with(req -> { req.setMethod("PUT"); return req; })
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_FILE_TYPE"))
                    .andExpect(jsonPath("$.message").value("Chỉ cho phép các định dạng ảnh JPEG, PNG và WebP."));


            // Cloudinary never called
            verify(cloudinaryService, never()).uploadImage(any(), anyString());

            // DB unchanged
            Course unchanged = courseRepository.findByIdAndNotDeleted(course.getId()).orElseThrow();
            assertThat(unchanged.getThumbnailUrl()).isEqualTo("https://old.url/thumb.jpg");
        }

        /**
         * TC_CM_UP_004
         * Update thumbnail with file exceeding 5MB — FILE_TOO_LARGE, Cloudinary never called.
         */
        @Test
        @DisplayName("TC_CM_UP_004 — file > 5MB → HTTP 400 FILE_TOO_LARGE, Cloudinary not called, DB unchanged")
        void tc_cm_up_004_fileTooLarge_returns400_cloudinaryNotCalled() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourseWithThumbnail("Original", JlptLevel.N5, CourseStatus.INACTIVE,
                    "https://old.url/thumb.jpg");

            MockMultipartFile requestPart = updateRequestPart("Original", "Desc");

            // Act + Assert
            mockMvc.perform(multipart("/api/v1/manager/courses/{courseId}", course.getId())
                            .file(requestPart)
                            .file(oversizedImageFile())
                            .with(req -> { req.setMethod("PUT"); return req; })
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
                    .andExpect(jsonPath("$.message").value("File vượt quá kích thước tối đa 5MB, vui lòng chọn file khác"));


            // Cloudinary never called
            verify(cloudinaryService, never()).uploadImage(any(), anyString());

            // DB unchanged
            Course unchanged = courseRepository.findByIdAndNotDeleted(course.getId()).orElseThrow();
            assertThat(unchanged.getThumbnailUrl()).isEqualTo("https://old.url/thumb.jpg");
        }

        /**
         * TC_CM_UP_005
         * deleteImage() failure is silently ignored — course update succeeds with new thumbnail.
         * Old image becomes orphan but update proceeds.
         */
        @Test
        @DisplayName("TC_CM_UP_005 — deleteImage() throws IOException → update succeeds, new thumbnailUrl in DB")
        void tc_cm_up_005_deleteImageFails_updateSucceeds_newThumbnailInDb() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourseWithThumbnail("Original", JlptLevel.N5, CourseStatus.INACTIVE,
                    "https://res.cloudinary.com/demo/image/upload/v123/courses/thumbnails/abc.jpg");

            String newThumbnailUrl = "https://res.cloudinary.com/demo/image/upload/v456/courses/thumbnails/new.jpg";

            Mockito.when(cloudinaryService.uploadImage(any(), anyString())).thenReturn(newThumbnailUrl);
            Mockito.when(cloudinaryService.extractPublicId(anyString())).thenReturn("courses/thumbnails/abc");
            Mockito.doThrow(new RuntimeException("IO error")).when(cloudinaryService).deleteImage(anyString());

            MockMultipartFile requestPart = updateRequestPart("Original", "Desc");

            // Act + Assert
            mockMvc.perform(multipart("/api/v1/manager/courses/{courseId}", course.getId())
                            .file(requestPart)
                            .file(validImageFile())
                            .with(req -> { req.setMethod("PUT"); return req; })
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.name").value("Original"))
                    .andExpect(jsonPath("$.data.description").value("Desc"))
                    .andExpect(jsonPath("$.data.thumbnailUrl").value(newThumbnailUrl));

            // DB state: new thumbnailUrl persisted
            Course updated = courseRepository.findByIdAndNotDeleted(course.getId()).orElseThrow();
            assertThat(updated.getThumbnailUrl()).isEqualTo(newThumbnailUrl);
        }

        // ── 4.3 Validation & Exception ────────────────────────────────────────

        /**
         * TC_CM_UP_006
         * Update with empty name — validation error, course not modified.
         */
        @Test
        @DisplayName("TC_CM_UP_006 — empty name → HTTP 400 validation error, course name unchanged")
        void tc_cm_up_006_emptyName_returns400_courseNameUnchanged() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Valid Name", JlptLevel.N5, CourseStatus.INACTIVE);

            MockMultipartFile requestPart = new MockMultipartFile(
                    "request", "", "application/json",
                    "{\"name\":\"\",\"description\":\"Desc\"}".getBytes());

            // Act + Assert
            mockMvc.perform(multipart("/api/v1/manager/courses/{courseId}", course.getId())
                            .file(requestPart)
                            .with(req -> { req.setMethod("PUT"); return req; })
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));

            // DB: name unchanged
            Course unchanged = courseRepository.findByIdAndNotDeleted(course.getId()).orElseThrow();
            assertThat(unchanged.getName()).isEqualTo("Valid Name");
        }

        /**
         * TC_CM_UP_007
         * Update non-existent courseId returns course not found.
         */
        @Test
        @DisplayName("TC_CM_UP_007 — non-existent courseId → HTTP 404 not found")
        void tc_cm_up_007_nonExistentCourseId_returns404() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            MockMultipartFile requestPart = updateRequestPart("Test Name", "Desc");

            // Act + Assert
            mockMvc.perform(multipart("/api/v1/manager/courses/{courseId}",
                            UUID.fromString("00000000-0000-0000-0000-000000000000"))
                            .file(requestPart)
                            .with(req -> { req.setMethod("PUT"); return req; })
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 5. DELETE COURSE — DELETE /api/v1/manager/courses/{courseId}
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. DELETE COURSE — DELETE /api/v1/manager/courses/{courseId}")
    class DeleteCourseTests {

        // ── 5.1 Happy Path ────────────────────────────────────────────────────

        /**
         * TC_CM_DL_001
         * Soft delete — deleted_at set to NOW(), course excluded from list.
         *
         * Note: deleted_by is set from @AuthenticationPrincipal(expression = "id") in the controller.
         * In integration tests via login JWT, the actual managerId is resolved from the token.
         */
        @Test
        @DisplayName("TC_CM_DL_001 — soft delete → HTTP 200, deleted_at set, course absent from list")
        void tc_cm_dl_001_softDelete_deletedAtSet_absentFromList() throws Exception {
            // Arrange
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Active Course", JlptLevel.N5, CourseStatus.ACTIVE);

            // Act — delete
            mockMvc.perform(delete("/api/v1/manager/courses/{courseId}", course.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            // Assert DB: deleted_at IS NOT NULL, status DELETED
            Course inDB = courseRepository.findById(course.getId()).orElseThrow();
            assertThat(inDB.getDeletedAt()).isNotNull();
            assertThat(inDB.getStatus()).isEqualTo(CourseStatus.DELETED);
            assertThat(inDB.getDeletedBy()).isEqualTo(manager.getId());

            // Assert list: course no longer appears
            mockMvc.perform(get("/api/v1/manager/courses")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }

        // ── 5.2 Cascade & Data Integrity ──────────────────────────────────────

        /**
         * TC_CM_DL_002
         * Soft delete course — related Lessons remain with deleted_at = null (no cascade).
         */
        @Test
        @DisplayName("TC_CM_DL_002 — delete course → lessons NOT cascade-deleted (deleted_at still null)")
        void tc_cm_dl_002_softDeleteCourse_lessonsNotCascadeDeleted() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Course With Lessons", JlptLevel.N5, CourseStatus.ACTIVE);
            Lesson lesson1 = saveLesson(course, "Lesson 1");
            Lesson lesson2 = saveLesson(course, "Lesson 2");

            // Act — delete course
            mockMvc.perform(delete("/api/v1/manager/courses/{courseId}", course.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            // Assert: lessons still present with deleted_at = null
            Lesson l1 = lessonRepository.findById(lesson1.getId()).orElseThrow();
            Lesson l2 = lessonRepository.findById(lesson2.getId()).orElseThrow();
            assertThat(l1.getDeletedAt()).isNull();
            assertThat(l2.getDeletedAt()).isNull();
        }

        // ── 5.3 Negative Cases ────────────────────────────────────────────────

        /**
         * TC_CM_DL_003
         * Delete non-existent courseId returns not found.
         */
        @Test
        @DisplayName("TC_CM_DL_003 — non-existent courseId → HTTP 404 not found")
        void tc_cm_dl_003_nonExistentCourseId_returns404() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            // Act + Assert
            mockMvc.perform(delete("/api/v1/manager/courses/{courseId}",
                            UUID.fromString("00000000-0000-0000-0000-000000000000"))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_CM_DL_004
         * Delete already-soft-deleted course returns not found — findByIdAndNotDeleted() excludes it.
         */
        @Test
        @DisplayName("TC_CM_DL_004 — double delete → second attempt returns HTTP 404")
        void tc_cm_dl_004_doubleDelete_secondAttemptReturns404() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Already Deleted", JlptLevel.N5, CourseStatus.ACTIVE);

            // First delete
            mockMvc.perform(delete("/api/v1/manager/courses/{courseId}", course.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            // Second delete — must fail
            mockMvc.perform(delete("/api/v1/manager/courses/{courseId}", course.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        // ── 5.4 Authorization ─────────────────────────────────────────────────

        /**
         * TC_CM_DL_005
         * Delete with LEARNER token returns 403 — course deletedAt remains null.
         */
        @Test
        @DisplayName("TC_CM_DL_005 — LEARNER token → HTTP 403, course deletedAt remains null")
        void tc_cm_dl_005_learnerToken_returns403_courseNotDeleted() throws Exception {
            // Arrange
            Course course = saveCourse("Protected Course", JlptLevel.N5, CourseStatus.ACTIVE);
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            // Act + Assert
            mockMvc.perform(delete("/api/v1/manager/courses/{courseId}", course.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));

            // DB: course not deleted
            Course inDB = courseRepository.findById(course.getId()).orElseThrow();
            assertThat(inDB.getDeletedAt()).isNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 6. ACTIVATE / DEACTIVATE — PATCH .../activate | .../deactivate
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. ACTIVATE / DEACTIVATE — PATCH .../activate | .../deactivate")
    class ActivateDeactivateTests {

        // ── 6.1 Happy Path ────────────────────────────────────────────────────

        /**
         * TC_CM_ST_001
         * Activate INACTIVE course — status updated to ACTIVE in DB.
         */
        @Test
        @DisplayName("TC_CM_ST_001 — activate INACTIVE course → HTTP 200, status=ACTIVE in DB")
        void tc_cm_st_001_activateInactiveCourse_statusActiveInDb() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Inactive Course", JlptLevel.N5, CourseStatus.INACTIVE);

            // Act
            mockMvc.perform(patch("/api/v1/manager/courses/{courseId}/activate", course.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));

            // Assert DB
            Course updated = courseRepository.findByIdAndNotDeleted(course.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(CourseStatus.ACTIVE);
            assertThat(updated.getUpdatedAt()).isNotNull();
        }

        /**
         * TC_CM_ST_002
         * Deactivate ACTIVE course — status updated to INACTIVE in DB.
         */
        @Test
        @DisplayName("TC_CM_ST_002 — deactivate ACTIVE course → HTTP 200, status=INACTIVE in DB")
        void tc_cm_st_002_deactivateActiveCourse_statusInactiveInDb() throws Exception {
            // Arrange
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Active Course", JlptLevel.N5, CourseStatus.ACTIVE);

            // Act
            mockMvc.perform(patch("/api/v1/manager/courses/{courseId}/deactivate", course.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("INACTIVE"));

            // Assert DB
            Course updated = courseRepository.findByIdAndNotDeleted(course.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(CourseStatus.INACTIVE);
            assertThat(updated.getUpdatedAt()).isNotNull();
        }
    }
}

