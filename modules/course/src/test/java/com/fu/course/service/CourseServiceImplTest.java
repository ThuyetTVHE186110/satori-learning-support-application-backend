package com.fu.course.service;

import com.fu.course.dto.request.CreateCourseRequest;
import com.fu.course.dto.request.UpdateCourseRequest;
import com.fu.course.dto.response.CourseResponse;
import com.fu.course.dto.response.CourseSummaryResponse;
import com.fu.course.entity.Course;
import com.fu.course.entity.Course.ContentStatus;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.exception.CourseNameAlreadyExistsException;
import com.fu.course.exception.CourseNotFoundException;
import com.fu.course.mapper.CourseMapper;
import com.fu.course.repository.CourseRepository;
import com.fu.course.repository.LessonRepository;
import com.fu.course.service.CourseService.CourseStatistics;
import com.fu.course.service.impl.CourseServiceImpl;
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
 * Unit tests for CourseServiceImpl.
 * Covers: CRUD, publish/archive, soft-delete, search, statistics.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CourseService Unit Tests")
class CourseServiceImplTest {

    @Mock
    private CourseRepository courseRepository;

    @Mock
    private CourseMapper courseMapper;

    @Mock
    private LessonRepository lessonRepository;

    @InjectMocks
    private CourseServiceImpl courseService;

    @Captor
    private ArgumentCaptor<Course> courseCaptor;

    // Test fixtures
    private Course testCourse;
    private CourseResponse testCourseResponse;
    private CourseSummaryResponse testSummaryResponse;
    private CreateCourseRequest createRequest;
    private UpdateCourseRequest updateRequest;
    private UUID managerId;
    private UUID courseId;

    @BeforeEach
    void setUp() {
        managerId = UUID.randomUUID();
        courseId = UUID.randomUUID();

        testCourse = Course.builder()
                .name("Japanese N5 Course")
                .description("Beginner Japanese course for N5 level")
                .jlptLevel(JlptLevel.N5)
                .thumbnailUrl("https://example.com/thumb.jpg")
                .orderIndex(1)
                .status(ContentStatus.DRAFT)
                .build();
        ReflectionTestUtils.setField(testCourse, "id", courseId);

        testCourseResponse = CourseResponse.builder()
                .id(courseId)
                .name("Japanese N5 Course")
                .description("Beginner Japanese course for N5 level")
                .jlptLevel(JlptLevel.N5)
                .thumbnailUrl("https://example.com/thumb.jpg")
                .orderIndex(1)
                .status(ContentStatus.DRAFT)
                .lessonCount(0L)
                .studentCount(0L)
                .build();

        testSummaryResponse = CourseSummaryResponse.builder()
                .id(courseId)
                .name("Japanese N5 Course")
                .jlptLevel(JlptLevel.N5)
                .status(ContentStatus.DRAFT)
                .orderIndex(1)
                .build();

        createRequest = CreateCourseRequest.builder()
                .name("Japanese N5 Course")
                .description("Beginner Japanese course for N5 level")
                .jlptLevel(JlptLevel.N5)
                .thumbnailUrl("https://example.com/thumb.jpg")
                .orderIndex(1)
                .build();

        updateRequest = UpdateCourseRequest.builder()
                .name("Updated N5 Course")
                .description("Updated description")
                .build();
    }

    // =====================================================
    // CREATE COURSE
    // =====================================================

    @Nested
    @DisplayName("Create Course Tests")
    class CreateCourseTests {

