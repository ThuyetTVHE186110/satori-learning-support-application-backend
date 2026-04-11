package com.fu.clazz.service.impl;

import com.fu.clazz.dto.request.AddMemberRequest;
import com.fu.clazz.dto.request.AddMembersRequest;
import com.fu.clazz.dto.request.CreateClassRequest;
import com.fu.clazz.dto.request.UpdateClassRequest;
import com.fu.clazz.dto.response.ClassMemberResponse;
import com.fu.clazz.dto.response.ClassResponse;
import com.fu.clazz.dto.response.ClassSummaryResponse;
import com.fu.clazz.dto.response.LearnerClassResponse;
import com.fu.clazz.dto.response.TeacherClassHistoryResponse;
import com.fu.clazz.entity.ClassMember;
import com.fu.clazz.entity.ClassStatus;
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
import com.fu.clazz.scheduler.ClassScheduleManager;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
    private final ClassScheduleManager classScheduleManager;

    // =====================================================
    // CLASS CRUD
    // =====================================================
    @Override
    @Transactional
    public ClassResponse closeClass(UUID classId, UUID managerId) {
        log.debug("Starting closeClass for classId: {}, managerId: {}", classId, managerId);

        Clazz clazz = findClassOrThrow(classId);
        if (clazz.getStatus() == ClassStatus.CLOSED) {
            throw new BusinessException("CLASS_ALREADY_INACTIVE", "Lớp học đã ngưng hoạt động.");
        }

        clazz.setStatus(ClassStatus.CLOSED);
        clazz.setEndDate(LocalDate.now());
        clazz.setUpdatedBy(managerId);

        Clazz updatedClazz = classRepository.save(clazz);

        // Deactivate all members when class is closed
        deactivateMembers(classId);
        log.info("Đóng lớp học thành công: {}", classId);
        ClassResponse response = toResponseWithMemberCount(updatedClazz);
        return response;
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
        // Status logic: if startDate is today or in the past, set ACTIVE. Otherwise NOT_STARTED (default)
        if (!request.getStartDate().isAfter(LocalDate.now())) {
            clazz.setStatus(ClassStatus.ACTIVE);
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
        // Dynamic scheduling for activation
        classScheduleManager.scheduleActivation(savedClazz);

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
        if (clazz.getStatus() == ClassStatus.CLOSED || clazz.getStatus() == ClassStatus.DELETED) {
            throw new BusinessException("CLASS_INACTIVE", "Không thể cập nhật lớp học đã ngưng hoạt động hoặc bị xóa.");
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
        if (clazz.getStatus() == ClassStatus.ACTIVE && request.getStartDate() != null && !request.getStartDate().isEqual(clazz.getStartDate())) {
            throw new BusinessException("INVALID_START_DATE", "Không thể cập nhật ngày bắt đầu sau khi lớp học đã hoạt động.");
        }

        // Auto-activate if startDate moved to today or earlier AND no members AND currently NOT_STARTED
        if (currentMembers == 0 && request.getStartDate() != null && !request.getStartDate().isAfter(LocalDate.now()) && clazz.getStatus() == ClassStatus.NOT_STARTED) {
            clazz.setStatus(ClassStatus.ACTIVE);
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

        // Re-schedule activation if startDate or status changed
        classScheduleManager.scheduleActivation(updatedClazz);

        return toResponseWithMemberCount(updatedClazz);

    }

    @Override
    public ClassResponse getClassById(UUID classId) {
        log.debug("Lấy thông tin lớp học theo ID: {}", classId);
        Clazz clazz = findClassOrThrow(classId);
        return toResponseWithMemberCount(clazz);
    }

    @Override
    public Page<ClassSummaryResponse> getClasses(String keyword, UUID teacherId, UUID courseId, ClassStatus status, Pageable pageable) {
        log.debug("Lấy danh sách lớp học với bộ lọc động");

        org.springframework.data.jpa.domain.Specification<Clazz> spec = org.springframework.data.jpa.domain.Specification.where(ClassSpecification.isNotDeleted()).and(ClassSpecification.hasKeyword(keyword)).and(ClassSpecification.hasTeacherId(teacherId)).and(ClassSpecification.hasCourseId(courseId)).and(ClassSpecification.hasStatus(status));

        Page<Clazz> classPage = classRepository.findAll(spec, pageable);
        
        // If no status filter and pageable has no sort, apply custom status priority sorting
        if (status == null && !pageable.getSort().isSorted()) {
            List<Clazz> sortedClasses = classPage.getContent().stream()
                    .sorted((c1, c2) -> {
                        // Priority order: ACTIVE (0) > NOT_STARTED (1) > CLOSED (2)
                        int priority1 = getStatusPriority(c1.getStatus());
                        int priority2 = getStatusPriority(c2.getStatus());
                        
                        if (priority1 != priority2) {
                            return Integer.compare(priority1, priority2);
                        }
                        // Same priority: sort by createdAt descending
                        return c2.getCreatedAt().compareTo(c1.getCreatedAt());
                    })
                    .toList();
            
            return new org.springframework.data.domain.PageImpl<>(
                    sortedClasses.stream().map(this::toSummaryWithMemberCount).toList(),
                    pageable,
                    classPage.getTotalElements()
            );
        }
        
        return classPage.map(this::toSummaryWithMemberCount);
    }
    
    /**
     * Get status priority for sorting (lower = higher priority)
     * Order: ACTIVE (0) > NOT_STARTED (1) > CLOSED (2)
     */
    private int getStatusPriority(ClassStatus status) {
        return switch (status) {
            case ACTIVE -> 0;
            case NOT_STARTED -> 1;
            case CLOSED -> 2;
            default -> 3;
        };
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
        clazz.setStatus(ClassStatus.DELETED);

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
        if (user.getStatus() != UserStatus.INACTIVE) {
            throw new BusinessException("INVALID_MEMBER_STATUS", "Chỉ có học viên hiện tại không học lớp nào mới được thêm vào lớp học.");
        }
        return addMemberToClass(clazz, request.getUserId());
    }

    @Override
    @Transactional
    public List<ClassMemberResponse> addMembers(UUID classId, AddMembersRequest request, UUID managerId) {
        log.info("Thêm {} thành viên vào lớp học: {} bởi quản lý: {}", request.getLearnerIds().size(), classId, managerId);

        // 1. Find and validate class
        Clazz clazz = findClassOrThrow(classId);
        validateClassAcceptingMembers(clazz);

        // 2. Get current active member count
        long currentMemberCount = classMemberRepository.countActiveByClassId(classId);

        // 3. Validate learnerIds not empty
        List<UUID> learnerIds = request.getLearnerIds();
        if (learnerIds == null || learnerIds.isEmpty()) {
            throw new BusinessException("INVALID_REQUEST", "Cần phải cung cấp ít nhất một ID học viên.");
        }

        // 4. Validate each learner before any database changes
        List<User> learnersToAdd = new ArrayList<>();
        Map<UUID, ClassMember> existingMembers = new HashMap<>();

        for (UUID learnerId : learnerIds) {
            // Find user
            User user = userRepository.findById(learnerId)
                    .filter(u -> u.getDeletedAt() == null)
                    .orElseThrow(() -> new ResourceNotFoundException("User", "id", learnerId.toString()));

            // Check status = INACTIVE
            if (user.getStatus() != UserStatus.INACTIVE) {
                throw new BusinessException("INVALID_MEMBER_STATUS", 
                        "Học viên " + learnerId + " phải có trạng thái KHÔNG HOẠT ĐỘNG.");
            }

            // Validate user has LEARNER role
            if (user.getRole() != UserRole.LEARNER) {
                throw new BusinessException("INVALID_MEMBER_ROLE", 
                        "Chỉ người dùng có vai trò HỌC VIÊN mới được thêm vào lớp học.");
            }

            // Check existing ClassMember
            Optional<ClassMember> existing = classMemberRepository.findByClassIdAndUserId(classId, learnerId);
            if (existing.isPresent()) {
                ClassMember member = existing.get();
                if (member.isActive()) {
                    // Already an active member
                    throw new BusinessException("MEMBER_ALREADY_EXISTS", 
                            "Học viên " + learnerId + " đã là thành viên của lớp này.");
                }
                // Store for restoration
                existingMembers.put(learnerId, member);
            }

            learnersToAdd.add(user);
        }

        // 5. Check capacity: currentCount + new learners > maxStudents?
        if (currentMemberCount + learnerIds.size() > clazz.getMaxStudents()) {
            throw new BusinessException("CLASS_FULL", 
                    "Vượt quá sức chứa: lớp có " + currentMemberCount + " học viên, tối đa là " 
                    + clazz.getMaxStudents() + ", không thể thêm " + learnerIds.size() + ".");
        }

        // 6. Add/restore all members (transactional)
        List<ClassMember> addedMembers = new ArrayList<>();
        for (UUID learnerId : learnerIds) {
            ClassMember member;
            if (existingMembers.containsKey(learnerId)) {
                // Restore removed member
                member = existingMembers.get(learnerId);
                member.restore();  // Sets removedAt = null, joinedAt = now
            } else {
                // Create new member
                member = ClassMember.builder()
                        .classId(classId)
                        .userId(learnerId)
                        .build();
            }
            addedMembers.add(classMemberRepository.save(member));
        }

        // 7. Update learner status to ACTIVE
        for (User learner : learnersToAdd) {
            if (learner.getStatus() == UserStatus.INACTIVE) {
                learner.setStatus(UserStatus.ACTIVE);
                userRepository.save(learner);
            }
        }

        // 8. Build and return responses
        Map<UUID, User> userMap = learnersToAdd.stream()
                .collect(Collectors.toMap(User::getId, user -> user));

        log.info("Thêm {} thành viên vào lớp học: {} thành công", learnerIds.size(), classId);

        return addedMembers.stream()
                .map(member -> {
                    ClassMemberResponse response = classMapper.toMemberResponse(member);
                    User user = userMap.get(member.getUserId());
                    if (user != null) {
                        response.setFullName(user.getFullName());
                        response.setEmail(user.getEmail());
                        response.setAvatarUrl(user.getAvatarUrl());
                        response.setRole(user.getRole().name());
                        response.setPhoneNumber(user.getPhoneNumber());
                    }
                    return response;
                })
                .collect(Collectors.toList());
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
                response.setPhoneNumber(user.getPhoneNumber());
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
    public Page<com.fu.user.dto.UserResponse> getEligibleLearners(UUID classId, String keyword, UUID filterByClassId, Pageable pageable) {
        log.debug("Lấy danh sách học viên đủ điều kiện thêm vào lớp: {} với filter: {}", classId, filterByClassId);

        // Verify class exists
        findClassOrThrow(classId);

        // If filterByClassId is provided, validate it exists and is CLOSED
        if (filterByClassId != null) {
            Clazz filterClass = findClassOrThrow(filterByClassId);
            if (filterClass.getStatus() != ClassStatus.CLOSED) {
                throw new BusinessException("INVALID_FILTER_CLASS", "Lớp lọc phải có trạng thái CLOSED.");
            }
        }
        
        List<UUID> excludedIds = classMemberRepository.findUserIdsInActiveClasses();
        Page<User> usersPage = userRepository.findEligibleLearnersForClass(keyword, excludedIds, pageable);
        
        List<com.fu.user.dto.UserResponse> responses = usersPage.getContent().stream()
                .map(user -> com.fu.user.dto.UserResponse.builder()
                        .id(user.getId())
                        .email(user.getEmail())
                        .firstName(user.getFirstName())
                        .lastName(user.getLastName())
                        .displayName(user.getDisplayName())
                        .fullName(user.getFullName())
                        .avatarUrl(user.getAvatarUrl())
                        .phoneNumber(user.getPhoneNumber())
                        .dateOfBirth(user.getDateOfBirth())
                        .role(user.getRole())
                        .status(user.getStatus())
                        .createdAt(user.getCreatedAt())
                        .updatedAt(user.getUpdatedAt())
                        .lastLoginAt(user.getLastLoginAt())
                        .build())
                .toList();
        
        // If filterByClassId is provided, filter by membership in that class
        if (filterByClassId != null) {
            List<UUID> filterClassMemberIds = classMemberRepository.findUserIdsByClosedClass(filterByClassId);
            responses = responses.stream()
                    .filter(response -> filterClassMemberIds.contains(response.getId()))
                    .toList();
        }
        
        return new PageImpl<>(responses, pageable, responses.size());
    }

    /**
     * Deactivate all members of a class.
     * Business rule: A learner is set to INACTIVE if their class is deleted or deactivated.
     */
    private void deactivateMembers(UUID classId) {
        try {
            List<UUID> memberIds = classMemberRepository.findActiveUserIdsByClassId(classId);
            if (!memberIds.isEmpty()) {
                userRepository.updateStatusByUserIdIn(memberIds, UserStatus.INACTIVE);
            }
        } catch (Exception e) {
            log.error("ERROR deactivating members for class: {}", classId, e);
            throw new BusinessException("MEMBER_DEACTIVATION_FAILED", "Có lỗi xảy ra khi cập nhật trạng thái học viên sau khi đóng lớp học.");
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
        if (clazz.getStatus() == ClassStatus.CLOSED || clazz.getStatus() == ClassStatus.DELETED) {
            throw new BusinessException("CLASS_INACTIVE", "Không thể thêm học viên vào lớp đã ngưng hoạt động hoặc bị xóa.");
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
            response.setPhoneNumber(user.getPhoneNumber());
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
                response.setTeacherId(teacher.getId());
                response.setTeacherName(teacher.getFullName());
                response.setTeacherEmail(teacher.getEmail());
                response.setTeacherAvatarUrl(teacher.getAvatarUrl());
                response.setTeacherPhoneNumber(teacher.getPhoneNumber());
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
                response.setTeacherId(teacher.getId());
                response.setTeacherName(teacher.getFullName());
            });
        }

        return response;
    }

    // =====================================================
    // LEARNER QUERIES
    // =====================================================
    @Override
    public List<LearnerClassResponse> getLearnerClasses(UUID learnerId) {
        log.info("Lấy danh sách lớp học của học viên: {}", learnerId);

        // Query for classes with status ACTIVE or CLOSED where learner is an active member
        List<ClassStatus> allowedStatuses = List.of(ClassStatus.ACTIVE, ClassStatus.CLOSED);
        List<Clazz> classes = classRepository.findLearnerClasses(learnerId, allowedStatuses);

        // Map to lightweight response
        return classes.stream()
                .map(classMapper::toLearnerClassResponse)
                .collect(Collectors.toList());
    }

    // =====================================================
    // TEACHER CLASS HISTORY
    // =====================================================
    @Override
    public Page<TeacherClassHistoryResponse> getTeacherClassHistory(UUID teacherId, Pageable pageable) {
        log.info("Lấy danh sách lớp đã/đang dạy của giáo viên: {}", teacherId);

        // Fetch teacher's class history sorted by assignedAt DESC
        Page<ClassTeacherHistory> historyPage = classTeacherHistoryRepository.findTeacherClassHistory(teacherId, pageable);

        // Map each history record to response DTO with class information
        List<TeacherClassHistoryResponse> responses = historyPage.getContent().stream()
                .map(history -> {
                    Clazz clazz = classRepository.findById(history.getClassId())
                            .orElse(null);

                    if (clazz == null) {
                        log.warn("Không tìm thấy lớp học với ID: {}", history.getClassId());
                        return null;
                    }

                    TeacherClassHistoryResponse response = classMapper.toTeacherClassHistoryResponse(clazz);
                    
                    // Enrich with course info
                    if (clazz.getCourseId() != null) {
                        courseRepository.findById(clazz.getCourseId()).ifPresent(course -> {
                            response.setCourseName(course.getName());
                            response.setCourseLevel(course.getJlptLevel() != null ? course.getJlptLevel().name() : null);
                        });
                    }

                    return response;
                })
                .filter(r -> r != null)
                .collect(Collectors.toList());

        return new org.springframework.data.domain.PageImpl<>(responses, pageable, historyPage.getTotalElements());
    }
}
