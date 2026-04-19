package com.fu.practice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.clazz.entity.ClassMember;
import com.fu.clazz.entity.ClassStatus;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.repository.ClassMemberRepository;
import com.fu.clazz.repository.ClassRepository;
import com.fu.course.entity.*;
import com.fu.course.repository.*;
import com.fu.infrastructure.email.EmailService;
import com.fu.practice.agent.ExerciseGeneratorAgent;
import com.fu.practice.agent.ExerciseGeneratorAgent.GeneratedItem;
import com.fu.practice.agent.SessionFeedbackAgent;
import com.fu.practice.dto.request.StartPracticeRequest;
import com.fu.practice.dto.request.SubmitAnswerRequest;
import com.fu.practice.entity.*;
import com.fu.practice.repository.ItemMasteryRepository;
import com.fu.practice.repository.LessonProgressRepository;
import com.fu.practice.repository.PracticeItemRepository;
import com.fu.practice.repository.PracticeSessionRepository;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = com.fu.StartApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("2.12 Practice with AI — Integration Tests")
class PracticeIntegrationTest {

    // ── Infrastructure ─────────────────────────────────────────────────────

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    // ── Repositories ───────────────────────────────────────────────────────

    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private VocabularyRepository vocabularyRepository;
    @Autowired private LessonVocabularyRepository lessonVocabularyRepository;
    @Autowired private GrammarPointRepository grammarPointRepository;
    @Autowired private LessonGrammarRepository lessonGrammarRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private ClassMemberRepository classMemberRepository;
    @Autowired private PracticeSessionRepository practiceSessionRepository;
    @Autowired private PracticeItemRepository practiceItemRepository;
    @Autowired private ItemMasteryRepository itemMasteryRepository;
    @Autowired private LessonProgressRepository lessonProgressRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    // ── Mocked external systems ────────────────────────────────────────────

    @MockBean private EmailService emailService;
    @MockBean private ExerciseGeneratorAgent exerciseGeneratorAgent;
    @MockBean private SessionFeedbackAgent sessionFeedbackAgent;

    // ── Shared fixtures ────────────────────────────────────────────────────

    private User learner;
    private User otherLearner;
    private User teacher;
    private User contentManager;
    private User admin;
    private Course course;
    private Lesson publishedLesson;
    private String learnerToken;
    private String teacherToken;
    private String managerToken;
    private String adminToken;

    // ── Setup / Teardown ──────────────────────────────────────────────────

    @BeforeEach
    void setUp() throws Exception {
        clearAllData();

        // Users
        learner       = saveUser("learner@test.com",  UserRole.LEARNER);
        otherLearner  = saveUser("other@test.com",    UserRole.LEARNER);
        teacher       = saveUser("teacher@test.com",  UserRole.TEACHER);
        contentManager= saveUser("manager@test.com",  UserRole.CONTENT_MANAGER);
        admin         = saveUser("admin@test.com",     UserRole.ADMIN);

        // Course + active class + enroll learner
        course = courseRepository.save(Course.builder()
                .name("N5 Test Course")
                .description("desc")
                .status(Course.CourseStatus.ACTIVE)
                .jlptLevel(Course.JlptLevel.N5)
                .build());

        Clazz clazz = classRepository.save(Clazz.builder()
                .name("Test Class")
                .courseId(course.getId())
                .maxStudents(30)
                .startDate(LocalDate.now().minusDays(1))
                .status(ClassStatus.ACTIVE)
                .build());

        classMemberRepository.save(ClassMember.builder()
                .classId(clazz.getId())
                .userId(learner.getId())
                .build());

        // Published lesson with vocab & grammar
        publishedLesson = lessonRepository.save(Lesson.builder()
                .course(course)
                .title("Lesson 1 — Greetings")
                .status(Lesson.ContentStatus.PUBLISHED)
                .orderIndex(1)
                .build());

        saveVocab(publishedLesson, "先生", "せんせい", "giáo viên");
        saveVocab(publishedLesson, "学生", "がくせい", "học sinh");
        saveGrammar(publishedLesson, "〜ます", "lịch sự thể");

        // Tokens
        learnerToken  = loginAndGetToken("learner@test.com",  "ValidPass@1");
        teacherToken  = loginAndGetToken("teacher@test.com",  "ValidPass@1");
        managerToken  = loginAndGetToken("manager@test.com",  "ValidPass@1");
        adminToken    = loginAndGetToken("admin@test.com",    "ValidPass@1");

        // Default AI mock: return 5 MC items for any generateItems call
        stubExerciseGenerator(5);
        when(sessionFeedbackAgent.generateFeedback(any(), anyDouble(), anyList(), any()))
                .thenReturn("{\"summary\":\"ok\"}");
    }

