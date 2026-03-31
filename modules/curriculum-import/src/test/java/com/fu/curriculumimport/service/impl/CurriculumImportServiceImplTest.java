package com.fu.curriculumimport.service.impl;

import com.fu.common.response.PageResponse;
import com.fu.course.entity.Course;
import com.fu.course.entity.GrammarPoint;
import com.fu.course.entity.Lesson;
import com.fu.course.entity.Vocabulary;
import com.fu.course.exception.CourseNotFoundException;
import com.fu.course.repository.CourseRepository;
import com.fu.course.repository.GrammarPointRepository;
import com.fu.course.repository.LessonRepository;
import com.fu.course.repository.VocabularyRepository;
import com.fu.curriculumimport.dto.response.*;
import com.fu.curriculumimport.entity.CurriculumImport;
import com.fu.curriculumimport.entity.CurriculumImport.ImportStatus;
import com.fu.curriculumimport.entity.ImportChunk;
import com.fu.curriculumimport.exception.CurriculumImportNotFoundException;
import com.fu.curriculumimport.exception.ImportStateException;
import com.fu.curriculumimport.exception.InvalidImportFileException;
import com.fu.curriculumimport.mapper.CurriculumImportMapper;
import com.fu.curriculumimport.repository.CurriculumImportRepository;
import com.fu.curriculumimport.repository.ImportChunkRepository;
import com.fu.curriculumimport.service.CurriculumImportService.LessonSummaryDto;
import com.fu.curriculumimport.service.orchestrator.ImportOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * Unit tests for CurriculumImportServiceImpl.
 *
 * Coverage:
 * - initiateImport: happy path, empty file, wrong MIME, oversized, course not found, null courseId
 * - getImportById: found, not found
 * - listImports: all 4 filter combinations (courseId+status, courseId only, status only, all)
 * - getImportChunks: import not found, empty chunks, with chunks
 * - retryImport: FAILED+retryable, FAILED+maxRetriesReached, non-FAILED status, not found
 * - cancelImport: COMPLETED guard, CANCELLED guard, active import, not found
 * - getImportLessons: not found, no lessons, with lessons
 * - getImportVocabulary: with courseId, without courseId, not found
 * - getImportGrammarPoints: with courseId, without courseId, not found
 * - getStatistics: normal flow, null cost
 * - linkToCourse: happy path, import not found, course not found
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CurriculumImportServiceImpl")
class CurriculumImportServiceImplTest {

    @Mock private CurriculumImportRepository importRepository;
    @Mock private ImportChunkRepository chunkRepository;
    @Mock private CourseRepository courseRepository;
    @Mock private LessonRepository lessonRepository;
    @Mock private VocabularyRepository vocabularyRepository;
    @Mock private GrammarPointRepository grammarPointRepository;
    @Mock private CurriculumImportMapper importMapper;
    @Mock private ImportOrchestrator orchestrator;

    @InjectMocks
    private CurriculumImportServiceImpl service;

    // Common test fixtures
    private static final UUID MANAGER_ID = UUID.randomUUID();
    private static final UUID IMPORT_ID  = UUID.randomUUID();
    private static final UUID COURSE_ID  = UUID.randomUUID();

    private CurriculumImport makeImport(ImportStatus status) {
        return CurriculumImport.builder()
                .status(status)
                .retryCount(0)
                .maxRetries(3)
                .courseId(COURSE_ID)
                .createdBy(MANAGER_ID)
                .originalFilename("test.pdf")
                .fileUrl("pending://upload")
                .build();
    }

    private CurriculumImportResponse stubResponse(CurriculumImport entity) {
        CurriculumImportResponse resp = new CurriculumImportResponse();
        resp.setId(IMPORT_ID);
        return resp;
    }

    // =========================================================================
    // initiateImport
    // =========================================================================

    @Nested
    @DisplayName("initiateImport")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class InitiateImportTests {

        @Mock private MultipartFile validPdf;

        @BeforeEach
        void setupValidFile() {
            given(validPdf.isEmpty()).willReturn(false);
            given(validPdf.getContentType()).willReturn("application/pdf");
            given(validPdf.getSize()).willReturn(1024L);
            given(validPdf.getOriginalFilename()).willReturn("lesson.pdf");
        }

        @Test
        @DisplayName("Happy path — valid PDF with courseId creates import and triggers processing")
        void initiateImport_validPdfWithCourse_createsAndTriggers() {
            // Arrange
            Course course = new Course();
            course.setName("Korean N5");
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));

            CurriculumImport savedEntity = makeImport(ImportStatus.QUEUED);
            given(importRepository.save(any(CurriculumImport.class))).willReturn(savedEntity);

