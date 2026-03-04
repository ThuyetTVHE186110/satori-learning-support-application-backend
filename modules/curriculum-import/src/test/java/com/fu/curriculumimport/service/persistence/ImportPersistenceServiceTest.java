package com.fu.curriculumimport.service.persistence;

import com.fu.course.entity.*;
import com.fu.course.entity.Course.JlptLevel;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
 *   - Delegates to importRepository.save
 *
 *  persistExtractedContent():
 *   - No courseId (null) -> lessons skipped, counts still updated from raw sizes
 *   - CourseId set but course not found -> lessons skipped (same as no course)
 *   - Empty results list -> nothing saved, all counts = 0
 *   - No lessons in result -> lesson not created, vocab/grammar counts still updated
 *   - Lesson created via itemHelper.saveLesson
 *   - Lesson save fails -> skips vocab/grammar linking for that lesson
 *   - New vocab -> itemHelper.findOrCreateVocabulary called and linked
 *   - Vocab returns null -> link skipped, failedItems incremented
 *   - Grammar linked correctly via itemHelper
 *   - Stats updated on importEntity: lessonsCreated, vocabularyCreated, grammarPointsCreated
 *   - importRepository.save called with updated entity
 *   - Multiple ExtractionResults -> vocab/grammar counts accumulate
 *   - Invalid lessonType string -> falls back to LessonType.MIXED
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
                .lessonType("VOCABULARY")
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
        @DisplayName("Delegates to importRepository.save")
        void updateImportProgress_delegatesToRepository() {
            CurriculumImport entity = makeImport(COURSE_ID);

            service.updateImportProgress(entity);

            then(importRepository).should().save(entity);
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
            given(importRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
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
            then(itemHelper).should(never()).saveLesson(any());
        }

        @Test
        @DisplayName("Null courseId -> lessons skipped, vocab/grammar raw counts still set")
        void persistExtracted_noCourseId_lessonsSkipped_countsUpdated() {
            CurriculumImport entity = makeImport(null); // no course
            ExtractionResult result = resultWith(
                    List.of(extractedLesson("Lesson 1")),
                    List.of(extractedVocab("食べる", "N5")),
                    List.of(extractedGrammar("〜ている", "N5")));

            service.persistExtractedContent(entity, List.of(result));

            then(itemHelper).should(never()).saveLesson(any());
            assertThat(entity.getLessonsCreated()).isZero();
            assertThat(entity.getVocabularyCreated()).isEqualTo(1);
            assertThat(entity.getGrammarPointsCreated()).isEqualTo(1);
            then(importRepository).should().save(entity);
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

            then(itemHelper).should(never()).saveLesson(any());
            assertThat(entity.getLessonsCreated()).isZero();
        }

        @Test
        @DisplayName("No lessons in result -> lesson not created, vocab/grammar counts still set")
        void persistExtracted_noLessons_countsUpdatedFromRawSize() {
            CurriculumImport entity = makeImport(COURSE_ID);
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(new Course()));
            ExtractionResult result = resultWith(
                    List.of(), // no lessons
                    List.of(extractedVocab("食べる", "N5"), extractedVocab("飲む", "N4")),
                    List.of(extractedGrammar("〜ている", "N5")));

            service.persistExtractedContent(entity, List.of(result));

            then(itemHelper).should(never()).saveLesson(any());
            assertThat(entity.getLessonsCreated()).isZero();
            assertThat(entity.getVocabularyCreated()).isEqualTo(2);
            assertThat(entity.getGrammarPointsCreated()).isEqualTo(1);
        }

        @Test
        @DisplayName("Lesson created via itemHelper.saveLesson with correct fields")
        void persistExtracted_lessonCreatedViaHelper() {
            CurriculumImport entity = makeImport(COURSE_ID);
            Course course = new Course();
            course.setId(COURSE_ID);
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));

            Lesson savedLesson = new Lesson();
            savedLesson.setId(UUID.randomUUID());
            given(itemHelper.saveLesson(any(Lesson.class))).willReturn(savedLesson);

            ExtractionResult result = resultWith(
                    List.of(extractedLesson("Grammar Basics")),
                    List.of(), List.of());

            service.persistExtractedContent(entity, List.of(result));

            ArgumentCaptor<Lesson> captor = ArgumentCaptor.forClass(Lesson.class);
            then(itemHelper).should().saveLesson(captor.capture());
            Lesson captured = captor.getValue();

            assertThat(captured.getCourse()).isSameAs(course);
            assertThat(captured.getImportId()).isEqualTo(IMPORT_ID);
            assertThat(captured.getTitle()).isEqualTo("Grammar Basics");
            assertThat(captured.getStatus()).isEqualTo(Lesson.ContentStatus.DRAFT);
            assertThat(captured.getProcessingStatus()).isEqualTo(Lesson.ProcessingStatus.COMPLETED);
            assertThat(entity.getLessonsCreated()).isEqualTo(1);
        }

        @Test
        @DisplayName("Lesson save fails -> skips vocab/grammar linking for that lesson")
        void persistExtracted_lessonSaveFails_vocabGrammarSkipped() {
            CurriculumImport entity = makeImport(COURSE_ID);
            Course course = new Course();
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));

            // itemHelper.saveLesson returns null (failure)
            given(itemHelper.saveLesson(any(Lesson.class))).willReturn(null);

            ExtractionResult result = resultWith(
                    List.of(extractedLesson("Bad Lesson")),
                    List.of(extractedVocab("食べる", "N5")),
                    List.of(extractedGrammar("〜ている", "N5")));

            service.persistExtractedContent(entity, List.of(result));

            assertThat(entity.getLessonsCreated()).isZero();
            // Vocab/grammar findOrCreate should NOT be called since lesson save failed
            then(itemHelper).should(never()).findOrCreateVocabulary(any(), any());
            then(itemHelper).should(never()).findOrCreateGrammarPoint(any(), any());
        }

        @Test
        @DisplayName("Vocab found/created and linked via itemHelper")
        void persistExtracted_vocabCreatedAndLinked() {
            CurriculumImport entity = makeImport(COURSE_ID);
            Course course = new Course();
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));

            UUID lessonId = UUID.randomUUID();
            Lesson savedLesson = new Lesson();
            savedLesson.setId(lessonId);
            given(itemHelper.saveLesson(any(Lesson.class))).willReturn(savedLesson);

            Vocabulary vocab = new Vocabulary();
            vocab.setId(UUID.randomUUID());
            given(itemHelper.findOrCreateVocabulary(any(), eq(MANAGER_ID))).willReturn(vocab);
            given(itemHelper.linkVocabularyToLesson(eq(lessonId), eq(vocab), anyInt(), anyBoolean()))
                    .willReturn(true);

            ExtractionResult result = resultWith(
                    List.of(extractedLesson("Vocab Lesson")),
                    List.of(extractedVocab("食べる", "N5")),
                    List.of());

            service.persistExtractedContent(entity, List.of(result));

            then(itemHelper).should().findOrCreateVocabulary(any(), eq(MANAGER_ID));
            then(itemHelper).should().linkVocabularyToLesson(eq(lessonId), eq(vocab), eq(0), anyBoolean());
        }

        @Test
        @DisplayName("Vocab returns null -> link skipped")
        void persistExtracted_vocabReturnsNull_linkSkipped() {
            CurriculumImport entity = makeImport(COURSE_ID);
            Course course = new Course();
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));

            Lesson savedLesson = new Lesson();
            savedLesson.setId(UUID.randomUUID());
            given(itemHelper.saveLesson(any(Lesson.class))).willReturn(savedLesson);
            given(itemHelper.findOrCreateVocabulary(any(), any())).willReturn(null);

            ExtractionResult result = resultWith(
                    List.of(extractedLesson("Lesson")),
                    List.of(extractedVocab("食べる", "N5")),
                    List.of());

            service.persistExtractedContent(entity, List.of(result));

            then(itemHelper).should(never()).linkVocabularyToLesson(any(), any(), anyInt(), anyBoolean());
        }

        @Test
        @DisplayName("Grammar found/created and linked via itemHelper")
        void persistExtracted_grammarCreatedAndLinked() {
            CurriculumImport entity = makeImport(COURSE_ID);
            Course course = new Course();
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));

            UUID lessonId = UUID.randomUUID();
            Lesson savedLesson = new Lesson();
            savedLesson.setId(lessonId);
            given(itemHelper.saveLesson(any(Lesson.class))).willReturn(savedLesson);

            GrammarPoint grammar = new GrammarPoint();
            grammar.setId(UUID.randomUUID());
            given(itemHelper.findOrCreateGrammarPoint(any(), eq(MANAGER_ID))).willReturn(grammar);
            given(itemHelper.linkGrammarToLesson(eq(lessonId), eq(grammar), anyInt(), anyBoolean()))
                    .willReturn(true);

            ExtractionResult result = resultWith(
                    List.of(extractedLesson("Grammar Lesson")),
                    List.of(),
                    List.of(extractedGrammar("〜ている", "N5")));

            service.persistExtractedContent(entity, List.of(result));

            then(itemHelper).should().findOrCreateGrammarPoint(any(), eq(MANAGER_ID));
            then(itemHelper).should().linkGrammarToLesson(eq(lessonId), eq(grammar), eq(0), anyBoolean());
        }

        @Test
        @DisplayName("Invalid lessonType -> falls back to LessonType.MIXED")
        void persistExtracted_invalidLessonType_fallsBackToMixed() {
            CurriculumImport entity = makeImport(COURSE_ID);
            Course course = new Course();
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));

            given(itemHelper.saveLesson(any(Lesson.class))).willAnswer(inv -> inv.getArgument(0));

            ExtractionResult.ExtractedLesson badTypeLesson = ExtractionResult.ExtractedLesson.builder()
                    .title("Unknown Type Lesson")
                    .lessonType("KANJI_ONLY") // invalid enum value
                    .pageStart(1).pageEnd(3).orderIndex(1)
                    .build();

            ExtractionResult result = resultWith(List.of(badTypeLesson), List.of(), List.of());

            service.persistExtractedContent(entity, List.of(result));

            ArgumentCaptor<Lesson> captor = ArgumentCaptor.forClass(Lesson.class);
            then(itemHelper).should().saveLesson(captor.capture());
            assertThat(captor.getValue().getLessonType()).isEqualTo(Lesson.LessonType.MIXED);
        }

        @Test
        @DisplayName("Multiple ExtractionResults -> vocab/grammar counts accumulate from raw sizes")
        void persistExtracted_multipleResults_countsAccumulate() {
            CurriculumImport entity = makeImport(null); // no course -> no lesson persisted
            ExtractionResult result1 = resultWith(List.of(),
                    List.of(extractedVocab("食べる", "N5"), extractedVocab("飲む", "N5")),
                    List.of(extractedGrammar("〜ている", "N5")));
            ExtractionResult result2 = resultWith(List.of(),
                    List.of(extractedVocab("走る", "N5")),
                    List.of(extractedGrammar("〜てみる", "N5"), extractedGrammar("〜てしまう", "N5")));

            service.persistExtractedContent(entity, List.of(result1, result2));

            // 2 + 1 = 3 vocab, 1 + 2 = 3 grammar
            assertThat(entity.getVocabularyCreated()).isEqualTo(3);
            assertThat(entity.getGrammarPointsCreated()).isEqualTo(3);
        }

        @Test
        @DisplayName("importRepository.save called with updated counts at end")
        void persistExtracted_importSavedWithUpdatedCounts() {
            CurriculumImport entity = makeImport(COURSE_ID);
            Course course = new Course();
            given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));

            ExtractionResult result = resultWith(
                    List.of(),
                    List.of(extractedVocab("食べる", "N5")),
                    List.of());

            service.persistExtractedContent(entity, List.of(result));

            then(importRepository).should().save(entity);
        }
    }
}
