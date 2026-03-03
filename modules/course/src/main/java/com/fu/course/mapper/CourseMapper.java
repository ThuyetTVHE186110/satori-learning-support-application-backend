package com.fu.course.mapper;

import com.fu.course.dto.request.CreateCourseRequest;
import com.fu.course.dto.request.UpdateCourseRequest;
import com.fu.course.dto.response.CourseResponse;
import com.fu.course.dto.response.CourseSummaryResponse;
import com.fu.course.entity.Course;
import org.mapstruct.*;

/**
 * Mapper for Course entity
 */
@Mapper(
    componentModel = "spring",
    unmappedTargetPolicy = ReportingPolicy.IGNORE,
    nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface CourseMapper {

    /**
     * Map CreateCourseRequest to Course entity
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "thumbnailUrl", ignore = true)  // Set by service after Cloudinary upload
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    Course toEntity(CreateCourseRequest request);

    /**
     * Update Course entity from UpdateCourseRequest.
     * Only updates non-null fields.
     * Status can be changed inline (ACTIVE/INACTIVE) — validated by service.
     */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "jlptLevel", ignore = true)      // Cannot change JLPT level
    @Mapping(target = "thumbnailUrl", ignore = true)    // Managed by service (Cloudinary)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    void updateEntity(@MappingTarget Course course, UpdateCourseRequest request);

    /**
     * Map Course entity to CourseResponse
     */
    @Mapping(target = "lessonCount", ignore = true)  // Set by service
    @Mapping(target = "studentCount", ignore = true)  // Set by service
    CourseResponse toResponse(Course course);

    /**
     * Map Course entity to CourseSummaryResponse
     */
    @Mapping(target = "lessonCount", ignore = true)  // Set by service
    @Mapping(target = "studentCount", ignore = true)  // Set by service
    CourseSummaryResponse toSummaryResponse(Course course);
}
