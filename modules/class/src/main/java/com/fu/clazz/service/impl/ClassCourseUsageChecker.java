package com.fu.clazz.service.impl;

import com.fu.clazz.entity.ClassStatus;
import com.fu.clazz.repository.ClassRepository;
import com.fu.course.service.CourseUsageChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Implementation of CourseUsageChecker for the class module.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClassCourseUsageChecker implements CourseUsageChecker {

    private final ClassRepository classRepository;

    @Override
    public boolean checkActiveUsage(UUID courseId) {
        log.debug("Checking active classes for course: {}", courseId);

        // Active usage means classes that are NOT_STARTED or ACTIVE
        boolean hasActiveClasses = classRepository.existsByCourseIdAndStatusInAndDeletedAtIsNull(
                courseId,
                List.of(ClassStatus.ACTIVE)
        );
        return hasActiveClasses;
    }
}
