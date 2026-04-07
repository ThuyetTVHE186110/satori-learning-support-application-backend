package com.fu.course.service.impl;

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
import com.fu.course.repository.specification.CourseSpecification;
import com.fu.course.service.CourseService;
import com.fu.course.service.CourseUsageChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Implementation of CourseService
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class CourseServiceImpl implements CourseService {

    private final CourseRepository courseRepository;
    private final CourseMapper courseMapper;
    private final LessonRepository lessonRepository;
    private final List<CourseUsageChecker> courseUsageCheckers;

    @Override
    @Transactional
    public CourseResponse createCourse(CreateCourseRequest request, UUID managerId) {
        log.info("Creating course: {} by manager: {}", request.getName(), managerId);
        // Check if course name already exists
        if (courseRepository.existsByNameIgnoreCase(request.getName())) {
            throw new CourseNameAlreadyExistsException(request.getName());
        }
        // Create course entity
        Course course = courseMapper.toEntity(request);
        course.setCreatedBy(managerId);
        course.setUpdatedBy(managerId);

        // Save course
        Course savedCourse = courseRepository.save(course);
        log.info("Course created successfully with ID: {}", savedCourse.getId());

        return courseMapper.toResponse(savedCourse);
    }

    @Override
    @Transactional
    public CourseResponse updateCourse(UUID courseId, UpdateCourseRequest request, UUID managerId) {
        log.info("Updating course: {} by manager: {}", courseId, managerId);

        // Find course
        Course course = findCourseOrThrow(courseId);

        // Check if new name conflicts with existing courses
        if (request.getName() != null &&
                !request.getName().equalsIgnoreCase(course.getName()) &&
                courseRepository.existsByNameIgnoreCaseAndIdNot(request.getName(), courseId)) {
            throw new CourseNameAlreadyExistsException(request.getName());
        }

        // Validate status change: cannot set to DELETED via update (use delete endpoint)
        if (request.getStatus() != null && request.getStatus() == CourseStatus.DELETED) {
            throw new BusinessException("INVALID_STATUS",
                    "Không thể cập nhật trạng thái thành DELETED. Vui lòng sử dụng chức năng xóa để xóa khóa học.");
        }
        if (request.getStatus() == CourseStatus.INACTIVE) {
            // Check if course is used by active classes
            if (isCourseInUse(courseId)) {
                throw new BusinessException("COURSE_IN_USE", "Không thể chuyển trạng thái khóa học khi đang có lớp đang hoạt động.");
            }
        }
        // Update course
        courseMapper.updateEntity(course, request);
        course.setUpdatedBy(managerId);

        Course updatedCourse = courseRepository.save(course);
        log.info("Course updated successfully: {}", courseId);

        return courseMapper.toResponse(updatedCourse);
    }

    @Override
    public CourseResponse getCourseById(UUID courseId) {
        log.debug("Fetching course by ID: {}", courseId);
        Course course = findCourseOrThrow(courseId);

        CourseResponse response = courseMapper.toResponse(course);
        response.setLessonCount(lessonRepository.countByCourseIdAndDeletedAtIsNull(courseId));
        // TODO: Set studentCount from enrollment table when available
        response.setStudentCount(0L);

        return response;
    }

    @Override
    public Page<CourseSummaryResponse> getAllCourses(Pageable pageable) {
        log.debug("Fetching all courses with pagination");
        return courseRepository.findAll(pageable)
                .map(this::toSummaryResponseWithCounts);
    }

    @Override
    public Page<CourseSummaryResponse> getCoursesByStatus(Course.CourseStatus status, Pageable pageable) {
        log.debug("Fetching courses by status: {}", status);
        return courseRepository.findByStatusAndDeletedAtIsNull(status, pageable)
                .map(this::toSummaryResponseWithCounts);
    }

    @Override
    public Page<CourseSummaryResponse> getCoursesByJlptLevel(JlptLevel jlptLevel, Pageable pageable) {
        log.debug("Fetching courses by JLPT level: {}", jlptLevel);
        return courseRepository.findByJlptLevelAndDeletedAtIsNull(jlptLevel, pageable)
                .map(this::toSummaryResponseWithCounts);
    }

    @Override
    public List<CourseSummaryResponse> getActiveCourses() {
        log.debug("Fetching all active courses");
        return courseRepository.findActiveCourses()
                .stream()
                .map(this::toSummaryResponseWithCounts)
                .toList();
    }

    @Override
    public List<CourseSummaryResponse> getActiveCoursesByLevel(JlptLevel jlptLevel) {
        log.debug("Fetching active courses by level: {}", jlptLevel);
        return courseRepository.findActiveCoursesByLevel(jlptLevel)
                .stream()
                .map(this::toSummaryResponseWithCounts)
                .toList();
    }

    @Override
    public Page<CourseSummaryResponse> searchCourses(String keyword, Pageable pageable) {
        log.debug("Searching courses by keyword: {}", keyword);
        return courseRepository.searchByName(keyword, pageable)
                .map(this::toSummaryResponseWithCounts);
    }

    @Override
    public Page<CourseSummaryResponse> filterCourses(CourseStatus status, JlptLevel level, String keyword, Pageable pageable) {
        log.debug("Filtering courses - status: {}, level: {}, keyword: {}", status, level, keyword);
        return courseRepository.findAll(CourseSpecification.withFilters(status, level, keyword), pageable)
                .map(this::toSummaryResponseWithCounts);
    }

    @Override
    @Transactional
    public CourseResponse activateCourse(UUID courseId, UUID managerId) {
        log.info("Activating course: {} by manager: {}", courseId, managerId);

        Course course = findCourseOrThrow(courseId);
        course.setStatus(Course.CourseStatus.ACTIVE);
        course.setUpdatedBy(managerId);

        Course updatedCourse = courseRepository.save(course);
        log.info("Course activated successfully: {}", courseId);

        return courseMapper.toResponse(updatedCourse);
    }

    @Override
    @Transactional
    public CourseResponse deactivateCourse(UUID courseId, UUID managerId) {
        log.info("Deactivating course: {} by manager: {}", courseId, managerId);

        Course course = findCourseOrThrow(courseId);

        // Check if course is used by active classes
        if (isCourseInUse(courseId)) {
            throw new BusinessException("COURSE_IN_USE", "Không thể chuyển trạng thái khóa học khi đang có lớp đang hoạt động.");
        }

        course.setStatus(CourseStatus.INACTIVE);
        course.setUpdatedBy(managerId);

        Course updatedCourse = courseRepository.save(course);
        log.info("Course deactivated successfully: {}", courseId);

        return courseMapper.toResponse(updatedCourse);
    }

    @Override
    @Transactional
    public void deleteCourse(UUID courseId, UUID managerId) {
        log.info("Soft-deleting course: {} by manager: {}", courseId, managerId);

        Course course = findCourseOrThrow(courseId);

        // Check if course is used by active classes
        if (isCourseInUse(courseId)) {
            throw new BusinessException("COURSE_IN_USE", "Không thể xóa khóa học đang có lớp đang hoạt động.");
        }

        course.softDelete(managerId);
        course.setStatus(CourseStatus.DELETED);
        courseRepository.save(course);
        log.info("Course soft-deleted successfully: {}", courseId);
    }


    @Override
    public boolean existsByName(String name) {
        return courseRepository.existsByNameIgnoreCase(name);
    }

    @Override
    public CourseStatistics getCourseStatistics() {
        log.debug("Fetching course statistics");

        long totalCourses = courseRepository.count();
        long activeCourses = courseRepository.countByStatusAndDeletedAtIsNull(Course.CourseStatus.ACTIVE);
        long inactiveCourses = courseRepository.countByStatusAndDeletedAtIsNull(Course.CourseStatus.INACTIVE);
        long deletedCourses = courseRepository.countByStatusAndDeletedAtIsNull(CourseStatus.DELETED);

        long n5Courses = courseRepository.countByJlptLevelAndDeletedAtIsNull(JlptLevel.N5);
        long n4Courses = courseRepository.countByJlptLevelAndDeletedAtIsNull(JlptLevel.N4);
        long n3Courses = courseRepository.countByJlptLevelAndDeletedAtIsNull(JlptLevel.N3);
        long n2Courses = courseRepository.countByJlptLevelAndDeletedAtIsNull(JlptLevel.N2);
        long n1Courses = courseRepository.countByJlptLevelAndDeletedAtIsNull(JlptLevel.N1);

        return new CourseStatistics(
                totalCourses,
                activeCourses,
                inactiveCourses,
                deletedCourses,
                n5Courses,
                n4Courses,
                n3Courses,
                n2Courses,
                n1Courses
        );
    }

    /**
     * Helper method to convert course to summary response and enrich with counts
     */
    private CourseSummaryResponse toSummaryResponseWithCounts(Course course) {
        CourseSummaryResponse response = courseMapper.toSummaryResponse(course);
        response.setLessonCount(lessonRepository.countByCourseIdAndDeletedAtIsNull(course.getId()));
        // TODO: Set studentCount from enrollment table when available
        response.setStudentCount(0L);
        return response;
    }

    /**
     * Helper method to find course or throw exception
     */
    private Course findCourseOrThrow(UUID courseId) {
        return courseRepository.findByIdAndNotDeleted(courseId)
                .orElseThrow(() -> new CourseNotFoundException(courseId));
    }

    /**
     * Call all registered course usage checkers to validate if course can be deactivated/deleted
     */
    private boolean isCourseInUse(UUID courseId) {
        if (courseUsageCheckers != null) {
            return courseUsageCheckers.stream().anyMatch(checker -> checker.checkActiveUsage(courseId));
        }
        return false;
    }
}