            CurriculumImportResponse expectedResponse = new CurriculumImportResponse();
            expectedResponse.setId(IMPORT_ID);
            given(importMapper.toResponse(savedEntity)).willReturn(expectedResponse);

            // Act
            CurriculumImportResponse result = service.initiateImport(validPdf, COURSE_ID, MANAGER_ID);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getCourseName()).isEqualTo("Korean N5");

            ArgumentCaptor<CurriculumImport> captor = ArgumentCaptor.forClass(CurriculumImport.class);
            then(importRepository).should().save(captor.capture());
            CurriculumImport captured = captor.getValue();
            assertThat(captured.getStatus()).isEqualTo(ImportStatus.QUEUED);
            assertThat(captured.getCourseId()).isEqualTo(COURSE_ID);
            assertThat(captured.getCreatedBy()).isEqualTo(MANAGER_ID);

            then(orchestrator).should().processImportAsync(any(), eq(validPdf));
        }

        @Test
        @DisplayName("Happy path — valid PDF without courseId creates import with null course")
        void initiateImport_validPdfWithoutCourse_createsImportNoCourse() {
            // Arrange
            CurriculumImport savedEntity = makeImport(ImportStatus.QUEUED);
            savedEntity.setCourseId(null);
            given(importRepository.save(any(CurriculumImport.class))).willReturn(savedEntity);

            CurriculumImportResponse expectedResponse = new CurriculumImportResponse();
            given(importMapper.toResponse(savedEntity)).willReturn(expectedResponse);

            // Act
            CurriculumImportResponse result = service.initiateImport(validPdf, null, MANAGER_ID);

            // Assert
            assertThat(result.getCourseName()).isNull();
            then(courseRepository).should(never()).findById(any());
            then(orchestrator).should().processImportAsync(any(), eq(validPdf));
        }

        @Test
        @DisplayName("Exception — null/empty file throws InvalidImportFileException")
        void initiateImport_emptyFile_throwsInvalidImportFileException() {
            MultipartFile emptyFile = mock(MultipartFile.class);
            given(emptyFile.isEmpty()).willReturn(true);

            assertThatThrownBy(() -> service.initiateImport(emptyFile, null, MANAGER_ID))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasMessageContaining("required");

            then(importRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("Exception — non-PDF file throws InvalidImportFileException")
        void initiateImport_wrongMimeType_throwsInvalidImportFileException() {
            MultipartFile wordFile = mock(MultipartFile.class);
            given(wordFile.isEmpty()).willReturn(false);
            given(wordFile.getContentType()).willReturn("application/msword");

            assertThatThrownBy(() -> service.initiateImport(wordFile, null, MANAGER_ID))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasMessageContaining("PDF");
        }

        @Test
        @DisplayName("Exception — null content-type throws InvalidImportFileException")
        void initiateImport_nullContentType_throwsInvalidImportFileException() {
            MultipartFile unknownFile = mock(MultipartFile.class);
            given(unknownFile.isEmpty()).willReturn(false);
            given(unknownFile.getContentType()).willReturn(null);

            assertThatThrownBy(() -> service.initiateImport(unknownFile, null, MANAGER_ID))
                    .isInstanceOf(InvalidImportFileException.class);
        }

        @Test
        @DisplayName("Exception — file exceeds 100 MB throws InvalidImportFileException")
        void initiateImport_oversizedFile_throwsInvalidImportFileException() {
            MultipartFile bigFile = mock(MultipartFile.class);
            long oversizeBytes = 101L * 1024 * 1024;
            given(bigFile.isEmpty()).willReturn(false);
            given(bigFile.getContentType()).willReturn("application/pdf");
            given(bigFile.getSize()).willReturn(oversizeBytes);

            assertThatThrownBy(() -> service.initiateImport(bigFile, null, MANAGER_ID))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasMessageContaining("100");
        }

        @Test
        @DisplayName("Edge case — exactly 100 MB file is accepted")
        void initiateImport_exactly100MbFile_accepted() {
            long exactBytes = 100L * 1024 * 1024;
            given(validPdf.getSize()).willReturn(exactBytes);

            CurriculumImport savedEntity = makeImport(ImportStatus.QUEUED);
            given(importRepository.save(any())).willReturn(savedEntity);
            given(importMapper.toResponse(savedEntity)).willReturn(new CurriculumImportResponse());
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(new Course()));

            assertThatNoException().isThrownBy(() -> service.initiateImport(validPdf, COURSE_ID, MANAGER_ID));
        }

        @Test
        @DisplayName("Exception — course not found throws CourseNotFoundException")
        void initiateImport_courseNotFound_throwsCourseNotFoundException() {
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.initiateImport(validPdf, COURSE_ID, MANAGER_ID))
                    .isInstanceOf(CourseNotFoundException.class);

            then(importRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("Happy path — PPTX MIME type is accepted and triggers processing")
        void initiateImport_validPptxMimeType_accepted() {
            MultipartFile pptxFile = mock(MultipartFile.class);
            given(pptxFile.isEmpty()).willReturn(false);
            given(pptxFile.getContentType()).willReturn(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation");
            given(pptxFile.getSize()).willReturn(1024L);
            given(pptxFile.getOriginalFilename()).willReturn("slides.pptx");

            CurriculumImport savedEntity = makeImport(ImportStatus.QUEUED);
            given(importRepository.save(any(CurriculumImport.class))).willReturn(savedEntity);
            given(importMapper.toResponse(savedEntity)).willReturn(new CurriculumImportResponse());

            assertThatNoException().isThrownBy(
                    () -> service.initiateImport(pptxFile, null, MANAGER_ID));

            then(orchestrator).should().processImportAsync(any(), eq(pptxFile));
        }

        @Test
        @DisplayName("Happy path — .pptx filename extension accepted even with generic MIME")
        void initiateImport_pptxExtension_acceptedWithGenericMime() {
            MultipartFile pptxFile = mock(MultipartFile.class);
            given(pptxFile.isEmpty()).willReturn(false);
            given(pptxFile.getContentType()).willReturn("application/octet-stream");
            given(pptxFile.getSize()).willReturn(2048L);
            given(pptxFile.getOriginalFilename()).willReturn("curriculum.pptx");

            CurriculumImport savedEntity = makeImport(ImportStatus.QUEUED);
            given(importRepository.save(any(CurriculumImport.class))).willReturn(savedEntity);
            given(importMapper.toResponse(savedEntity)).willReturn(new CurriculumImportResponse());

            assertThatNoException().isThrownBy(
                    () -> service.initiateImport(pptxFile, null, MANAGER_ID));
        }

        @Test
        @DisplayName("Exception — .docx file (wrong extension, wrong MIME) throws InvalidImportFileException")
        void initiateImport_docxFile_throwsInvalidImportFileException() {
            MultipartFile docxFile = mock(MultipartFile.class);
            given(docxFile.isEmpty()).willReturn(false);
            given(docxFile.getContentType()).willReturn("application/msword");
            given(docxFile.getSize()).willReturn(1024L);
            given(docxFile.getOriginalFilename()).willReturn("document.docx");

            assertThatThrownBy(() -> service.initiateImport(docxFile, null, MANAGER_ID))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasMessageContaining("PDF and PPTX");
        }
    }

    // =========================================================================
    // getImportById
    // =========================================================================

    @Nested
    @DisplayName("getImportById")
    class GetImportByIdTests {

        @Test
        @DisplayName("Happy path — import found returns response with course name")
        void getImportById_found_returnsEnrichedResponse() {
            CurriculumImport entity = makeImport(ImportStatus.COMPLETED);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            CurriculumImportResponse resp = new CurriculumImportResponse();
            resp.setId(IMPORT_ID);
            given(importMapper.toResponse(entity)).willReturn(resp);

            Course course = new Course();
            course.setName("N4 Course");
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));

            CurriculumImportResponse result = service.getImportById(IMPORT_ID);

            assertThat(result.getId()).isEqualTo(IMPORT_ID);
            assertThat(result.getCourseName()).isEqualTo("N4 Course");
        }

        @Test
        @DisplayName("Happy path — import with no courseId returns response without course name")
        void getImportById_noCourseId_responseNoCourse() {
            CurriculumImport entity = makeImport(ImportStatus.COMPLETED);
            entity.setCourseId(null);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));
            given(importMapper.toResponse(entity)).willReturn(new CurriculumImportResponse());

            CurriculumImportResponse result = service.getImportById(IMPORT_ID);

            assertThat(result.getCourseName()).isNull();
            then(courseRepository).should(never()).findById(any());
        }

        @Test
        @DisplayName("Exception — import not found throws CurriculumImportNotFoundException")
        void getImportById_notFound_throwsNotFoundException() {
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getImportById(IMPORT_ID))
                    .isInstanceOf(CurriculumImportNotFoundException.class);
        }
    }

    // =========================================================================
    // listImports
    // =========================================================================

    @Nested
    @DisplayName("listImports")
    class ListImportsTests {

        private CurriculumImport entity;
        private CurriculumImportSummaryResponse summaryResp;

        @BeforeEach
        void setup() {
            entity = makeImport(ImportStatus.COMPLETED);
            summaryResp = new CurriculumImportSummaryResponse();
            summaryResp.setId(IMPORT_ID);
        }

        @Test
        @DisplayName("Both courseId and status filter applied")
        void listImports_bothFilters_usesCombinedQuery() {
            Page<CurriculumImport> page = new PageImpl<>(List.of(entity));
            given(importRepository.findByCourseIdAndStatusOrderByCreatedAtDesc(
                    eq(COURSE_ID), eq(ImportStatus.COMPLETED), any(Pageable.class)))
                    .willReturn(page);
            given(importMapper.toSummaryResponse(entity)).willReturn(summaryResp);
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(new Course()));

            PageResponse<CurriculumImportSummaryResponse> result =
                    service.listImports(COURSE_ID, ImportStatus.COMPLETED, 0, 10);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getId()).isEqualTo(IMPORT_ID);
        }

        @Test
        @DisplayName("CourseId only filter applied")
        void listImports_courseIdOnly_usesCourseQuery() {
            Page<CurriculumImport> page = new PageImpl<>(List.of(entity));
            given(importRepository.findByCourseIdOrderByCreatedAtDesc(
                    eq(COURSE_ID), any(Pageable.class))).willReturn(page);
            given(importMapper.toSummaryResponse(entity)).willReturn(summaryResp);
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(new Course()));

            PageResponse<CurriculumImportSummaryResponse> result =
                    service.listImports(COURSE_ID, null, 0, 10);

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("Status only filter applied")
        void listImports_statusOnly_usesStatusQuery() {
            Page<CurriculumImport> page = new PageImpl<>(List.of(entity));
            given(importRepository.findByStatusOrderByCreatedAtDesc(
                    eq(ImportStatus.FAILED), any(Pageable.class))).willReturn(page);
            given(importMapper.toSummaryResponse(entity)).willReturn(summaryResp);
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(new Course()));

            PageResponse<CurriculumImportSummaryResponse> result =
                    service.listImports(null, ImportStatus.FAILED, 0, 10);

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("No filter — queries all")
        void listImports_noFilters_usesAllQuery() {
            Page<CurriculumImport> page = new PageImpl<>(List.of(entity));
            given(importRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class))).willReturn(page);
            given(importMapper.toSummaryResponse(entity)).willReturn(summaryResp);
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(new Course()));

            PageResponse<CurriculumImportSummaryResponse> result =
                    service.listImports(null, null, 0, 10);

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("Size capped to 100 per page")
        void listImports_sizeExceeds100_cappedAt100() {
            Page<CurriculumImport> page = new PageImpl<>(List.of());
            given(importRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class))).willReturn(page);

            service.listImports(null, null, 0, 999);

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            then(importRepository).should().findAllByOrderByCreatedAtDesc(pageableCaptor.capture());
            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
        }
    }

    // =========================================================================
    // getImportChunks
    // =========================================================================

    @Nested
    @DisplayName("getImportChunks")
    class GetImportChunksTests {

        @Test
        @DisplayName("Exception — import not found throws CurriculumImportNotFoundException")
        void getImportChunks_importNotFound_throws() {
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getImportChunks(IMPORT_ID, 0, 10))
                    .isInstanceOf(CurriculumImportNotFoundException.class);
        }

        @Test
        @DisplayName("Happy path — returns empty page when no chunks exist")
        void getImportChunks_noChunks_returnsEmptyPage() {
            given(importRepository.findById(IMPORT_ID))
                    .willReturn(Optional.of(makeImport(ImportStatus.COMPLETED)));
            Page<ImportChunk> emptyPage = new PageImpl<>(List.of());
            given(chunkRepository.findByCurriculumImportIdOrderByChunkIndexAsc(
                    eq(IMPORT_ID), any(Pageable.class))).willReturn(emptyPage);

            PageResponse<ImportChunkResponse> result = service.getImportChunks(IMPORT_ID, 0, 10);

            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
        }

        @Test
        @DisplayName("Happy path — returns mapped chunks")
        void getImportChunks_withChunks_returnsMappedResults() {
            given(importRepository.findById(IMPORT_ID))
                    .willReturn(Optional.of(makeImport(ImportStatus.COMPLETED)));

            ImportChunk chunk = new ImportChunk();
            chunk.setId(UUID.randomUUID());
            chunk.setChunkIndex(0);

            ImportChunkResponse chunkResp = new ImportChunkResponse();
            chunkResp.setId(chunk.getId());

            Page<ImportChunk> page = new PageImpl<>(List.of(chunk));
            given(chunkRepository.findByCurriculumImportIdOrderByChunkIndexAsc(
                    eq(IMPORT_ID), any(Pageable.class))).willReturn(page);
            given(importMapper.toChunkResponse(chunk)).willReturn(chunkResp);

            PageResponse<ImportChunkResponse> result = service.getImportChunks(IMPORT_ID, 0, 10);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getId()).isEqualTo(chunk.getId());
        }
    }

    // =========================================================================
    // retryImport
    // =========================================================================

    @Nested
    @DisplayName("retryImport")
    class RetryImportTests {

        @Test
        @DisplayName("Happy path — FAILED import below max retries resets and queues")
        void retryImport_failedRetryable_resetsAndQueues() {
            CurriculumImport entity = makeImport(ImportStatus.FAILED);
            entity.setRetryCount(1);
            entity.setMaxRetries(3);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));
            given(importRepository.save(any())).willReturn(entity);
            given(importMapper.toResponse(entity)).willReturn(new CurriculumImportResponse());
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(new Course()));

            CurriculumImportResponse result = service.retryImport(IMPORT_ID);

            assertThat(result).isNotNull();
            assertThat(entity.getStatus()).isEqualTo(ImportStatus.QUEUED);
            assertThat(entity.getRetryCount()).isEqualTo(2); // incremented by resetForRetry
            assertThat(entity.getErrorMessage()).isNull();
            then(importRepository).should().save(entity);
        }

        @Test
        @DisplayName("Exception — FAILED import at max retries throws ImportStateException")
        void retryImport_failedMaxRetries_throwsImportStateException() {
            CurriculumImport entity = makeImport(ImportStatus.FAILED);
            entity.setRetryCount(3);
            entity.setMaxRetries(3);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.retryImport(IMPORT_ID))
                    .isInstanceOf(ImportStateException.class)
                    .hasMessageContaining("retry");

            then(importRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("Exception — non-FAILED import throws ImportStateException")
        void retryImport_notFailed_throwsImportStateException() {
            CurriculumImport entity = makeImport(ImportStatus.COMPLETED);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.retryImport(IMPORT_ID))
                    .isInstanceOf(ImportStateException.class);
        }

        @Test
        @DisplayName("Exception — import not found throws CurriculumImportNotFoundException")
        void retryImport_notFound_throws() {
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.retryImport(IMPORT_ID))
                    .isInstanceOf(CurriculumImportNotFoundException.class);
        }

        @Test
        @DisplayName("Alternative — QUEUED status also cannot be retried")
        void retryImport_queuedStatus_throwsImportStateException() {
            CurriculumImport entity = makeImport(ImportStatus.QUEUED);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.retryImport(IMPORT_ID))
                    .isInstanceOf(ImportStateException.class);
        }

        @Test
        @DisplayName("Alternative — CANCELLED status cannot be retried")
        void retryImport_cancelledStatus_throwsImportStateException() {
            CurriculumImport entity = makeImport(ImportStatus.CANCELLED);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.retryImport(IMPORT_ID))
                    .isInstanceOf(ImportStateException.class);
        }
    }

    // =========================================================================
    // cancelImport
    // =========================================================================

    @Nested
    @DisplayName("cancelImport")
    class CancelImportTests {

        @Test
        @DisplayName("Happy path — active import is cancelled")
        void cancelImport_activeImport_marksCancelled() {
            CurriculumImport entity = makeImport(ImportStatus.AI_PROCESSING);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));
            given(importRepository.save(any())).willReturn(entity);
            given(importMapper.toResponse(entity)).willReturn(new CurriculumImportResponse());
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(new Course()));

            service.cancelImport(IMPORT_ID);

            assertThat(entity.getStatus()).isEqualTo(ImportStatus.CANCELLED);
            then(orchestrator).should().requestCancel(IMPORT_ID);
            then(importRepository).should().save(entity);
        }

        @Test
        @DisplayName("Happy path — QUEUED import can also be cancelled")
        void cancelImport_queuedImport_marksCancelled() {
            CurriculumImport entity = makeImport(ImportStatus.QUEUED);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));
            given(importRepository.save(any())).willReturn(entity);
            given(importMapper.toResponse(entity)).willReturn(new CurriculumImportResponse());
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(new Course()));

            service.cancelImport(IMPORT_ID);

            assertThat(entity.getStatus()).isEqualTo(ImportStatus.CANCELLED);
        }

        @Test
        @DisplayName("Exception — COMPLETED import cannot be cancelled")
        void cancelImport_completed_throwsImportStateException() {
            CurriculumImport entity = makeImport(ImportStatus.COMPLETED);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.cancelImport(IMPORT_ID))
                    .isInstanceOf(ImportStateException.class)
                    .hasMessageContaining("completed");

            then(orchestrator).should(never()).requestCancel(any());
        }

        @Test
        @DisplayName("Exception — already CANCELLED import throws ImportStateException")
        void cancelImport_alreadyCancelled_throwsImportStateException() {
            CurriculumImport entity = makeImport(ImportStatus.CANCELLED);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.cancelImport(IMPORT_ID))
                    .isInstanceOf(ImportStateException.class)
                    .hasMessageContaining("cancelled");
        }

        @Test
        @DisplayName("Exception — import not found throws CurriculumImportNotFoundException")
        void cancelImport_notFound_throws() {
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancelImport(IMPORT_ID))
                    .isInstanceOf(CurriculumImportNotFoundException.class);
        }
    }

    // =========================================================================
    // getImportLessons
    // =========================================================================

    @Nested
    @DisplayName("getImportLessons")
    class GetImportLessonsTests {

        @Test
        @DisplayName("Exception — import not found throws CurriculumImportNotFoundException")
        void getImportLessons_importNotFound_throws() {
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getImportLessons(IMPORT_ID))
                    .isInstanceOf(CurriculumImportNotFoundException.class);
        }

        @Test
        @DisplayName("Happy path — no lessons returns empty list")
        void getImportLessons_noLessons_returnsEmptyList() {
            given(importRepository.findById(IMPORT_ID))
                    .willReturn(Optional.of(makeImport(ImportStatus.COMPLETED)));
            given(lessonRepository.findByImportIdAndNotDeleted(IMPORT_ID)).willReturn(List.of());

            List<LessonSummaryDto> result = service.getImportLessons(IMPORT_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Happy path — lessons are mapped to LessonSummaryDto")
        void getImportLessons_withLessons_returnsMappedList() {
            given(importRepository.findById(IMPORT_ID))
                    .willReturn(Optional.of(makeImport(ImportStatus.COMPLETED)));

            Lesson lesson = new Lesson();
            lesson.setId(UUID.randomUUID());
            lesson.setTitle("Lesson 1");
            lesson.setTitleJapanese("レッスン 1");
            lesson.setOrderIndex(1);
            lesson.setPageStart(1);
            lesson.setPageEnd(5);
            lesson.setStatus(Lesson.ContentStatus.DRAFT);

            given(lessonRepository.findByImportIdAndNotDeleted(IMPORT_ID)).willReturn(List.of(lesson));

            List<LessonSummaryDto> result = service.getImportLessons(IMPORT_ID);

            assertThat(result).hasSize(1);
            LessonSummaryDto dto = result.get(0);
            assertThat(dto.id()).isEqualTo(lesson.getId());
            assertThat(dto.title()).isEqualTo("Lesson 1");
            assertThat(dto.status()).isEqualTo("DRAFT");
        }
    }

    // =========================================================================
    // getImportVocabulary
    // =========================================================================

    @Nested
    @DisplayName("getImportVocabulary")
    class GetImportVocabularyTests {

        @Test
        @DisplayName("Exception — import not found throws CurriculumImportNotFoundException")
        void getImportVocabulary_importNotFound_throws() {
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getImportVocabulary(IMPORT_ID, 0, 10))
                    .isInstanceOf(CurriculumImportNotFoundException.class);
        }

        @Test
        @DisplayName("Happy path — import has courseId returns vocabulary page")
        void getImportVocabulary_withCourseId_returnsVocabPage() {
            given(importRepository.findById(IMPORT_ID))
                    .willReturn(Optional.of(makeImport(ImportStatus.COMPLETED)));

            Vocabulary vocab = new Vocabulary();
            vocab.setId(UUID.randomUUID());

            Page<Vocabulary> page = new PageImpl<>(List.of(vocab));
            given(vocabularyRepository.findByCourseId(eq(COURSE_ID), any(Pageable.class)))
                    .willReturn(page);

            VocabularyResponse vocabResp = new VocabularyResponse();
            vocabResp.setId(vocab.getId());
            given(importMapper.toVocabularyResponse(vocab)).willReturn(vocabResp);

            PageResponse<VocabularyResponse> result = service.getImportVocabulary(IMPORT_ID, 0, 10);

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("Alternative — import has no courseId returns empty page")
        void getImportVocabulary_noCourseId_returnsEmptyPage() {
            CurriculumImport entity = makeImport(ImportStatus.COMPLETED);
            entity.setCourseId(null);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            PageResponse<VocabularyResponse> result = service.getImportVocabulary(IMPORT_ID, 0, 10);

            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
            then(vocabularyRepository).should(never()).findByCourseId(any(), any());
        }
    }

    // =========================================================================
    // getImportGrammarPoints
    // =========================================================================

    @Nested
    @DisplayName("getImportGrammarPoints")
    class GetImportGrammarPointsTests {

        @Test
        @DisplayName("Exception — import not found throws CurriculumImportNotFoundException")
        void getImportGrammarPoints_importNotFound_throws() {
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getImportGrammarPoints(IMPORT_ID, 0, 10))
                    .isInstanceOf(CurriculumImportNotFoundException.class);
        }

        @Test
        @DisplayName("Happy path — import has courseId returns grammar page")
        void getImportGrammarPoints_withCourseId_returnsGrammarPage() {
            given(importRepository.findById(IMPORT_ID))
                    .willReturn(Optional.of(makeImport(ImportStatus.COMPLETED)));

            GrammarPoint gp = new GrammarPoint();
            gp.setId(UUID.randomUUID());

            Page<GrammarPoint> page = new PageImpl<>(List.of(gp));
            given(grammarPointRepository.findByCourseId(eq(COURSE_ID), any(Pageable.class)))
                    .willReturn(page);

            GrammarPointResponse gpResp = new GrammarPointResponse();
            gpResp.setId(gp.getId());
            given(importMapper.toGrammarPointResponse(gp)).willReturn(gpResp);

            PageResponse<GrammarPointResponse> result = service.getImportGrammarPoints(IMPORT_ID, 0, 10);

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("Alternative — import has no courseId returns empty page")
        void getImportGrammarPoints_noCourseId_returnsEmptyPage() {
            CurriculumImport entity = makeImport(ImportStatus.COMPLETED);
            entity.setCourseId(null);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            PageResponse<GrammarPointResponse> result = service.getImportGrammarPoints(IMPORT_ID, 0, 10);

            assertThat(result.getContent()).isEmpty();
            then(grammarPointRepository).should(never()).findByCourseId(any(), any());
        }
    }

    // =========================================================================
    // getStatistics
    // =========================================================================

    @Nested
    @DisplayName("getStatistics")
    class GetStatisticsTests {

        @Test
        @DisplayName("Happy path — returns aggregated statistics from repository")
        void getStatistics_normal_returnsAggregatedStats() {
            given(importRepository.count()).willReturn(10L);
            given(importRepository.countByStatus(ImportStatus.COMPLETED)).willReturn(6L);
            given(importRepository.countByStatus(ImportStatus.FAILED)).willReturn(2L);
            given(importRepository.countByStatus(ImportStatus.QUEUED)).willReturn(1L);
            given(importRepository.countByStatus(ImportStatus.CANCELLED)).willReturn(0L);
            given(importRepository.sumLessonsCreated()).willReturn(50L);
            given(importRepository.sumVocabularyCreated()).willReturn(300L);
            given(importRepository.sumGrammarPointsCreated()).willReturn(100L);
            given(importRepository.sumTokensUsed()).willReturn(1_000_000L);
            given(importRepository.sumActualCostUsd()).willReturn(new BigDecimal("0.30"));
            given(importRepository.sumValidationPassed()).willReturn(90L);
            given(importRepository.sumValidationFailed()).willReturn(10L);
            given(importRepository.sumReExtractions()).willReturn(5L);
            given(importRepository.sumEmbeddingsCreated()).willReturn(400L);
            given(importRepository.sumHallucinatedItems()).willReturn(3L);

            ImportStatisticsResponse result = service.getStatistics();

            assertThat(result.getTotalImports()).isEqualTo(10L);
            assertThat(result.getCompletedImports()).isEqualTo(6L);
            assertThat(result.getFailedImports()).isEqualTo(2L);
            assertThat(result.getQueuedImports()).isEqualTo(1L);
            // in-progress = 10 - 6 - 2 - 1 - 0 = 1
            assertThat(result.getInProgressImports()).isEqualTo(1L);
            assertThat(result.getTotalLessonsCreated()).isEqualTo(50L);
            assertThat(result.getTotalVocabularyCreated()).isEqualTo(300L);
            assertThat(result.getTotalGrammarPointsCreated()).isEqualTo(100L);
            assertThat(result.getTotalTokensUsed()).isEqualTo(1_000_000L);
            assertThat(result.getTotalCostUsd()).isEqualByComparingTo("0.30");
            assertThat(result.getTotalValidationPassed()).isEqualTo(90L);
            assertThat(result.getTotalHallucinatedItems()).isEqualTo(3L);
        }

        @Test
        @DisplayName("Edge case — null cost from repository defaults to ZERO")
        void getStatistics_nullCost_defaultsToZero() {
            given(importRepository.count()).willReturn(0L);
            given(importRepository.countByStatus(any())).willReturn(0L);
            given(importRepository.sumLessonsCreated()).willReturn(0L);
            given(importRepository.sumVocabularyCreated()).willReturn(0L);
            given(importRepository.sumGrammarPointsCreated()).willReturn(0L);
            given(importRepository.sumTokensUsed()).willReturn(0L);
            given(importRepository.sumActualCostUsd()).willReturn(null);
            given(importRepository.sumValidationPassed()).willReturn(0L);
            given(importRepository.sumValidationFailed()).willReturn(0L);
            given(importRepository.sumReExtractions()).willReturn(0L);
            given(importRepository.sumEmbeddingsCreated()).willReturn(0L);
            given(importRepository.sumHallucinatedItems()).willReturn(0L);

            ImportStatisticsResponse result = service.getStatistics();

            assertThat(result.getTotalCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Edge case — in-progress cannot be negative")
        void getStatistics_negativeInProgress_floorsToZero() {
            // This can happen if counts are inconsistent
            given(importRepository.count()).willReturn(5L);
            given(importRepository.countByStatus(ImportStatus.COMPLETED)).willReturn(5L);
            given(importRepository.countByStatus(ImportStatus.FAILED)).willReturn(2L); // sum > total
            given(importRepository.countByStatus(ImportStatus.QUEUED)).willReturn(0L);
            given(importRepository.countByStatus(ImportStatus.CANCELLED)).willReturn(0L);
            given(importRepository.sumLessonsCreated()).willReturn(0L);
            given(importRepository.sumVocabularyCreated()).willReturn(0L);
            given(importRepository.sumGrammarPointsCreated()).willReturn(0L);
            given(importRepository.sumTokensUsed()).willReturn(0L);
            given(importRepository.sumActualCostUsd()).willReturn(BigDecimal.ZERO);
            given(importRepository.sumValidationPassed()).willReturn(0L);
            given(importRepository.sumValidationFailed()).willReturn(0L);
            given(importRepository.sumReExtractions()).willReturn(0L);
            given(importRepository.sumEmbeddingsCreated()).willReturn(0L);
            given(importRepository.sumHallucinatedItems()).willReturn(0L);

            ImportStatisticsResponse result = service.getStatistics();

            assertThat(result.getInProgressImports()).isGreaterThanOrEqualTo(0L);
        }
    }

    // =========================================================================
    // linkToCourse
    // =========================================================================

    @Nested
    @DisplayName("linkToCourse")
    class LinkToCourseTests {

        @Test
        @DisplayName("Happy path — links import and reassigns all lessons to target course")
        void linkToCourse_validIds_linksImportAndLessons() {
            CurriculumImport entity = makeImport(ImportStatus.COMPLETED);
            entity.setCourseId(null);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            Course targetCourse = new Course();
            targetCourse.setId(COURSE_ID);
            targetCourse.setName("Target Course");
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(targetCourse));

            Lesson lesson1 = new Lesson();
            lesson1.setId(UUID.randomUUID());
            Lesson lesson2 = new Lesson();
            lesson2.setId(UUID.randomUUID());
            given(lessonRepository.findByImportIdAndNotDeleted(IMPORT_ID))
                    .willReturn(List.of(lesson1, lesson2));
            given(lessonRepository.save(any(Lesson.class))).willAnswer(inv -> inv.getArgument(0));
            given(importRepository.save(entity)).willReturn(entity);

            CurriculumImportResponse resp = new CurriculumImportResponse();
            given(importMapper.toResponse(entity)).willReturn(resp);

            CurriculumImportResponse result = service.linkToCourse(IMPORT_ID, COURSE_ID, MANAGER_ID);

            assertThat(result.getCourseName()).isEqualTo("Target Course");
            assertThat(entity.getCourseId()).isEqualTo(COURSE_ID);

            then(lessonRepository).should(times(2)).save(any(Lesson.class));
            assertThat(lesson1.getCourse()).isSameAs(targetCourse);
            assertThat(lesson2.getCourse()).isSameAs(targetCourse);
        }

        @Test
        @DisplayName("Happy path — import with no lessons still links the import record")
        void linkToCourse_noLessons_linksImportRecord() {
            CurriculumImport entity = makeImport(ImportStatus.COMPLETED);
            entity.setCourseId(null);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            Course course = new Course();
            course.setName("Empty Course");
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));
            given(lessonRepository.findByImportIdAndNotDeleted(IMPORT_ID)).willReturn(List.of());
            given(importRepository.save(entity)).willReturn(entity);
            given(importMapper.toResponse(entity)).willReturn(new CurriculumImportResponse());

            service.linkToCourse(IMPORT_ID, COURSE_ID, MANAGER_ID);

            then(lessonRepository).should(never()).save(any());
            assertThat(entity.getCourseId()).isEqualTo(COURSE_ID);
        }

        @Test
        @DisplayName("Exception — import not found throws CurriculumImportNotFoundException")
        void linkToCourse_importNotFound_throws() {
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.linkToCourse(IMPORT_ID, COURSE_ID, MANAGER_ID))
                    .isInstanceOf(CurriculumImportNotFoundException.class);
        }

        @Test
        @DisplayName("Exception — target course not found throws CourseNotFoundException")
        void linkToCourse_courseNotFound_throws() {
            given(importRepository.findById(IMPORT_ID))
                    .willReturn(Optional.of(makeImport(ImportStatus.COMPLETED)));
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.linkToCourse(IMPORT_ID, COURSE_ID, MANAGER_ID))
                    .isInstanceOf(CourseNotFoundException.class);

            then(importRepository).should(never()).save(any());
        }
    }
}
