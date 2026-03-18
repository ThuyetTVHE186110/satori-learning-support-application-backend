package com.fu.course.service.impl;

import com.fu.course.dto.request.CreateLessonRequest;
import com.fu.course.dto.request.UpdateLessonRequest;
import com.fu.course.dto.response.LessonResponse;
import com.fu.course.dto.response.LessonSummaryResponse;
import com.fu.course.entity.Course;
import com.fu.course.entity.Lesson;
import com.fu.course.entity.Lesson.ContentStatus;
import com.fu.course.exception.CourseNotFoundException;
import com.fu.course.exception.LessonNotFoundException;
import com.fu.course.mapper.LessonMapper;
import com.fu.course.repository.CourseRepository;
import com.fu.course.repository.LessonRepository;
import com.fu.course.service.LessonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Implementation of LessonService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class LessonServiceImpl implements LessonService {

    private final LessonRepository lessonRepository;
    private final CourseRepository courseRepository;
    private final LessonMapper lessonMapper;

    // ===== CRUD =====

    @Override
    @Transactional
    public LessonResponse createLesson(CreateLessonRequest request, UUID managerId) {
        log.info("Creating lesson '{}' for course {} by manager {}", request.getTitle(), request.getCourseId(), managerId);

        // Verify course exists
        Course course = courseRepository.findByIdAndNotDeleted(request.getCourseId())
                .orElseThrow(() -> new CourseNotFoundException(request.getCourseId()));

        // Map DTO to entity
        Lesson lesson = lessonMapper.toEntity(request);
        lesson.setCourse(course);
        lesson.setCreatedBy(managerId);
        lesson.setUpdatedBy(managerId);

        Lesson savedLesson = lessonRepository.save(lesson);
        log.info("Lesson created successfully with ID: {}", savedLesson.getId());

        return lessonMapper.toResponse(savedLesson);
    }

    @Override
    @Transactional
    public LessonResponse updateLesson(UUID lessonId, UpdateLessonRequest request, UUID managerId) {
        log.info("Updating lesson {} by manager {}", lessonId, managerId);

        Lesson lesson = findLessonOrThrow(lessonId);

        // Check title uniqueness within the same course if title is being changed
        if (request.getTitle() != null
                && !request.getTitle().equalsIgnoreCase(lesson.getTitle())
                && lessonRepository.existsByTitleIgnoreCaseAndCourseIdAndIdNot(
                        request.getTitle(), lesson.getCourse().getId(), lessonId)) {
            throw new com.fu.common.exception.BusinessException(
                    "Lesson with title '" + request.getTitle() + "' already exists in this course");
        }

        lessonMapper.updateEntity(lesson, request);
        lesson.setUpdatedBy(managerId);

        Lesson updatedLesson = lessonRepository.save(lesson);
        log.info("Lesson updated successfully: {}", lessonId);

        return lessonMapper.toResponse(updatedLesson);
    }

    @Override
    public LessonResponse getLessonById(UUID lessonId) {
        log.debug("Fetching lesson by ID: {}", lessonId);
        Lesson lesson = findLessonOrThrow(lessonId);

        LessonResponse response = lessonMapper.toResponse(lesson);
        // TODO: Set vocabularyCount and grammarPointCount from join tables
        response.setVocabularyCount(0L);
        response.setGrammarPointCount(0L);

        return response;
    }

    @Override
    @Transactional
    public void deleteLesson(UUID lessonId, UUID managerId) {
        log.info("Soft-deleting lesson {} by manager {}", lessonId, managerId);

        Lesson lesson = findLessonOrThrow(lessonId);
        lesson.softDelete(managerId);

        lessonRepository.save(lesson);
        log.info("Lesson soft-deleted successfully: {}", lessonId);
    }

    // ===== Query =====

    @Override
    public List<LessonSummaryResponse> getLessonsByCourseId(UUID courseId) {
        log.debug("Fetching all lessons for course: {}", courseId);
        return lessonRepository.findByCourseIdAndNotDeleted(courseId)
                .stream()
                .map(lessonMapper::toSummaryResponse)
                .toList();
    }

    @Override
    public Page<LessonSummaryResponse> getLessonsByCourseId(UUID courseId, Pageable pageable) {
        log.debug("Fetching lessons for course {} with pagination", courseId);
        return lessonRepository.findByCourseIdAndNotDeleted(courseId, pageable)
                .map(lessonMapper::toSummaryResponse);
    }

    @Override
    public List<LessonSummaryResponse> getPublishedLessonsByCourseId(UUID courseId) {
        log.debug("Fetching published lessons for course: {}", courseId);
        return lessonRepository.findPublishedByCourseId(courseId)
                .stream()
                .map(lessonMapper::toSummaryResponse)
                .toList();
    }

    @Override
    public Page<LessonSummaryResponse> searchLessons(String keyword, Pageable pageable) {
        log.debug("Searching lessons by keyword: {}", keyword);
        return lessonRepository.searchByTitle(keyword, pageable)
                .map(lessonMapper::toSummaryResponse);
    }

    @Override
    public Page<LessonSummaryResponse> searchLessonsInCourse(UUID courseId, String keyword, Pageable pageable) {
        log.debug("Searching lessons in course {} by keyword: {}", courseId, keyword);
        return lessonRepository.searchByTitleInCourse(courseId, keyword, pageable)
                .map(lessonMapper::toSummaryResponse);
    }

    // ===== Status Management =====

    @Override
    @Transactional
    public LessonResponse publishLesson(UUID lessonId, UUID managerId) {
        log.info("Publishing lesson {} by manager {}", lessonId, managerId);

        Lesson lesson = findLessonOrThrow(lessonId);
        lesson.setStatus(ContentStatus.PUBLISHED);
        lesson.setUpdatedBy(managerId);

        Lesson updatedLesson = lessonRepository.save(lesson);
        log.info("Lesson published successfully: {}", lessonId);

        return lessonMapper.toResponse(updatedLesson);
    }

    @Override
    @Transactional
    public LessonResponse archiveLesson(UUID lessonId, UUID managerId) {
        log.info("Archiving lesson {} by manager {}", lessonId, managerId);

        Lesson lesson = findLessonOrThrow(lessonId);
        lesson.setStatus(ContentStatus.ARCHIVED);
        lesson.setUpdatedBy(managerId);

        Lesson updatedLesson = lessonRepository.save(lesson);
        log.info("Lesson archived successfully: {}", lessonId);

        return lessonMapper.toResponse(updatedLesson);
    }

    // ===== Counting =====

    @Override
    public long countLessonsByCourseId(UUID courseId) {
        return lessonRepository.countByCourseIdAndDeletedAtIsNull(courseId);
    }

    // ===== Statistics =====

    @Override
    public LessonStatistics getLessonStatistics(UUID courseId) {
        log.debug("Fetching lesson statistics for course: {}", courseId);

        long total = lessonRepository.countByCourseIdAndDeletedAtIsNull(courseId);
        long published = lessonRepository.countByCourseIdAndStatusAndDeletedAtIsNull(courseId, ContentStatus.PUBLISHED);
        long draft = lessonRepository.countByCourseIdAndStatusAndDeletedAtIsNull(courseId, ContentStatus.DRAFT);
        long archived = lessonRepository.countByCourseIdAndStatusAndDeletedAtIsNull(courseId, ContentStatus.ARCHIVED);

        return new LessonStatistics(total, published, draft, archived);
    }

    // ===== Helper =====

    private Lesson findLessonOrThrow(UUID lessonId) {
        return lessonRepository.findByIdAndNotDeleted(lessonId)
                .orElseThrow(() -> new LessonNotFoundException(lessonId));
    }
}
