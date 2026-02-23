package com.fu.clazz.service.impl;

import com.fu.clazz.dto.request.AddMemberRequest;
import com.fu.clazz.dto.request.CreateClassRequest;
import com.fu.clazz.dto.request.UpdateClassRequest;
import com.fu.clazz.dto.response.ClassMemberResponse;
import com.fu.clazz.dto.response.ClassResponse;
import com.fu.clazz.dto.response.ClassSummaryResponse;
import com.fu.clazz.entity.ClassMember;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.entity.Clazz.JlptLevel;
import com.fu.clazz.exception.ClassFullException;
import com.fu.clazz.exception.ClazzNotFoundException;
import com.fu.clazz.exception.MemberAlreadyExistsException;
import com.fu.clazz.mapper.ClazzMapper;
import com.fu.clazz.repository.ClassMemberRepository;
import com.fu.clazz.repository.ClazzRepository;
import com.fu.clazz.service.ClazzService;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.user.entity.User;
import com.fu.user.entity.UserRole;
import com.fu.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of ClazzService
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ClazzServiceImpl implements ClazzService {

    private final ClazzRepository clazzRepository;
    private final ClassMemberRepository classMemberRepository;
    private final ClazzMapper clazzMapper;
    private final UserRepository userRepository;

    private static final int CODE_LENGTH = 8;
    private static final int MAX_CODE_GENERATION_ATTEMPTS = 10;

    // =====================================================
    // CLASS CRUD
    // =====================================================

    @Override
    @Transactional
    public ClassResponse createClass(CreateClassRequest request, UUID managerId) {
        log.info("Creating class: {} by manager: {}", request.getName(), managerId);

        // Validate teacher exists and has TEACHER role
        User teacher = userRepository.findById(request.getTeacherId())
                .orElseThrow(() -> new ResourceNotFoundException("Teacher not found with ID: " + request.getTeacherId()));

        if (teacher.getRole() != UserRole.TEACHER) {
            throw new BusinessException("INVALID_TEACHER_ROLE",
                    "Only users with TEACHER role can be assigned as class teacher. User '" + teacher.getFullName() + "' has role: " + teacher.getRole());
        }

        // Generate unique join code
        String code = generateUniqueCode();

        // Create class entity
        Clazz clazz = clazzMapper.toEntity(request);
        clazz.setCode(code);
        clazz.setCreatedBy(managerId);
        clazz.setUpdatedBy(managerId);

        // Save class
        Clazz savedClazz = clazzRepository.save(clazz);
        log.info("Class created successfully with ID: {} and code: {}", savedClazz.getId(), code);

        ClassResponse response = clazzMapper.toResponse(savedClazz);
        response.setMemberCount(0L);
        return response;
    }

    @Override
    @Transactional
    public ClassResponse updateClass(UUID classId, UpdateClassRequest request, UUID managerId) {
        log.info("Updating class: {} by manager: {}", classId, managerId);

        Clazz clazz = findClassOrThrow(classId);

        // Check if new name conflicts with existing classes
        if (request.getName() != null &&
                !request.getName().equalsIgnoreCase(clazz.getName()) &&
                clazzRepository.existsByNameIgnoreCaseAndIdNot(request.getName(), classId)) {
            throw new BusinessException("CLASS_NAME_EXISTS",
                    "Class with name '" + request.getName() + "' already exists");
        }

        // Update class
        clazzMapper.updateEntity(clazz, request);
        clazz.setUpdatedBy(managerId);

        Clazz updatedClazz = clazzRepository.save(clazz);
        log.info("Class updated successfully: {}", classId);

        return toResponseWithMemberCount(updatedClazz);
    }

    @Override
    public ClassResponse getClassById(UUID classId) {
        log.debug("Fetching class by ID: {}", classId);
        Clazz clazz = findClassOrThrow(classId);
        return toResponseWithMemberCount(clazz);
    }

    @Override
    public Page<ClassSummaryResponse> getAllClasses(Pageable pageable) {
        log.debug("Fetching all classes with pagination");
        return clazzRepository.findAllNotDeleted(pageable)
                .map(this::toSummaryWithMemberCount);
    }

    @Override
    public Page<ClassSummaryResponse> getClassesByActiveStatus(Boolean isActive, Pageable pageable) {
        log.debug("Fetching classes by active status: {}", isActive);
        return clazzRepository.findByIsActiveAndDeletedAtIsNull(isActive, pageable)
                .map(this::toSummaryWithMemberCount);
    }

    @Override
    public Page<ClassSummaryResponse> getClassesByJlptLevel(JlptLevel jlptLevel, Pageable pageable) {
        log.debug("Fetching classes by JLPT level: {}", jlptLevel);
        return clazzRepository.findByJlptLevelAndDeletedAtIsNull(jlptLevel, pageable)
                .map(this::toSummaryWithMemberCount);
    }

    @Override
    public Page<ClassSummaryResponse> getClassesByTeacher(UUID teacherId, Pageable pageable) {
        log.debug("Fetching classes by teacher: {}", teacherId);
        return clazzRepository.findByTeacherIdAndDeletedAtIsNull(teacherId, pageable)
                .map(this::toSummaryWithMemberCount);
    }

    @Override
    public Page<ClassSummaryResponse> searchClasses(String keyword, Pageable pageable) {
        log.debug("Searching classes by keyword: {}", keyword);
        return clazzRepository.searchByName(keyword, pageable)
                .map(this::toSummaryWithMemberCount);
    }

    // =====================================================
    // STATUS MANAGEMENT
    // =====================================================

    @Override
    @Transactional
    public ClassResponse activateClass(UUID classId, UUID managerId) {
        log.info("Activating class: {} by manager: {}", classId, managerId);

        Clazz clazz = findClassOrThrow(classId);
        clazz.setIsActive(true);
        clazz.setUpdatedBy(managerId);

        Clazz updatedClazz = clazzRepository.save(clazz);
        log.info("Class activated successfully: {}", classId);

        return toResponseWithMemberCount(updatedClazz);
    }

    @Override
    @Transactional
    public ClassResponse deactivateClass(UUID classId, UUID managerId) {
        log.info("Deactivating class: {} by manager: {}", classId, managerId);

        Clazz clazz = findClassOrThrow(classId);
        clazz.setIsActive(false);
        clazz.setUpdatedBy(managerId);

        Clazz updatedClazz = clazzRepository.save(clazz);
        log.info("Class deactivated successfully: {}", classId);

        return toResponseWithMemberCount(updatedClazz);
    }

    @Override
    @Transactional
    public void deleteClass(UUID classId, UUID managerId) {
        log.info("Soft-deleting class: {} by manager: {}", classId, managerId);

        Clazz clazz = findClassOrThrow(classId);
        clazz.softDelete(managerId);

        clazzRepository.save(clazz);
        log.info("Class soft-deleted successfully: {}", classId);
    }

    // =====================================================
    // TEACHER ASSIGNMENT
    // =====================================================

    @Override
    @Transactional
    public ClassResponse assignTeacher(UUID classId, UUID teacherId, UUID managerId) {
        log.info("Assigning teacher: {} to class: {} by manager: {}", teacherId, classId, managerId);

        Clazz clazz = findClassOrThrow(classId);

        // Validate user exists and has TEACHER role
        User user = userRepository.findById(teacherId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + teacherId));

        if (user.getRole() != UserRole.TEACHER) {
            throw new BusinessException("INVALID_TEACHER_ROLE",
                    "Only users with TEACHER role can be assigned as class teacher. User '" + user.getFullName() + "' has role: " + user.getRole());
        }

        clazz.setTeacherId(teacherId);
        clazz.setUpdatedBy(managerId);

        Clazz updatedClazz = clazzRepository.save(clazz);
        log.info("Teacher assigned successfully to class: {}", classId);

        return toResponseWithMemberCount(updatedClazz);
    }

    // =====================================================
    // MEMBERSHIP MANAGEMENT
    // =====================================================

    @Override
    @Transactional
    public ClassMemberResponse addMember(UUID classId, AddMemberRequest request, UUID managerId) {
        log.info("Adding member: {} to class: {} by manager: {}", request.getUserId(), classId, managerId);

        Clazz clazz = findClassOrThrow(classId);

        // Validate user exists and has LEARNER role
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + request.getUserId()));

        if (user.getRole() != UserRole.LEARNER) {
            throw new BusinessException("INVALID_MEMBER_ROLE",
                    "Only users with LEARNER role can be added as class members. User '" + user.getFullName() + "' has role: " + user.getRole());
        }

        // Check if user is the teacher of the class
        if (clazz.getTeacherId() != null && clazz.getTeacherId().equals(request.getUserId())) {
            throw new BusinessException("USER_IS_TEACHER",
                    "User is already the teacher of this class");
        }

        validateClassAcceptingMembers(clazz);

        return addMemberToClass(clazz, request.getUserId());
    }

    @Override
    @Transactional
    public void removeMember(UUID classId, UUID userId, UUID managerId) {
        log.info("Removing member: {} from class: {} by manager: {}", userId, classId, managerId);

        // Verify class exists
        findClassOrThrow(classId);

        ClassMember member = classMemberRepository.findByClassIdAndUserId(classId, userId)
                .orElseThrow(() -> new BusinessException("MEMBER_NOT_FOUND",
                        "User is not a member of this class"));

        if (member.getRemovedAt() != null) {
            throw new BusinessException("MEMBER_ALREADY_REMOVED",
                    "User has already been removed from this class");
        }

        member.remove();
        classMemberRepository.save(member);
        log.info("Member removed successfully from class: {}", classId);
    }


    @Override
    public Page<ClassMemberResponse> getClassMembers(UUID classId, Pageable pageable) {
        log.debug("Fetching members of class: {}", classId);

        // Verify class exists
        findClassOrThrow(classId);

        return classMemberRepository.findActiveByClassId(classId, pageable)
                .map(clazzMapper::toMemberResponse);
    }


    // =====================================================
    // HELPER METHODS
    // =====================================================

    /**
     * Find class or throw exception
     */
    private Clazz findClassOrThrow(UUID classId) {
        return clazzRepository.findByIdAndNotDeleted(classId)
                .orElseThrow(() -> new ClazzNotFoundException(classId));
    }

    /**
     * Generate unique 8-character alphanumeric join code
     */
    private String generateUniqueCode() {
        for (int i = 0; i < MAX_CODE_GENERATION_ATTEMPTS; i++) {
            String code = UUID.randomUUID().toString()
                    .replace("-", "")
                    .substring(0, CODE_LENGTH)
                    .toUpperCase();
            if (!clazzRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new BusinessException("CODE_GENERATION_FAILED",
                "Failed to generate unique class code after " + MAX_CODE_GENERATION_ATTEMPTS + " attempts");
    }

    /**
     * Validate that the class is active and not full
     */
    private void validateClassAcceptingMembers(Clazz clazz) {
        if (!clazz.getIsActive()) {
            throw new BusinessException("CLASS_INACTIVE", "Cannot join an inactive class");
        }

        long currentMembers = classMemberRepository.countActiveByClassId(clazz.getId());
        if (currentMembers >= clazz.getMaxStudents()) {
            throw new ClassFullException(clazz.getName(), clazz.getMaxStudents());
        }
    }

    /**
     * Add a user to a class, handling re-join of previously removed members
     */
    private ClassMemberResponse addMemberToClass(Clazz clazz, UUID userId) {
        Optional<ClassMember> existing = classMemberRepository.findByClassIdAndUserId(clazz.getId(), userId);

        ClassMember member;
        if (existing.isPresent()) {
            member = existing.get();
            if (member.isActive()) {
                throw new MemberAlreadyExistsException();
            }
            // Re-join: restore previously removed member
            member.restore();
        } else {
            member = ClassMember.builder()
                    .classId(clazz.getId())
                    .userId(userId)
                    .build();
        }

        ClassMember savedMember = classMemberRepository.save(member);
        log.info("Member: {} added to class: {}", userId, clazz.getId());
        return clazzMapper.toMemberResponse(savedMember);
    }

    /**
     * Map to ClassResponse with computed memberCount
     */
    private ClassResponse toResponseWithMemberCount(Clazz clazz) {
        ClassResponse response = clazzMapper.toResponse(clazz);
        response.setMemberCount(classMemberRepository.countActiveByClassId(clazz.getId()));
        return response;
    }

    /**
     * Map to ClassSummaryResponse with computed memberCount
     */
    private ClassSummaryResponse toSummaryWithMemberCount(Clazz clazz) {
        ClassSummaryResponse response = clazzMapper.toSummaryResponse(clazz);
        response.setMemberCount(classMemberRepository.countActiveByClassId(clazz.getId()));
        return response;
    }
}

