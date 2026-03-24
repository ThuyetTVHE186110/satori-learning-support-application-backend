package com.fu.course;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.course.entity.Course;
import com.fu.course.entity.Course.CourseStatus;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.entity.GrammarPoint;
import com.fu.course.entity.Lesson;
import com.fu.course.entity.Vocabulary;
import com.fu.course.repository.*;
import com.fu.curriculumimport.entity.CurriculumImport;
import com.fu.curriculumimport.entity.CurriculumImport.ImportStatus;
import com.fu.curriculumimport.repository.CurriculumImportRepository;
import com.fu.curriculumimport.service.embedding.EmbeddingService;
import com.fu.curriculumimport.service.orchestrator.ImportOrchestrator;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — Learning Content Management.
 *
 * Runs from the start module so all modules are on the classpath.
 *
 * Requires: docker exec satori-postgres psql -U satori_user -d satori_learning \
 *   -c "CREATE DATABASE satori_test OWNER satori_user;"
 *
 * Covers (test plan):
 *   1. VIEW CONTENT LIST       — GET    /api/v1/manager/lessons
 *   2. VIEW CONTENT INFO       — GET    /api/v1/manager/lessons/{id}
 *   3. VIEW IMPORT HISTORY     — GET    /api/v1/manager/curriculum-imports
 *   4. IMPORT LEARNING RESOURCE— POST   /api/v1/manager/curriculum-imports
 *   5. CREATE CONTENT          — POST   /api/v1/manager/lessons | vocabulary | grammar-points
 *   6. UPDATE CONTENT          — PUT    /api/v1/manager/lessons | vocabulary | grammar-points
 *   7. DELETE CONTENT          — DELETE /api/v1/manager/lessons/{id}
 */
