package com.fu.curriculumimport.service.persistence;

import com.fu.course.entity.*;
import com.fu.course.repository.*;
import com.fu.curriculumimport.entity.CurriculumImport;
import com.fu.curriculumimport.entity.ImportChunk;
import com.fu.curriculumimport.repository.CurriculumImportRepository;
import com.fu.curriculumimport.repository.ImportChunkRepository;
import com.fu.curriculumimport.service.agent.ExtractionResult;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
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

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * Unit tests for ImportPersistenceService.
 *
 * Coverage:
 *  persistChunks():
 *   - Empty document list -> nothing saved, empty list returned
 *   - Multiple chunks -> each saved, returned in order
 *   - Fields mapped correctly to ImportChunk (index, content, pages, tokenCount, chunkType)
 *
 *  saveChunk():
 *   - Delegates to chunkRepository.save
 *
 *  updateImportProgress():
 *   - Reloads entity via findById, copies fields, calls save
 *
 *  persistExtractedContent():
 *   - Empty results list -> nothing saved, all counts = 0
 *   - Null courseId -> lessons skipped, vocab/grammar still persisted independently
 *   - CourseId set but course not found -> lessons skipped
 *   - No lessons in result -> vocab/grammar persisted, counts reflect persisted items
 *   - Lesson created via itemHelper.findOrCreateLesson (deduplicates by importId+title)
 *   - Lesson save fails -> vocab/grammar persisted but linking skipped
 *   - Vocab batch found/created and batch-linked to lesson
 *   - Vocab batch returns null item -> link skipped, failedItems incremented
 *   - Grammar batch linked correctly via itemHelper
 *   - Stats updated on importEntity: lessonsCreated, vocabularyCreated, grammarPointsCreated
 *   - importRepository.save called via updateImportProgress at end
 *   - Multiple ExtractionResults -> vocab/grammar counts accumulate
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ImportPersistenceService")
class ImportPersistenceServiceTest {

    @Mock private CurriculumImportRepository importRepository;
    @Mock private ImportChunkRepository chunkRepository;
    @Mock private CourseRepository courseRepository;
    @Mock private LessonRepository lessonRepository;
    @Mock private LessonVocabularyRepository lessonVocabularyRepository;
    @Mock private LessonGrammarRepository lessonGrammarRepository;
    @Mock private ImportItemPersistenceHelper itemHelper;

    @InjectMocks
    private ImportPersistenceService service;

    private static final UUID IMPORT_ID  = UUID.randomUUID();
    private static final UUID COURSE_ID  = UUID.randomUUID();
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

    private DocumentChunk docChunk(int index, String content) {
        return DocumentChunk.builder()
                .chunkIndex(index)
                .content(content)
                .pageStart(index + 1)
                .pageEnd(index + 2)
                .chunkType("TEXT")
                .estimatedTokenCount(100)
                .build();
    }

    private ExtractionResult.ExtractedLesson extractedLesson(String title) {
        return ExtractionResult.ExtractedLesson.builder()
                .title(title)
                .titleJapanese("タイトル")
                .pageStart(1)
                .pageEnd(5)
                .orderIndex(1)
                .build();
    }

    private ExtractionResult.ExtractedVocabulary extractedVocab(String japaneseText, String jlptLevel) {
        return ExtractionResult.ExtractedVocabulary.builder()
                .japaneseText(japaneseText)
                .reading("よみかた")
                .meaningEn("meaning")
                .meaningVi("nghia")
                .jlptLevel(jlptLevel)
                .build();
    }

    private ExtractionResult.ExtractedGrammar extractedGrammar(String pattern, String jlptLevel) {
        return ExtractionResult.ExtractedGrammar.builder()
                .pattern(pattern)
                .meaningEn("ongoing action")
                .meaningVi("dang lam")
                .jlptLevel(jlptLevel)
                .category("TENSE")
                .build();
    }

    private ExtractionResult resultWith(List<ExtractionResult.ExtractedLesson> lessons,
                                        List<ExtractionResult.ExtractedVocabulary> vocab,
                                        List<ExtractionResult.ExtractedGrammar> grammar) {
        return ExtractionResult.builder()
                .chunkIndex(0)
                .confidence(0.9)
                .tokensUsed(200)
                .lessons(lessons)
                .vocabulary(vocab)
                .grammarPoints(grammar)
                .build();
    }

