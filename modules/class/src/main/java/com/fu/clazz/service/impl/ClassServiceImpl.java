package com.fu.clazz.service.impl;

import com.fu.clazz.dto.request.AddMemberRequest;
import com.fu.clazz.dto.request.CreateClassRequest;
import com.fu.clazz.dto.request.UpdateClassRequest;
import com.fu.clazz.dto.response.ClassMemberResponse;
import com.fu.clazz.dto.response.ClassResponse;
import com.fu.clazz.dto.response.ClassSummaryResponse;
import com.fu.clazz.entity.ClassMember;
import com.fu.clazz.entity.ClassTeacherHistory;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.exception.ClassFullException;
import com.fu.clazz.exception.ClassNotFoundException;
import com.fu.clazz.exception.MemberAlreadyExistsException;
import com.fu.clazz.mapper.ClassMapper;
import com.fu.clazz.repository.ClassMemberRepository;
import com.fu.clazz.repository.ClassRepository;
import com.fu.clazz.repository.ClassTeacherHistoryRepository;
import com.fu.clazz.repository.specification.ClassSpecification;
import com.fu.clazz.service.ClassService;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.course.entity.Course;
import com.fu.course.repository.CourseRepository;
import com.fu.user.entity.User;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import com.fu.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final ClassTeacherHistoryRepository classTeacherHistoryRepository;
    // =====================================================
    // CLASS CRUD
    // =====================================================

    @Override
    public ClassResponse closeClass(UUID classId, UUID managerId) {
        Clazz clazz = findClassOrThrow(classId);
        if (!clazz.getIsActive()) {
            throw new BusinessException("CLASS_ALREADY_INACTIVE", "Lớp học đã ngưng hoạt động.");
        }
        clazz.setIsActive(false);
        clazz.setEndDate(LocalDate.now());
        clazz.setUpdatedBy(managerId);
        Clazz updatedClazz = classRepository.save(clazz);
        log.info("Đóng lớp học thành công: {}", classId);
        // Deactivate all members when class is closed
        deactivateMembers(classId);
        return toResponseWithMemberCount(updatedClazz);
    }

    @Override
    @Transactional
    public ClassResponse createClass(CreateClassRequest request, UUID managerId) {
        log.info("Tạo lớp học: {} bởi quản lý: {}", request.getName(), managerId);
        // Check if class name already exists
        if (classRepository.existsByNameIgnoreCase(request.getName())) {
            throw new BusinessException("CLASS_NAME_EXISTS", "Tên lớp học đã tồn tại.");
        }
        // Validate teacher exists and has TEACHER role
        User teacher = userRepository.findById(request.getTeacherId()).orElseThrow(() -> new BusinessException("INVALID_TEACHER", "Giáo viên không tồn tại hoặc không hợp lệ."));

        if (teacher.getRole() != UserRole.TEACHER || teacher.getStatus() != com.fu.user.entity.UserStatus.ACTIVE) {
            throw new BusinessException("INVALID_TEACHER", "Giáo viên không tồn tại hoặc không hợp lệ.");
        }

        // Validate Course exists, is ACTIVE, and not deleted
        Course course = courseRepository.findByIdAndNotDeleted(request.getCourseId()).orElseThrow(() -> new BusinessException("INVALID_COURSE", "Khóa học không tồn tại."));

        if (course.getStatus() != Course.CourseStatus.ACTIVE) {
            throw new BusinessException("INVALID_COURSE_STATUS", "Chỉ có thể tạo lớp học cho khóa học đang hoạt động.");
        }

        // Create class entity
        Clazz clazz = classMapper.toEntity(request);
        clazz.setCourseId(course.getId());
        clazz.setCreatedBy(managerId);
        clazz.setUpdatedBy(managerId);
        if (request.getMaxStudents() == null) {
            clazz.setMaxStudents(8);
        } else {
            clazz.setMaxStudents(request.getMaxStudents());
        }
        // Start date after today, class is not active
        if (request.getStartDate().isAfter(LocalDate.now())) {
            clazz.setIsActive(false);
        }
        // Save class
        Clazz savedClazz = classRepository.save(clazz);
        // Save teacher assignment in history table
        ClassTeacherHistory history = ClassTeacherHistory.builder()
                .classId(savedClazz.getId())
                .teacherId(teacher.getId())
                .assignedAt(LocalDate.now())
                .build();
        history.setCreatedBy(managerId);
        history.setUpdatedBy(managerId);
        classTeacherHistoryRepository.save(history);

        log.info("Tạo lớp học thành công với ID: {}", savedClazz.getId());

        ClassResponse response = toResponseWithMemberCount(clazz);
        response.setMemberCount(0L);
        return response;
    }

    @Override
    @Transactional
    public ClassResponse updateClass(UUID classId, UpdateClassRequest request, UUID managerId) {
        log.info("Cập nhật lớp học: {} bởi quản lý: {}", classId, managerId);

        Clazz clazz = findClassOrThrow(classId);

        // Check if new name conflicts with existing classes
        if (request.getName() != null && !request.getName().equalsIgnoreCase(clazz.getName()) && classRepository.existsByNameIgnoreCaseAndIdNot(request.getName(), classId)) {
            throw new BusinessException("CLASS_NAME_EXISTS", "Tên lớp học đã tồn tại.");
        }

        // Validate teacher if provided
        if (request.getTeacherId() != null) {

            User newTeacher = userRepository.findById(request.getTeacherId()).orElseThrow(() -> new BusinessException("INVALID_TEACHER", "Giáo viên không tồn tại hoặc không hợp lệ."));

            if (newTeacher.getRole() != UserRole.TEACHER || newTeacher.getStatus() != com.fu.user.entity.UserStatus.ACTIVE) {
                throw new BusinessException("INVALID_TEACHER", "Giáo viên không tồn tại hoặc không hợp lệ.");
            }
        }

        // Validate capacity (E4: newMaxStudents >= currentMembers)
        long currentMembers = classMemberRepository.countActiveByClassId(classId);
        if (request.getMaxStudents() != null) {
            if (request.getMaxStudents() < currentMembers) {
                throw new BusinessException("CAPACITY_VIOLATION", "Số lượng học viên tối đa không thể nhỏ hơn số lượng học viên hiện tại đang theo học.");
            }
        }
        if (currentMembers > 0 && request.getStartDate() != null && request.getStartDate().isAfter(clazz.getStartDate())) {
            throw new BusinessException("INVALID_START_DATE", "Không thể cập nhật ngày bắt đầu sau ngày bắt đầu cũ khi đã có học viên trong lớp.");
        }

        // Auto-activate if startDate moved to today or earlier AND no members AND currently inactive
        if (currentMembers == 0 && request.getStartDate() != null && !request.getStartDate().isAfter(LocalDate.now()) && !clazz.getIsActive()) {
            clazz.setIsActive(true);
            log.info("Lớp học {} được tự động kích hoạt do ngày bắt đầu lùi về hiện tại/quá khứ và chưa có học viên.", classId);
        }

        // Update class (only non-null fields are mapped)
        classMapper.updateEntity(clazz, request);
        clazz.setUpdatedBy(managerId);
        UUID currentTeacherId = classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId).orElse(null);

        // If teacher is changed, update history
        if (request.getTeacherId() != null && !request.getTeacherId().equals(currentTeacherId)) {
            if (currentTeacherId != null) {
                ClassTeacherHistory oldHistory = classTeacherHistoryRepository.findActiveByClassIdAndTeacherId(classId, currentTeacherId)
                        .orElseThrow(() -> new BusinessException("CURRENT_TEACHER_HISTORY_NOT_FOUND", "Không tìm thấy lịch sử giáo viên hiện tại."));
                oldHistory.setReleasedAt(LocalDate.now());
                oldHistory.setUpdatedAt(LocalDateTime.now());
                oldHistory.setUpdatedBy(managerId);
                classTeacherHistoryRepository.save(oldHistory);
            }
            // Create new history record for new teacher
            ClassTeacherHistory newHistory = ClassTeacherHistory.builder()
                    .classId(classId)
                    .teacherId(request.getTeacherId())
                    .assignedAt(LocalDate.now())
                    .build();
            newHistory.setCreatedBy(managerId);
            newHistory.setUpdatedBy(managerId);
            classTeacherHistoryRepository.save(newHistory);
        }
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

        org.springframework.data.jpa.domain.Specification<Clazz> spec = org.springframework.data.jpa.domain.Specification.where(ClassSpecification.isNotDeleted()).and(ClassSpecification.hasKeyword(keyword)).and(ClassSpecification.hasTeacherId(teacherId)).and(ClassSpecification.hasCourseId(courseId)).and(ClassSpecification.hasIsActive(isActive));

        return classRepository.findAll(spec, pageable).map(this::toSummaryWithMemberCount);
    }

    // =====================================================
    // STATUS MANAGEMENT
    // =====================================================

    @Override
    @Transactional
    public ClassResponse deleteClass(UUID classId, UUID managerId) {
        log.info("Xóa lớp học: {} bởi quản lý: {}", classId, managerId);

        Clazz clazz = findClassOrThrow(classId);
        //Check if class has members
        long memberCount = classMemberRepository.countActiveByClassId(classId);
        if (memberCount > 0) {
            throw new BusinessException("CLASS_DELETE_VIOLATION", "Không thể xóa lớp học khi còn học viên đang theo học.");
        }
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
        User user = userRepository.findById(request.getUserId()).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy học viên."));

        if (user.getRole() != UserRole.LEARNER) {
            throw new BusinessException("INVALID_MEMBER_ROLE", "Chỉ người dùng có vai trò HỌC VIÊN mới được thêm vào lớp học.");
        }

        return addMemberToClass(clazz, request.getUserId());
    }

    @Override
    @Transactional
    public void removeMember(UUID classId, UUID userId, UUID managerId) {
        log.info("Xóa thành viên: {} khỏi lớp học: {} bởi quản lý: {}", userId, classId, managerId);

        // Verify class exists
        findClassOrThrow(classId);

        ClassMember member = classMemberRepository.findByClassIdAndUserId(classId, userId).orElseThrow(() -> new BusinessException("MEMBER_NOT_FOUND", "Học viên không phải là thành viên của lớp này."));

        if (member.getRemovedAt() != null) {
            throw new BusinessException("MEMBER_ALREADY_REMOVED", "Học viên đã bị xóa khỏi lớp này trước đó.");
        }

        member.remove();
        classMemberRepository.save(member);
        log.info("Xóa thành viên thành công khỏi lớp học: {}", classId);

        // If they were removed from class, demote role status to INACTIVE
        userRepository.findById(userId).ifPresent(user -> {
            if (user.getRole() == UserRole.LEARNER && user.getStatus() == UserStatus.ACTIVE) {
                user.setStatus(UserStatus.INACTIVE);
                userRepository.save(user);
                log.info("Learner {} demoted to INACTIVE as they have no remaining active class memberships.", userId);
            }
        });
    }

    @Override
    public Page<ClassMemberResponse> getClassMembers(UUID classId, String keyword, Pageable pageable) {
        log.debug("Lấy danh sách thành viên của lớp: {} với từ khóa: {}", classId, keyword);

        // Verify class exists
        findClassOrThrow(classId);

        Page<ClassMember> membersPage = classMemberRepository.findActiveByClassIdAndKeyword(classId, keyword, pageable);

        // Batch fetch user info to avoid N+1 queries
        List<UUID> userIds = membersPage.getContent().stream().map(ClassMember::getUserId).toList();
        Map<UUID, User> userMap = userRepository.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, user -> user));

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
        verifyTeacherAccess(clazz, teacherId);

        return toResponseWithMemberCount(clazz);
    }

    @Override
    public Page<ClassMemberResponse> getClassMembersForTeacher(UUID classId, UUID teacherId, String keyword, Pageable pageable) {
        log.debug("Giáo viên {} xem thành viên lớp: {} với từ khóa: {}", teacherId, classId, keyword);
        Clazz clazz = findClassOrThrow(classId);

        verifyTeacherAccess(clazz, teacherId);

        return getClassMembers(classId, keyword, pageable);
    }

    @Override
    public Page<com.fu.user.dto.UserResponse> getEligibleLearners(UUID classId, String keyword, Pageable pageable) {
        log.debug("Lấy danh sách học viên đủ điều kiện thêm vào lớp: {}", classId);

        // Verify class exists
        findClassOrThrow(classId);
        List<UUID> excludedIds = classMemberRepository.findUserIdsInActiveClasses();
        Page<User> usersPage = userRepository.findEligibleLearnersForClass(keyword, excludedIds, pageable);

        return usersPage.map(user -> com.fu.user.dto.UserResponse.builder().id(user.getId()).email(user.getEmail()).firstName(user.getFirstName()).lastName(user.getLastName()).displayName(user.getDisplayName()).fullName(user.getFullName()).avatarUrl(user.getAvatarUrl()).phoneNumber(user.getPhoneNumber()).dateOfBirth(user.getDateOfBirth()).role(user.getRole()).status(user.getStatus()).createdAt(user.getCreatedAt()).updatedAt(user.getUpdatedAt()).lastLoginAt(user.getLastLoginAt()).build());
    }

    /**
     * Deactivate all members of a class.
     * Business rule: A learner is set to INACTIVE if their class is deleted or deactivated.
     */
    private void deactivateMembers(UUID classId) {
        List<UUID> memberIds = classMemberRepository.findActiveUserIdsByClassId(classId);
        if (!memberIds.isEmpty()) {
            log.info("Deactivating {} members from class {}", memberIds.size(), classId);
            userRepository.updateStatusByUserIdIn(memberIds, UserStatus.INACTIVE);
        }
    }

    // =====================================================
    // HELPER METHODS
    // =====================================================
    private void verifyTeacherAccess(Clazz clazz, UUID teacherId) {
        UUID currentTeacherId = classTeacherHistoryRepository.findCurrentTeacherIdByClassId(clazz.getId()).orElse(null);
        if (clazz != null && (currentTeacherId == null || !currentTeacherId.equals(teacherId))) {
            throw new BusinessException("ACCESS_DENIED", "Bạn không phải là giáo viên của lớp này.");
        }
    }

    /**
     * Find non-deleted class or throw exception (BR-73)
     */
    private Clazz findClassOrThrow(UUID classId) {
        return classRepository.findByIdAndNotDeleted(classId).orElseThrow(() -> new ClassNotFoundException("Lớp học không tồn tại."));
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
            member = ClassMember.builder().classId(clazz.getId()).userId(userId).build();
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
        User learner = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy học viên."));
        // If learner was previously INACTIVE, set to ACTIVE upon joining a class
        if (learner.getStatus() == UserStatus.INACTIVE) {
            learner.setStatus(UserStatus.ACTIVE);
        }
        userRepository.save(learner);
        return response;
    }

    /**
     * Map to ClassResponse with computed memberCount
     */
    private ClassResponse toResponseWithMemberCount(Clazz clazz) {
        ClassResponse response = classMapper.toResponse(clazz);
        response.setMemberCount(classMemberRepository.countActiveByClassId(clazz.getId()));
        UUID currentTeacherId = classTeacherHistoryRepository.findCurrentTeacherIdByClassId(clazz.getId()).orElse(null);
        // Enrich with teacher info
        if (currentTeacherId != null) {
            userRepository.findById(currentTeacherId).ifPresent(teacher -> {
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
        UUID currentTeacherId = classTeacherHistoryRepository.findCurrentTeacherIdByClassId(clazz.getId()).orElse(null);
        if (clazz.getCourseId() != null) {
            courseRepository.findById(clazz.getCourseId()).ifPresent(course -> {
                response.setCourseName(course.getName());
                response.setCourseLevel(course.getJlptLevel() != null ? course.getJlptLevel().name() : null);
                response.setCourseStatus(course.getStatus() != null ? course.getStatus().name() : null);
            });
        }
        if (currentTeacherId != null) {
            userRepository.findById(currentTeacherId).ifPresent(teacher -> {
                response.setTeacherName(teacher.getFullName());
            });
        }

        return response;
    }
}