@SpringBootTest(classes = com.fu.StartApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Learning Content Management — Integration Tests (PostgreSQL satori_test)")
class LearningContentManagementTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private CourseRepository courseRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private VocabularyRepository vocabularyRepository;
    @Autowired private GrammarPointRepository grammarPointRepository;
    @Autowired private LessonVocabularyRepository lessonVocabularyRepository;
    @Autowired private LessonGrammarRepository lessonGrammarRepository;
    @Autowired private CurriculumImportRepository curriculumImportRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean private EmailService emailService;
    @MockBean private CloudinaryService cloudinaryService;
    @MockBean private ImportOrchestrator importOrchestrator;
    @MockBean private EmbeddingService embeddingService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User saveManager(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("ValidPass@1"))
                .firstName("Test").lastName("Manager")
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

    private Course saveCourse(String name) {
        return courseRepository.save(Course.builder()
                .name(name)
                .description("Test course")
                .jlptLevel(JlptLevel.N5)
                .status(CourseStatus.ACTIVE)
                .orderIndex(0)
                .build());
    }

    private Lesson saveLesson(Course course, String title, Lesson.LessonType type, int orderIndex) {
        return lessonRepository.save(Lesson.builder()
                .course(course)
                .title(title)
                .lessonType(type)
                .status(Lesson.ContentStatus.DRAFT)
                .orderIndex(orderIndex)
                .build());
    }

    private Lesson saveLessonWithImportId(Course course, String title, UUID importId, int orderIndex) {
        return lessonRepository.save(Lesson.builder()
                .course(course)
                .title(title)
                .lessonType(Lesson.LessonType.VOCABULARY)
                .status(Lesson.ContentStatus.DRAFT)
                .importId(importId)
                .orderIndex(orderIndex)
                .build());
    }

    private Vocabulary saveVocabulary(String japaneseText, JlptLevel level) {
        return vocabularyRepository.save(Vocabulary.builder()
                .japaneseText(japaneseText)
                .jlptLevel(level)
                .build());
    }

    private GrammarPoint saveGrammarPoint(String pattern, JlptLevel level) {
        return grammarPointRepository.save(GrammarPoint.builder()
                .pattern(pattern)
                .jlptLevel(level)
                .build());
    }

    private CurriculumImport saveImport(UUID courseId, ImportStatus status, UUID managerId) {
        return curriculumImportRepository.save(CurriculumImport.builder()
                .originalFilename("test.pdf")
                .fileUrl("file://test.pdf")
                .fileSizeBytes(1024L)
                .fileType("application/pdf")
                .courseId(courseId)
                .status(status)
                .createdBy(managerId)
                .build());
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        lessonVocabularyRepository.deleteAll();
        lessonGrammarRepository.deleteAll();
        lessonRepository.deleteAll();
        vocabularyRepository.deleteAll();
        grammarPointRepository.deleteAll();
        courseRepository.deleteAll();
        curriculumImportRepository.deleteAll();
        sessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        lessonVocabularyRepository.deleteAll();
        lessonGrammarRepository.deleteAll();
        lessonRepository.deleteAll();
        vocabularyRepository.deleteAll();
        grammarPointRepository.deleteAll();
        courseRepository.deleteAll();
        curriculumImportRepository.deleteAll();
        sessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1. VIEW CONTENT LIST — GET /api/v1/manager/lessons
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. VIEW CONTENT LIST — GET /api/v1/manager/lessons")
    class ViewContentListTests {

        @Test
        @DisplayName("TC_LCM_VL_001 — paginated list returned for authorised Manager")
        void tc_lcm_vl_001_viewLessonList_returnsPaginatedList() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Tiếng Nhật N5");

            saveLesson(course, "Lesson A", Lesson.LessonType.VOCABULARY, 0);
            saveLesson(course, "Lesson B", Lesson.LessonType.GRAMMAR, 1);
            saveLesson(course, "Lesson C", Lesson.LessonType.READING, 2);

            MvcResult result = mockMvc.perform(get("/api/v1/manager/lessons")
                            .param("courseId", course.getId().toString())
                            .param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content.length()").value(3))
                    .andReturn();

            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            var content = objectMapper.readTree(body).path("data").path("content");
            content.forEach(item -> {
                assertThat(item.has("id")).isTrue();
                assertThat(item.has("title")).isTrue();
            });
        }

        @Test
        @DisplayName("TC_LCM_VL_002 — keyword='Mua sắm' → only matching lessons returned")
        void tc_lcm_vl_002_searchByKeyword_returnsMatchingOnly() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Tiếng Nhật N5");

            saveLesson(course, "Mua sắm tại siêu thị", Lesson.LessonType.VOCABULARY, 0);
            saveLesson(course, "Mua sắm online", Lesson.LessonType.VOCABULARY, 1);
            saveLesson(course, "Giới thiệu bản thân", Lesson.LessonType.VOCABULARY, 2);

            MvcResult result = mockMvc.perform(get("/api/v1/manager/lessons")
                            .param("courseId", course.getId().toString())
                            .param("keyword", "Mua sắm")
                            .param("page", "0").param("size", "20")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content.length()").value(2))
                    .andReturn();

            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            var content = objectMapper.readTree(body).path("data").path("content");
            content.forEach(item ->
                assertThat(item.path("title").asText()).containsIgnoringCase("Mua sắm")
            );
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2. VIEW CONTENT INFORMATION — GET /api/v1/manager/lessons/{id}
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. VIEW CONTENT INFORMATION — GET /api/v1/manager/lessons/{id}")
    class ViewContentInfoTests {

        @Test
        @DisplayName("TC_LCM_VI_001 — all fields of a specific lesson returned")
        void tc_lcm_vi_001_viewLessonDetail_returnsAllFields() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Tiếng Nhật N5");
            Lesson lesson = saveLesson(course, "テスト授業", Lesson.LessonType.VOCABULARY, 0);

            mockMvc.perform(get("/api/v1/manager/lessons/{lessonId}", lesson.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(lesson.getId().toString()))
                    .andExpect(jsonPath("$.data.title").value("テスト授業"))
                    .andExpect(jsonPath("$.data.lessonType").value("VOCABULARY"))
                    .andExpect(jsonPath("$.data.courseId").isNotEmpty());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3. VIEW IMPORT HISTORY — GET /api/v1/manager/curriculum-imports
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. VIEW IMPORT HISTORY + ROLLBACK")
    class ViewImportHistoryTests {

        @Test
        @DisplayName("TC_LCM_IH_001 — list of past curriculum import jobs returned")
        void tc_lcm_ih_001_viewImportHistory_returnsListOfJobs() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            saveImport(null, ImportStatus.COMPLETED, manager.getId());
            saveImport(null, ImportStatus.CANCELLED, manager.getId());

            mockMvc.perform(get("/api/v1/manager/curriculum-imports")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content.length()").value(2));
        }

        @Test
        @DisplayName("TC_LCM_IH_002 — rollback COMPLETED import → ROLLED_BACK, lessons soft-deleted")
        void tc_lcm_ih_002_rollbackCompletedImport_lessonsDeleted() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Tiếng Nhật N5");

            CurriculumImport ci = saveImport(course.getId(), ImportStatus.COMPLETED, manager.getId());
            Lesson l1 = saveLessonWithImportId(course, "Imported Lesson 1", ci.getId(), 0);
            Lesson l2 = saveLessonWithImportId(course, "Imported Lesson 2", ci.getId(), 1);

            // Act — rollback
            mockMvc.perform(post("/api/v1/manager/curriculum-imports/{importId}/rollback", ci.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            // Assert DB — import status
            CurriculumImport updated = curriculumImportRepository.findById(ci.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ImportStatus.ROLLED_BACK);

            // Assert DB — lessons soft-deleted
            assertThat(lessonRepository.findByIdAndNotDeleted(l1.getId())).isEmpty();
            assertThat(lessonRepository.findByIdAndNotDeleted(l2.getId())).isEmpty();

            // Assert API — lessons NOT in list
            mockMvc.perform(get("/api/v1/manager/lessons")
                            .param("courseId", course.getId().toString())
                            .param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(0));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. IMPORT LEARNING RESOURCE — POST /api/v1/manager/curriculum-imports
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. IMPORT LEARNING RESOURCE — POST /api/v1/manager/curriculum-imports")
    class ImportLearningResourceTests {

        @Test
        @DisplayName("TC_LCM_IR_001 — import PDF and approve → COMPLETED, content retained")
        void tc_lcm_ir_001_importAndApprove_statusCompleted() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Tiếng Nhật N5");

            // Step 1 — upload PDF → 202 Accepted
            MockMultipartFile pdf = new MockMultipartFile(
                    "file", "test.pdf", "application/pdf", "%PDF-1.4 test".getBytes());

            MvcResult uploadResult = mockMvc.perform(multipart("/api/v1/manager/curriculum-imports")
                            .file(pdf)
                            .param("courseId", course.getId().toString())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("QUEUED"))
                    .andReturn();

            String importIdStr = objectMapper.readTree(
                    uploadResult.getResponse().getContentAsString()).path("data").path("id").asText();
            UUID importId = UUID.fromString(importIdStr);

            // Simulate extraction complete — update to PENDING_APPROVAL, add lessons
            CurriculumImport ci = curriculumImportRepository.findById(importId).orElseThrow();
            ci.markPendingApproval();
            curriculumImportRepository.save(ci);
            saveLessonWithImportId(course, "Extracted Lesson", importId, 0);

            // Step 2 — approve
            mockMvc.perform(post("/api/v1/manager/curriculum-imports/{importId}/approve", importId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("COMPLETED"));

            // Assert — lessons still accessible
            mockMvc.perform(get("/api/v1/manager/lessons")
                            .param("courseId", course.getId().toString())
                            .param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(jsonPath("$.data.content.length()").value(1));
        }

        @Test
        @DisplayName("TC_LCM_IR_002 — import PDF and reject → CANCELLED, draft content soft-deleted")
        void tc_lcm_ir_002_importAndReject_statusCancelled() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Tiếng Nhật N5");

            // Create PENDING_APPROVAL import with lessons
            CurriculumImport ci = saveImport(course.getId(), ImportStatus.PENDING_APPROVAL, manager.getId());
            Lesson l1 = saveLessonWithImportId(course, "Draft Lesson", ci.getId(), 0);

            // Reject
            mockMvc.perform(post("/api/v1/manager/curriculum-imports/{importId}/reject", ci.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));

            // Assert — lesson soft-deleted
            assertThat(lessonRepository.findByIdAndNotDeleted(l1.getId())).isEmpty();
        }

        @Test
        @DisplayName("TC_LCM_IR_003 — extraction runs in background, 202 returned immediately")
        void tc_lcm_ir_003_backgroundExtraction_returns202() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            MockMultipartFile pdf = new MockMultipartFile(
                    "file", "curriculum.pdf", "application/pdf", "%PDF-1.4 content".getBytes());

            mockMvc.perform(multipart("/api/v1/manager/curriculum-imports")
                            .file(pdf)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("QUEUED"))
                    .andExpect(jsonPath("$.data.id").isNotEmpty());

            // Import record exists in DB
            assertThat(curriculumImportRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("TC_LCM_IR_004 — non-PDF/non-PPTX file → 400 INVALID_FILE")
        void tc_lcm_ir_004_invalidFileType_returns400() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            MockMultipartFile txtFile = new MockMultipartFile(
                    "file", "test.txt", "text/plain", "hello world".getBytes());

            mockMvc.perform(multipart("/api/v1/manager/curriculum-imports")
                            .file(txtFile)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_FILE"));

            // No import record created
            assertThat(curriculumImportRepository.count()).isEqualTo(0);
        }

        @Test
        @DisplayName("TC_LCM_IR_005 — PDF exceeding 100MB → 400 INVALID_FILE")
        void tc_lcm_ir_005_fileTooLarge_returns400() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            // Override getSize() to simulate >100MB without allocating real memory
            MockMultipartFile oversizedPdf = new MockMultipartFile(
                    "file", "large.pdf", "application/pdf", "%PDF".getBytes()) {
                @Override
                public long getSize() {
                    return 101L * 1024 * 1024;
                }
            };

            mockMvc.perform(multipart("/api/v1/manager/curriculum-imports")
                            .file(oversizedPdf)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_FILE"));

            assertThat(curriculumImportRepository.count()).isEqualTo(0);
        }

        @Test
        @DisplayName("TC_LCM_IR_006 — extraction failure → FAILED, no partial content")
        void tc_lcm_ir_006_extractionFailure_statusFailed() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            // Upload (202) — orchestrator is mocked, no real processing
            MockMultipartFile pdf = new MockMultipartFile(
                    "file", "test.pdf", "application/pdf", "%PDF-1.4".getBytes());

            MvcResult result = mockMvc.perform(multipart("/api/v1/manager/curriculum-imports")
                            .file(pdf)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isAccepted())
                    .andReturn();

            String importIdStr = objectMapper.readTree(
                    result.getResponse().getContentAsString()).path("data").path("id").asText();
            UUID importId = UUID.fromString(importIdStr);

            // Simulate extraction failure
            CurriculumImport ci = curriculumImportRepository.findById(importId).orElseThrow();
            ci.markFailed("AI extraction error: simulated failure");
            curriculumImportRepository.save(ci);

            // Assert — FAILED status, zero partial records
            CurriculumImport failed = curriculumImportRepository.findById(importId).orElseThrow();
            assertThat(failed.getStatus()).isEqualTo(ImportStatus.FAILED);
            assertThat(lessonRepository.findByImportIdAndNotDeleted(importId)).isEmpty();
        }

        @Test
        @DisplayName("TC_LCM_IR_007 — no Authorization header → 401 Unauthorized")
        void tc_lcm_ir_007_noAuth_returns401() throws Exception {
            MockMultipartFile pdf = new MockMultipartFile(
                    "file", "test.pdf", "application/pdf", "%PDF".getBytes());

            mockMvc.perform(multipart("/api/v1/manager/curriculum-imports").file(pdf))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("TC_LCM_IR_008 — LEARNER token → 403 Forbidden FORBIDDEN")
        void tc_lcm_ir_008_learnerToken_returns403() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            MockMultipartFile pdf = new MockMultipartFile(
                    "file", "test.pdf", "application/pdf", "%PDF".getBytes());

            mockMvc.perform(multipart("/api/v1/manager/curriculum-imports")
                            .file(pdf)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 5. CREATE CONTENT MANUALLY
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. CREATE CONTENT MANUALLY")
    class CreateContentTests {

        @Test
        @DisplayName("TC_LCM_CL_001 — create lesson successfully → 201 Created")
        void tc_lcm_cl_001_createLesson_returns201() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Tiếng Nhật N5");

            String body = String.format(
                    "{\"courseId\":\"%s\",\"title\":\"テスト授業\",\"lessonType\":\"VOCABULARY\"}",
                    course.getId());

            mockMvc.perform(post("/api/v1/manager/lessons")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").isNotEmpty())
                    .andExpect(jsonPath("$.data.title").value("テスト授業"))
                    .andExpect(jsonPath("$.data.courseId").value(course.getId().toString()));
        }

        @Test
        @DisplayName("TC_LCM_CV_001 — create vocabulary successfully → 201 Created")
        void tc_lcm_cv_001_createVocabulary_returns201() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            String body = "{\"japaneseText\":\"猫\",\"jlptLevel\":\"N5\"}";

            mockMvc.perform(post("/api/v1/manager/vocabulary")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").isNotEmpty())
                    .andExpect(jsonPath("$.data.japaneseText").value("猫"))
                    .andExpect(jsonPath("$.data.jlptLevel").value("N5"));
        }

        @Test
        @DisplayName("TC_LCM_CG_001 — create grammar point successfully → 201 Created")
        void tc_lcm_cg_001_createGrammarPoint_returns201() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            String body = "{\"pattern\":\"〜てから\",\"jlptLevel\":\"N4\"}";

            mockMvc.perform(post("/api/v1/manager/grammar-points")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").isNotEmpty())
                    .andExpect(jsonPath("$.data.pattern").value("〜てから"))
                    .andExpect(jsonPath("$.data.jlptLevel").value("N4"));
        }

        @Test
        @DisplayName("TC_LCM_CL_002 — blank title → 400 VALIDATION_ERROR")
        void tc_lcm_cl_002_blankTitle_returns400() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Tiếng Nhật N5");

            String body = String.format(
                    "{\"courseId\":\"%s\",\"title\":\"\",\"lessonType\":\"VOCABULARY\"}", course.getId());

            mockMvc.perform(post("/api/v1/manager/lessons")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.data.title").isNotEmpty());
        }

        @Test
        @DisplayName("TC_LCM_CL_003 — title shorter than 3 chars → 400 VALIDATION_ERROR")
        void tc_lcm_cl_003_titleTooShort_returns400() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Tiếng Nhật N5");

            String body = String.format(
                    "{\"courseId\":\"%s\",\"title\":\"AB\",\"lessonType\":\"VOCABULARY\"}", course.getId());

            mockMvc.perform(post("/api/v1/manager/lessons")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.data.title").isNotEmpty());
        }

        @Test
        @DisplayName("TC_LCM_CL_004 — non-existing courseId → NOT_FOUND")
        void tc_lcm_cl_004_nonExistingCourseId_returnsNotFound() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            UUID randomId = UUID.randomUUID();
            String body = String.format(
                    "{\"courseId\":\"%s\",\"title\":\"ValidTitle\",\"lessonType\":\"VOCABULARY\"}", randomId);

            mockMvc.perform(post("/api/v1/manager/lessons")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("TC_LCM_CV_002 — missing jlptLevel → 400 VALIDATION_ERROR")
        void tc_lcm_cv_002_missingJlptLevel_returns400() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            String body = "{\"japaneseText\":\"猫\"}";

            mockMvc.perform(post("/api/v1/manager/vocabulary")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.data.jlptLevel").isNotEmpty());
        }

        @Test
        @DisplayName("TC_LCM_CL_005 — LEARNER token → 403 Forbidden FORBIDDEN")
        void tc_lcm_cl_005_learnerToken_returns403() throws Exception {
            saveLearner("learner@test.com");
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            String body = "{\"courseId\":\"" + UUID.randomUUID() + "\",\"title\":\"Test\",\"lessonType\":\"VOCABULARY\"}";

            mockMvc.perform(post("/api/v1/manager/lessons")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 6. UPDATE CONTENT
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. UPDATE CONTENT")
    class UpdateContentTests {

        @Test
        @DisplayName("TC_LCM_UL_001 — partial update, immutable fields (lessonType, courseId) unchanged")
        void tc_lcm_ul_001_updateLesson_partialUpdate() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Tiếng Nhật N5");
            Lesson lesson = saveLesson(course, "Mua sắm tại siêu thị",
                    Lesson.LessonType.VOCABULARY, 0);

            String body = "{\"title\":\"Mua sắm tại siêu thị - Updated\",\"description\":\"Mô tả mới\"}";

            mockMvc.perform(put("/api/v1/manager/lessons/{lessonId}", lesson.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.title").value("Mua sắm tại siêu thị - Updated"))
                    .andExpect(jsonPath("$.data.description").value("Mô tả mới"))
                    .andExpect(jsonPath("$.data.lessonType").value("VOCABULARY"))
                    .andExpect(jsonPath("$.data.courseId").value(course.getId().toString()));
        }

        @Test
        @DisplayName("TC_LCM_UL_002 — empty body {} → 200 OK, no fields changed")
        void tc_lcm_ul_002_emptyBody_noFieldsChanged() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Tiếng Nhật N5");
            Lesson lesson = saveLesson(course, "Original Title", Lesson.LessonType.VOCABULARY, 0);

            mockMvc.perform(put("/api/v1/manager/lessons/{lessonId}", lesson.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.title").value("Original Title"));
        }

        @Test
        @DisplayName("TC_LCM_UL_003 — title < 3 chars → 400 VALIDATION_ERROR")
        void tc_lcm_ul_003_titleTooShort_returns400() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Tiếng Nhật N5");
            Lesson lesson = saveLesson(course, "Original Title", Lesson.LessonType.VOCABULARY, 0);

            mockMvc.perform(put("/api/v1/manager/lessons/{lessonId}", lesson.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"AB\"}")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.data.title").isNotEmpty());
        }

        @Test
        @DisplayName("TC_LCM_UL_004 — non-existing lesson → 404 NOT_FOUND")
        void tc_lcm_ul_004_nonExistingLesson_returns404() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            UUID randomId = UUID.randomUUID();

            mockMvc.perform(put("/api/v1/manager/lessons/{lessonId}", randomId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"New Title\"}")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("TC_LCM_UV_001 — update vocabulary, japaneseText and jlptLevel immutable")
        void tc_lcm_uv_001_updateVocabulary_immutableFieldsUnchanged() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Vocabulary vocab = saveVocabulary("猫", JlptLevel.N5);

            String body = "{\"meaningVi\":\"Con mèo\",\"reading\":\"ねこ\"}";

            mockMvc.perform(put("/api/v1/manager/vocabulary/{vocabularyId}", vocab.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.meaningVi").value("Con mèo"))
                    .andExpect(jsonPath("$.data.reading").value("ねこ"))
                    .andExpect(jsonPath("$.data.japaneseText").value("猫"))
                    .andExpect(jsonPath("$.data.jlptLevel").value("N5"));
        }

        @Test
        @DisplayName("TC_LCM_UG_001 — update grammar point, pattern and jlptLevel immutable")
        void tc_lcm_ug_001_updateGrammarPoint_immutableFieldsUnchanged() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            GrammarPoint gp = saveGrammarPoint("〜てから", JlptLevel.N4);

            String body = "{\"meaningVi\":\"Sau khi làm gì đó thì...\"}";

            mockMvc.perform(put("/api/v1/manager/grammar-points/{grammarPointId}", gp.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.meaningVi").value("Sau khi làm gì đó thì..."))
                    .andExpect(jsonPath("$.data.pattern").value("〜てから"))
                    .andExpect(jsonPath("$.data.jlptLevel").value("N4"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 7. DELETE CONTENT — DELETE /api/v1/manager/lessons/{id}
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. DELETE CONTENT — DELETE /api/v1/manager/lessons/{id}")
    class DeleteContentTests {

        @Test
        @DisplayName("TC_LCM_DL_001 — soft-delete lesson, no longer visible in list")
        void tc_lcm_dl_001_deleteLesson_softDeleted() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Tiếng Nhật N5");
            Lesson lesson = saveLesson(course, "To Delete", Lesson.LessonType.VOCABULARY, 0);

            // Delete
            mockMvc.perform(delete("/api/v1/manager/lessons/{lessonId}", lesson.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            // Assert — soft-deleted in DB
            assertThat(lessonRepository.findByIdAndNotDeleted(lesson.getId())).isEmpty();
            Lesson deleted = lessonRepository.findById(lesson.getId()).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();

            // Assert — not in list
            mockMvc.perform(get("/api/v1/manager/lessons")
                            .param("courseId", course.getId().toString())
                            .param("page", "0").param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(jsonPath("$.data.content.length()").value(0));

            // Assert — GET by id → 404
            mockMvc.perform(get("/api/v1/manager/lessons/{lessonId}", lesson.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TC_LCM_DL_002 — cancel deletion (no DELETE sent) → lesson unchanged")
        void tc_lcm_dl_002_cancelDeletion_lessonUnchanged() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("Tiếng Nhật N5");
            Lesson lesson = saveLesson(course, "Keep Me", Lesson.LessonType.VOCABULARY, 0);

            // No DELETE request — confirm lesson is still accessible
            mockMvc.perform(get("/api/v1/manager/lessons/{lessonId}", lesson.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("Keep Me"));

            // DB — deletedAt is still null
            assertThat(lessonRepository.findByIdAndNotDeleted(lesson.getId())).isPresent();
        }

        @Test
        @DisplayName("TC_LCM_DL_003 — non-existing lessonId → 404 NOT_FOUND")
        void tc_lcm_dl_003_nonExistingLessonId_returns404() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(delete("/api/v1/manager/lessons/{lessonId}", UUID.randomUUID())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("TC_LCM_DL_004 — LEARNER token → 403 Forbidden FORBIDDEN")
        void tc_lcm_dl_004_learnerToken_returns403() throws Exception {
            saveLearner("learner@test.com");
            saveManager("manager@test.com");
            String learnerToken = loginAndGetToken("learner@test.com", "ValidPass@1");
            Course course = saveCourse("Tiếng Nhật N5");
            Lesson lesson = saveLesson(course, "Protected", Lesson.LessonType.VOCABULARY, 0);

            mockMvc.perform(delete("/api/v1/manager/lessons/{lessonId}", lesson.getId())
                            .header("Authorization", "Bearer " + learnerToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));

            // Assert — lesson unchanged
            assertThat(lessonRepository.findByIdAndNotDeleted(lesson.getId())).isPresent();
        }
    }
}
