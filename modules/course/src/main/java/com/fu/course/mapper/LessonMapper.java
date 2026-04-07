package com.fu.course.mapper;

import com.fu.course.dto.request.CreateLessonRequest;
import com.fu.course.dto.request.UpdateLessonRequest;
import com.fu.course.dto.response.LessonResponse;
import com.fu.course.dto.response.LessonSummaryResponse;
import com.fu.course.entity.Lesson;
import org.mapstruct.*;

import java.util.Arrays;
import java.util.List;

/**
 * MapStruct mapper for Lesson entity ↔ DTOs.
 */
@Mapper(
    componentModel = "spring",
    unmappedTargetPolicy = ReportingPolicy.IGNORE,
    nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface LessonMapper {

    /**
     * Map CreateLessonRequest to Lesson entity.
     * Course is set manually in the service layer.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "course", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    @Mapping(target = "processingStatus", ignore = true)
    @Mapping(target = "processedAt", ignore = true)
    @Mapping(target = "processingError", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "objectives", source = "objectives", qualifiedByName = "listToArray")
    Lesson toEntity(CreateLessonRequest request);

    /**
     * Update Lesson entity from UpdateLessonRequest.
     * Only updates non-null fields.
     */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "course", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "processingStatus", ignore = true)
    @Mapping(target = "processedAt", ignore = true)
    @Mapping(target = "processingError", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    @Mapping(target = "objectives", source = "objectives", qualifiedByName = "listToArray")
    void updateEntity(@MappingTarget Lesson lesson, UpdateLessonRequest request);

    /**
     * Map Lesson entity to full LessonResponse.
     */
    @Mapping(target = "courseId", source = "course.id")
    @Mapping(target = "courseName", source = "course.name")
    @Mapping(target = "objectives", source = "objectives", qualifiedByName = "arrayToList")
    @Mapping(target = "vocabularyCount", ignore = true)
    @Mapping(target = "grammarPointCount", ignore = true)
    LessonResponse toResponse(Lesson lesson);

    /**
     * Map Lesson entity to summary response.
     */
    @Mapping(target = "courseId", source = "course.id")
    @Mapping(target = "vocabularyCount", ignore = true)
    @Mapping(target = "grammarPointCount", ignore = true)
    LessonSummaryResponse toSummaryResponse(Lesson lesson);

    @Named("listToArray")
    default String[] listToArray(java.util.List<String> list) {
        if (list == null) return null;
        return list.toArray(new String[0]);
    }

    @Named("arrayToList")
    default List<String> arrayToList(String[] array) {
        if (array == null) return null;
        return Arrays.asList(array);
    }
}