        @Test
        @DisplayName("Should create course successfully")
        void shouldCreateCourseSuccessfully() {
            // Given
            given(courseRepository.existsByNameIgnoreCase(createRequest.getName())).willReturn(false);
            given(courseMapper.toEntity(createRequest)).willReturn(testCourse);
            given(courseRepository.save(any(Course.class))).willReturn(testCourse);
            given(courseMapper.toResponse(testCourse)).willReturn(testCourseResponse);

            // When
            CourseResponse result = courseService.createCourse(createRequest, managerId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("Japanese N5 Course");
            assertThat(result.getJlptLevel()).isEqualTo(JlptLevel.N5);

            verify(courseRepository).existsByNameIgnoreCase(createRequest.getName());
            verify(courseRepository).save(courseCaptor.capture());

            Course savedCourse = courseCaptor.getValue();
            assertThat(savedCourse.getCreatedBy()).isEqualTo(managerId);
            assertThat(savedCourse.getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should throw exception when course name already exists")
        void shouldThrowExceptionWhenNameExists() {
            // Given
            given(courseRepository.existsByNameIgnoreCase(createRequest.getName())).willReturn(true);

            // When/Then
            assertThatThrownBy(() -> courseService.createCourse(createRequest, managerId))
                    .isInstanceOf(CourseNameAlreadyExistsException.class)
                    .hasMessageContaining(createRequest.getName());

            verify(courseRepository, never()).save(any());
        }
    }

    // =====================================================
    // UPDATE COURSE
    // =====================================================

    @Nested
    @DisplayName("Update Course Tests")
    class UpdateCourseTests {

        @Test
        @DisplayName("Should update course successfully")
        void shouldUpdateCourseSuccessfully() {
            // Given
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
            given(courseRepository.existsByNameIgnoreCaseAndIdNot("Updated N5 Course", courseId)).willReturn(false);
            given(courseRepository.save(any(Course.class))).willReturn(testCourse);
            given(courseMapper.toResponse(testCourse)).willReturn(testCourseResponse);

            // When
            CourseResponse result = courseService.updateCourse(courseId, updateRequest, managerId);

            // Then
            assertThat(result).isNotNull();
            verify(courseMapper).updateEntity(testCourse, updateRequest);
            verify(courseRepository).save(courseCaptor.capture());
            assertThat(courseCaptor.getValue().getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should throw exception when course not found for update")
        void shouldThrowExceptionWhenCourseNotFound() {
            // Given
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> courseService.updateCourse(courseId, updateRequest, managerId))
                    .isInstanceOf(CourseNotFoundException.class);

            verify(courseRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw exception when new name already exists")
        void shouldThrowExceptionWhenNewNameExists() {
            // Given
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
            given(courseRepository.existsByNameIgnoreCaseAndIdNot("Updated N5 Course", courseId)).willReturn(true);

            // When/Then
            assertThatThrownBy(() -> courseService.updateCourse(courseId, updateRequest, managerId))
                    .isInstanceOf(CourseNameAlreadyExistsException.class);

            verify(courseRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should skip name-uniqueness check when name is null")
        void shouldSkipNameCheckWhenNull() {
            // Given
            UpdateCourseRequest requestWithoutName = UpdateCourseRequest.builder()
                    .description("Only update description")
                    .build();

            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
            given(courseRepository.save(any(Course.class))).willReturn(testCourse);
            given(courseMapper.toResponse(testCourse)).willReturn(testCourseResponse);

            // When
            courseService.updateCourse(courseId, requestWithoutName, managerId);

            // Then
            verify(courseRepository, never()).existsByNameIgnoreCaseAndIdNot(anyString(), any());
        }
    }

    // =====================================================
    // GET COURSE
    // =====================================================

    @Nested
    @DisplayName("Get Course Tests")
    class GetCourseTests {

        @Test
        @DisplayName("Should get course by ID successfully")
        void shouldGetCourseByIdSuccessfully() {
            // Given
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
            given(courseMapper.toResponse(testCourse)).willReturn(testCourseResponse);
            given(lessonRepository.countByCourseIdAndDeletedAtIsNull(courseId)).willReturn(0L);

            // When
            CourseResponse result = courseService.getCourseById(courseId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(courseId);
            assertThat(result.getLessonCount()).isEqualTo(0L);
            assertThat(result.getStudentCount()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should throw exception when course not found")
        void shouldThrowWhenCourseNotFound() {
            // Given
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> courseService.getCourseById(courseId))
                    .isInstanceOf(CourseNotFoundException.class);
        }
    }

    // =====================================================
    // LIST & SEARCH COURSES
    // =====================================================

    @Nested
    @DisplayName("List & Search Course Tests")
    class ListAndSearchTests {

        @Test
        @DisplayName("Should get all courses with pagination")
        void shouldGetAllCoursesWithPagination() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Course> coursePage = new PageImpl<>(List.of(testCourse), pageable, 1);
            given(courseRepository.findAll(pageable)).willReturn(coursePage);
            given(courseMapper.toSummaryResponse(testCourse)).willReturn(testSummaryResponse);

            // When
            Page<CourseSummaryResponse> result = courseService.getAllCourses(pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().getFirst().getName()).isEqualTo("Japanese N5 Course");
        }

        @Test
        @DisplayName("Should get courses by status")
        void shouldGetCoursesByStatus() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Course> coursePage = new PageImpl<>(List.of(testCourse), pageable, 1);
            given(courseRepository.findByStatusAndDeletedAtIsNull(ContentStatus.DRAFT, pageable)).willReturn(coursePage);
            given(courseMapper.toSummaryResponse(testCourse)).willReturn(testSummaryResponse);

            // When
            Page<CourseSummaryResponse> result = courseService.getCoursesByStatus(ContentStatus.DRAFT, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should get courses by JLPT level")
        void shouldGetCoursesByJlptLevel() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Course> coursePage = new PageImpl<>(List.of(testCourse), pageable, 1);
            given(courseRepository.findByJlptLevelAndDeletedAtIsNull(JlptLevel.N5, pageable)).willReturn(coursePage);
            given(courseMapper.toSummaryResponse(testCourse)).willReturn(testSummaryResponse);

            // When
            Page<CourseSummaryResponse> result = courseService.getCoursesByJlptLevel(JlptLevel.N5, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should get published courses")
        void shouldGetPublishedCourses() {
            // Given
            given(courseRepository.findPublishedCourses()).willReturn(List.of(testCourse));
            given(courseMapper.toSummaryResponse(testCourse)).willReturn(testSummaryResponse);

            // When
            List<CourseSummaryResponse> result = courseService.getPublishedCourses();

            // Then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("Should search courses by keyword")
        void shouldSearchCoursesByKeyword() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Course> coursePage = new PageImpl<>(List.of(testCourse), pageable, 1);
            given(courseRepository.searchByName("N5", pageable)).willReturn(coursePage);
            given(courseMapper.toSummaryResponse(testCourse)).willReturn(testSummaryResponse);

            // When
            Page<CourseSummaryResponse> result = courseService.searchCourses("N5", pageable);

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
        @DisplayName("Should publish course successfully")
        void shouldPublishCourseSuccessfully() {
            // Given
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
            given(courseRepository.save(any(Course.class))).willReturn(testCourse);

            CourseResponse publishedResponse = CourseResponse.builder()
                    .id(courseId)
                    .status(ContentStatus.PUBLISHED)
                    .build();
            given(courseMapper.toResponse(testCourse)).willReturn(publishedResponse);

            // When
            CourseResponse result = courseService.publishCourse(courseId, managerId);

            // Then
            assertThat(result.getStatus()).isEqualTo(ContentStatus.PUBLISHED);
            verify(courseRepository).save(courseCaptor.capture());
            assertThat(courseCaptor.getValue().getStatus()).isEqualTo(ContentStatus.PUBLISHED);
            assertThat(courseCaptor.getValue().getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should archive course successfully")
        void shouldArchiveCourseSuccessfully() {
            // Given
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
            given(courseRepository.save(any(Course.class))).willReturn(testCourse);

            CourseResponse archivedResponse = CourseResponse.builder()
                    .id(courseId)
                    .status(ContentStatus.ARCHIVED)
                    .build();
            given(courseMapper.toResponse(testCourse)).willReturn(archivedResponse);

            // When
            CourseResponse result = courseService.archiveCourse(courseId, managerId);

            // Then
            assertThat(result.getStatus()).isEqualTo(ContentStatus.ARCHIVED);
            verify(courseRepository).save(courseCaptor.capture());
            assertThat(courseCaptor.getValue().getStatus()).isEqualTo(ContentStatus.ARCHIVED);
        }

        @Test
        @DisplayName("Should soft-delete course successfully")
        void shouldSoftDeleteCourseSuccessfully() {
            // Given
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));

            // When
            courseService.deleteCourse(courseId, managerId);

            // Then
            verify(courseRepository).save(courseCaptor.capture());
            Course deleted = courseCaptor.getValue();
            assertThat(deleted.isDeleted()).isTrue();
            assertThat(deleted.getDeletedAt()).isNotNull();
            assertThat(deleted.getDeletedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should throw exception when deleting non-existent course")
        void shouldThrowWhenDeletingNonExistentCourse() {
            // Given
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> courseService.deleteCourse(courseId, managerId))
                    .isInstanceOf(CourseNotFoundException.class);
        }
    }

    // =====================================================
    // STATISTICS
    // =====================================================

    @Nested
    @DisplayName("Statistics Tests")
    class StatisticsTests {

        @Test
        @DisplayName("Should get course statistics")
        void shouldGetCourseStatistics() {
            // Given
            given(courseRepository.count()).willReturn(10L);
            given(courseRepository.countByStatusAndDeletedAtIsNull(ContentStatus.PUBLISHED)).willReturn(5L);
            given(courseRepository.countByStatusAndDeletedAtIsNull(ContentStatus.DRAFT)).willReturn(3L);
            given(courseRepository.countByStatusAndDeletedAtIsNull(ContentStatus.ARCHIVED)).willReturn(2L);
            given(courseRepository.countByJlptLevelAndDeletedAtIsNull(JlptLevel.N5)).willReturn(3L);
            given(courseRepository.countByJlptLevelAndDeletedAtIsNull(JlptLevel.N4)).willReturn(2L);
            given(courseRepository.countByJlptLevelAndDeletedAtIsNull(JlptLevel.N3)).willReturn(2L);
            given(courseRepository.countByJlptLevelAndDeletedAtIsNull(JlptLevel.N2)).willReturn(2L);
            given(courseRepository.countByJlptLevelAndDeletedAtIsNull(JlptLevel.N1)).willReturn(1L);

            // When
            CourseStatistics stats = courseService.getCourseStatistics();

            // Then
            assertThat(stats.totalCourses()).isEqualTo(10L);
            assertThat(stats.publishedCourses()).isEqualTo(5L);
            assertThat(stats.draftCourses()).isEqualTo(3L);
            assertThat(stats.archivedCourses()).isEqualTo(2L);
            assertThat(stats.coursesByN5()).isEqualTo(3L);
            assertThat(stats.coursesByN4()).isEqualTo(2L);
            assertThat(stats.coursesByN1()).isEqualTo(1L);
        }
    }

    // =====================================================
    // EXISTS BY NAME
    // =====================================================

    @Nested
    @DisplayName("Exists By Name Tests")
    class ExistsByNameTests {

        @Test
        @DisplayName("Should return true when name exists")
        void shouldReturnTrueWhenNameExists() {
            given(courseRepository.existsByNameIgnoreCase("Existing")).willReturn(true);
            assertThat(courseService.existsByName("Existing")).isTrue();
        }

        @Test
        @DisplayName("Should return false when name does not exist")
        void shouldReturnFalseWhenNameDoesNotExist() {
            given(courseRepository.existsByNameIgnoreCase("New")).willReturn(false);
            assertThat(courseService.existsByName("New")).isFalse();
        }
    }
}