    @AfterEach
    void tearDown() {
        clearAllData();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void clearAllData() {
        itemMasteryRepository.deleteAllInBatch();
        lessonProgressRepository.deleteAllInBatch();
        practiceItemRepository.deleteAllInBatch();
        practiceSessionRepository.deleteAllInBatch();
        lessonVocabularyRepository.deleteAllInBatch();
        lessonGrammarRepository.deleteAllInBatch();
        vocabularyRepository.deleteAllInBatch();
        grammarPointRepository.deleteAllInBatch();
        lessonRepository.deleteAllInBatch();
        classMemberRepository.deleteAllInBatch();
        classRepository.deleteAllInBatch();
        courseRepository.deleteAllInBatch();
        userSessionRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private User saveUser(String email, UserRole role) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("ValidPass@1"))
                .firstName("Test").lastName(role.name())
                .role(role).status(UserStatus.ACTIVE)
                .failedLoginAttempts(0)
                .build());
    }

    /**
     * Save a Vocabulary and link it to a Lesson via the LessonVocabulary join table.
     * Vocabulary does NOT have a direct lesson foreign key.
     */
    private Vocabulary saveVocab(Lesson lesson, String japanese, String reading, String meaning) {
        Vocabulary vocab = vocabularyRepository.save(Vocabulary.builder()
                .japaneseText(japanese)
                .reading(reading)
                .meaningVi(meaning)
                .jlptLevel(lesson.getCourse().getJlptLevel())
                .status(Course.ContentStatus.PUBLISHED)
                .build());
        lessonVocabularyRepository.save(LessonVocabulary.builder()
                .lesson(lesson)
                .vocabulary(vocab)
                .orderIndex(0)
                .build());
        return vocab;
    }

    /**
     * Save a GrammarPoint and link it to a Lesson via the LessonGrammar join table.
     * GrammarPoint does NOT have a direct lesson foreign key.
     */
    private GrammarPoint saveGrammar(Lesson lesson, String pattern, String meaning) {
        GrammarPoint grammar = grammarPointRepository.save(GrammarPoint.builder()
                .pattern(pattern)
                .meaningVi(meaning)
                .jlptLevel(lesson.getCourse().getJlptLevel())
                .status(Course.ContentStatus.PUBLISHED)
                .build());
        lessonGrammarRepository.save(LessonGrammar.builder()
                .lesson(lesson)
                .grammarPoint(grammar)
                .orderIndex(0)
                .build());
        return grammar;
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

    /** Stub ExerciseGeneratorAgent to return N simple MULTIPLE_CHOICE items. */
    private void stubExerciseGenerator(int count) {
        List<GeneratedItem> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            GeneratedItem item = new GeneratedItem();
            // Use reflection-free public setters (GeneratedItem has public getters/setters via @JsonProperty)
            // Build via JSON string and deserialize to bypass constructor constraints
            items.add(buildMcItem("Question " + i, i));
        }
        when(exerciseGeneratorAgent.generateItems(
                any(), any(), any(), anyInt(), any(), any(),
                anyList(), anyList(), anyList(), anyList(),
                anyBoolean(), any(), any(), any()))
                .thenReturn(items);
    }

    /** Build a minimal MULTIPLE_CHOICE GeneratedItem via ObjectMapper. */
    private GeneratedItem buildMcItem(String question, int index) {
        try {
            String json = String.format("""
                    {
                      "itemType": "MULTIPLE_CHOICE",
                      "question": "%s",
                      "questionJapanese": null,
                      "options": [
                        {"text": "A", "isCorrect": true},
                        {"text": "B", "isCorrect": false},
                        {"text": "C", "isCorrect": false},
                        {"text": "D", "isCorrect": false}
                      ],
                      "pairs": null,
                      "correctAnswer": "A",
                      "hint": null,
                      "explanation": "A is correct.",
                      "refContentId": null,
                      "refContentType": "VOCABULARY"
                    }
                    """, question);
            return objectMapper.readValue(json, GeneratedItem.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build mock GeneratedItem", e);
        }
    }

    /** Build a GeneratedItem for a specific itemType with 4 options (for format TCs). */
    private GeneratedItem buildTypedItem(String itemType, String question) {
        try {
            String optionsJson = switch (itemType) {
                case "TRUE_FALSE" -> """
                        [{"text":"Đúng","isCorrect":true},{"text":"Sai","isCorrect":false}]
                        """;
                case "MATCHING" -> "[]";
                case "SENTENCE_ORDER" -> """
                        [{"text":"先生","isCorrect":false},{"text":"は","isCorrect":false},{"text":"田中","isCorrect":false}]
                        """;
                default -> """
                        [{"text":"A","isCorrect":true},{"text":"B","isCorrect":false},
                         {"text":"C","isCorrect":false},{"text":"D","isCorrect":false}]
                        """;
            };
            String pairsJson = itemType.equals("MATCHING")
                    ? "[{\"left\":\"先生\",\"right\":\"giáo viên\"},{\"left\":\"学生\",\"right\":\"học sinh\"},{\"left\":\"先輩\",\"right\":\"đàn anh\"},{\"left\":\"後輩\",\"right\":\"đàn em\"}]"
                    : "null";
            String correctAnswer = switch (itemType) {
                case "MATCHING" -> null;
                case "SENTENCE_ORDER" -> "0,1,2";
                default -> "A";
            };
            String correctAnswerJson = correctAnswer == null ? "null" : "\"" + correctAnswer + "\"";

            String json = String.format("""
                    {
                      "itemType": "%s",
                      "question": "%s",
                      "questionJapanese": null,
                      "options": %s,
                      "pairs": %s,
                      "correctAnswer": %s,
                      "hint": null,
                      "explanation": "Explanation here.",
                      "refContentId": null,
                      "refContentType": "VOCABULARY"
                    }
                    """, itemType, question, optionsJson, pairsJson, correctAnswerJson);
            return objectMapper.readValue(json, GeneratedItem.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build typed GeneratedItem for " + itemType, e);
        }
    }

    /** Create a minimal PracticeSession + items directly in DB for submit / summary tests. */
    private PracticeSession createSession(User user, int totalItems) {
        PracticeSession session = practiceSessionRepository.save(PracticeSession.builder()
                .userId(user.getId())
                .lessonId(publishedLesson.getId())
                .courseId(course.getId())
                .sessionType(PracticeSessionType.VOCAB_DRILL)
                .status(PracticeStatus.IN_PROGRESS)
                .totalItems(totalItems)
                .completedItems(0)
                .correctItems(0)
                .score(BigDecimal.ZERO)
                .runningScoreSum(0)
                .build());

        for (int i = 0; i < totalItems; i++) {
            // options JSON: index 0 = correct
            String optionsJson = "[{\"text\":\"A\",\"isCorrect\":true},{\"text\":\"B\",\"isCorrect\":false}," +
                                  "{\"text\":\"C\",\"isCorrect\":false},{\"text\":\"D\",\"isCorrect\":false}]";
            practiceItemRepository.save(PracticeItem.builder()
                    .sessionId(session.getId())
                    .itemIndex(i)
                    .itemType(PracticeItemType.MULTIPLE_CHOICE)
                    .question("Question " + i)
                    .options(optionsJson)
                    .correctAnswer("0")   // index 0 = "A"
                    .explanation("A is correct.")
                    .build());
        }
        return session;
    }

    private UUID extractSessionId(MvcResult result) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("session").path("sessionId").asText());
    }

    /** Fetch the Course from DB to access jlptLevel (lesson.getCourse() may be lazy). */
    private Course.JlptLevel getJlptLevel(Lesson lesson) {
        return courseRepository.findById(lesson.getCourse().getId())
                .map(Course::getJlptLevel)
                .orElse(Course.JlptLevel.N5);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Section 0 — RBAC: all /api/v1/learner/practice/** is LEARNER-only
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Section 0 — RBAC Guard")
    class RbacTests {

        @Test
        @DisplayName("TC_PAC_001 — Unauthenticated POST /sessions → 401")
        void tc_pac_001_unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/learner/practice/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("TC_PAC_002 — CONTENT_MANAGER POST /sessions → 403")
        void tc_pac_002_contentManager_returns403() throws Exception {
            String body = objectMapper.writeValueAsString(StartPracticeRequest.builder()
                    .lessonId(publishedLesson.getId())
                    .sessionType(PracticeSessionType.VOCAB_DRILL)
                    .build());
            mockMvc.perform(post("/api/v1/learner/practice/sessions")
                            .header("Authorization", "Bearer " + managerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("TC_PAC_003 — TEACHER GET /lessons/{id}/items → 403")
        void tc_pac_003_teacher_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/learner/practice/lessons/{id}/items",
                            publishedLesson.getId())
                            .header("Authorization", "Bearer " + teacherToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("TC_PAC_004 — ADMIN GET /sessions/{id}/summary → 403")
        void tc_pac_004_admin_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/learner/practice/sessions/{id}/summary",
                            UUID.randomUUID())
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isForbidden());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Section 1 — UC 2.12.1: View Lesson List (GET /api/v1/courses/{id}/lessons)
    // Public endpoint — no auth guard, only PUBLISHED lessons returned
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Section 1 — UC 2.12.1 View Lesson List")
    class ViewLessonListTests {

        @Test
        @DisplayName("TC_PVL_001 — 3 PUBLISHED lessons returned with required fields")
        void tc_pvl_001_publishedLessons_returnedWithFields() throws Exception {
            // Arrange: add 2 more published lessons (1 already in setUp)
            lessonRepository.save(Lesson.builder().course(course).title("Lesson 2")
                    .status(Lesson.ContentStatus.PUBLISHED).orderIndex(2).build());
            lessonRepository.save(Lesson.builder().course(course).title("Lesson 3")
                    .status(Lesson.ContentStatus.PUBLISHED).orderIndex(3).build());

            // Act + Assert
            MvcResult result = mockMvc.perform(get("/api/v1/courses/{courseId}/lessons", course.getId())
                            .header("Authorization", "Bearer " + learnerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.length()").value(3))
                    .andReturn();

            // Verify required fields present on each item
            var content = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
            content.forEach(item -> {
                assertThat(item.has("id")).isTrue();
                assertThat(item.path("id").asText()).isNotBlank();
                assertThat(item.has("title")).isTrue();
            });
        }

        @Test
        @DisplayName("TC_PVL_002 — DRAFT and ARCHIVED lessons excluded, only PUBLISHED returned (BR-260)")
        void tc_pvl_002_draftAndArchived_excluded() throws Exception {
            // Arrange: setUp already has 1 PUBLISHED. Add 1 DRAFT + 1 ARCHIVED.
            lessonRepository.save(Lesson.builder().course(course).title("Draft Lesson")
                    .status(Lesson.ContentStatus.DRAFT).orderIndex(2).build());
            lessonRepository.save(Lesson.builder().course(course).title("Archived Lesson")
                    .status(Lesson.ContentStatus.ARCHIVED).orderIndex(3).build());

            mockMvc.perform(get("/api/v1/courses/{courseId}/lessons", course.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].title").value("Lesson 1 — Greetings"));
        }

        @Test
        @DisplayName("TC_PVL_003 — Soft-deleted PUBLISHED lesson not returned")
        void tc_pvl_003_softDeletedLesson_excluded() throws Exception {
            // Arrange: soft-delete the published lesson from setUp
            publishedLesson.softDelete(admin.getId());
            lessonRepository.save(publishedLesson);

            mockMvc.perform(get("/api/v1/courses/{courseId}/lessons", course.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("TC_PVL_004 — Course with no PUBLISHED lessons → empty list (A1 of UC 2.12.1)")
        void tc_pvl_004_noPublishedLessons_returnsEmptyList() throws Exception {
            // Arrange: delete published lesson from setUp
            lessonRepository.delete(publishedLesson);

            mockMvc.perform(get("/api/v1/courses/{courseId}/lessons", course.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("TC_PVL_005 — Non-existent courseId → empty list (no crash)")
        void tc_pvl_005_nonExistentCourseId_returnsEmptyList() throws Exception {
            mockMvc.perform(get("/api/v1/courses/{courseId}/lessons", UUID.randomUUID()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Section 2 — Lesson Items Picker (GET /api/v1/learner/practice/lessons/{id}/items)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Section 2 — Lesson Items Picker")
    class LessonItemsTests {

        @Test
        @DisplayName("TC_PLI_001 — Happy path: vocabulary and grammar items returned with summary")
        void tc_pli_001_happyPath_returnsVocabAndGrammar() throws Exception {
            MvcResult result = mockMvc.perform(
                            get("/api/v1/learner/practice/lessons/{lessonId}/items", publishedLesson.getId())
                                    .header("Authorization", "Bearer " + learnerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.vocabulary").isArray())
                    .andExpect(jsonPath("$.data.grammar").isArray())
                    .andExpect(jsonPath("$.data.summary.totalVocab").value(2))
                    .andExpect(jsonPath("$.data.summary.totalGrammar").value(1))
                    .andReturn();

            var data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
            // Each vocab item must have id, japaneseText, reading, meaningVi
            data.path("vocabulary").forEach(v -> {
                assertThat(v.path("id").asText()).isNotBlank();
                assertThat(v.path("japaneseText").asText()).isNotBlank();
            });
        }

        @Test
        @DisplayName("TC_PLI_002 — masteryLevel=0 filter: only items with no mastery record returned")
        void tc_pli_002_masteryLevelFilter_zero_returnsUnpracticed() throws Exception {
            // Items in setUp have no mastery → effectiveLevel=0 → should match
            mockMvc.perform(
                            get("/api/v1/learner/practice/lessons/{lessonId}/items", publishedLesson.getId())
                                    .param("masteryLevel", "0")
                                    .header("Authorization", "Bearer " + learnerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.vocabulary.length()").value(2))
                    .andExpect(jsonPath("$.data.grammar.length()").value(1));
        }

        @Test
        @DisplayName("TC_PLI_003 — masteryLevel=0&masteryLevel=1 multi-value: OR filter works correctly")
        void tc_pli_003_multiValueMasteryFilter_orLogic() throws Exception {
            // All items have no mastery (level 0) → should appear when filtering 0 or 1
            mockMvc.perform(
                            get("/api/v1/learner/practice/lessons/{lessonId}/items", publishedLesson.getId())
                                    .param("masteryLevel", "0")
                                    .param("masteryLevel", "1")
                                    .header("Authorization", "Bearer " + learnerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.vocabulary.length()").value(2));
        }

        @Test
        @DisplayName("TC_PLI_004 — Lesson not belonging to learner's enrolled course → 4xx")
        void tc_pli_004_unenrolledLesson_returns4xx() throws Exception {
            // Create a separate course with no enrollment and a lesson in it
            Course otherCourse = courseRepository.save(Course.builder()
                    .name("Other Course").description("x")
                    .status(Course.CourseStatus.ACTIVE).jlptLevel(Course.JlptLevel.N4)
                    .build());
            Lesson otherLesson = lessonRepository.save(Lesson.builder()
                    .course(otherCourse).title("Other Lesson")
                    .status(Lesson.ContentStatus.PUBLISHED).orderIndex(1).build());

            mockMvc.perform(
                            get("/api/v1/learner/practice/lessons/{lessonId}/items", otherLesson.getId())
                                    .header("Authorization", "Bearer " + learnerToken))
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("TC_PLI_005 — Non-existent lessonId → 4xx LESSON_NOT_FOUND")
        void tc_pli_005_nonExistentLesson_returns4xx() throws Exception {
            mockMvc.perform(
                            get("/api/v1/learner/practice/lessons/{lessonId}/items", UUID.randomUUID())
                                    .header("Authorization", "Bearer " + learnerToken))
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Section 3 — UC 2.12.2: Start Practice Session (POST /sessions)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Section 3 — UC 2.12.2 Start Practice Session")
    class StartSessionTests {

        @Test
        @DisplayName("TC_PSS_001 — VOCAB_DRILL + preset QUICK → 5 items, session IN_PROGRESS (BR-365)")
        void tc_pss_001_quickPreset_returns5Items() throws Exception {
            stubExerciseGenerator(5);

            String body = objectMapper.writeValueAsString(StartPracticeRequest.builder()
                    .lessonId(publishedLesson.getId())
                    .sessionType(PracticeSessionType.VOCAB_DRILL)
                    .preset(PracticePreset.QUICK)
                    .build());

            MvcResult result = mockMvc.perform(post("/api/v1/learner/practice/sessions")
                            .header("Authorization", "Bearer " + learnerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.session.sessionId").isNotEmpty())
                    .andExpect(jsonPath("$.data.session.status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.data.items.length()").value(5))
                    .andReturn();

            UUID sessionId = extractSessionId(result);
            PracticeSession saved = practiceSessionRepository.findById(sessionId).orElseThrow();
            assertThat(saved.getUserId()).isEqualTo(learner.getId());
            assertThat(saved.getStatus()).isEqualTo(PracticeStatus.IN_PROGRESS);
            assertThat(saved.getTotalItems()).isEqualTo(5);
            assertThat(saved.isExplicitMode()).isFalse(); // QUICK preset = auto mode
        }

        @Test
        @DisplayName("TC_PSS_002 — GRAMMAR_DRILL + preset STANDARD → 10 items (BR-365)")
        void tc_pss_002_standardPreset_returns10Items() throws Exception {
            stubExerciseGenerator(10);

            String body = objectMapper.writeValueAsString(StartPracticeRequest.builder()
                    .lessonId(publishedLesson.getId())
                    .sessionType(PracticeSessionType.GRAMMAR_DRILL)
                    .preset(PracticePreset.STANDARD)
                    .build());

            mockMvc.perform(post("/api/v1/learner/practice/sessions")
                            .header("Authorization", "Bearer " + learnerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.items.length()").value(10));
        }

        @Test
        @DisplayName("TC_PSS_003 — MIXED_LESSON + preset FULL → 20 items (BR-365)")
        void tc_pss_003_fullPreset_returns20Items() throws Exception {
            stubExerciseGenerator(20);

            String body = objectMapper.writeValueAsString(StartPracticeRequest.builder()
                    .lessonId(publishedLesson.getId())
                    .sessionType(PracticeSessionType.MIXED_LESSON)
                    .preset(PracticePreset.FULL)
                    .build());

            mockMvc.perform(post("/api/v1/learner/practice/sessions")
                            .header("Authorization", "Bearer " + learnerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.items.length()").value(20));
        }

        @Test
        @DisplayName("TC_PSS_004 — Explicit mode: selectedVocabIds passed → session marked explicitMode=true (BR-366)")
        void tc_pss_004_explicitMode_persistsFlag() throws Exception {
            Vocabulary v = vocabularyRepository.findAll().get(0);
            stubExerciseGenerator(1);

            String body = objectMapper.writeValueAsString(StartPracticeRequest.builder()
                    .lessonId(publishedLesson.getId())
                    .sessionType(PracticeSessionType.VOCAB_DRILL)
                    .selectedVocabIds(List.of(v.getId()))
                    .build());

            MvcResult result = mockMvc.perform(post("/api/v1/learner/practice/sessions")
                            .header("Authorization", "Bearer " + learnerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andReturn();

            UUID sessionId = extractSessionId(result);
            PracticeSession saved = practiceSessionRepository.findById(sessionId).orElseThrow();
            assertThat(saved.isExplicitMode()).isTrue(); // `is_explicit_mode` column via Lombok boolean getter
        }

        @Test
        @DisplayName("TC_PSS_005 — Missing lessonId → 400 (@NotNull)")
        void tc_pss_005_missingLessonId_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(StartPracticeRequest.builder()
                    .sessionType(PracticeSessionType.VOCAB_DRILL)
                    .build());

            mockMvc.perform(post("/api/v1/learner/practice/sessions")
                            .header("Authorization", "Bearer " + learnerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("TC_PSS_006 — Missing sessionType → 400 (@NotNull)")
        void tc_pss_006_missingSessionType_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(StartPracticeRequest.builder()
                    .lessonId(publishedLesson.getId())
                    .build());

            mockMvc.perform(post("/api/v1/learner/practice/sessions")
                            .header("Authorization", "Bearer " + learnerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("TC_PSS_007 — selectedVocabIds > 50 items → 400 (@Size max=50)")
        void tc_pss_007_tooManySelectedVocabIds_returns400() throws Exception {
            List<UUID> tooMany = new ArrayList<>();
            for (int i = 0; i < 51; i++) tooMany.add(UUID.randomUUID());

            String body = objectMapper.writeValueAsString(StartPracticeRequest.builder()
                    .lessonId(publishedLesson.getId())
                    .sessionType(PracticeSessionType.VOCAB_DRILL)
                    .selectedVocabIds(tooMany)
                    .build());

            mockMvc.perform(post("/api/v1/learner/practice/sessions")
                            .header("Authorization", "Bearer " + learnerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("TC_PSS_008 — Lesson belongs to course learner is not enrolled in → 4xx NOT_ENROLLED")
        void tc_pss_008_unenrolledCourse_returns4xx() throws Exception {
            Course other = courseRepository.save(Course.builder()
                    .name("Unenrolled").description("x")
                    .status(Course.CourseStatus.ACTIVE).jlptLevel(Course.JlptLevel.N3)
                    .build());
            Lesson otherLesson = lessonRepository.save(Lesson.builder()
                    .course(other).title("Other").status(Lesson.ContentStatus.PUBLISHED)
                    .orderIndex(1).build());

            String body = objectMapper.writeValueAsString(StartPracticeRequest.builder()
                    .lessonId(otherLesson.getId())
                    .sessionType(PracticeSessionType.VOCAB_DRILL)
                    .build());

            mockMvc.perform(post("/api/v1/learner/practice/sessions")
                            .header("Authorization", "Bearer " + learnerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Section 4 — Item Format per Exercise Type
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Section 4 — Item Format per Exercise Type")
    class ItemFormatTests {

        private MvcResult startSessionWith(String itemType) throws Exception {
            GeneratedItem item = buildTypedItem(itemType, "Test question for " + itemType);
            when(exerciseGeneratorAgent.generateItems(
                    any(), any(), any(), anyInt(), any(), any(),
                    anyList(), anyList(), anyList(), anyList(),
                    anyBoolean(), any(), any(), any()))
                    .thenReturn(List.of(item));

            String body = objectMapper.writeValueAsString(StartPracticeRequest.builder()
                    .lessonId(publishedLesson.getId())
                    .sessionType(PracticeSessionType.VOCAB_DRILL)
                    .preset(PracticePreset.QUICK)
                    .itemTypes(List.of(PracticeItemType.valueOf(itemType)))
                    .build());

            return mockMvc.perform(post("/api/v1/learner/practice/sessions")
                            .header("Authorization", "Bearer " + learnerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andReturn();
        }

        @Test
        @DisplayName("TC_PIF_001 — MULTIPLE_CHOICE: 4 options, isCorrect=null before submit, question not blank")
        void tc_pif_001_multipleChoice_format() throws Exception {
            MvcResult result = startSessionWith("MULTIPLE_CHOICE");
            var firstItem = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("items").get(0);

            assertThat(firstItem.path("itemType").asText()).isEqualTo("MULTIPLE_CHOICE");
            assertThat(firstItem.path("question").asText()).isNotBlank();
            assertThat(firstItem.path("options").size()).isEqualTo(4);
            // isCorrect must not be exposed before submit (NonNull serialization strips null)
            firstItem.path("options").forEach(opt -> {
                assertThat(opt.path("id").asInt()).isGreaterThanOrEqualTo(0);
                assertThat(opt.path("text").asText()).isNotBlank();
                assertThat(opt.has("isCorrect")).isFalse(); // null → stripped by @JsonInclude(NON_NULL)
            });
        }

        @Test
        @DisplayName("TC_PIF_002 — TRUE_FALSE: exactly 2 options ('Đúng'/'Sai'), isCorrect=null before submit")
        void tc_pif_002_trueFalse_format() throws Exception {
            MvcResult result = startSessionWith("TRUE_FALSE");
            var firstItem = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("data").path("items").get(0);

            assertThat(firstItem.path("itemType").asText()).isEqualTo("TRUE_FALSE");
            assertThat(firstItem.path("options").size()).isEqualTo(2);

            List<String> optionTexts = new ArrayList<>();
            firstItem.path("options").forEach(opt -> optionTexts.add(opt.path("text").asText()));
            assertThat(optionTexts).containsExactlyInAnyOrder("Đúng", "Sai");

            // isCorrect not exposed before submit
            firstItem.path("options").forEach(opt ->
                    assertThat(opt.has("isCorrect")).isFalse());
        }

        @Test
        @DisplayName("TC_PIF_003 — FILL_BLANK: has options (NOT empty), question not blank")
        void tc_pif_003_fillBlank_hasOptions() throws Exception {
            // FILL_BLANK uses same buildSelectionItem as MC — options must be present
            MvcResult result = startSessionWith("FILL_BLANK");
            var firstItem = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("items").get(0);

            assertThat(firstItem.path("itemType").asText()).isEqualTo("FILL_BLANK");
            assertThat(firstItem.path("question").asText()).isNotBlank();
            // options are present (comment in entity was WRONG — code uses buildSelectionItem which always builds options)
            assertThat(firstItem.path("options").size()).isGreaterThanOrEqualTo(2);
            firstItem.path("options").forEach(opt ->
                    assertThat(opt.has("isCorrect")).isFalse());
        }

        @Test
        @DisplayName("TC_PIF_004 — TRANSLATION: 4 options (NOT empty, same as MC), isCorrect=null before submit")
        void tc_pif_004_translation_hasOptions() throws Exception {
            // TRANSLATION uses buildSelectionItem → 4 options, NOT free-text
            // Entity comment "Rỗng ([]) với FILL_BLANK và TRANSLATION" is INCORRECT
            MvcResult result = startSessionWith("TRANSLATION");
            var firstItem = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("items").get(0);

            assertThat(firstItem.path("itemType").asText()).isEqualTo("TRANSLATION");
            assertThat(firstItem.path("options").size()).isEqualTo(4);
            firstItem.path("options").forEach(opt -> {
                assertThat(opt.path("id").asInt()).isGreaterThanOrEqualTo(0);
                assertThat(opt.path("text").asText()).isNotBlank();
                assertThat(opt.has("isCorrect")).isFalse();
            });
        }

        @Test
        @DisplayName("TC_PIF_005 — SENTENCE_ORDER: tokens as options, correctMapping=null, correctAnswer not exposed")
        void tc_pif_005_sentenceOrder_hasTokenOptions() throws Exception {
            MvcResult result = startSessionWith("SENTENCE_ORDER");
            var firstItem = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("items").get(0);

            assertThat(firstItem.path("itemType").asText()).isEqualTo("SENTENCE_ORDER");
            assertThat(firstItem.path("options").size()).isGreaterThanOrEqualTo(2);
            // correctMapping only present for MATCHING
            assertThat(firstItem.has("correctMapping")).isFalse();
            // correctAnswer must not be exposed in item response
            assertThat(firstItem.has("correctAnswer")).isFalse();
        }

        @Test
        @DisplayName("TC_PIF_006 — MATCHING: LEFT+RIGHT options with side field, correctMapping not null")
        void tc_pif_006_matching_hasSideAndCorrectMapping() throws Exception {
            MvcResult result = startSessionWith("MATCHING");
            var firstItem = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("items").get(0);

            assertThat(firstItem.path("itemType").asText()).isEqualTo("MATCHING");

            // Options must contain LEFT and RIGHT items
            List<String> sides = new ArrayList<>();
            firstItem.path("options").forEach(opt -> sides.add(opt.path("side").asText()));
            assertThat(sides).contains("LEFT", "RIGHT");

            // correctMapping must be present and non-empty (exposed upfront for client-side validation)
            assertThat(firstItem.has("correctMapping")).isTrue();
            assertThat(firstItem.path("correctMapping").size()).isGreaterThan(0);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Section 5 — UC 2.12.3: Submit Answer
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Section 5 — UC 2.12.3 Submit Answer")
    class SubmitAnswerTests {

        @Test
        @DisplayName("TC_PSA_001 — Submit correct option index → isCorrect=true, score > 0, correctAnswerText not blank (BR-265)")
        void tc_psa_001_correctAnswer_returnsSuccess() throws Exception {
            PracticeSession session = createSession(learner, 2);
            UUID itemId = practiceItemRepository.findBySessionIdOrderByItemIndex(session.getId()).get(0).getId();

            String body = objectMapper.writeValueAsString(
                    SubmitAnswerRequest.builder().userAnswer("0").build()); // "0" = correct index

            mockMvc.perform(post("/api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer",
                            session.getId(), itemId)
                            .header("Authorization", "Bearer " + learnerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.correct").value(true))
                    .andExpect(jsonPath("$.data.score").value(100))
                    .andExpect(jsonPath("$.data.correctAnswerText").isNotEmpty())
                    .andExpect(jsonPath("$.data.sessionCompleted").value(false)); // 1/2 done
        }

        @Test
        @DisplayName("TC_PSA_002 — Submit wrong option index → isCorrect=false, correctAnswer revealed, explanation not blank (BR-265)")
        void tc_psa_002_wrongAnswer_revealsFeedback() throws Exception {
            PracticeSession session = createSession(learner, 2);
            UUID itemId = practiceItemRepository.findBySessionIdOrderByItemIndex(session.getId()).get(0).getId();

            String body = objectMapper.writeValueAsString(
                    SubmitAnswerRequest.builder().userAnswer("1").build()); // "1" = wrong

            mockMvc.perform(post("/api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer",
                            session.getId(), itemId)
                            .header("Authorization", "Bearer " + learnerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.correct").value(false))
                    .andExpect(jsonPath("$.data.score").value(0))
                    .andExpect(jsonPath("$.data.correctAnswer").isNotEmpty()) // revealed after submit
                    .andExpect(jsonPath("$.data.explanation").isNotEmpty());
        }

        @Test
        @DisplayName("TC_PSA_003 — Submit last item → sessionCompleted=true, nextItem=null, DB status=COMPLETED")
        void tc_psa_003_lastAnswer_completesSession() throws Exception {
            PracticeSession session = createSession(learner, 1); // only 1 item
            UUID itemId = practiceItemRepository.findBySessionIdOrderByItemIndex(session.getId()).get(0).getId();

            String body = objectMapper.writeValueAsString(
                    SubmitAnswerRequest.builder().userAnswer("0").build());

            mockMvc.perform(post("/api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer",
                            session.getId(), itemId)
                            .header("Authorization", "Bearer " + learnerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sessionCompleted").value(true))
                    .andExpect(jsonPath("$.data.nextItem").doesNotExist());

            PracticeSession saved = practiceSessionRepository.findById(session.getId()).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(PracticeStatus.COMPLETED);
            assertThat(saved.getCompletedItems()).isEqualTo(1);
            assertThat(saved.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("TC_PSA_004 — Submit non-last item → sessionCompleted=false, nextItem present with required fields")
        void tc_psa_004_nonLastAnswer_returnsNextItem() throws Exception {
            PracticeSession session = createSession(learner, 2);
            UUID firstItemId = practiceItemRepository.findBySessionIdOrderByItemIndex(session.getId()).get(0).getId();

            String body = objectMapper.writeValueAsString(
                    SubmitAnswerRequest.builder().userAnswer("0").build());

            mockMvc.perform(post("/api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer",
                            session.getId(), firstItemId)
                            .header("Authorization", "Bearer " + learnerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sessionCompleted").value(false))
                    .andExpect(jsonPath("$.data.nextItem").exists())
                    .andExpect(jsonPath("$.data.nextItem.id").isNotEmpty())
                    .andExpect(jsonPath("$.data.nextItem.itemType").isNotEmpty());
        }

        @Test
        @DisplayName("TC_PSA_005 — sessionId belongs to otherLearner → 4xx SESSION_ACCESS_DENIED")
        void tc_psa_005_otherUserSession_returns4xx() throws Exception {
            PracticeSession session = createSession(otherLearner, 1);
            UUID itemId = practiceItemRepository.findBySessionIdOrderByItemIndex(session.getId()).get(0).getId();

            String body = objectMapper.writeValueAsString(
                    SubmitAnswerRequest.builder().userAnswer("0").build());

            mockMvc.perform(post("/api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer",
                            session.getId(), itemId)
                            .header("Authorization", "Bearer " + learnerToken) // learner trying otherLearner's session
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("TC_PSA_006 — userAnswer blank → 400 (@NotBlank)")
        void tc_psa_006_blankAnswer_returns400() throws Exception {
            PracticeSession session = createSession(learner, 1);
            UUID itemId = practiceItemRepository.findBySessionIdOrderByItemIndex(session.getId()).get(0).getId();

            String body = objectMapper.writeValueAsString(
                    SubmitAnswerRequest.builder().userAnswer("").build());

            mockMvc.perform(post("/api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer",
                            session.getId(), itemId)
                            .header("Authorization", "Bearer " + learnerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("TC_PSA_007 — userAnswer > 500 chars → 400 (@Size max=500)")
        void tc_psa_007_tooLongAnswer_returns400() throws Exception {
            PracticeSession session = createSession(learner, 1);
            UUID itemId = practiceItemRepository.findBySessionIdOrderByItemIndex(session.getId()).get(0).getId();

            String body = objectMapper.writeValueAsString(
                    SubmitAnswerRequest.builder().userAnswer("x".repeat(501)).build());

            mockMvc.perform(post("/api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer",
                            session.getId(), itemId)
                            .header("Authorization", "Bearer " + learnerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Section 6 — Session Summary (GET /sessions/{id}/summary)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Section 6 — Session Summary")
    class SessionSummaryTests {

        @Test
        @DisplayName("TC_PSU_001 — Completed session → summary with score, correctItems, totalItems")
        void tc_psu_001_completedSession_returnsSummary() throws Exception {
            // Complete a session: create 1-item session, submit the answer
            stubExerciseGenerator(1);
            String startBody = objectMapper.writeValueAsString(StartPracticeRequest.builder()
                    .lessonId(publishedLesson.getId())
                    .sessionType(PracticeSessionType.VOCAB_DRILL)
                    .preset(PracticePreset.QUICK)
                    .build());

            MvcResult startResult = mockMvc.perform(post("/api/v1/learner/practice/sessions")
                            .header("Authorization", "Bearer " + learnerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(startBody))
                    .andExpect(status().isCreated())
                    .andReturn();

            UUID sessionId = extractSessionId(startResult);
            UUID itemId = UUID.fromString(objectMapper.readTree(startResult.getResponse().getContentAsString())
                    .path("data").path("items").get(0).path("id").asText());

            // Get correct answer from database (not exposed in API response)
            PracticeItem item = practiceItemRepository.findById(itemId).orElseThrow();
            String correctAnswer = item.getCorrectAnswer();

            // Submit the correct answer
            String submitBody = objectMapper.writeValueAsString(
                    SubmitAnswerRequest.builder().userAnswer(correctAnswer).build());
            mockMvc.perform(post("/api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer",
                            sessionId, itemId)
                            .header("Authorization", "Bearer " + learnerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submitBody))
                    .andExpect(status().isOk());

            // Now fetch summary
            mockMvc.perform(get("/api/v1/learner/practice/sessions/{sessionId}/summary", sessionId)
                            .header("Authorization", "Bearer " + learnerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.sessionId").value(sessionId.toString()))
                    .andExpect(jsonPath("$.data.score").isNotEmpty())
                    .andExpect(jsonPath("$.data.totalItems").value(1))
                    .andExpect(jsonPath("$.data.correctItems").value(1));
        }

        @Test
        @DisplayName("TC_PSU_002 — Summary for session belonging to otherLearner → 4xx SESSION_ACCESS_DENIED")
        void tc_psu_002_otherUserSession_returns4xx() throws Exception {
            PracticeSession otherSession = createSession(otherLearner, 1);

            mockMvc.perform(get("/api/v1/learner/practice/sessions/{sessionId}/summary",
                            otherSession.getId())
                            .header("Authorization", "Bearer " + learnerToken))
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }
}
