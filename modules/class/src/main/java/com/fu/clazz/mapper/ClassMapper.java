package com.fu.clazz.mapper;

import com.fu.clazz.dto.request.CreateClassRequest;
import com.fu.clazz.dto.request.UpdateClassRequest;
import com.fu.clazz.dto.response.ClassMemberResponse;
import com.fu.clazz.dto.response.ClassResponse;
import com.fu.clazz.dto.response.ClassSummaryResponse;
import com.fu.clazz.dto.response.LearnerClassResponse;
import com.fu.clazz.dto.response.TeacherClassHistoryResponse;
import com.fu.clazz.entity.ClassMember;
import com.fu.clazz.entity.Clazz;
import org.mapstruct.*;

/**
 * Mapper for Clazz and ClassMember entities
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE, nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface ClassMapper {

    /**
     * Map CreateClassRequest to Clazz entity
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    Clazz toEntity(CreateClassRequest request);

    /**
     * Update Clazz entity from UpdateClassRequest
     * Only updates non-null fields
     */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    void updateEntity(@MappingTarget Clazz clazz, UpdateClassRequest request);

    /**
     * Map Clazz entity to ClassResponse
     */
    @Mapping(target = "memberCount", ignore = true) // Set by service
    @Mapping(target = "courseName", ignore = true) // Set by service
    @Mapping(target = "courseLevel", ignore = true) // Set by service
    @Mapping(target = "courseStatus", ignore = true)
    // Set by service
    ClassResponse toResponse(Clazz clazz);

    /**
     * Map Clazz entity to ClassSummaryResponse
     */
    @Mapping(target = "memberCount", ignore = true) // Set by service
    @Mapping(target = "courseName", ignore = true) // Set by service
    @Mapping(target = "courseLevel", ignore = true) // Set by service
    @Mapping(target = "courseStatus", ignore = true) // Set by service
    @Mapping(target = "teacherName", ignore = true)
    // Set by service
    ClassSummaryResponse toSummaryResponse(Clazz clazz);

    /**
     * Map ClassMember entity to ClassMemberResponse
     */
    ClassMemberResponse toMemberResponse(ClassMember member);

    /**
     * Map Clazz entity to LearnerClassResponse (lightweight)
     */
    LearnerClassResponse toLearnerClassResponse(Clazz clazz);

    /**
     * Map Clazz entity to TeacherClassHistoryResponse
     */
    @Mapping(target = "courseName", ignore = true) // Set by service
    @Mapping(target = "courseLevel", ignore = true) // Set by service
    TeacherClassHistoryResponse toTeacherClassHistoryResponse(Clazz clazz);
}
