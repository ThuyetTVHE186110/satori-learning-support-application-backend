package com.fu.course.service;

import com.fu.common.exception.BusinessException;
import com.fu.course.dto.request.CreateLessonRequest;
import com.fu.course.dto.request.UpdateLessonRequest;
import com.fu.course.dto.response.LessonResponse;
import com.fu.course.dto.response.LessonSummaryResponse;
import com.fu.course.entity.Course;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.entity.Lesson;
import com.fu.course.entity.Lesson.ContentStatus;
import com.fu.course.entity.Lesson.LessonType;
import com.fu.course.entity.Lesson.ProcessingStatus;
import com.fu.course.exception.CourseNotFoundException;
import com.fu.course.exception.LessonNotFoundException;
import com.fu.course.mapper.LessonMapper;
import com.fu.course.repository.CourseRepository;
import com.fu.course.repository.LessonRepository;
import com.fu.course.service.LessonService.LessonStatistics;
import com.fu.course.service.impl.LessonServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Unit tests for LessonServiceImpl.
 * Covers: CRUD, publish/archive, soft-delete, search, statistics.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LessonService Unit Tests")
class LessonServiceImplTest {

    @Mock
    private LessonRepository lessonRepository;

    @Mock
    private CourseRepository courseRepository;

    @Mock
    private LessonMapper lessonMapper;

    @InjectMocks
    private LessonServiceImpl lessonService;

    @Captor
    private ArgumentCaptor<Lesson> lessonCaptor;

    // Test fixtures
    private Course testCourse;
    private Lesson testLesson;
    private LessonResponse testLessonResponse;
    private LessonSummaryResponse testSummaryResponse;
    private CreateLessonRequest createRequest;
    private UpdateLessonRequest updateRequest;
    private UUID managerId;
    private UUID courseId;
    private UUID lessonId;