    // =========================================================================
    // persistChunks
    // =========================================================================

    @Nested
    @DisplayName("persistChunks")
    class PersistChunksTests {

        @Test
        @DisplayName("Empty document list -> returns empty list, nothing saved")
        void persistChunks_emptyList_returnsEmpty() {
            CurriculumImport entity = makeImport(COURSE_ID);

            List<ImportChunk> result = service.persistChunks(entity, List.of());

            assertThat(result).isEmpty();
            then(chunkRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("Two document chunks -> each saved, both returned")
        void persistChunks_twoChunks_bothSaved() {
            CurriculumImport entity = makeImport(COURSE_ID);
            DocumentChunk doc1 = docChunk(0, "Lesson 1 content");
            DocumentChunk doc2 = docChunk(1, "Lesson 2 content");

            ImportChunk saved1 = ImportChunk.builder().chunkIndex(0).build();
            ImportChunk saved2 = ImportChunk.builder().chunkIndex(1).build();
            given(chunkRepository.save(any(ImportChunk.class)))
                    .willReturn(saved1, saved2);

            List<ImportChunk> result = service.persistChunks(entity, List.of(doc1, doc2));

            assertThat(result).hasSize(2);
            then(chunkRepository).should(times(2)).save(any(ImportChunk.class));
        }

        @Test
        @DisplayName("Fields mapped correctly: chunkIndex, content, pages, tokenCount, chunkType")
        void persistChunks_fieldsMappedCorrectly() {
            CurriculumImport entity = makeImport(COURSE_ID);
            DocumentChunk doc = DocumentChunk.builder()
                    .chunkIndex(3)
                    .content("Detailed chunk content")
                    .pageStart(7)
                    .pageEnd(9)
                    .chunkType("TABLE")
                    .estimatedTokenCount(250)
                    .build();

            given(chunkRepository.save(any(ImportChunk.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.persistChunks(entity, List.of(doc));

            ArgumentCaptor<ImportChunk> captor = ArgumentCaptor.forClass(ImportChunk.class);
            then(chunkRepository).should().save(captor.capture());
            ImportChunk saved = captor.getValue();

            assertThat(saved.getChunkIndex()).isEqualTo(3);
            assertThat(saved.getContent()).isEqualTo("Detailed chunk content");
            assertThat(saved.getPageStart()).isEqualTo(7);
            assertThat(saved.getPageEnd()).isEqualTo(9);
            assertThat(saved.getChunkType()).isEqualTo("TABLE");
            assertThat(saved.getTokenCount()).isEqualTo(250);
            assertThat(saved.getCurriculumImport()).isSameAs(entity);
        }
    }

    // =========================================================================
    // saveChunk
    // =========================================================================

    @Nested
    @DisplayName("saveChunk")
    class SaveChunkTests {

        @Test
        @DisplayName("Delegates to chunkRepository.save")
        void saveChunk_delegatesToRepository() {
            ImportChunk chunk = ImportChunk.builder().chunkIndex(0).build();

            service.saveChunk(chunk);

            then(chunkRepository).should().save(chunk);
        }
    }

    // =========================================================================
    // updateImportProgress
    // =========================================================================

    @Nested
    @DisplayName("updateImportProgress")
    class UpdateImportProgressTests {

        @Test
        @DisplayName("Reloads entity via findById, copies fields, calls save")
        void updateImportProgress_reloadsAndSaves() {
            CurriculumImport entity = makeImport(COURSE_ID);
            entity.setVocabularyCreated(5);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            service.updateImportProgress(entity);

            then(importRepository).should().findById(IMPORT_ID);
            then(importRepository).should().save(entity);
        }

        @Test
        @DisplayName("Entity not found in DB -> save skipped gracefully")
        void updateImportProgress_entityNotFound_saveSkipped() {
            CurriculumImport entity = makeImport(COURSE_ID);
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.empty());

            service.updateImportProgress(entity);

            then(importRepository).should(never()).save(any());
        }
    }

    // =========================================================================
    // persistExtractedContent
    // =========================================================================

    @Nested
    @DisplayName("persistExtractedContent")
    class PersistExtractedContentTests {

        @BeforeEach
        void commonStubs() {
            // updateImportProgress (called at end of persistExtractedContent) reloads via findById
            given(importRepository.findById(any())).willAnswer(inv -> {
                // Return the entity that was passed to updateImportProgress
                // (captured via the importRepository mock — not directly available here,
                //  so we use a permissive stub that returns empty; individual tests
                //  that care about save() will stub findById explicitly)
                return Optional.empty();
            });
        }

        @Test
        @DisplayName("Empty results list -> nothing saved, counts all zero")
        void persistExtracted_emptyResults_allCountsZero() {
            CurriculumImport entity = makeImport(COURSE_ID);
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(new Course()));

            service.persistExtractedContent(entity, List.of());

            assertThat(entity.getLessonsCreated()).isZero();
            assertThat(entity.getVocabularyCreated()).isZero();
            assertThat(entity.getGrammarPointsCreated()).isZero();
            then(itemHelper).should(never()).findOrCreateLesson(any(), any());
        }

        @Test
        @DisplayName("Null courseId -> lessons skipped, vocab/grammar still persisted independently")
        void persistExtracted_noCourseId_lessonsSkipped_countsUpdated() {
            CurriculumImport entity = makeImport(null); // no course

            Vocabulary vocab = new Vocabulary();
            vocab.setId(UUID.randomUUID());
            given(itemHelper.findOrCreateVocabularyBatch(anyList(), eq(MANAGER_ID)))
                    .willReturn(List.of(vocab));

            GrammarPoint grammar = new GrammarPoint();
            grammar.setId(UUID.randomUUID());
            given(itemHelper.findOrCreateGrammarBatch(anyList(), eq(MANAGER_ID)))
                    .willReturn(List.of(grammar));

            ExtractionResult result = resultWith(
                    List.of(extractedLesson("Lesson 1")),
                    List.of(extractedVocab("食べる", "N5")),
                    List.of(extractedGrammar("〜ている", "N5")));

            service.persistExtractedContent(entity, List.of(result));

            // Lessons skipped (no course), but vocab/grammar persisted independently
            then(itemHelper).should(never()).findOrCreateLesson(any(), any());
            then(itemHelper).should().findOrCreateVocabularyBatch(anyList(), eq(MANAGER_ID));
            then(itemHelper).should().findOrCreateGrammarBatch(anyList(), eq(MANAGER_ID));
            assertThat(entity.getLessonsCreated()).isZero();
            assertThat(entity.getVocabularyCreated()).isEqualTo(1);
            assertThat(entity.getGrammarPointsCreated()).isEqualTo(1);
        }

        @Test
        @DisplayName("CourseId set but course not found -> lessons skipped")
        void persistExtracted_courseNotFound_lessonsSkipped() {
            CurriculumImport entity = makeImport(COURSE_ID);
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.empty());
            ExtractionResult result = resultWith(
                    List.of(extractedLesson("Lesson 1")),
                    List.of(), List.of());

            service.persistExtractedContent(entity, List.of(result));

            then(itemHelper).should(never()).findOrCreateLesson(any(), any());
            assertThat(entity.getLessonsCreated()).isZero();
        }

        @Test
        @DisplayName("No lessons in result -> vocab/grammar persisted, counts reflect batch size")
        void persistExtracted_noLessons_countsUpdatedFromBatch() {
            CurriculumImport entity = makeImport(COURSE_ID);
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(new Course()));

            Vocabulary v1 = new Vocabulary(); v1.setId(UUID.randomUUID());
            Vocabulary v2 = new Vocabulary(); v2.setId(UUID.randomUUID());
            given(itemHelper.findOrCreateVocabularyBatch(anyList(), eq(MANAGER_ID)))
                    .willReturn(List.of(v1, v2));

            GrammarPoint g = new GrammarPoint(); g.setId(UUID.randomUUID());
            given(itemHelper.findOrCreateGrammarBatch(anyList(), eq(MANAGER_ID)))
                    .willReturn(List.of(g));

            ExtractionResult result = resultWith(
                    List.of(), // no lessons
                    List.of(extractedVocab("食べる", "N5"), extractedVocab("飲む", "N4")),
                    List.of(extractedGrammar("〜ている", "N5")));

            service.persistExtractedContent(entity, List.of(result));

            then(itemHelper).should(never()).findOrCreateLesson(any(), any());
            then(itemHelper).should().findOrCreateVocabularyBatch(anyList(), eq(MANAGER_ID));
            then(itemHelper).should().findOrCreateGrammarBatch(anyList(), eq(MANAGER_ID));
            assertThat(entity.getLessonsCreated()).isZero();
            assertThat(entity.getVocabularyCreated()).isEqualTo(2);
            assertThat(entity.getGrammarPointsCreated()).isEqualTo(1);
        }

        @Test
        @DisplayName("Lesson created via itemHelper.findOrCreateLesson with correct fields")
        void persistExtracted_lessonCreatedViaHelper() {
            CurriculumImport entity = makeImport(COURSE_ID);
            Course course = new Course();
            course.setId(COURSE_ID);
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));

