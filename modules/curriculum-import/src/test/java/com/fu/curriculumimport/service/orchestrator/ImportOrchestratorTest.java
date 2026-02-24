package com.fu.curriculumimport.service.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.course.entity.Course;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.repository.CourseRepository;
import com.fu.curriculumimport.config.AgenticRagProperties;
import com.fu.curriculumimport.entity.CurriculumImport;
import com.fu.curriculumimport.entity.CurriculumImport.ImportStatus;
import com.fu.curriculumimport.entity.ImportChunk;
import com.fu.curriculumimport.repository.CurriculumImportRepository;
import com.fu.curriculumimport.repository.ImportChunkRepository;
import com.fu.curriculumimport.service.agent.*;
import com.fu.curriculumimport.service.embedding.EmbeddingService;
import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import com.fu.curriculumimport.service.ingestion.DocumentParseResult;
import com.fu.curriculumimport.service.ingestion.PdfIngestionService;
import com.fu.curriculumimport.service.persistence.ImportPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

/**
 * Unit tests for ImportOrchestrator.
 *
 * Note: @Async is ignored in unit tests (no Spring context), so processImportAsync
 * and processRetryAsync run synchronously, making state assertions reliable.
 *
 * Coverage:
 *  requestCancel():
 *   - No active import (no flag) → returns false
 *   - Active import (flag registered) → sets flag to true, returns true
 *
 *  processImportAsync():
 *   - Happy path: all 7 stages complete → entity marked COMPLETED
 *   - Exception during PDF parsing → entity marked FAILED with error message
 *   - Cancellation: cancel flag set during processing → entity marked CANCELLED
 *   - Import not found in repository → IllegalStateException thrown
 *   - JLPT level resolved from course (N5 default when no courseId)
 *
 *  processRetryAsync():
 *   - Import not found → IllegalStateException thrown
 *   - No existing chunks → entity marked FAILED "No chunks found"
 *   - Existing chunks → re-classifies, re-extracts, marks COMPLETED
 *
 *  routeToAgent():
 *   - VOCABULARY → uses vocabularyAgent.extractVocabulary
 *   - GRAMMAR → uses grammarAgent.extractGrammar
 *   - EXERCISE / OTHER → returns empty result (no AI call)
 *   - MIXED / default → uses extractionAgent.extractContent
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ImportOrchestrator")
class ImportOrchestratorTest {

    @Mock private PdfIngestionService pdfIngestionService;
    @Mock private ContentExtractionAgent extractionAgent;
    @Mock private ContentClassifierAgent classifierAgent;
    @Mock private VocabularyExtractionAgent vocabularyAgent;
    @Mock private GrammarExtractionAgent grammarAgent;
    @Mock private ContentValidationAgent validationAgent;
    @Mock private EmbeddingService embeddingService;
    @Mock private ImportPersistenceService persistenceService;
    @Mock private CurriculumImportRepository importRepository;
    @Mock private ImportChunkRepository chunkRepository;
    @Mock private CourseRepository courseRepository;
    @Mock private AgenticRagProperties ragProperties;
    @Mock private Executor importTaskExecutor;
    @Spy  private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ImportOrchestrator orchestrator;

    private static final UUID IMPORT_ID = UUID.randomUUID();
    private static final UUID COURSE_ID = UUID.randomUUID();
    private static final UUID MANAGER_ID = UUID.randomUUID();

    private CurriculumImport makeImport(UUID courseId) {
        CurriculumImport entity = CurriculumImport.builder()
                .courseId(courseId)
                .createdBy(MANAGER_ID)
                .originalFilename("test.pdf")
                .fileUrl("url")
                .build();
        entity.setId(IMPORT_ID);
        return entity;
    }

    private DocumentChunk docChunk(int index) {
        return DocumentChunk.builder()
                .chunkIndex(index)
                .content("Japanese lesson content 食べる N5 vocabulary")
                .pageStart(1).pageEnd(3)
                .chunkType("TEXT")
                .estimatedTokenCount(100)
                .build();
    }

    private ImportChunk dbChunk(int index) {
        ImportChunk chunk = ImportChunk.builder()
                .chunkIndex(index)
                .content("Japanese lesson content")
                .build();
        chunk.setId(UUID.randomUUID());
        return chunk;
    }

    private ExtractionResult emptyResult(int chunkIndex) {
        return ExtractionResult.builder()
                .chunkIndex(chunkIndex)
                .confidence(0.9)
                .tokensUsed(150)
                .processingTimeMs(200)
                .lessons(List.of())
                .vocabulary(List.of())
                .grammarPoints(List.of())
                .build();
    }

    private DocumentParseResult parsedDoc(List<DocumentChunk> chunks) {
        return DocumentParseResult.builder()
                .totalPages(5)
                .fullText("full text here")
                .pageTexts(List.of("page 1", "page 2"))
                .chunks(chunks)
                .build();
    }

    /** Stub all the RAG feature flags to be disabled (minimal mode). */
    private void disableAllRagFeatures() {
        given(ragProperties.isParallelProcessing()).willReturn(false);
        given(ragProperties.isRagRetrievalEnabled()).willReturn(false);
        given(ragProperties.isValidationEnabled()).willReturn(false);
        given(ragProperties.isEmbeddingEnabled()).willReturn(false);
    }

    // =========================================================================
    // requestCancel
    // =========================================================================

    @Nested
    @DisplayName("requestCancel")
    class RequestCancelTests {

        @Test
        @DisplayName("No active import (no flag registered) → returns false")
        void requestCancel_noActiveFlag_returnsFalse() {
            boolean result = orchestrator.requestCancel(UUID.randomUUID());

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Active import → cancel flag set to true, returns true")
        void requestCancel_activeImport_flagSetReturnsTrue() {
            // Arrange: set up a full pipeline to register the import ID in cancelFlags
            CurriculumImport entity = makeImport(null);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));
            given(pdfIngestionService.parsePdf(any())).willAnswer(inv -> {
                // While parsePdf is "running", cancel the import
                orchestrator.requestCancel(IMPORT_ID);
                return parsedDoc(List.of(docChunk(0)));
            });
            disableAllRagFeatures();
            given(persistenceService.persistChunks(any(), any())).willReturn(List.of(dbChunk(0)));
            given(classifierAgent.classify(any())).willReturn(ClassificationResult.fallback());

            MultipartFile file = mock(MultipartFile.class);
            orchestrator.processImportAsync(IMPORT_ID, file);

            // Verify cancellation was handled
            assertThat(entity.getStatus()).isEqualTo(ImportStatus.CANCELLED);
        }
    }

    // =========================================================================
    // processImportAsync
    // =========================================================================

    @Nested
    @DisplayName("processImportAsync")
    class ProcessImportAsyncTests {

        @Test
        @DisplayName("Happy path: all stages complete → entity marked COMPLETED")
        void processImportAsync_happyPath_markedCompleted() {
            // Arrange
            CurriculumImport entity = makeImport(COURSE_ID);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            Course course = new Course();
            course.setId(COURSE_ID);
            course.setJlptLevel(JlptLevel.N5);
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));

            DocumentChunk chunk0 = docChunk(0);
            ImportChunk dbChunk0 = dbChunk(0);
            given(pdfIngestionService.parsePdf(any())).willReturn(parsedDoc(List.of(chunk0)));
            given(persistenceService.persistChunks(any(), any())).willReturn(List.of(dbChunk0));

            ClassificationResult classification = ClassificationResult.fallback(); // MIXED
            given(classifierAgent.classify(any())).willReturn(classification);

            ExtractionResult result = emptyResult(0);
            given(extractionAgent.extractContent(any(), any())).willReturn(result);

            disableAllRagFeatures();

            MultipartFile file = mock(MultipartFile.class);

            // Act
            orchestrator.processImportAsync(IMPORT_ID, file);

            // Assert
            assertThat(entity.getStatus()).isEqualTo(ImportStatus.COMPLETED);
            then(persistenceService).should(atLeastOnce()).updateImportProgress(entity);
            then(persistenceService).should().persistExtractedContent(eq(entity), any());
        }

        @Test
        @DisplayName("Exception during PDF parsing → entity marked FAILED with error message")
        void processImportAsync_pdfParseFails_markedFailed() {
            CurriculumImport entity = makeImport(COURSE_ID);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(new Course() {{
                setJlptLevel(JlptLevel.N5);
            }}));

            given(pdfIngestionService.parsePdf(any()))
                    .willThrow(new RuntimeException("PDF corrupted"));

            MultipartFile file = mock(MultipartFile.class);
            orchestrator.processImportAsync(IMPORT_ID, file);

            assertThat(entity.getStatus()).isEqualTo(ImportStatus.FAILED);
            assertThat(entity.getErrorMessage()).contains("PDF corrupted");
        }

        @Test
        @DisplayName("Import not found → IllegalStateException propagates (no entity to mark failed)")
        void processImportAsync_importNotFound_throwsIllegalState() {
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.empty());

            MultipartFile file = mock(MultipartFile.class);

            // When import entity cannot be found, the method throws before the try-catch block
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> orchestrator.processImportAsync(IMPORT_ID, file));
        }

        @Test
        @DisplayName("No courseId → JLPT defaults to N5, lesson processing still works")
        void processImportAsync_noCourseId_jlptDefaultsToN5() {
            CurriculumImport entity = makeImport(null); // no course
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            DocumentChunk chunk0 = docChunk(0);
            ImportChunk dbChunk0 = dbChunk(0);
            given(pdfIngestionService.parsePdf(any())).willReturn(parsedDoc(List.of(chunk0)));
            given(persistenceService.persistChunks(any(), any())).willReturn(List.of(dbChunk0));

            ClassificationResult classification = ClassificationResult.fallback(); // MIXED
            given(classifierAgent.classify(any())).willReturn(classification);
            ExtractionResult result = emptyResult(0);
            given(extractionAgent.extractContent(any(), any())).willReturn(result);

            disableAllRagFeatures();

            MultipartFile file = mock(MultipartFile.class);
            orchestrator.processImportAsync(IMPORT_ID, file);

            // Should complete without consulting courseRepository
            assertThat(entity.getStatus()).isEqualTo(ImportStatus.COMPLETED);
            then(courseRepository).should(never()).findById(any());
        }

        @Test
        @DisplayName("VOCABULARY chunk → routed to vocabularyAgent.extractVocabulary")
        void processImportAsync_vocabularyChunk_usesVocabularyAgent() {
            CurriculumImport entity = makeImport(null);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            DocumentChunk chunk0 = docChunk(0);
            ImportChunk dbChunk0 = dbChunk(0);
            given(pdfIngestionService.parsePdf(any())).willReturn(parsedDoc(List.of(chunk0)));
            given(persistenceService.persistChunks(any(), any())).willReturn(List.of(dbChunk0));

            ClassificationResult vocabClassification = new ClassificationResult();
            vocabClassification.setContentType(ClassificationResult.ContentType.VOCABULARY);
            given(classifierAgent.classify(any())).willReturn(vocabClassification);

            given(vocabularyAgent.extractVocabulary(any(), any(), any())).willReturn(emptyResult(0));

            disableAllRagFeatures();

            orchestrator.processImportAsync(IMPORT_ID, mock(MultipartFile.class));

            then(vocabularyAgent).should().extractVocabulary(any(), any(), any());
            then(grammarAgent).should(never()).extractGrammar(any(), any(), any());
            then(extractionAgent).should(never()).extractContent(any(), any());
        }

        @Test
        @DisplayName("GRAMMAR chunk → routed to grammarAgent.extractGrammar")
        void processImportAsync_grammarChunk_usesGrammarAgent() {
            CurriculumImport entity = makeImport(null);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            DocumentChunk chunk0 = docChunk(0);
            ImportChunk dbChunk0 = dbChunk(0);
            given(pdfIngestionService.parsePdf(any())).willReturn(parsedDoc(List.of(chunk0)));
            given(persistenceService.persistChunks(any(), any())).willReturn(List.of(dbChunk0));

            ClassificationResult grammarClassification = new ClassificationResult();
            grammarClassification.setContentType(ClassificationResult.ContentType.GRAMMAR);
            given(classifierAgent.classify(any())).willReturn(grammarClassification);

            given(grammarAgent.extractGrammar(any(), any(), any())).willReturn(emptyResult(0));

            disableAllRagFeatures();

            orchestrator.processImportAsync(IMPORT_ID, mock(MultipartFile.class));

            then(grammarAgent).should().extractGrammar(any(), any(), any());
            then(vocabularyAgent).should(never()).extractVocabulary(any(), any(), any());
        }

        @Test
        @DisplayName("EXERCISE chunk → returns empty result without calling any AI agent")
        void processImportAsync_exerciseChunk_noAgentCalled() {
            CurriculumImport entity = makeImport(null);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            DocumentChunk chunk0 = docChunk(0);
            ImportChunk dbChunk0 = dbChunk(0);
            given(pdfIngestionService.parsePdf(any())).willReturn(parsedDoc(List.of(chunk0)));
            given(persistenceService.persistChunks(any(), any())).willReturn(List.of(dbChunk0));

            ClassificationResult exerciseClassification = new ClassificationResult();
            exerciseClassification.setContentType(ClassificationResult.ContentType.EXERCISE);
            given(classifierAgent.classify(any())).willReturn(exerciseClassification);

            disableAllRagFeatures();

            orchestrator.processImportAsync(IMPORT_ID, mock(MultipartFile.class));

            then(vocabularyAgent).should(never()).extractVocabulary(any(), any(), any());
            then(grammarAgent).should(never()).extractGrammar(any(), any(), any());
            then(extractionAgent).should(never()).extractContent(any(), any());
            assertThat(entity.getStatus()).isEqualTo(ImportStatus.COMPLETED);
        }

        @Test
        @DisplayName("Empty chunk list (no pages) → pipeline completes with 0 chunks")
        void processImportAsync_emptyChunks_completesWithZeroCounts() {
            CurriculumImport entity = makeImport(null);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));
            given(pdfIngestionService.parsePdf(any())).willReturn(parsedDoc(List.of()));
            given(persistenceService.persistChunks(any(), any())).willReturn(List.of());

            disableAllRagFeatures();

            orchestrator.processImportAsync(IMPORT_ID, mock(MultipartFile.class));

            assertThat(entity.getStatus()).isEqualTo(ImportStatus.COMPLETED);
            then(classifierAgent).should(never()).classify(any());
            then(persistenceService).should().persistExtractedContent(eq(entity), any());
        }
    }

    // =========================================================================
    // processRetryAsync
    // =========================================================================

    @Nested
    @DisplayName("processRetryAsync")
    class ProcessRetryAsyncTests {

        @Test
        @DisplayName("Import not found → IllegalStateException thrown")
        void processRetryAsync_importNotFound_throwsIllegalState() {
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.empty());

            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> orchestrator.processRetryAsync(IMPORT_ID));
        }

        @Test
        @DisplayName("No existing chunks → entity marked FAILED with 'No chunks found'")
        void processRetryAsync_noChunks_markedFailed() {
            CurriculumImport entity = makeImport(COURSE_ID);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));
            given(chunkRepository.findByCurriculumImportIdOrderByChunkIndexAsc(IMPORT_ID))
                    .willReturn(List.of());

            orchestrator.processRetryAsync(IMPORT_ID);

            assertThat(entity.getStatus()).isEqualTo(ImportStatus.FAILED);
            assertThat(entity.getErrorMessage()).containsIgnoringCase("chunks");
            then(persistenceService).should(atLeastOnce()).updateImportProgress(entity);
        }

        @Test
        @DisplayName("Existing chunks found → re-classifies, re-extracts, marks COMPLETED")
        void processRetryAsync_existingChunks_markedCompleted() {
            CurriculumImport entity = makeImport(null); // no course for simplicity
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            ImportChunk existingChunk = dbChunk(0);
            existingChunk.setContent("Existing chunk content 食べる");
            existingChunk.setChunkType("TEXT");
            existingChunk.setPageStart(1);
            existingChunk.setPageEnd(3);
            existingChunk.setTokenCount(100);

            given(chunkRepository.findByCurriculumImportIdOrderByChunkIndexAsc(IMPORT_ID))
                    .willReturn(List.of(existingChunk));

            ClassificationResult classification = ClassificationResult.fallback();
            given(classifierAgent.classify(any())).willReturn(classification);
            given(extractionAgent.extractContent(any(), any())).willReturn(emptyResult(0));

            disableAllRagFeatures();

            orchestrator.processRetryAsync(IMPORT_ID);

            assertThat(entity.getStatus()).isEqualTo(ImportStatus.COMPLETED);
            then(classifierAgent).should().classify(any());
            then(persistenceService).should().persistExtractedContent(eq(entity), any());
        }

        @Test
        @DisplayName("Exception during retry processing → entity marked FAILED")
        void processRetryAsync_exceptionDuringProcessing_markedFailed() {
            CurriculumImport entity = makeImport(null);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            ImportChunk existingChunk = dbChunk(0);
            existingChunk.setContent("content");
            existingChunk.setChunkType("TEXT");
            existingChunk.setPageStart(1);
            existingChunk.setPageEnd(3);
            existingChunk.setTokenCount(100);

            given(chunkRepository.findByCurriculumImportIdOrderByChunkIndexAsc(IMPORT_ID))
                    .willReturn(List.of(existingChunk));

            disableAllRagFeatures();
            given(classifierAgent.classify(any()))
                    .willThrow(new RuntimeException("AI service unavailable"));

            orchestrator.processRetryAsync(IMPORT_ID);

            assertThat(entity.getStatus()).isEqualTo(ImportStatus.FAILED);
            assertThat(entity.getErrorMessage()).contains("AI service unavailable");
        }
    }
}
