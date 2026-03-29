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

@SpringBootTest(classes = com.fu.StartApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Learning Content Management — Integration Tests")
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

    private User saveManager(String email) {
        return userRepository.save(User.builder().email(email).passwordHash(passwordEncoder.encode("ValidPass@1")).firstName("Test").lastName("Manager").role(UserRole.CONTENT_MANAGER).status(UserStatus.ACTIVE).authProvider(AuthProvider.LOCAL).failedLoginAttempts(0).build());
    }

    private User saveLearner(String email) {
        return userRepository.save(User.builder().email(email).passwordHash(passwordEncoder.encode("ValidPass@1")).firstName("Test").lastName("Learner").role(UserRole.LEARNER).status(UserStatus.ACTIVE).authProvider(AuthProvider.LOCAL).failedLoginAttempts(0).build());
    }

    private String loginAndGetToken(String email, String password) throws Exception {
        String body = String.format("{\"email\":\"%s\",\"password\":\"%s\"}", email, password);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }

    private Course saveCourse(String name) {
        return courseRepository.save(Course.builder().name(name).description("Test course").jlptLevel(JlptLevel.N5).status(CourseStatus.ACTIVE).orderIndex(0).build());
    }

    private Lesson saveLesson(Course course, String title, Lesson.LessonType type, int orderIndex) {
        return lessonRepository.save(Lesson.builder().course(course).title(title).lessonType(type).status(Lesson.ContentStatus.DRAFT).orderIndex(orderIndex).build());
    }
    
    private Lesson saveLessonWithStatus(Course course, String title, Lesson.LessonType type, Lesson.ContentStatus status, int orderIndex) {
        return lessonRepository.save(Lesson.builder().course(course).title(title).lessonType(type).status(status).orderIndex(orderIndex).build());
    }

    private Lesson saveLessonWithImportId(Course course, String title, UUID importId, int orderIndex) {
        return lessonRepository.save(Lesson.builder().course(course).title(title).lessonType(Lesson.LessonType.VOCABULARY).status(Lesson.ContentStatus.DRAFT).importId(importId).orderIndex(orderIndex).build());
    }

    private Vocabulary saveVocabulary(String japaneseText, JlptLevel level) {
        return vocabularyRepository.save(Vocabulary.builder().japaneseText(japaneseText).jlptLevel(level).build());
    }

    private GrammarPoint saveGrammarPoint(String pattern, JlptLevel level) {
        return grammarPointRepository.save(GrammarPoint.builder().pattern(pattern).jlptLevel(level).build());
    }

    private CurriculumImport saveImport(UUID courseId, ImportStatus status, UUID managerId) {
        return curriculumImportRepository.save(CurriculumImport.builder().originalFilename("test.pdf").fileUrl("file://test.pdf").fileSizeBytes(1024L).fileType("application/pdf").courseId(courseId).status(status).createdBy(managerId).build());
    }

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

    @Nested
    @DisplayName("1. View Lesson List (VL)")
    class ViewLessonListTests {
        @Test
        @DisplayName("TC_LCM_VL_001 – View lesson list successfully")
        void tc_lcm_vl_001() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("N5 Course");
            saveLesson(course, "Lesson 1", Lesson.LessonType.VOCABULARY, 0);

            mockMvc.perform(get("/api/v1/manager/lessons").param("courseId", course.getId().toString()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content.length()").value(1));
        }

        @Test
        @DisplayName("TC_LCM_VL_002 – Search lessons by keyword")
        void tc_lcm_vl_002() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("N5 Course");
            saveLesson(course, "Apple Lesson", Lesson.LessonType.VOCABULARY, 0);
            saveLesson(course, "Banana Lesson", Lesson.LessonType.VOCABULARY, 1);

            mockMvc.perform(get("/api/v1/manager/lessons").param("courseId", course.getId().toString()).param("keyword", "Apple").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].title").value("Apple Lesson"));
        }

        @Test
        @DisplayName("TC_LCM_VL_003 – Filter lessons by status")
        void tc_lcm_vl_003() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("N5 Course");
            saveLessonWithStatus(course, "Draft Lesson", Lesson.LessonType.VOCABULARY, Lesson.ContentStatus.DRAFT, 0);
            saveLessonWithStatus(course, "Published Lesson", Lesson.LessonType.VOCABULARY, Lesson.ContentStatus.PUBLISHED, 1);

            mockMvc.perform(get("/api/v1/manager/lessons").param("courseId", course.getId().toString()).param("status", "PUBLISHED").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("TC_LCM_VL_004 – Pagination works correctly")
        void tc_lcm_vl_004() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("N5 Course");
            for(int i=0; i<15; i++) {
                saveLesson(course, "Lesson " + i, Lesson.LessonType.VOCABULARY, i);
            }

            mockMvc.perform(get("/api/v1/manager/lessons").param("courseId", course.getId().toString()).param("page", "1").param("size", "10").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content.length()").value(5))
                    .andExpect(jsonPath("$.data.pageNumber").value(1))
                    .andExpect(jsonPath("$.data.pageSize").value(10));
        }

        @Test
        @DisplayName("TC_LCM_VL_005 – Only approved import lessons are visible")
        void tc_lcm_vl_005() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("N5 Course");
            
            CurriculumImport pendingImport = saveImport(course.getId(), ImportStatus.PENDING_APPROVAL, manager.getId());
            saveLessonWithImportId(course, "Pending Lesson", pendingImport.getId(), 0);
            
            CurriculumImport completedImport = saveImport(course.getId(), ImportStatus.COMPLETED, manager.getId());
            saveLessonWithImportId(course, "Completed Lesson", completedImport.getId(), 1);

            mockMvc.perform(get("/api/v1/manager/lessons").param("courseId", course.getId().toString()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("TC_LCM_VL_006 – No result found")
        void tc_lcm_vl_006() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("N5 Course");

            mockMvc.perform(get("/api/v1/manager/lessons").param("courseId", course.getId().toString()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content.length()").value(0));
        }
    }

    @Nested
    @DisplayName("2. View Lesson Detail (VD)")
    class ViewLessonDetailTests {
        @Test
        @DisplayName("TC_LCM_VD_001 – View lesson detail successfully")
        void tc_lcm_vd_001() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("N5");
            Lesson lesson = saveLesson(course, "Detail Lesson", Lesson.LessonType.VOCABULARY, 0);

            mockMvc.perform(get("/api/v1/manager/lessons/{id}", lesson.getId()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(lesson.getId().toString()));
        }

        @Test
        @DisplayName("TC_LCM_VD_002 – Lesson not found")
        void tc_lcm_vd_002() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(get("/api/v1/manager/lessons/{id}", UUID.randomUUID()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    @Nested
    @DisplayName("3. Import (IM)")
    class ImportTests {
        @Test
        @DisplayName("TC_LCM_IM_001 – Create import job successfully")
        void tc_lcm_im_001() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            MockMultipartFile pdf = new MockMultipartFile("file", "test.pdf", "application/pdf", "%PDF".getBytes());

            mockMvc.perform(multipart("/api/v1/manager/curriculum-imports").file(pdf).header("Authorization", "Bearer " + token))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").isNotEmpty());
        }

        @Test
        @DisplayName("TC_LCM_IM_002 – Reject invalid file type")
        void tc_lcm_im_002() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            MockMultipartFile txt = new MockMultipartFile("file", "test.txt", "text/plain", "hello".getBytes());

            mockMvc.perform(multipart("/api/v1/manager/curriculum-imports").file(txt).header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_FILE"));
        }

        @Test
        @DisplayName("TC_LCM_IM_003 – Reject file size exceeds limit")
        void tc_lcm_im_003() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            MockMultipartFile bigPdf = new MockMultipartFile("file", "big.pdf", "application/pdf", "%PDF".getBytes()){
                @Override public long getSize() { return 101L * 1024 * 1024; }
            };

            mockMvc.perform(multipart("/api/v1/manager/curriculum-imports").file(bigPdf).header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_FILE"));
        }
    }

    @Nested
    @DisplayName("4. Import History / Detail (IH)")
    class ImportHistoryTests {
        @Test
        @DisplayName("TC_LCM_IH_001 – View import history")
        void tc_lcm_ih_001() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            saveImport(null, ImportStatus.COMPLETED, manager.getId());

            mockMvc.perform(get("/api/v1/manager/curriculum-imports").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isArray());
        }

        @Test
        @DisplayName("TC_LCM_IH_002 – View import detail")
        void tc_lcm_ih_002() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            CurriculumImport ci = saveImport(null, ImportStatus.COMPLETED, manager.getId());

            mockMvc.perform(get("/api/v1/manager/curriculum-imports/{id}", ci.getId()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(ci.getId().toString()));
        }

        @Test
        @DisplayName("TC_LCM_IH_003 – Import not found")
        void tc_lcm_ih_003() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(get("/api/v1/manager/curriculum-imports/{id}", UUID.randomUUID()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    @Nested
    @DisplayName("5. Approve / Rollback Import (AI)")
    class ApproveRollbackImportTests {
        @Test
        @DisplayName("TC_LCM_AI_001 – Approve import successfully")
        void tc_lcm_ai_001() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            CurriculumImport ci = saveImport(null, ImportStatus.PENDING_APPROVAL, manager.getId());

            mockMvc.perform(post("/api/v1/manager/curriculum-imports/{id}/approve", ci.getId()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
                    
            CurriculumImport updated = curriculumImportRepository.findById(ci.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ImportStatus.COMPLETED);
        }

        @Test
        @DisplayName("TC_LCM_AI_002 – Reject approve with invalid status")
        void tc_lcm_ai_002() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            CurriculumImport ci = saveImport(null, ImportStatus.COMPLETED, manager.getId());

            mockMvc.perform(post("/api/v1/manager/curriculum-imports/{id}/approve", ci.getId()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("TC_LCM_AI_003 – Rollback import successfully")
        void tc_lcm_ai_003() throws Exception {
            User manager = saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("N5 Course");
            CurriculumImport ci = saveImport(course.getId(), ImportStatus.COMPLETED, manager.getId());
            Lesson lesson = saveLessonWithImportId(course, "ToDelete", ci.getId(), 0);

            mockMvc.perform(post("/api/v1/manager/curriculum-imports/{id}/rollback", ci.getId()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            CurriculumImport updated = curriculumImportRepository.findById(ci.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ImportStatus.ROLLED_BACK);
            assertThat(lessonRepository.findByIdAndNotDeleted(lesson.getId())).isEmpty();
        }

        @Test
        @DisplayName("TC_LCM_AI_004 – Import not found when rollback")
        void tc_lcm_ai_004() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/manager/curriculum-imports/{id}/rollback", UUID.randomUUID()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    @Nested
    @DisplayName("6. Lesson Lifecycle (LL)")
    class LessonLifecycleTests {
        @Test
        @DisplayName("TC_LCM_LL_001 – Approve lesson successfully")
        void tc_lcm_ll_001() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("N5 Course");
            Lesson lesson = saveLessonWithStatus(course, "Draft Lesson", Lesson.LessonType.VOCABULARY, Lesson.ContentStatus.DRAFT, 0);

            mockMvc.perform(patch("/api/v1/manager/lessons/{id}/publish", lesson.getId()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
                    
            Lesson updated = lessonRepository.findById(lesson.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(Lesson.ContentStatus.PUBLISHED);
        }

        @Test
        @DisplayName("TC_LCM_LL_002 – Reject approve invalid status")
        void tc_lcm_ll_002() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("N5 Course");
            Lesson lesson = saveLessonWithStatus(course, "Published Lesson", Lesson.LessonType.VOCABULARY, Lesson.ContentStatus.PUBLISHED, 0);

            mockMvc.perform(patch("/api/v1/manager/lessons/{id}/publish", lesson.getId()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("TC_LCM_LL_003 – Unpublish lesson successfully")
        void tc_lcm_ll_003() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("N5 Course");
            Lesson lesson = saveLessonWithStatus(course, "Published Lesson", Lesson.LessonType.VOCABULARY, Lesson.ContentStatus.PUBLISHED, 0);

            mockMvc.perform(patch("/api/v1/manager/lessons/{id}/archive", lesson.getId()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
                    
            Lesson updated = lessonRepository.findById(lesson.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(Lesson.ContentStatus.ARCHIVED);
        }

        @Test
        @DisplayName("TC_LCM_LL_004 – Reject unpublish invalid status")
        void tc_lcm_ll_004() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("N5 Course");
            Lesson lesson = saveLessonWithStatus(course, "Draft Lesson", Lesson.LessonType.VOCABULARY, Lesson.ContentStatus.DRAFT, 0);

            mockMvc.perform(patch("/api/v1/manager/lessons/{id}/archive", lesson.getId()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    @Nested
    @DisplayName("7. Lesson CRUD (LS)")
    class LessonCrudTests {
        @Test
        @DisplayName("TC_LCM_LS_001 – Create lesson successfully")
        void tc_lcm_ls_001() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("N5");

            String body = String.format("{\"courseId\":\"%s\",\"title\":\"New Lesson\",\"lessonType\":\"VOCABULARY\"}", course.getId());

            mockMvc.perform(post("/api/v1/manager/lessons").contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.title").value("New Lesson"));
        }

        @Test
        @DisplayName("TC_LCM_LS_002 – Create lesson missing required fields")
        void tc_lcm_ls_002() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            String body = "{\"title\":\"\"}"; // missing courseId and lessonType

            mockMvc.perform(post("/api/v1/manager/lessons").contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("TC_LCM_LS_003 – Update lesson successfully")
        void tc_lcm_ls_003() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("N5");
            Lesson lesson = saveLesson(course, "Old Name", Lesson.LessonType.VOCABULARY, 0);

            String body = "{\"title\":\"Updated Name\"}";

            mockMvc.perform(put("/api/v1/manager/lessons/{id}", lesson.getId()).contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.title").value("Updated Name"));
        }

        @Test
        @DisplayName("TC_LCM_LS_004 – Delete lesson (soft delete)")
        void tc_lcm_ls_004() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Course course = saveCourse("N5");
            Lesson lesson = saveLesson(course, "To Delete", Lesson.LessonType.VOCABULARY, 0);

            mockMvc.perform(delete("/api/v1/manager/lessons/{id}", lesson.getId()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            assertThat(lessonRepository.findByIdAndNotDeleted(lesson.getId())).isEmpty();
        }
    }

    @Nested
    @DisplayName("8. Vocabulary (VO)")
    class VocabularyTests {
        @Test
        @DisplayName("TC_LCM_VO_001 – Create vocabulary")
        void tc_lcm_vo_001() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            String body = "{\"japaneseText\":\"猫\",\"jlptLevel\":\"N5\"}";

            mockMvc.perform(post("/api/v1/manager/vocabulary").contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("TC_LCM_VO_002 – Update vocabulary")
        void tc_lcm_vo_002() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Vocabulary v = saveVocabulary("猫", JlptLevel.N5);

            String body = "{\"meaningVi\":\"Con mèo\",\"reading\":\"ねこ\"}";

            mockMvc.perform(put("/api/v1/manager/vocabulary/{id}", v.getId()).contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.meaningVi").value("Con mèo"));
        }

        @Test
        @DisplayName("TC_LCM_VO_003 – Delete vocabulary")
        void tc_lcm_vo_003() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            Vocabulary v = saveVocabulary("猫", JlptLevel.N5);

            mockMvc.perform(delete("/api/v1/manager/vocabulary/{id}", v.getId()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    @Nested
    @DisplayName("9. Grammar (GR)")
    class GrammarTests {
        @Test
        @DisplayName("TC_LCM_GR_001 – Create grammar")
        void tc_lcm_gr_001() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            String body = "{\"pattern\":\"〜てから\",\"jlptLevel\":\"N4\"}";

            mockMvc.perform(post("/api/v1/manager/grammar-points").contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("TC_LCM_GR_002 – Update grammar")
        void tc_lcm_gr_002() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            GrammarPoint gp = saveGrammarPoint("〜てから", JlptLevel.N4);

            String body = "{\"meaningVi\":\"Sau khi...\"}";

            mockMvc.perform(put("/api/v1/manager/grammar-points/{id}", gp.getId()).contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.meaningVi").value("Sau khi..."));
        }

        @Test
        @DisplayName("TC_LCM_GR_003 – Delete grammar")
        void tc_lcm_gr_003() throws Exception {
            saveManager("manager@test.com");
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            GrammarPoint gp = saveGrammarPoint("〜てから", JlptLevel.N4);

            mockMvc.perform(delete("/api/v1/manager/grammar-points/{id}", gp.getId()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }
}