            Lesson savedLesson = new Lesson();
            savedLesson.setId(UUID.randomUUID());
            given(itemHelper.findOrCreateLesson(any(Lesson.class), any(UUID.class))).willReturn(savedLesson);

            ExtractionResult result = resultWith(
                    List.of(extractedLesson("Grammar Basics")),
                    List.of(), List.of());

            service.persistExtractedContent(entity, List.of(result));

            ArgumentCaptor<Lesson> captor = ArgumentCaptor.forClass(Lesson.class);
            then(itemHelper).should().findOrCreateLesson(captor.capture(), any());
            Lesson captured = captor.getValue();

            assertThat(captured.getCourse()).isSameAs(course);
            assertThat(captured.getImportId()).isEqualTo(IMPORT_ID);
            assertThat(captured.getTitle()).isEqualTo("Grammar Basics");
            assertThat(captured.getStatus()).isEqualTo(Lesson.ContentStatus.DRAFT);
            assertThat(captured.getProcessingStatus()).isEqualTo(Lesson.ProcessingStatus.COMPLETED);
            assertThat(entity.getLessonsCreated()).isEqualTo(1);
        }

        @Test
        @DisplayName("Lesson save fails -> vocab/grammar persisted but batch-linking skipped")
        void persistExtracted_lessonSaveFails_linkingSkipped() {
            CurriculumImport entity = makeImport(COURSE_ID);
            Course course = new Course();
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));

            // itemHelper.findOrCreateLesson returns null (failure)
            given(itemHelper.findOrCreateLesson(any(Lesson.class), any(UUID.class))).willReturn(null);

            Vocabulary vocab = new Vocabulary(); vocab.setId(UUID.randomUUID());
            given(itemHelper.findOrCreateVocabularyBatch(anyList(), eq(MANAGER_ID)))
                    .willReturn(List.of(vocab));

            GrammarPoint grammar = new GrammarPoint(); grammar.setId(UUID.randomUUID());
            given(itemHelper.findOrCreateGrammarBatch(anyList(), eq(MANAGER_ID)))
                    .willReturn(List.of(grammar));

            ExtractionResult result = resultWith(
                    List.of(extractedLesson("Bad Lesson")),
                    List.of(extractedVocab("食べる", "N5")),
                    List.of(extractedGrammar("〜ている", "N5")));

            service.persistExtractedContent(entity, List.of(result));

            assertThat(entity.getLessonsCreated()).isZero();
            // Vocab/grammar ARE persisted independently
            then(itemHelper).should().findOrCreateVocabularyBatch(anyList(), eq(MANAGER_ID));
            then(itemHelper).should().findOrCreateGrammarBatch(anyList(), eq(MANAGER_ID));
            // But batch-linking is skipped since lesson save failed
            then(itemHelper).should(never()).linkVocabularyBatchToLesson(any(), anyList(), anyList(), anyInt());
            then(itemHelper).should(never()).linkGrammarBatchToLesson(any(), anyList(), anyList(), anyInt());
        }

        @Test
        @DisplayName("Vocab batch found/created and batch-linked to lesson")
        void persistExtracted_vocabBatchCreatedAndLinked() {
            CurriculumImport entity = makeImport(COURSE_ID);
            Course course = new Course();
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));

            UUID lessonId = UUID.randomUUID();
            Lesson savedLesson = new Lesson();
            savedLesson.setId(lessonId);
            given(itemHelper.findOrCreateLesson(any(Lesson.class), any(UUID.class))).willReturn(savedLesson);

            Vocabulary vocab = new Vocabulary();
            vocab.setId(UUID.randomUUID());
            given(itemHelper.findOrCreateVocabularyBatch(anyList(), eq(MANAGER_ID)))
                    .willReturn(List.of(vocab));
            given(itemHelper.linkVocabularyBatchToLesson(eq(lessonId), anyList(), anyList(), anyInt()))
                    .willReturn(1);

            ExtractionResult result = resultWith(
                    List.of(extractedLesson("Vocab Lesson")),
                    List.of(extractedVocab("食べる", "N5")),
                    List.of());

            service.persistExtractedContent(entity, List.of(result));

            then(itemHelper).should().findOrCreateVocabularyBatch(anyList(), eq(MANAGER_ID));
            then(itemHelper).should().linkVocabularyBatchToLesson(eq(lessonId), anyList(), anyList(), eq(0));
        }

        @Test
        @DisplayName("Vocab batch returns null item -> link still attempted for non-null items")
        void persistExtracted_vocabBatchHasNull_nullSkippedInLinking() {
            CurriculumImport entity = makeImport(COURSE_ID);
            Course course = new Course();
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));

            Lesson savedLesson = new Lesson();
            savedLesson.setId(UUID.randomUUID());
            given(itemHelper.findOrCreateLesson(any(Lesson.class), any(UUID.class))).willReturn(savedLesson);

            // Batch returns null for the one item (failed to persist)
            given(itemHelper.findOrCreateVocabularyBatch(anyList(), any()))
                    .willReturn(Arrays.asList((Vocabulary) null));

            ExtractionResult result = resultWith(
                    List.of(extractedLesson("Lesson")),
                    List.of(extractedVocab("食べる", "N5")),
                    List.of());

            service.persistExtractedContent(entity, List.of(result));

            // persistedVocab is empty after filtering nulls → no batch link call
            then(itemHelper).should(never()).linkVocabularyBatchToLesson(any(), anyList(), anyList(), anyInt());
            assertThat(entity.getVocabularyCreated()).isZero(); // null not counted
        }

        @Test
        @DisplayName("Grammar batch found/created and batch-linked to lesson")
        void persistExtracted_grammarBatchCreatedAndLinked() {
            CurriculumImport entity = makeImport(COURSE_ID);
            Course course = new Course();
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));

            UUID lessonId = UUID.randomUUID();
            Lesson savedLesson = new Lesson();
            savedLesson.setId(lessonId);
            given(itemHelper.findOrCreateLesson(any(Lesson.class), any(UUID.class))).willReturn(savedLesson);

            GrammarPoint grammar = new GrammarPoint();
            grammar.setId(UUID.randomUUID());
            given(itemHelper.findOrCreateGrammarBatch(anyList(), eq(MANAGER_ID)))
                    .willReturn(List.of(grammar));
            given(itemHelper.linkGrammarBatchToLesson(eq(lessonId), anyList(), anyList(), anyInt()))
                    .willReturn(1);

            ExtractionResult result = resultWith(
                    List.of(extractedLesson("Grammar Lesson")),
                    List.of(),
                    List.of(extractedGrammar("〜ている", "N5")));

            service.persistExtractedContent(entity, List.of(result));

            then(itemHelper).should().findOrCreateGrammarBatch(anyList(), eq(MANAGER_ID));
            then(itemHelper).should().linkGrammarBatchToLesson(eq(lessonId), anyList(), anyList(), eq(0));
        }

        @Test
        @DisplayName("Lesson entity always gets LessonType.MIXED regardless of content")
        void persistExtracted_lessonType_alwaysMixed() {
            CurriculumImport entity = makeImport(COURSE_ID);
            Course course = new Course();
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));

            given(itemHelper.findOrCreateLesson(any(Lesson.class), any(UUID.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ExtractionResult.ExtractedLesson lesson = ExtractionResult.ExtractedLesson.builder()
                    .title("Some Lesson")
                    .pageStart(1).pageEnd(3).orderIndex(1)
                    .build();

            ExtractionResult result = resultWith(List.of(lesson), List.of(), List.of());

            service.persistExtractedContent(entity, List.of(result));

            ArgumentCaptor<Lesson> captor = ArgumentCaptor.forClass(Lesson.class);
            then(itemHelper).should().findOrCreateLesson(captor.capture(), any());
            assertThat(captor.getValue().getLessonType()).isEqualTo(Lesson.LessonType.MIXED);
        }

        @Test
        @DisplayName("Multiple ExtractionResults -> vocab/grammar counts accumulate from batch results")
        void persistExtracted_multipleResults_countsAccumulate() {
            CurriculumImport entity = makeImport(null); // no course -> no lesson persisted

            // 2 vocab in result1, 1 vocab in result2 → 2 separate batch calls (different groups)
            Vocabulary v1 = new Vocabulary(); v1.setId(UUID.randomUUID());
            Vocabulary v2 = new Vocabulary(); v2.setId(UUID.randomUUID());
            Vocabulary v3 = new Vocabulary(); v3.setId(UUID.randomUUID());
            given(itemHelper.findOrCreateVocabularyBatch(anyList(), eq(MANAGER_ID)))
                    .willReturn(List.of(v1, v2), List.of(v3));

            // 1 grammar in result1, 2 grammar in result2
            GrammarPoint g1 = new GrammarPoint(); g1.setId(UUID.randomUUID());
            GrammarPoint g2 = new GrammarPoint(); g2.setId(UUID.randomUUID());
            GrammarPoint g3 = new GrammarPoint(); g3.setId(UUID.randomUUID());
            given(itemHelper.findOrCreateGrammarBatch(anyList(), eq(MANAGER_ID)))
                    .willReturn(List.of(g1), List.of(g2, g3));

            ExtractionResult result1 = resultWith(List.of(),
                    List.of(extractedVocab("食べる", "N5"), extractedVocab("飲む", "N5")),
                    List.of(extractedGrammar("〜ている", "N5")));
            ExtractionResult result2 = resultWith(List.of(),
                    List.of(extractedVocab("走る", "N5")),
                    List.of(extractedGrammar("〜てみる", "N5"), extractedGrammar("〜てしまう", "N5")));
            // Give result2 a different chunkIndex so it is a separate group
            ExtractionResult result2WithIndex = ExtractionResult.builder()
                    .chunkIndex(1)
                    .confidence(0.9).tokensUsed(200)
                    .lessons(List.of())
                    .vocabulary(List.of(extractedVocab("走る", "N5")))
                    .grammarPoints(List.of(extractedGrammar("〜てみる", "N5"), extractedGrammar("〜てしまう", "N5")))
                    .build();

            service.persistExtractedContent(entity, List.of(result1, result2WithIndex));

            // 2 + 1 = 3 vocab, 1 + 2 = 3 grammar
            assertThat(entity.getVocabularyCreated()).isEqualTo(3);
            assertThat(entity.getGrammarPointsCreated()).isEqualTo(3);
        }

        @Test
        @DisplayName("importRepository.save called via updateImportProgress at end")
        void persistExtracted_importSavedAtEnd() {
            CurriculumImport entity = makeImport(COURSE_ID);
            Course course = new Course();
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));
            // Stub findById so updateImportProgress actually calls save
            given(importRepository.findById(IMPORT_ID)).willReturn(Optional.of(entity));

            Vocabulary vocab = new Vocabulary(); vocab.setId(UUID.randomUUID());
            given(itemHelper.findOrCreateVocabularyBatch(anyList(), eq(MANAGER_ID)))
                    .willReturn(List.of(vocab));

            ExtractionResult result = resultWith(
                    List.of(),
                    List.of(extractedVocab("食べる", "N5")),
                    List.of());

            service.persistExtractedContent(entity, List.of(result));

            then(importRepository).should().save(entity);
        }
    }
}
