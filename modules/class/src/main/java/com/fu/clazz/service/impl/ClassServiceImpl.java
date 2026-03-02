package com.fu.clazz.service.impl;

import com.fu.clazz.dto.request.AddMemberRequest;
import com.fu.clazz.dto.request.CreateClassRequest;
import com.fu.clazz.dto.request.UpdateClassRequest;
import com.fu.clazz.dto.response.ClassMemberResponse;
import com.fu.clazz.dto.response.ClassResponse;
import com.fu.clazz.dto.response.ClassSummaryResponse;
import com.fu.clazz.entity.ClassMember;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.exception.ClassFullException;
import com.fu.clazz.exception.ClassNotFoundException;
import com.fu.clazz.exception.MemberAlreadyExistsException;
import com.fu.clazz.mapper.ClassMapper;
import com.fu.clazz.repository.ClassMemberRepository;
import com.fu.clazz.repository.ClassRepository;
import com.fu.clazz.repository.specification.ClassSpecification;
import com.fu.clazz.service.ClassService;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.course.entity.Course;
import com.fu.course.repository.CourseRepository;
import com.fu.user.entity.User;
import com.fu.user.entity.UserRole;
import com.fu.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of ClazzService
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ClassServiceImpl implements ClassService {

    private final ClassRepository classRepository;
    private final ClassMemberRepository classMemberRepository;
    private final ClassMapper classMapper;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;

    // =====================================================
    // CLASS CRUD
    // =====================================================

    @Override
    @Transactional
    public ClassResponse createClass(CreateClassRequest request, UUID managerId) {
        log.info("Tạo lớp học: {} bởi quản lý: {}", request.getName(), managerId);

        // Validate date range
        if (request.getStartDate() != null && request.getStartDate().isBefore(java.time.LocalDate.now())) {
            throw new BusinessException("INVALID_START_DATE",
                    "Ngày bắt đầu phải ở tương lai.");
        }

        if (request.getStartDate() != null && request.getEndDate() != null
                && !request.getEndDate().isAfter(request.getStartDate())) {
            throw new BusinessException("INVALID_DATE_RANGE",
                    "Ngày kết thúc phải sau ngày bắt đầu.");
        }
        // Check if class name already exists
        if (classRepository.existsByNameIgnoreCase(request.getName())) {
            throw new BusinessException("CLASS_NAME_EXISTS",
                    "Tên lớp học đã tồn tại.");
        }
        // Validate teacher exists and has TEACHER role
        User teacher = userRepository.findById(request.getTeacherId())
                .orElseThrow(
                        () -> new BusinessException("INVALID_TEACHER", "Giáo viên không tồn tại hoặc không hợp lệ."));

        if (teacher.getRole() != UserRole.TEACHER || teacher.getStatus() != com.fu.user.entity.UserStatus.ACTIVE) {
            throw new BusinessException("INVALID_TEACHER",
                    "Giáo viên không tồn tại hoặc không hợp lệ.");
        }

        // Validate Course exists, is PUBLISHED, and not deleted
        Course course = courseRepository.findById(request.getCourseId())
                .orElseThrow(() -> new BusinessException("INVALID_COURSE", "Khóa học không tồn tại."));

        if (course.getDeletedAt() != null) {
            throw new BusinessException("INVALID_COURSE", "Khóa học đã bị xóa.");
        }

        if (course.getStatus() != Course.ContentStatus.PUBLISHED) {
            throw new BusinessException("INVALID_COURSE_STATUS", "Chỉ có thể tạo lớp học cho khóa học đã được xuất bản (PUBLISHED).");
        }

        // Generate unique join code
        String code = generateUniqueCode();

        // Create class entity
        Clazz clazz = classMapper.toEntity(request);
        clazz.setCode(code);
        clazz.setCourseId(course.getId());
        clazz.setCreatedBy(managerId);
        clazz.setUpdatedBy(managerId);

        // Save class
        Clazz savedClazz = classRepository.save(clazz);
        log.info("Tạo lớp học thành công với ID: {} và mã lớp: {}", savedClazz.getId(), code);

        ClassResponse response = classMapper.toResponse(savedClazz);
        response.setMemberCount(0L);
        return response;
    }

    @Override
    @Transactional
    public ClassResponse updateClass(UUID classId, UpdateClassRequest request, UUID managerId) {
        log.info("Cập nhật lớp học: {} bởi quản lý: {}", classId, managerId);

        Clazz clazz = findClassOrThrow(classId);

        // Check if new name conflicts with existing classes
        if (request.getName() != null &&
                !request.getName().equalsIgnoreCase(clazz.getName()) &&
                classRepository.existsByNameIgnoreCaseAndIdNot(request.getName(), classId)) {
            throw new BusinessException("CLASS_NAME_EXISTS",
                    "Tên lớp học đã tồn tại.");
        }

        // Validate teacher if provided
        if (request.getTeacherId() != null) {

            User newTeacher = userRepository.findById(request.getTeacherId())
                    .orElseThrow(() -> new BusinessException("INVALID_TEACHER",
                            "Giáo viên không tồn tại hoặc không hợp lệ."));

            if (newTeacher.getRole() != UserRole.TEACHER
                    || newTeacher.getStatus() != com.fu.user.entity.UserStatus.ACTIVE) {
                throw new BusinessException("INVALID_TEACHER",
                        "Giáo viên không tồn tại hoặc không hợp lệ.");
            }
        }

        // Validate date range (E3: startDate must be before endDate)
        LocalDate effectiveStartDate = request.getStartDate() != null ? request.getStartDate() : clazz.getStartDate();
        LocalDate effectiveEndDate = request.getEndDate() != null ? request.getEndDate() : clazz.getEndDate();
        if (effectiveStartDate != null && effectiveEndDate != null
                && !effectiveEndDate.isAfter(effectiveStartDate)) {
            throw new BusinessException("INVALID_DATE_RANGE",
                    "Ngày bắt đầu phải trước ngày kết thúc.");
        }

        // Validate capacity (E4: newMaxStudents >= currentMembers)
        if (request.getMaxStudents() != null) {
            long currentMembers = classMemberRepository.countActiveByClassId(classId);
            if (request.getMaxStudents() < currentMembers) {
                throw new BusinessException("CAPACITY_VIOLATION",
                        "Số lượng học viên tối đa không thể nhỏ hơn số lượng học viên hiện tại đang theo học.");
            }
        }

        // Update class (only non-null fields are mapped)
        classMapper.updateEntity(clazz, request);
        clazz.setUpdatedBy(managerId);

        Clazz updatedClazz = classRepository.save(clazz);
        log.info("Cập nhật lớp học thành công: {}", classId);

        return toResponseWithMemberCount(updatedClazz);
    }

    @Override
    public ClassResponse getClassById(UUID classId) {
        log.debug("Lấy thông tin lớp học theo ID: {}", classId);
        Clazz clazz = findClassOrThrow(classId);
        return toResponseWithMemberCount(clazz);
    }

    @Override
    public Page<ClassSummaryResponse> getClasses(String keyword, UUID teacherId, UUID courseId, Boolean isActive, Pageable pageable) {
        log.debug("Lấy danh sách lớp học với bộ lọc động");

        org.springframework.data.jpa.domain.Specification<Clazz> spec = org.springframework.data.jpa.domain.Specification
                .where(ClassSpecification.isNotDeleted())
                .and(ClassSpecification.hasKeyword(keyword))
                .and(ClassSpecification.hasTeacherId(teacherId))
                .and(ClassSpecification.hasCourseId(courseId))
                .and(ClassSpecification.hasIsActive(isActive));

        return classRepository.findAll(spec, pageable)
                .map(this::toSummaryWithMemberCount);
    }

    // =====================================================
    // STATUS MANAGEMENT
    // =====================================================

    @Override
    @Transactional
    public ClassResponse deleteClass(UUID classId, UUID managerId) {
        log.info("Xóa lớp học: {} bởi quản lý: {}", classId, managerId);

        Clazz clazz = findClassOrThrow(classId);

        // Soft delete: stamp deletedAt + deletedBy
        clazz.softDelete(managerId);
        clazz.setUpdatedBy(managerId);

        Clazz deletedClazz = classRepository.save(clazz);
        log.info("Xóa lớp học thành công: {}", classId);

        return toResponseWithMemberCount(deletedClazz);
    }

    // =====================================================
    // MEMBERSHIP MANAGEMENT
    // =====================================================

    @Override
    @Transactional
    public ClassMemberResponse addMember(UUID classId, AddMemberRequest request, UUID managerId) {
        log.info("Thêm thành viên: {} vào lớp học: {} bởi quản lý: {}", request.getUserId(), classId, managerId);

        Clazz clazz = findClassOrThrow(classId);
        validateClassAcceptingMembers(clazz);
        // Validate user exists and has LEARNER role
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy học viên."));

        if (user.getRole() != UserRole.LEARNER) {
            throw new BusinessException("INVALID_MEMBER_ROLE",
                    "Chỉ người dùng có vai trò HỌC VIÊN mới được thêm vào lớp học.");
        }

        return addMemberToClass(clazz, request.getUserId());
    }

    @Override
    @Transactional
    public void removeMember(UUID classId, UUID userId, UUID managerId) {
        log.info("Xóa thành viên: {} khỏi lớp học: {} bởi quản lý: {}", userId, classId, managerId);

        // Verify class exists
        findClassOrThrow(classId);

        ClassMember member = classMemberRepository.findByClassIdAndUserId(classId, userId)
                .orElseThrow(() -> new BusinessException("MEMBER_NOT_FOUND",
                        "Học viên không phải là thành viên của lớp này."));

        if (member.getRemovedAt() != null) {
            throw new BusinessException("MEMBER_ALREADY_REMOVED",
                    "Học viên đã bị xóa khỏi lớp này trước đó.");
        }

        member.remove();
        classMemberRepository.save(member);
        log.info("Xóa thành viên thành công khỏi lớp học: {}", classId);
    }

    @Override
    public Page<ClassMemberResponse> getClassMembers(UUID classId, Pageable pageable) {
        log.debug("Lấy danh sách thành viên của lớp: {}", classId);

        // Verify class exists
        findClassOrThrow(classId);

        Page<ClassMember> membersPage = classMemberRepository.findActiveByClassId(classId, pageable);

        // Batch fetch user info to avoid N+1 queries
        List<UUID> userIds = membersPage.getContent().stream()
                .map(ClassMember::getUserId)
                .toList();
        Map<UUID, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));

        return membersPage.map(member -> {
            ClassMemberResponse response = classMapper.toMemberResponse(member);
            User user = userMap.get(member.getUserId());
            if (user != null) {
                response.setFullName(user.getFullName());
                response.setEmail(user.getEmail());
                response.setAvatarUrl(user.getAvatarUrl());
                response.setRole(user.getRole() != null ? user.getRole().name() : null);
            }
            return response;
        });
    }

    @Override
    public ClassResponse getClassByIdForTeacher(UUID classId, UUID teacherId) {
        log.debug("Giáo viên {} xem chi tiết lớp: {}", teacherId, classId);
        Clazz clazz = findClassOrThrow(classId);

        if (!clazz.getTeacherId().equals(teacherId)) {
            throw new BusinessException("ACCESS_DENIED", "Bạn không có quyền xem lớp học này.");
        }

        return toResponseWithMemberCount(clazz);
    }

    @Override
    public Page<ClassMemberResponse> getClassMembersForTeacher(UUID classId, UUID teacherId, Pageable pageable) {
        log.debug("Giáo viên {} xem thành viên lớp: {}", teacherId, classId);
        Clazz clazz = findClassOrThrow(classId);

        if (!clazz.getTeacherId().equals(teacherId)) {
            throw new BusinessException("ACCESS_DENIED", "Bạn không có quyền xem thành viên của lớp học này.");
        }

        return getClassMembers(classId, pageable);
    }

    // =====================================================
    // HELPER METHODS
    // =====================================================

    /**
     * Find non-deleted class or throw exception (BR-73)
     */
    private Clazz findClassOrThrow(UUID classId) {
        return classRepository.findByIdAndNotDeleted(classId)
                .orElseThrow(() -> new ClassNotFoundException("Lớp học không tồn tại."));
    }

    /**
     * Generate unique 8-character alphanumeric join code
     */
    private String generateUniqueCode() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * Validate that the class is active and not full
     */
    private void validateClassAcceptingMembers(Clazz clazz) {
        if (!clazz.getIsActive()) {
            throw new BusinessException("CLASS_INACTIVE", "Không thể thêm học viên vào lớp đã ngưng hoạt động.");
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
        log.info("Thêm thành viên: {} vào lớp học: {} thành công", userId, clazz.getId());

        ClassMemberResponse response = classMapper.toMemberResponse(savedMember);

        // Enrich with user info
        userRepository.findById(userId).ifPresent(user -> {
            response.setFullName(user.getFullName());
            response.setEmail(user.getEmail());
            response.setAvatarUrl(user.getAvatarUrl());
            response.setRole(user.getRole().name());
        });

        return response;
    }

    /**
     * Map to ClassResponse with computed memberCount
     */
    private ClassResponse toResponseWithMemberCount(Clazz clazz) {
        ClassResponse response = classMapper.toResponse(clazz);
        response.setMemberCount(classMemberRepository.countActiveByClassId(clazz.getId()));

        // Enrich with teacher info
        if (clazz.getTeacherId() != null) {
            userRepository.findById(clazz.getTeacherId()).ifPresent(teacher -> {
                response.setTeacherName(teacher.getFullName());
                response.setTeacherEmail(teacher.getEmail());
                response.setTeacherAvatarUrl(teacher.getAvatarUrl());
            });
        }

        // Enrich with course info
        if (clazz.getCourseId() != null) {
            courseRepository.findById(clazz.getCourseId()).ifPresent(course -> {
                response.setCourseName(course.getName());
                response.setCourseLevel(course.getJlptLevel() != null ? course.getJlptLevel().name() : null);
                response.setCourseStatus(course.getStatus() != null ? course.getStatus().name() : null);
            });
        }

        return response;
    }

    /**
     * Map to ClassSummaryResponse with computed memberCount
     */
    private ClassSummaryResponse toSummaryWithMemberCount(Clazz clazz) {
        ClassSummaryResponse response = classMapper.toSummaryResponse(clazz);
        response.setMemberCount(classMemberRepository.countActiveByClassId(clazz.getId()));

        if (clazz.getCourseId() != null) {
            courseRepository.findById(clazz.getCourseId()).ifPresent(course -> {
                response.setCourseName(course.getName());
                response.setCourseLevel(course.getJlptLevel() != null ? course.getJlptLevel().name() : null);
                response.setCourseStatus(course.getStatus() != null ? course.getStatus().name() : null);
            });
        }

        return response;
    }
}