    @BeforeEach
    void setUp() {
        managerId = UUID.randomUUID();
        courseId = UUID.randomUUID();
        lessonId = UUID.randomUUID();

        testCourse = Course.builder()
                .name("Japanese N5 Course")
                .jlptLevel(JlptLevel.N5)
                .status(Course.CourseStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(testCourse, "id", courseId);

        testLesson = Lesson.builder()
                .course(testCourse)
                .title("Hiragana Basics")
                .titleJapanese("ひらがな基礎")
                .description("Learn basic Hiragana characters")
                .lessonType(LessonType.VOCABULARY)
                .orderIndex(1)
                .estimatedDurationMinutes(30)
                .status(ContentStatus.DRAFT)
                .processingStatus(ProcessingStatus.COMPLETED)
                .build();
        ReflectionTestUtils.setField(testLesson, "id", lessonId);

        testLessonResponse = LessonResponse.builder()
                .id(lessonId)
                .courseId(courseId)
                .courseName("Japanese N5 Course")
                .title("Hiragana Basics")
                .titleJapanese("ひらがな基礎")
                .lessonType(LessonType.VOCABULARY)
                .orderIndex(1)
                .estimatedDurationMinutes(30)
                .status(ContentStatus.DRAFT)
                .processingStatus(ProcessingStatus.COMPLETED)
                .vocabularyCount(0L)
                .grammarPointCount(0L)
                .build();

        testSummaryResponse = LessonSummaryResponse.builder()
                .id(lessonId)
                .courseId(courseId)
                .title("Hiragana Basics")
                .lessonType(LessonType.VOCABULARY)
                .orderIndex(1)
                .status(ContentStatus.DRAFT)
                .build();

        createRequest = CreateLessonRequest.builder()
                .courseId(courseId)
                .title("Hiragana Basics")
                .titleJapanese("ひらがな基礎")
                .description("Learn basic Hiragana characters")
                .lessonType(LessonType.VOCABULARY)
                .orderIndex(1)
                .estimatedDurationMinutes(30)
                .build();

        updateRequest = UpdateLessonRequest.builder()
                .title("Hiragana Advanced")
                .description("Updated description")
                .build();
    }

    // =====================================================
    // CREATE LESSON
    // =====================================================

    @Nested
    @DisplayName("Create Lesson Tests")
    class CreateLessonTests {

        @Test
        @DisplayName("Should create lesson successfully")
        void shouldCreateLessonSuccessfully() {
            // Given
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
            given(lessonMapper.toEntity(createRequest)).willReturn(testLesson);
            given(lessonRepository.save(any(Lesson.class))).willReturn(testLesson);
            given(lessonMapper.toResponse(testLesson)).willReturn(testLessonResponse);

            // When
            LessonResponse result = lessonService.createLesson(createRequest, managerId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getTitle()).isEqualTo("Hiragana Basics");
            assertThat(result.getLessonType()).isEqualTo(LessonType.VOCABULARY);

            verify(courseRepository).findByIdAndNotDeleted(courseId);
            verify(lessonRepository).save(lessonCaptor.capture());

            Lesson savedLesson = lessonCaptor.getValue();
            assertThat(savedLesson.getCourse()).isEqualTo(testCourse);
            assertThat(savedLesson.getCreatedBy()).isEqualTo(managerId);
            assertThat(savedLesson.getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should throw exception when course not found")
        void shouldThrowExceptionWhenCourseNotFound() {
            // Given
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> lessonService.createLesson(createRequest, managerId))
                    .isInstanceOf(CourseNotFoundException.class);

            verify(lessonRepository, never()).save(any());
        }
    }

    // =====================================================
    // UPDATE LESSON
    // =====================================================

    @Nested
    @DisplayName("Update Lesson Tests")
    class UpdateLessonTests {

        @Test
        @DisplayName("Should update lesson successfully")
        void shouldUpdateLessonSuccessfully() {
            // Given
            given(lessonRepository.findByIdAndNotDeleted(lessonId)).willReturn(Optional.of(testLesson));
            given(lessonRepository.existsByTitleIgnoreCaseAndCourseIdAndIdNot("Hiragana Advanced", courseId, lessonId))
                    .willReturn(false);
            given(lessonRepository.save(any(Lesson.class))).willReturn(testLesson);
            given(lessonMapper.toResponse(testLesson)).willReturn(testLessonResponse);

            // When
            LessonResponse result = lessonService.updateLesson(lessonId, updateRequest, managerId);

            // Then
            assertThat(result).isNotNull();
            verify(lessonMapper).updateEntity(testLesson, updateRequest);
            verify(lessonRepository).save(lessonCaptor.capture());
            assertThat(lessonCaptor.getValue().getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should throw exception when lesson not found for update")
        void shouldThrowExceptionWhenLessonNotFound() {
            // Given
            given(lessonRepository.findByIdAndNotDeleted(lessonId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> lessonService.updateLesson(lessonId, updateRequest, managerId))
                    .isInstanceOf(LessonNotFoundException.class);

            verify(lessonRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw exception when duplicate title in same course")
        void shouldThrowExceptionWhenDuplicateTitle() {
            // Given
            given(lessonRepository.findByIdAndNotDeleted(lessonId)).willReturn(Optional.of(testLesson));
            given(lessonRepository.existsByTitleIgnoreCaseAndCourseIdAndIdNot("Hiragana Advanced", courseId, lessonId))
                    .willReturn(true);

            // When/Then
            assertThatThrownBy(() -> lessonService.updateLesson(lessonId, updateRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already exists in this course");

            verify(lessonRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should skip title uniqueness check when title is null")
        void shouldSkipTitleCheckWhenNull() {
            // Given
            UpdateLessonRequest requestWithoutTitle = UpdateLessonRequest.builder()
                    .description("Only update description")
                    .build();

            given(lessonRepository.findByIdAndNotDeleted(lessonId)).willReturn(Optional.of(testLesson));
            given(lessonRepository.save(any(Lesson.class))).willReturn(testLesson);
            given(lessonMapper.toResponse(testLesson)).willReturn(testLessonResponse);

            // When
            lessonService.updateLesson(lessonId, requestWithoutTitle, managerId);

            // Then
            verify(lessonRepository, never()).existsByTitleIgnoreCaseAndCourseIdAndIdNot(anyString(), any(), any());
        }
    }

    // =====================================================
    // GET LESSON
    // =====================================================

    @Nested
    @DisplayName("Get Lesson Tests")
    class GetLessonTests {

        @Test
        @DisplayName("Should get lesson by ID successfully")
        void shouldGetLessonByIdSuccessfully() {
            // Given
            given(lessonRepository.findByIdAndNotDeleted(lessonId)).willReturn(Optional.of(testLesson));
            given(lessonMapper.toResponse(testLesson)).willReturn(testLessonResponse);

            // When
            LessonResponse result = lessonService.getLessonById(lessonId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(lessonId);
            assertThat(result.getVocabularyCount()).isEqualTo(0L);
            assertThat(result.getGrammarPointCount()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should throw exception when lesson not found")
        void shouldThrowWhenLessonNotFound() {
            // Given
            given(lessonRepository.findByIdAndNotDeleted(lessonId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> lessonService.getLessonById(lessonId))
                    .isInstanceOf(LessonNotFoundException.class);
        }
    }

    // =====================================================
    // LIST & SEARCH LESSONS
    // =====================================================

    @Nested
    @DisplayName("List & Search Lesson Tests")
    class ListAndSearchTests {

        @Test
        @DisplayName("Should get lessons by course ID")
        void shouldGetLessonsByCourseId() {
            // Given
            given(lessonRepository.findByCourseIdAndNotDeleted(courseId)).willReturn(List.of(testLesson));
            given(lessonMapper.toSummaryResponse(testLesson)).willReturn(testSummaryResponse);

            // When
            List<LessonSummaryResponse> result = lessonService.getLessonsByCourseId(courseId);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().getTitle()).isEqualTo("Hiragana Basics");
        }

        @Test
        @DisplayName("Should get lessons by course ID with pagination")
        void shouldGetLessonsByCourseIdWithPagination() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Lesson> lessonPage = new PageImpl<>(List.of(testLesson), pageable, 1);
            given(lessonRepository.findByCourseIdAndNotDeleted(courseId, pageable)).willReturn(lessonPage);
            given(lessonMapper.toSummaryResponse(testLesson)).willReturn(testSummaryResponse);

            // When
            Page<LessonSummaryResponse> result = lessonService.getLessonsByCourseId(courseId, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should get published lessons by course ID")
        void shouldGetPublishedLessons() {
            // Given
            given(lessonRepository.findPublishedByCourseId(courseId)).willReturn(List.of(testLesson));
            given(lessonMapper.toSummaryResponse(testLesson)).willReturn(testSummaryResponse);

            // When
            List<LessonSummaryResponse> result = lessonService.getPublishedLessonsByCourseId(courseId);

            // Then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("Should search lessons by keyword")
        void shouldSearchLessonsByKeyword() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Lesson> lessonPage = new PageImpl<>(List.of(testLesson), pageable, 1);
            given(lessonRepository.searchByTitle("Hiragana", pageable)).willReturn(lessonPage);
            given(lessonMapper.toSummaryResponse(testLesson)).willReturn(testSummaryResponse);

            // When
            Page<LessonSummaryResponse> result = lessonService.searchLessons("Hiragana", pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should search lessons within a specific course")
        void shouldSearchLessonsInCourse() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Lesson> lessonPage = new PageImpl<>(List.of(testLesson), pageable, 1);
            given(lessonRepository.searchByTitleInCourse(courseId, "Hiragana", pageable)).willReturn(lessonPage);
            given(lessonMapper.toSummaryResponse(testLesson)).willReturn(testSummaryResponse);

            // When
            Page<LessonSummaryResponse> result = lessonService.searchLessonsInCourse(courseId, "Hiragana", pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
        }
    }

    // =====================================================
    // PUBLISH / ARCHIVE / DELETE
    // =====================================================

    @Nested
    @DisplayName("Status Management Tests")
    class StatusManagementTests {

        @Test
        @DisplayName("Should publish lesson successfully")
        void shouldPublishLessonSuccessfully() {
            // Given
            given(lessonRepository.findByIdAndNotDeleted(lessonId)).willReturn(Optional.of(testLesson));
            given(lessonRepository.save(any(Lesson.class))).willReturn(testLesson);

            LessonResponse publishedResponse = LessonResponse.builder()
                    .id(lessonId)
                    .status(ContentStatus.PUBLISHED)
                    .build();
            given(lessonMapper.toResponse(testLesson)).willReturn(publishedResponse);

            // When
            LessonResponse result = lessonService.publishLesson(lessonId, managerId);

            // Then
            assertThat(result.getStatus()).isEqualTo(ContentStatus.PUBLISHED);
            verify(lessonRepository).save(lessonCaptor.capture());
            assertThat(lessonCaptor.getValue().getStatus()).isEqualTo(ContentStatus.PUBLISHED);
            assertThat(lessonCaptor.getValue().getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should archive lesson successfully")
        void shouldArchiveLessonSuccessfully() {
            // Given
            given(lessonRepository.findByIdAndNotDeleted(lessonId)).willReturn(Optional.of(testLesson));
            given(lessonRepository.save(any(Lesson.class))).willReturn(testLesson);

            LessonResponse archivedResponse = LessonResponse.builder()
                    .id(lessonId)
                    .status(ContentStatus.ARCHIVED)
                    .build();
            given(lessonMapper.toResponse(testLesson)).willReturn(archivedResponse);

            // When
            LessonResponse result = lessonService.archiveLesson(lessonId, managerId);

            // Then
            assertThat(result.getStatus()).isEqualTo(ContentStatus.ARCHIVED);
            verify(lessonRepository).save(lessonCaptor.capture());
            assertThat(lessonCaptor.getValue().getStatus()).isEqualTo(ContentStatus.ARCHIVED);
        }

        @Test
        @DisplayName("Should soft-delete lesson successfully")
        void shouldSoftDeleteLessonSuccessfully() {
            // Given
            given(lessonRepository.findByIdAndNotDeleted(lessonId)).willReturn(Optional.of(testLesson));

            // When
            lessonService.deleteLesson(lessonId, managerId);

            // Then
            verify(lessonRepository).save(lessonCaptor.capture());
            Lesson deleted = lessonCaptor.getValue();
            assertThat(deleted.isDeleted()).isTrue();
            assertThat(deleted.getDeletedAt()).isNotNull();
            assertThat(deleted.getDeletedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should throw exception when deleting non-existent lesson")
        void shouldThrowWhenDeletingNonExistentLesson() {
            // Given
            given(lessonRepository.findByIdAndNotDeleted(lessonId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> lessonService.deleteLesson(lessonId, managerId))
                    .isInstanceOf(LessonNotFoundException.class);
        }
    }

    // =====================================================
    // COUNTING & STATISTICS
    // =====================================================

    @Nested
    @DisplayName("Counting & Statistics Tests")
    class CountingAndStatisticsTests {

        @Test
        @DisplayName("Should count lessons by course ID")
        void shouldCountLessonsByCourseId() {
            // Given
            given(lessonRepository.countByCourseIdAndDeletedAtIsNull(courseId)).willReturn(5L);

            // When
            long count = lessonService.countLessonsByCourseId(courseId);

            // Then
            assertThat(count).isEqualTo(5L);
        }

        @Test
        @DisplayName("Should get lesson statistics for a course")
        void shouldGetLessonStatistics() {
            // Given
            given(lessonRepository.countByCourseIdAndDeletedAtIsNull(courseId)).willReturn(10L);
            given(lessonRepository.countByCourseIdAndStatusAndDeletedAtIsNull(courseId, ContentStatus.PUBLISHED)).willReturn(5L);
            given(lessonRepository.countByCourseIdAndStatusAndDeletedAtIsNull(courseId, ContentStatus.DRAFT)).willReturn(3L);
            given(lessonRepository.countByCourseIdAndStatusAndDeletedAtIsNull(courseId, ContentStatus.ARCHIVED)).willReturn(2L);

            // When
            LessonStatistics stats = lessonService.getLessonStatistics(courseId);

            // Then
            assertThat(stats.totalLessons()).isEqualTo(10L);
            assertThat(stats.publishedLessons()).isEqualTo(5L);
            assertThat(stats.draftLessons()).isEqualTo(3L);
            assertThat(stats.archivedLessons()).isEqualTo(2L);
        }
    }
}
