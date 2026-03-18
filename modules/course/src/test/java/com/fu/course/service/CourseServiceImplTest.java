package com.fu.course.service;

import com.fu.common.exception.BusinessException;
import com.fu.course.dto.request.CreateCourseRequest;
import com.fu.course.dto.request.UpdateCourseRequest;
import com.fu.course.dto.response.CourseResponse;
import com.fu.course.dto.response.CourseSummaryResponse;
import com.fu.course.entity.Course;
import com.fu.course.entity.Course.CourseStatus;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.exception.CourseNameAlreadyExistsException;
import com.fu.course.exception.CourseNotFoundException;
import com.fu.course.mapper.CourseMapper;
import com.fu.course.repository.CourseRepository;
import com.fu.course.repository.LessonRepository;
import com.fu.course.service.CourseService.CourseStatistics;
import com.fu.course.service.impl.CourseServiceImpl;
import com.fu.infrastructure.cloudinary.CloudinaryService;
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
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockMultipartFile;
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

    @Mock
    private CloudinaryService cloudinaryService;

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
                .status(Course.CourseStatus.INACTIVE)
                .build();
        ReflectionTestUtils.setField(testCourse, "id", courseId);

        testCourseResponse = CourseResponse.builder()
                .id(courseId)
                .name("Japanese N5 Course")
                .description("Beginner Japanese course for N5 level")
                .jlptLevel(JlptLevel.N5)
                .thumbnailUrl("https://example.com/thumb.jpg")
                .orderIndex(1)
                .status(CourseStatus.INACTIVE)
                .lessonCount(0L)
                .studentCount(0L)
                .build();

        testSummaryResponse = CourseSummaryResponse.builder()
                .id(courseId)
                .name("Japanese N5 Course")
                .jlptLevel(JlptLevel.N5)
                .status(CourseStatus.INACTIVE)
                .orderIndex(1)
                .build();

        createRequest = CreateCourseRequest.builder()
                .name("Japanese N5 Course")
                .description("Beginner Japanese course for N5 level")
                .jlptLevel(JlptLevel.N5)
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
        @DisplayName("Should create course successfully without thumbnail")
        void shouldCreateCourseSuccessfullyWithoutThumbnail() {
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
            verify(cloudinaryService, never()).uploadImage(any(), any());

            Course savedCourse = courseCaptor.getValue();
            assertThat(savedCourse.getCreatedBy()).isEqualTo(managerId);
            assertThat(savedCourse.getUpdatedBy()).isEqualTo(managerId);
            assertThat(savedCourse.getThumbnailUrl()).isNull();
        }

        @Test
        @DisplayName("Should create course successfully with thumbnail upload")
        void shouldCreateCourseSuccessfullyWithThumbnail() {
            // Given
            MockMultipartFile thumbnailFile = new MockMultipartFile(
                    "thumbnail", "thumb.jpg", "image/jpeg", new byte[1024]);
            String uploadedUrl = "https://res.cloudinary.com/test/image/upload/v1/courses/thumbnails/thumb.jpg";

            given(courseRepository.existsByNameIgnoreCase(createRequest.getName())).willReturn(false);
            given(cloudinaryService.uploadImage(thumbnailFile, "courses/thumbnails")).willReturn(uploadedUrl);
            given(courseMapper.toEntity(createRequest)).willReturn(testCourse);
            given(courseRepository.save(any(Course.class))).willReturn(testCourse);
            given(courseMapper.toResponse(testCourse)).willReturn(testCourseResponse);

            // When
            CourseResponse result = courseService.createCourse(createRequest, thumbnailFile, managerId);

            // Then
            assertThat(result).isNotNull();

            verify(cloudinaryService).uploadImage(thumbnailFile, "courses/thumbnails");
            verify(courseRepository).save(courseCaptor.capture());

            Course savedCourse = courseCaptor.getValue();
            assertThat(savedCourse.getThumbnailUrl()).isEqualTo(uploadedUrl);
            assertThat(savedCourse.getCreatedBy()).isEqualTo(managerId);
            assertThat(savedCourse.getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should create course with null thumbnail (backward compatible)")
        void shouldCreateCourseWithNullThumbnail() {
            // Given
            given(courseRepository.existsByNameIgnoreCase(createRequest.getName())).willReturn(false);
            given(courseMapper.toEntity(createRequest)).willReturn(testCourse);
            given(courseRepository.save(any(Course.class))).willReturn(testCourse);
            given(courseMapper.toResponse(testCourse)).willReturn(testCourseResponse);

            // When
            CourseResponse result = courseService.createCourse(createRequest, null, managerId);

            // Then
            assertThat(result).isNotNull();
            verify(cloudinaryService, never()).uploadImage(any(), any());

            verify(courseRepository).save(courseCaptor.capture());
            assertThat(courseCaptor.getValue().getThumbnailUrl()).isNull();
        }

        @Test
        @DisplayName("Should skip upload for empty thumbnail file")
        void shouldSkipUploadForEmptyThumbnail() {
            // Given
            MockMultipartFile emptyFile = new MockMultipartFile(
                    "thumbnail", "empty.jpg", "image/jpeg", new byte[0]);

            given(courseRepository.existsByNameIgnoreCase(createRequest.getName())).willReturn(false);
            given(courseMapper.toEntity(createRequest)).willReturn(testCourse);
            given(courseRepository.save(any(Course.class))).willReturn(testCourse);
            given(courseMapper.toResponse(testCourse)).willReturn(testCourseResponse);

            // When
            CourseResponse result = courseService.createCourse(createRequest, emptyFile, managerId);

            // Then
            assertThat(result).isNotNull();
            verify(cloudinaryService, never()).uploadImage(any(), any());

            verify(courseRepository).save(courseCaptor.capture());
            assertThat(courseCaptor.getValue().getThumbnailUrl()).isNull();
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
            verify(cloudinaryService, never()).uploadImage(any(), any());
        }

        @Test
        @DisplayName("Should throw exception when name exists even with thumbnail provided")
        void shouldThrowExceptionWhenNameExistsWithThumbnail() {
            // Given
            MockMultipartFile thumbnailFile = new MockMultipartFile(
                    "thumbnail", "thumb.jpg", "image/jpeg", new byte[1024]);

            given(courseRepository.existsByNameIgnoreCase(createRequest.getName())).willReturn(true);

            // When/Then
            assertThatThrownBy(() -> courseService.createCourse(createRequest, thumbnailFile, managerId))
                    .isInstanceOf(CourseNameAlreadyExistsException.class);

            verify(courseRepository, never()).save(any());
            verify(cloudinaryService, never()).uploadImage(any(), any());
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
            CourseResponse result = courseService.updateCourse(courseId, updateRequest, null, managerId);

            // Then
            assertThat(result).isNotNull();
            verify(courseMapper).updateEntity(testCourse, updateRequest);
            verify(courseRepository).save(courseCaptor.capture());
            assertThat(courseCaptor.getValue().getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should update course successfully with new thumbnail")
        void shouldUpdateCourseSuccessfullyWithNewThumbnail() {
            // Given
            MockMultipartFile thumbnailFile = new MockMultipartFile(
                    "thumbnail", "new-thumb.jpg", "image/jpeg", new byte[1024]);
            String uploadedUrl = "https://res.cloudinary.com/test/image/upload/v1/courses/thumbnails/new-thumb.jpg";

            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
            given(courseRepository.existsByNameIgnoreCaseAndIdNot("Updated N5 Course", courseId)).willReturn(false);
            given(cloudinaryService.uploadImage(thumbnailFile, "courses/thumbnails")).willReturn(uploadedUrl);
            given(cloudinaryService.extractPublicId(testCourse.getThumbnailUrl())).willReturn("old-thumb-id");
            // deleteImage is void, just need to verify it's called
            given(courseRepository.save(any(Course.class))).willReturn(testCourse);
            
            CourseResponse updatedResponse = CourseResponse.builder()
                .id(courseId)
                .name("Updated N5 Course")
                .thumbnailUrl(uploadedUrl)
                .build();
            given(courseMapper.toResponse(testCourse)).willReturn(updatedResponse);

            // When
            CourseResponse result = courseService.updateCourse(courseId, updateRequest, thumbnailFile, managerId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getThumbnailUrl()).isEqualTo(uploadedUrl);
            
            verify(cloudinaryService).uploadImage(thumbnailFile, "courses/thumbnails");
            verify(cloudinaryService).extractPublicId("https://example.com/thumb.jpg");
            verify(cloudinaryService).deleteImage("old-thumb-id");
            verify(courseMapper).updateEntity(testCourse, updateRequest);
            verify(courseRepository).save(courseCaptor.capture());
            assertThat(courseCaptor.getValue().getThumbnailUrl()).isEqualTo(uploadedUrl);
            assertThat(courseCaptor.getValue().getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should throw exception when course not found for update")
        void shouldThrowExceptionWhenCourseNotFound() {
            // Given
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> courseService.updateCourse(courseId, updateRequest, null, managerId))
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
            assertThatThrownBy(() -> courseService.updateCourse(courseId, updateRequest, null, managerId))
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
            courseService.updateCourse(courseId, requestWithoutName, null, managerId);

            // Then
            verify(courseRepository, never()).existsByNameIgnoreCaseAndIdNot(anyString(), any());
        }

        @Test
        @DisplayName("Should update course status to ACTIVE inline")
        void shouldUpdateStatusToActive() {
            // Given
            UpdateCourseRequest statusRequest = UpdateCourseRequest.builder()
                    .status(CourseStatus.ACTIVE)
                    .build();

            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
            given(courseRepository.save(any(Course.class))).willReturn(testCourse);

            CourseResponse activeResponse = CourseResponse.builder()
                    .id(courseId)
                    .status(CourseStatus.ACTIVE)
                    .build();
            given(courseMapper.toResponse(testCourse)).willReturn(activeResponse);

            // When
            CourseResponse result = courseService.updateCourse(courseId, statusRequest, null, managerId);

            // Then
            assertThat(result.getStatus()).isEqualTo(CourseStatus.ACTIVE);
            verify(courseMapper).updateEntity(testCourse, statusRequest);
            verify(courseRepository).save(courseCaptor.capture());
            assertThat(courseCaptor.getValue().getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should update course status to INACTIVE inline")
        void shouldUpdateStatusToInactive() {
            // Given
            testCourse.setStatus(CourseStatus.ACTIVE); // start active
            UpdateCourseRequest statusRequest = UpdateCourseRequest.builder()
                    .status(CourseStatus.INACTIVE)
                    .build();

            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
            given(courseRepository.save(any(Course.class))).willReturn(testCourse);

            CourseResponse inactiveResponse = CourseResponse.builder()
                    .id(courseId)
                    .status(CourseStatus.INACTIVE)
                    .build();
            given(courseMapper.toResponse(testCourse)).willReturn(inactiveResponse);

            // When
            CourseResponse result = courseService.updateCourse(courseId, statusRequest, null, managerId);

            // Then
            assertThat(result.getStatus()).isEqualTo(CourseStatus.INACTIVE);
            verify(courseMapper).updateEntity(testCourse, statusRequest);
            verify(courseRepository).save(any(Course.class));
        }

        @Test
        @DisplayName("Should reject updating status to DELETED")
        void shouldRejectUpdatingStatusToDeleted() {
            // Given
            UpdateCourseRequest deletedRequest = UpdateCourseRequest.builder()
                    .status(CourseStatus.DELETED)
                    .build();

            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));

            // When/Then
            assertThatThrownBy(() -> courseService.updateCourse(courseId, deletedRequest, null, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Không thể cập nhật trạng thái thành DELETED");

            verify(courseRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should update name and status together")
        void shouldUpdateNameAndStatusTogether() {
            // Given
            UpdateCourseRequest combinedRequest = UpdateCourseRequest.builder()
                    .name("New Course Name")
                    .status(CourseStatus.ACTIVE)
                    .build();

            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
            given(courseRepository.existsByNameIgnoreCaseAndIdNot("New Course Name", courseId)).willReturn(false);
            given(courseRepository.save(any(Course.class))).willReturn(testCourse);

            CourseResponse updatedResponse = CourseResponse.builder()
                    .id(courseId)
                    .name("New Course Name")
                    .status(CourseStatus.ACTIVE)
                    .build();
            given(courseMapper.toResponse(testCourse)).willReturn(updatedResponse);

            // When
            CourseResponse result = courseService.updateCourse(courseId, combinedRequest, null, managerId);

            // Then
            assertThat(result.getName()).isEqualTo("New Course Name");
            assertThat(result.getStatus()).isEqualTo(CourseStatus.ACTIVE);
            verify(courseMapper).updateEntity(testCourse, combinedRequest);
            verify(courseRepository).save(courseCaptor.capture());
            assertThat(courseCaptor.getValue().getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should allow update with null status (no status change)")
        void shouldAllowNullStatus() {
            // Given
            UpdateCourseRequest noStatusRequest = UpdateCourseRequest.builder()
                    .description("Updated description only")
                    .build();

            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
            given(courseRepository.save(any(Course.class))).willReturn(testCourse);
            given(courseMapper.toResponse(testCourse)).willReturn(testCourseResponse);

            // When
            CourseResponse result = courseService.updateCourse(courseId, noStatusRequest, null, managerId);

            // Then
            assertThat(result).isNotNull();
            verify(courseMapper).updateEntity(testCourse, noStatusRequest);
            verify(courseRepository).save(any(Course.class));
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
            given(courseRepository.findByStatusAndDeletedAtIsNull(CourseStatus.INACTIVE, pageable)).willReturn(coursePage);
            given(courseMapper.toSummaryResponse(testCourse)).willReturn(testSummaryResponse);

            // When
            Page<CourseSummaryResponse> result = courseService.getCoursesByStatus(Course.CourseStatus.INACTIVE, pageable);

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
        @DisplayName("Should get active courses")
        void shouldGetActiveCourses() {
            // Given
            given(courseRepository.findActiveCourses()).willReturn(List.of(testCourse));
            given(courseMapper.toSummaryResponse(testCourse)).willReturn(testSummaryResponse);

            // When
            List<CourseSummaryResponse> result = courseService.getActiveCourses();

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

        @Test
        @DisplayName("Should filter courses with no filters (returns all non-deleted, non-DELETED-status)")
        @SuppressWarnings("unchecked")
        void shouldFilterCoursesWithNoFilters() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Course> coursePage = new PageImpl<>(List.of(testCourse), pageable, 1);
            given(courseRepository.findAll(any(Specification.class), eq(pageable))).willReturn(coursePage);
            given(courseMapper.toSummaryResponse(testCourse)).willReturn(testSummaryResponse);

            // When
            Page<CourseSummaryResponse> result = courseService.filterCourses(null, null, null, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().getFirst().getName()).isEqualTo("Japanese N5 Course");
            verify(courseRepository).findAll(any(Specification.class), eq(pageable));
        }

        @Test
        @DisplayName("Should filter courses by status only")
        @SuppressWarnings("unchecked")
        void shouldFilterCoursesByStatusOnly() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Course> coursePage = new PageImpl<>(List.of(testCourse), pageable, 1);
            given(courseRepository.findAll(any(Specification.class), eq(pageable))).willReturn(coursePage);
            given(courseMapper.toSummaryResponse(testCourse)).willReturn(testSummaryResponse);

            // When
            Page<CourseSummaryResponse> result = courseService.filterCourses(CourseStatus.INACTIVE, null, null, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
            verify(courseRepository).findAll(any(Specification.class), eq(pageable));
        }

        @Test
        @DisplayName("Should filter courses by JLPT level only")
        @SuppressWarnings("unchecked")
        void shouldFilterCoursesByLevelOnly() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Course> coursePage = new PageImpl<>(List.of(testCourse), pageable, 1);
            given(courseRepository.findAll(any(Specification.class), eq(pageable))).willReturn(coursePage);
            given(courseMapper.toSummaryResponse(testCourse)).willReturn(testSummaryResponse);

            // When
            Page<CourseSummaryResponse> result = courseService.filterCourses(null, JlptLevel.N5, null, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
            verify(courseRepository).findAll(any(Specification.class), eq(pageable));
        }

        @Test
        @DisplayName("Should filter courses by keyword only")
        @SuppressWarnings("unchecked")
        void shouldFilterCoursesByKeywordOnly() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Course> coursePage = new PageImpl<>(List.of(testCourse), pageable, 1);
            given(courseRepository.findAll(any(Specification.class), eq(pageable))).willReturn(coursePage);
            given(courseMapper.toSummaryResponse(testCourse)).willReturn(testSummaryResponse);

            // When
            Page<CourseSummaryResponse> result = courseService.filterCourses(null, null, "N5", pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
            verify(courseRepository).findAll(any(Specification.class), eq(pageable));
        }

        @Test
        @DisplayName("Should filter courses by status and level combined")
        @SuppressWarnings("unchecked")
        void shouldFilterCoursesByStatusAndLevel() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Course> coursePage = new PageImpl<>(List.of(testCourse), pageable, 1);
            given(courseRepository.findAll(any(Specification.class), eq(pageable))).willReturn(coursePage);
            given(courseMapper.toSummaryResponse(testCourse)).willReturn(testSummaryResponse);

            // When
            Page<CourseSummaryResponse> result = courseService.filterCourses(CourseStatus.INACTIVE, JlptLevel.N5, null, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
            verify(courseRepository).findAll(any(Specification.class), eq(pageable));
        }

        @Test
        @DisplayName("Should filter courses by all criteria combined")
        @SuppressWarnings("unchecked")
        void shouldFilterCoursesByAllCriteria() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Course> coursePage = new PageImpl<>(List.of(testCourse), pageable, 1);
            given(courseRepository.findAll(any(Specification.class), eq(pageable))).willReturn(coursePage);
            given(courseMapper.toSummaryResponse(testCourse)).willReturn(testSummaryResponse);

            // When
            Page<CourseSummaryResponse> result = courseService.filterCourses(CourseStatus.INACTIVE, JlptLevel.N5, "Japanese", pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().getFirst().getName()).isEqualTo("Japanese N5 Course");
            verify(courseRepository).findAll(any(Specification.class), eq(pageable));
        }

        @Test
        @DisplayName("Should return empty page when no courses match filters")
        @SuppressWarnings("unchecked")
        void shouldReturnEmptyPageWhenNoMatch() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Course> emptyPage = new PageImpl<>(List.of(), pageable, 0);
            given(courseRepository.findAll(any(Specification.class), eq(pageable))).willReturn(emptyPage);

            // When
            Page<CourseSummaryResponse> result = courseService.filterCourses(CourseStatus.ACTIVE, JlptLevel.N1, "nonexistent", pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(0);
            assertThat(result.getContent()).isEmpty();
            verify(courseRepository).findAll(any(Specification.class), eq(pageable));
        }

        @Test
        @DisplayName("Should filter courses by status and keyword combined")
        @SuppressWarnings("unchecked")
        void shouldFilterCoursesByStatusAndKeyword() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Course> coursePage = new PageImpl<>(List.of(testCourse), pageable, 1);
            given(courseRepository.findAll(any(Specification.class), eq(pageable))).willReturn(coursePage);
            given(courseMapper.toSummaryResponse(testCourse)).willReturn(testSummaryResponse);

            // When
            Page<CourseSummaryResponse> result = courseService.filterCourses(CourseStatus.INACTIVE, null, "Japanese", pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
            verify(courseRepository).findAll(any(Specification.class), eq(pageable));
        }

        @Test
        @DisplayName("Should filter courses by level and keyword combined")
        @SuppressWarnings("unchecked")
        void shouldFilterCoursesByLevelAndKeyword() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Course> coursePage = new PageImpl<>(List.of(testCourse), pageable, 1);
            given(courseRepository.findAll(any(Specification.class), eq(pageable))).willReturn(coursePage);
            given(courseMapper.toSummaryResponse(testCourse)).willReturn(testSummaryResponse);

            // When
            Page<CourseSummaryResponse> result = courseService.filterCourses(null, JlptLevel.N5, "Japanese", pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
            verify(courseRepository).findAll(any(Specification.class), eq(pageable));
        }
    }

    // =====================================================
    // ACTIVATE / DEACTIVATE / DELETE
    // =====================================================

    @Nested
    @DisplayName("Status Management Tests")
    class StatusManagementTests {

        @Test
        @DisplayName("Should activate course successfully")
        void shouldActivateCourseSuccessfully() {
            // Given
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
            given(courseRepository.save(any(Course.class))).willReturn(testCourse);

            CourseResponse activatedResponse = CourseResponse.builder()
                    .id(courseId)
                    .status(CourseStatus.ACTIVE)
                    .build();
            given(courseMapper.toResponse(testCourse)).willReturn(activatedResponse);

            // When
            CourseResponse result = courseService.activateCourse(courseId, managerId);

            // Then
            assertThat(result.getStatus()).isEqualTo(CourseStatus.ACTIVE);
            verify(courseRepository).save(courseCaptor.capture());
            assertThat(courseCaptor.getValue().getStatus()).isEqualTo(CourseStatus.ACTIVE);
            assertThat(courseCaptor.getValue().getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should deactivate course successfully")
        void shouldDeactivateCourseSuccessfully() {
            // Given
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
            given(courseRepository.save(any(Course.class))).willReturn(testCourse);

            CourseResponse deactivatedResponse = CourseResponse.builder()
                    .id(courseId)
                    .status(CourseStatus.INACTIVE)
                    .build();
            given(courseMapper.toResponse(testCourse)).willReturn(deactivatedResponse);

            // When
            CourseResponse result = courseService.deactivateCourse(courseId, managerId);

            // Then
            assertThat(result.getStatus()).isEqualTo(CourseStatus.INACTIVE);
            verify(courseRepository).save(courseCaptor.capture());
            assertThat(courseCaptor.getValue().getStatus()).isEqualTo(CourseStatus.INACTIVE);
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
    // UPDATE THUMBNAIL
    // =====================================================

    @Nested
    @DisplayName("Update Thumbnail Tests")
    class UpdateThumbnailTests {

        @Test
        @DisplayName("Should update thumbnail successfully")
        void shouldUpdateThumbnailSuccessfully() {
            // Given
            String newThumbnailUrl = "https://res.cloudinary.com/test/image/upload/v1/courses/thumbnails/new-thumb.jpg";
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
            given(courseRepository.save(any(Course.class))).willReturn(testCourse);
            given(courseMapper.toResponse(testCourse)).willReturn(testCourseResponse);
            given(lessonRepository.countByCourseIdAndDeletedAtIsNull(courseId)).willReturn(3L);

            // When
            CourseResponse result = courseService.updateThumbnail(courseId, newThumbnailUrl, managerId);

            // Then
            assertThat(result).isNotNull();
            verify(courseRepository).save(courseCaptor.capture());

            Course savedCourse = courseCaptor.getValue();
            assertThat(savedCourse.getThumbnailUrl()).isEqualTo(newThumbnailUrl);
            assertThat(savedCourse.getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should throw exception when course not found for thumbnail update")
        void shouldThrowExceptionWhenCourseNotFound() {
            // Given
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> courseService.updateThumbnail(courseId, "https://example.com/new.jpg", managerId))
                    .isInstanceOf(CourseNotFoundException.class);

            verify(courseRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should set lesson count and student count in response")
        void shouldSetComputedFieldsInResponse() {
            // Given
            String newThumbnailUrl = "https://res.cloudinary.com/test/image/upload/v1/courses/thumbnails/thumb.jpg";
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
            given(courseRepository.save(any(Course.class))).willReturn(testCourse);
            given(courseMapper.toResponse(testCourse)).willReturn(testCourseResponse);
            given(lessonRepository.countByCourseIdAndDeletedAtIsNull(courseId)).willReturn(5L);

            // When
            CourseResponse result = courseService.updateThumbnail(courseId, newThumbnailUrl, managerId);

            // Then
            assertThat(result.getLessonCount()).isEqualTo(5L);
            assertThat(result.getStudentCount()).isEqualTo(0L);
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
            given(courseRepository.countByStatusAndDeletedAtIsNull(CourseStatus.ACTIVE)).willReturn(5L);
            given(courseRepository.countByStatusAndDeletedAtIsNull(CourseStatus.INACTIVE)).willReturn(3L);
            given(courseRepository.countByStatusAndDeletedAtIsNull(Course.CourseStatus.DELETED)).willReturn(2L);
            given(courseRepository.countByJlptLevelAndDeletedAtIsNull(JlptLevel.N5)).willReturn(3L);
            given(courseRepository.countByJlptLevelAndDeletedAtIsNull(JlptLevel.N4)).willReturn(2L);
            given(courseRepository.countByJlptLevelAndDeletedAtIsNull(JlptLevel.N3)).willReturn(2L);
            given(courseRepository.countByJlptLevelAndDeletedAtIsNull(JlptLevel.N2)).willReturn(2L);
            given(courseRepository.countByJlptLevelAndDeletedAtIsNull(JlptLevel.N1)).willReturn(1L);

            // When
            CourseStatistics stats = courseService.getCourseStatistics();

            // Then
            assertThat(stats.totalCourses()).isEqualTo(10L);
            assertThat(stats.activeCourses()).isEqualTo(5L);
            assertThat(stats.inactiveCourses()).isEqualTo(3L);
            assertThat(stats.deletedCourses()).isEqualTo(2L);
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
