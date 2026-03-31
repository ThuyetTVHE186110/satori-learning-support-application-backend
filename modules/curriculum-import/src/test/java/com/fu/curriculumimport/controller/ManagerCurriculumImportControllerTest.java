package com.fu.curriculumimport.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.response.ApiResponse;
import com.fu.common.response.PageResponse;
import com.fu.curriculumimport.dto.response.*;
import com.fu.curriculumimport.entity.CurriculumImport.ImportStatus;
import com.fu.curriculumimport.service.CurriculumImportService;
import com.fu.curriculumimport.service.CurriculumImportService.LessonSummaryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller slice tests for ManagerCurriculumImportController.
 *
 * Security auto-configuration is completely excluded so that no
 * authentication or CSRF is required. All requests reach the
 * controller directly, and the service layer is replaced by a mock.
 *
 * Note: @AuthenticationPrincipal(expression = "id") resolves to null
 * with no active security context — that is acceptable since the
 * service is always stubbed regardless of managerId value.
 */
@WebMvcTest(
        value = ManagerCurriculumImportController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc
@DisplayName("ManagerCurriculumImportController")
class ManagerCurriculumImportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CurriculumImportService importService;

    // ---------------------------------------------------------------------------
    // Shared test data
    // ---------------------------------------------------------------------------

    private static final String BASE_URL = "/api/v1/manager/curriculum-imports";
    private static final UUID IMPORT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COURSE_ID  = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private CurriculumImportResponse sampleImportResponse;
    private PageResponse<CurriculumImportSummaryResponse> sampleImportPage;
    private PageResponse<ImportChunkResponse> sampleChunkPage;
    private PageResponse<VocabularyResponse> sampleVocabPage;
    private PageResponse<GrammarPointResponse> sampleGrammarPage;

    @BeforeEach
    void setUp() {
        sampleImportResponse = CurriculumImportResponse.builder()
                .id(IMPORT_ID)
                .originalFilename("minna_no_nihongo.pdf")
                .status(ImportStatus.QUEUED)
                .progressPercent(BigDecimal.ZERO)
                .chunksCreated(0)
                .build();

        sampleImportPage = PageResponse.<CurriculumImportSummaryResponse>builder()
                .content(List.of(new CurriculumImportSummaryResponse()))
                .pageNumber(0)
                .pageSize(10)
                .totalElements(1)
                .totalPages(1)
                .first(true)
                .last(true)
                .build();

        sampleChunkPage = PageResponse.<ImportChunkResponse>builder()
                .content(List.of(new ImportChunkResponse()))
                .pageNumber(0)
                .pageSize(20)
                .totalElements(1)
                .totalPages(1)
                .first(true)
                .last(true)
                .build();

        sampleVocabPage = PageResponse.<VocabularyResponse>builder()
                .content(List.of(new VocabularyResponse()))
                .pageNumber(0)
                .pageSize(20)
                .totalElements(1)
                .totalPages(1)
                .first(true)
                .last(true)
                .build();

        sampleGrammarPage = PageResponse.<GrammarPointResponse>builder()
                .content(List.of(new GrammarPointResponse()))
                .pageNumber(0)
                .pageSize(20)
                .totalElements(1)
                .totalPages(1)
                .first(true)
                .last(true)
                .build();
    }

    // ===========================================================================
    // POST / — uploadAndImport
    // ===========================================================================

    @Nested
    @DisplayName("POST / — uploadAndImport")
    class UploadAndImportTests {

        @Test
        @DisplayName("Valid multipart PDF → 202 ACCEPTED, success = true")
        void uploadAndImport_validPdfFile_returns202() throws Exception {
            // Arrange
            MockMultipartFile pdfFile = new MockMultipartFile(
                    "file",
                    "lesson_n5.pdf",
                    MediaType.APPLICATION_PDF_VALUE,
                    "PDF binary content".getBytes()
            );

            given(importService.initiateImport(any(), isNull(), isNull()))
                    .willReturn(sampleImportResponse);

            // Act
            ResultActions result = mockMvc.perform(
                    MockMvcRequestBuilders.multipart(BASE_URL)
                            .file(pdfFile)
            );

            // Assert
            result.andDo(print())
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(IMPORT_ID.toString()))
                    .andExpect(jsonPath("$.data.originalFilename").value("minna_no_nihongo.pdf"));
        }

        @Test
        @DisplayName("Valid multipart PDF with courseId → 202 ACCEPTED, success = true")
        void uploadAndImport_withCourseId_returns202() throws Exception {
            // Arrange
            MockMultipartFile pdfFile = new MockMultipartFile(
                    "file",
                    "lesson_n4.pdf",
                    MediaType.APPLICATION_PDF_VALUE,
                    "PDF binary content".getBytes()
            );

            given(importService.initiateImport(any(), eq(COURSE_ID), isNull()))
                    .willReturn(sampleImportResponse);

            // Act
            ResultActions result = mockMvc.perform(
                    MockMvcRequestBuilders.multipart(BASE_URL)
                            .file(pdfFile)
                            .param("courseId", COURSE_ID.toString())
            );

            // Assert
            result.andDo(print())
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    // ===========================================================================
    // GET /{importId} — getImportStatus
    // ===========================================================================

    @Nested
    @DisplayName("GET /{importId} — getImportStatus")
    class GetImportStatusTests {

        @Test
        @DisplayName("Valid importId → 200 OK, success = true")
        void getImportStatus_validId_returns200() throws Exception {
            // Arrange
            given(importService.getImportById(IMPORT_ID)).willReturn(sampleImportResponse);

            // Act
            ResultActions result = mockMvc.perform(
                    get(BASE_URL + "/{importId}", IMPORT_ID)
            );

            // Assert
            result.andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(IMPORT_ID.toString()))
                    .andExpect(jsonPath("$.data.status").value("QUEUED"));
        }
    }

    // ===========================================================================
    // GET / — listImports
    // ===========================================================================

    @Nested
    @DisplayName("GET / — listImports")
    class ListImportsTests {

        @Test
        @DisplayName("No filters → 200 OK, success = true, paginated results")
        void listImports_noFilters_returns200() throws Exception {
            // Arrange
            given(importService.listImports(isNull(), isNull(), eq(0), eq(10)))
                    .willReturn(sampleImportPage);

            // Act
            ResultActions result = mockMvc.perform(get(BASE_URL));

            // Assert
            result.andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }

        @Test
        @DisplayName("With status filter → 200 OK, success = true")
        void listImports_withStatusFilter_returns200() throws Exception {
            // Arrange
            given(importService.listImports(isNull(), eq(ImportStatus.COMPLETED), eq(0), eq(10)))
                    .willReturn(sampleImportPage);

            // Act
            ResultActions result = mockMvc.perform(get(BASE_URL)
                    .param("status", "COMPLETED")
            );

            // Assert
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    // ===========================================================================
    // GET /{importId}/chunks — getImportChunks
    // ===========================================================================

    @Nested
    @DisplayName("GET /{importId}/chunks — getImportChunks")
    class GetImportChunksTests {

        @Test
        @DisplayName("Valid importId → 200 OK, success = true, paginated chunk list")
        void getImportChunks_validId_returns200() throws Exception {
            // Arrange
            given(importService.getImportChunks(IMPORT_ID, 0, 20)).willReturn(sampleChunkPage);

            // Act
            ResultActions result = mockMvc.perform(
                    get(BASE_URL + "/{importId}/chunks", IMPORT_ID)
            );

            // Assert
            result.andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }
    }

    // ===========================================================================
    // POST /{importId}/retry — retryImport
    // ===========================================================================

    @Nested
    @DisplayName("POST /{importId}/retry — retryImport")
    class RetryImportTests {

        @Test
        @DisplayName("Valid importId → 202 ACCEPTED, success = true")
        void retryImport_validId_returns202() throws Exception {
            // Arrange
            CurriculumImportResponse queued = CurriculumImportResponse.builder()
                    .id(IMPORT_ID)
                    .status(ImportStatus.QUEUED)
                    .build();
            given(importService.retryImport(IMPORT_ID)).willReturn(queued);

            // Act
            ResultActions result = mockMvc.perform(
                    post(BASE_URL + "/{importId}/retry", IMPORT_ID)
            );

            // Assert
            result.andDo(print())
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("QUEUED"));
        }
    }

    // ===========================================================================
    // POST /{importId}/cancel — cancelImport
    // ===========================================================================

    @Nested
    @DisplayName("POST /{importId}/cancel — cancelImport")
    class CancelImportTests {

        @Test
        @DisplayName("Valid importId → 200 OK, success = true")
        void cancelImport_validId_returns200() throws Exception {
            // Arrange
            CurriculumImportResponse cancelled = CurriculumImportResponse.builder()
                    .id(IMPORT_ID)
                    .status(ImportStatus.CANCELLED)
                    .build();
            given(importService.cancelImport(IMPORT_ID)).willReturn(cancelled);

            // Act
            ResultActions result = mockMvc.perform(
                    post(BASE_URL + "/{importId}/cancel", IMPORT_ID)
            );

            // Assert
            result.andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        }
    }

    // ===========================================================================
    // PATCH /{importId}/link-course — linkImportToCourse
    // ===========================================================================

    @Nested
    @DisplayName("PATCH /{importId}/link-course — linkImportToCourse")
    class LinkImportToCourseTests {

        @Test
        @DisplayName("Valid importId and courseId → 200 OK, success = true")
        void linkImportToCourse_validIds_returns200() throws Exception {
            // Arrange
            CurriculumImportResponse linked = CurriculumImportResponse.builder()
                    .id(IMPORT_ID)
                    .courseId(COURSE_ID)
                    .status(ImportStatus.COMPLETED)
                    .build();
            given(importService.linkToCourse(IMPORT_ID, COURSE_ID, null))
                    .willReturn(linked);

            // Act
            ResultActions result = mockMvc.perform(
                    patch(BASE_URL + "/{importId}/link-course", IMPORT_ID)
                            .param("courseId", COURSE_ID.toString())
            );

            // Assert
            result.andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.courseId").value(COURSE_ID.toString()));
        }
    }

    // ===========================================================================
    // GET /{importId}/lessons — getImportLessons
    // ===========================================================================

    @Nested
    @DisplayName("GET /{importId}/lessons — getImportLessons")
    class GetImportLessonsTests {

        @Test
        @DisplayName("Valid importId → 200 OK, success = true, lesson list")
        void getImportLessons_validId_returns200() throws Exception {
            // Arrange
            LessonSummaryDto lesson = new LessonSummaryDto(
                    UUID.randomUUID(),
                    "Lesson 1: Basic Greetings",
                    "第一課：挨拶",
                    1, 1, 10,
                    "PUBLISHED"
            );
            given(importService.getImportLessons(IMPORT_ID)).willReturn(List.of(lesson));

            // Act
            ResultActions result = mockMvc.perform(
                    get(BASE_URL + "/{importId}/lessons", IMPORT_ID)
            );

            // Assert
            result.andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].title").value("Lesson 1: Basic Greetings"));
        }
    }

    // ===========================================================================
    // GET /{importId}/vocabulary — getImportVocabulary
    // ===========================================================================

    @Nested
    @DisplayName("GET /{importId}/vocabulary — getImportVocabulary")
    class GetImportVocabularyTests {

        @Test
        @DisplayName("Valid importId → 200 OK, success = true, paginated vocabulary")
        void getImportVocabulary_validId_returns200() throws Exception {
            // Arrange
            given(importService.getImportVocabulary(IMPORT_ID, 0, 20)).willReturn(sampleVocabPage);

            // Act
            ResultActions result = mockMvc.perform(
                    get(BASE_URL + "/{importId}/vocabulary", IMPORT_ID)
            );

            // Assert
            result.andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }
    }

    // ===========================================================================
    // GET /{importId}/grammar-points — getImportGrammarPoints
    // ===========================================================================

    @Nested
    @DisplayName("GET /{importId}/grammar-points — getImportGrammarPoints")
    class GetImportGrammarPointsTests {

        @Test
        @DisplayName("Valid importId → 200 OK, success = true, paginated grammar points")
        void getImportGrammarPoints_validId_returns200() throws Exception {
            // Arrange
            given(importService.getImportGrammarPoints(IMPORT_ID, 0, 20)).willReturn(sampleGrammarPage);

            // Act
            ResultActions result = mockMvc.perform(
                    get(BASE_URL + "/{importId}/grammar-points", IMPORT_ID)
            );

            // Assert
            result.andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }
    }

    // ===========================================================================
    // GET /statistics — getStatistics
    // ===========================================================================

    @Nested
    @DisplayName("GET /statistics — getStatistics")
    class GetStatisticsTests {

        @Test
        @DisplayName("GET /statistics → 200 OK, success = true")
        void getStatistics_returns200() throws Exception {
            // Arrange
            ImportStatisticsResponse stats = ImportStatisticsResponse.builder()
                    .totalImports(42)
                    .completedImports(35)
                    .failedImports(3)
                    .totalLessonsCreated(200)
                    .totalVocabularyCreated(1500)
                    .totalGrammarPointsCreated(300)
                    .build();
            given(importService.getStatistics()).willReturn(stats);

            // Act
            ResultActions result = mockMvc.perform(get(BASE_URL + "/statistics"));

            // Assert
            result.andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.totalImports").value(42))
                    .andExpect(jsonPath("$.data.completedImports").value(35));
        }
    }

    // ===========================================================================
    // Minimal @SpringBootApplication for @WebMvcTest discovery in multi-module project
    // ===========================================================================

    /**
     * Stub Spring Boot configuration so the @WebMvcTest slice can bootstrap.
     * In a multi-module project the real @SpringBootApplication lives in the
     * 'start' module which is not on the test classpath.  This nested class gives
     * the test bootstrapper a @SpringBootConfiguration to locate.
     * Security exclusions are handled by the @WebMvcTest annotation above.
     */
    @SpringBootApplication(scanBasePackages = "com.fu.curriculumimport.controller")
    static class TestApplication {}
}
