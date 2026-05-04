package com.fu.clazz.service.impl;

import com.fu.clazz.dto.request.AddMemberRequest;
import com.fu.clazz.dto.request.AddMembersRequest;
import com.fu.clazz.dto.request.ApproveImportClassesRequest;
import com.fu.clazz.dto.request.CreateClassRequest;
import com.fu.clazz.dto.request.UpdateClassRequest;
import com.fu.clazz.dto.response.ClassImportError;
import com.fu.clazz.dto.response.ClassMemberResponse;
import com.fu.clazz.dto.response.ClassPreview;
import com.fu.clazz.dto.response.ClassProgressResponse;
import com.fu.clazz.dto.response.ClassProgressStatsResponse;
import com.fu.clazz.dto.response.ClassResponse;
import com.fu.clazz.dto.response.ClassSummaryResponse;
import com.fu.clazz.dto.response.ImportClassesPreview;
import com.fu.clazz.dto.response.ImportClassesResult;
import com.fu.clazz.dto.response.LearnerClassResponse;
import com.fu.clazz.dto.response.LearnerInfo;
import com.fu.clazz.dto.response.LearnerProgressResponse;
import com.fu.clazz.dto.response.LearningTrendDataPoint;
import com.fu.clazz.dto.response.TeacherClassHistoryResponse;
import com.fu.clazz.utils.ClassExcelRow;
import com.fu.clazz.utils.ExcelClassImportUtil;
import com.fu.clazz.utils.LearnerExcelRow;
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
import com.fu.course.repository.LessonRepository;
import com.fu.user.entity.User;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import com.fu.user.repository.UserRepository;
import com.fu.clazz.repository.ClassProgressAnalyticsRepository;
import com.fu.clazz.dto.analytics.LearnerProgressMetrics;
import com.fu.clazz.dto.analytics.ClassMemberInfo;
import com.fu.clazz.dto.analytics.LearningTrendMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.math.BigDecimal;

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
    private final LessonRepository lessonRepository;
    private final ClassTeacherHistoryRepository classTeacherHistoryRepository;
    private final ClassScheduleManager classScheduleManager;
    private final ClassProgressAnalyticsRepository analyticsRepository;

    // =====================================================
    // HELPER METHODS
    // =====================================================

    /**
     * Safely trims a string value, handling null gracefully
     *
     * @param value the string to trim, can be null
     * @return trimmed string or null if input is null
     */
    private String safeGetTrimmedValue(String value) {
        return value != null ? value.trim() : null;
    }

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

        // Validate expectedEndDate >= startDate if both are provided
        if (request.getExpectedEndDate() != null && request.getStartDate() != null &&
                request.getExpectedEndDate().isBefore(request.getStartDate())) {
            throw new BusinessException("INVALID_EXPECTED_END_DATE", "Ngày kết thúc dự kiến" + request.getExpectedEndDate() + " không được trước ngày bắt đầu " + request.getStartDate());
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

        // Validate expectedEndDate >= startDate if both are provided
        LocalDate effectiveStartDate = request.getStartDate() != null ? request.getStartDate() : clazz.getStartDate();
        LocalDate effectiveExpectedEndDate = request.getExpectedEndDate() != null ? request.getExpectedEndDate() : clazz.getExpectedEndDate();
        if (effectiveStartDate != null && effectiveExpectedEndDate != null && effectiveExpectedEndDate.isBefore(effectiveStartDate)) {
            throw new BusinessException("INVALID_EXPECTED_END_DATE", "Ngày kết thúc dự kiến không được trước ngày bắt đầu.");
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

            // Validate user has LEARNER role
            if (user.getRole() != UserRole.LEARNER) {
                throw new BusinessException("INVALID_MEMBER_ROLE",
                        "Chỉ người dùng có vai trò HỌC VIÊN mới được thêm vào lớp học.");
            }
            // Check status = INACTIVE
            if (user.getStatus() != UserStatus.INACTIVE) {
                throw new BusinessException("INVALID_MEMBER_STATUS",
                        "Học viên " + learnerId + " phải có trạng thái KHÔNG HOẠT ĐỘNG.");
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
     * Step 1: Import classes from Excel file (Preview)
     * Reads Excel file, validates data, and returns preview for client confirmation.
     *
     * @param file      Excel file (.xlsx or .xls)
     * @param managerId ID of the manager performing import
     * @return ImportClassesPreview containing validation results and data for step 2
     * @throws IOException if file read fails
     */
    @Override
    @Transactional(readOnly = true)
    public ImportClassesPreview importClassesFromExcel(MultipartFile file, UUID managerId) throws Exception {
        log.info("Manager {} starting class import from Excel", managerId);

        List<ClassExcelRow> excelRows = ExcelClassImportUtil.readClassesFromExcel(file);
        Map<String, List<LearnerExcelRow>> learnersByClass = ExcelClassImportUtil.readLearnersDetailedFromExcel(file);

        List<ClassImportError> errors = new ArrayList<>();
        List<ClassPreview> validClasses = new ArrayList<>();
        Set<String> seenClassNames = new HashSet<>();

        // Step 1: Build map of learner email -> classes with status info (for enhanced multi-class detection)
        // Map: learnerEmail -> Map(className -> classStatus)
        Map<String, Map<String, ClassStatus>> learnerToClassesWithStatus = new HashMap<>();
        Map<String, String> classNameToIdMap = new HashMap<>();  // className -> classId for lookups
        log.debug("Building learner-to-classes map from {} excel rows and learner data", excelRows.size());
        log.debug("Learner data available for classes: {}", learnersByClass.keySet());

        for (ClassExcelRow excelRow : excelRows) {
            if (excelRow.isEmpty()) continue;

            String className = excelRow.getName().trim();

            // Determine status based on startDate (same logic as later in code)
            ClassStatus status = ClassStatus.NOT_STARTED;
            if (excelRow.getStartDate() != null && !excelRow.getStartDate().isAfter(LocalDate.now())) {
                status = ClassStatus.ACTIVE;
            }

            // Try to find existing class to get its actual status
            Clazz existingClass = classRepository.findByNameIgnoreCaseAndNotDeleted(className).orElse(null);
            if (existingClass != null) {
                status = existingClass.getStatus();
                classNameToIdMap.put(className, existingClass.getId().toString());
            }

            // Try to find learners for this class (case-insensitive)
            List<LearnerExcelRow> learnerExcelRows = new ArrayList<>();
            for (String key : learnersByClass.keySet()) {
                if (key.equalsIgnoreCase(className)) {
                    learnerExcelRows = learnersByClass.get(key);
                    break;
                }
            }

            log.debug("Class '{}' has {} learners in import data, status={}", className, learnerExcelRows.size(), status);

            for (LearnerExcelRow learnerExcelRow : learnerExcelRows) {
                String learnerEmail = learnerExcelRow.getEmail().trim().toLowerCase();
                learnerToClassesWithStatus
                        .computeIfAbsent(learnerEmail, k -> new HashMap<>())
                        .put(className, status);
            }
        }

        // Pre-load all ClassMember records for CLOSED/DELETED classes (for ghost learner detection)
        Map<String, ClassMember> classMemberLookup = new HashMap<>();
        for (ClassExcelRow excelRow : excelRows) {
            if (excelRow.isEmpty()) continue;
            Clazz existingClass = classRepository.findByNameIgnoreCaseAndNotDeleted(excelRow.getName().trim()).orElse(null);
            if (existingClass != null && (existingClass.getStatus() == ClassStatus.CLOSED || existingClass.getStatus() == ClassStatus.DELETED)) {
                // Load all members for this closed/deleted class
                List<ClassMember> members = classMemberRepository.findAllByClassId(existingClass.getId());
                for (ClassMember member : members) {
                    String key = existingClass.getId() + ":" + member.getUserId();
                    classMemberLookup.put(key, member);
                }
            }
        }

        // Track learners already assigned to a class in this batch (first-come-first-served)
        // key = learnerEmail, value = className that claimed this learner first
        Map<String, String> assignedLearnersInBatch = new HashMap<>();

        for (ClassExcelRow excelRow : excelRows) {
            if (excelRow.isEmpty()) {
                continue; // Skip empty rows
            }
            // Validate row
            Optional<ClassImportError> validationError = ExcelClassImportUtil.validateClassRow(excelRow);
            if (validationError.isPresent()) {
                errors.add(validationError.get());
                continue;
            }

            String className = excelRow.getName().trim();

            // Check for duplicates within batch
            if (seenClassNames.contains(className)) {
                errors.add(ClassImportError.builder()
                        .rowNumber(excelRow.getRowNumber())
                        .className(className)
                        .errorMessage("Tên lớp bị trùng lặp trong file import này")
                        .build());
                continue;
            }
            seenClassNames.add(className);

            // Find course by name
            Course course = courseRepository.findByName(excelRow.getCourseName().trim()).orElse(null);
            if (course == null) {
                errors.add(ClassImportError.builder()
                        .rowNumber(excelRow.getRowNumber())
                        .className(className)
                        .errorMessage("Không tìm thấy khóa học: " + excelRow.getCourseName())
                        .build());
                continue;
            }

            // Find teacher by email
            String teacherEmail = safeGetTrimmedValue(excelRow.getTeacherEmail());
            if (teacherEmail == null || teacherEmail.isEmpty()) {
                errors.add(ClassImportError.builder()
                        .rowNumber(excelRow.getRowNumber())
                        .className(className)
                        .errorMessage("Email giảng viên không được để trống")
                        .build());
                continue;
            }
            User teacher = userRepository.findByEmail(teacherEmail).orElse(null);
            if (teacher == null || teacher.getRole() != UserRole.TEACHER) {
                errors.add(ClassImportError.builder()
                        .rowNumber(excelRow.getRowNumber())
                        .className(className)
                        .errorMessage("Giảng viên không tồn tại hoặc không có quyền TEACHER: " + teacherEmail)
                        .build());
                continue;
            }

            // Determine type: NEW or UPDATE
            boolean classExists = classRepository.existsByNameIgnoreCase(className);
            Clazz existingClass = null;
            if (classExists) {
                // Retrieve the actual class for learner membership checks
                existingClass = classRepository.findByNameIgnoreCase(className).orElse(null);
            }

            // Determine status based on startDate
            ClassStatus status = ClassStatus.NOT_STARTED;
            if (excelRow.getStartDate() != null && !excelRow.getStartDate().isAfter(LocalDate.now())) {
                status = ClassStatus.ACTIVE;
            }
            if (existingClass != null &&
                    (existingClass.getStatus().equals(ClassStatus.ACTIVE) ||
                            existingClass.getStatus().equals(ClassStatus.CLOSED) ||
                            existingClass.getStatus().equals(ClassStatus.DELETED))
            ) {
                status = existingClass.getStatus();
            }

            // Build learner list with full details and validate
            List<LearnerInfo> learnersToAdd = new ArrayList<>();

            // Find learners for this class (case-insensitive)
            List<LearnerExcelRow> learnerExcelRows = new ArrayList<>();
            for (String key : learnersByClass.keySet()) {
                if (key.equalsIgnoreCase(className)) {
                    learnerExcelRows = learnersByClass.get(key);
                    break;
                }
            }

            // Check for duplicate learners in this class first and add consolidated errors
            List<ClassImportError> duplicateErrors = ExcelClassImportUtil.findDuplicateLearnerErrors(
                    learnerExcelRows, className, excelRow.getRowNumber());
            errors.addAll(duplicateErrors);

            // Build set of emails with duplicates to skip them during processing
            Set<String> duplicateLearnerEmails = duplicateErrors.stream()
                    .map(error -> {
                        // Extract email from error message: "Học sinh {email} xuất hiện..."
                        String msg = error.getErrorMessage();
                        if (msg.contains("xuất hiện")) {
                            int startIdx = msg.indexOf("Học sinh ") + 9;
                            int endIdx = msg.indexOf(" xuất hiện");
                            if (startIdx > 8 && endIdx > startIdx) {
                                return msg.substring(startIdx, endIdx).toLowerCase();
                            }
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            for (LearnerExcelRow learnerExcelRow : learnerExcelRows) {
                String learnerEmail = safeGetTrimmedValue(learnerExcelRow.getEmail());

                // Validate email is not null
                if (learnerEmail == null || learnerEmail.isEmpty()) {
                    errors.add(ClassImportError.builder()
                            .rowNumber(excelRow.getRowNumber())
                            .className(className)
                            .errorMessage("Email học sinh không được để trống")
                            .build());
                    continue;
                }

                learnerEmail = learnerEmail.toLowerCase();

                // Skip learners that are duplicates in this class
                if (duplicateLearnerEmails.contains(learnerEmail)) {
                    log.debug("Skipping duplicate learner {} in class {}", learnerEmail, className);
                    continue;
                }

                User learner = userRepository.findByEmail(learnerEmail).orElse(null);
                if (learner == null) {
                    errors.add(ClassImportError.builder()
                            .rowNumber(excelRow.getRowNumber())
                            .className(className)
                            .errorMessage("Học sinh không tồn tại: " + learnerExcelRow.getEmail())
                            .build());
                    continue;
                }

                // Validate learner not deleted
                if (learner.getDeletedAt() != null) {
                    errors.add(ClassImportError.builder()
                            .rowNumber(excelRow.getRowNumber())
                            .className(className)
                            .errorMessage("Học sinh đã bị xóa: " + learnerExcelRow.getEmail())
                            .build());
                    continue;
                }

                // Validate learner in multiple classes — first-come-first-served
                if (assignedLearnersInBatch.containsKey(learnerEmail)) {
                    String assignedClassName = assignedLearnersInBatch.get(learnerEmail);
                    errors.add(ClassImportError.builder()
                            .rowNumber(excelRow.getRowNumber())
                            .className(className)
                            .errorMessage(String.format(
                                    "Học sinh %s đã được thêm vào lớp %s trong file import này, không thể thêm vào nhiều lớp cùng lúc",
                                    learnerEmail, assignedClassName))
                            .build());
                    continue;
                }

                // For learners in CLOSED/DELETED classes: must have existing membership record
                Map<String, ClassStatus> learnerClassMap = learnerToClassesWithStatus.get(learnerEmail);
                if (learnerClassMap != null && learnerClassMap.size() > 1) {
                    boolean hasClosedDeletedError = false;
                    for (Map.Entry<String, ClassStatus> entry : learnerClassMap.entrySet()) {
                        ClassStatus entryStatus = entry.getValue();
                        if (entryStatus == ClassStatus.CLOSED || entryStatus == ClassStatus.DELETED) {
                            String closedClassId = classNameToIdMap.get(entry.getKey());
                            if (closedClassId != null) {
                                String memberKey = closedClassId + ":" + learner.getId();
                                ClassMember membership = classMemberLookup.get(memberKey);
                                if (membership == null) {
                                    errors.add(ClassImportError.builder()
                                            .rowNumber(excelRow.getRowNumber())
                                            .className(className)
                                            .errorMessage(String.format(
                                                    "Không tìm thấy ghi nhận học viên %s từng học ở lớp %s (Lớp này đã bị xóa hoặc đã đóng).",
                                                    learnerEmail, entry.getKey()))
                                            .build());
                                    hasClosedDeletedError = true;
                                    break;
                                }
                                if (entryStatus == ClassStatus.DELETED) {
                                    errors.add(ClassImportError.builder()
                                            .rowNumber(excelRow.getRowNumber())
                                            .className(className)
                                            .errorMessage(String.format(
                                                    "Không thể thêm học viên %s vào lớp %s đã bị xóa",
                                                    learnerEmail, entry.getKey()))
                                            .build());
                                    hasClosedDeletedError = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (hasClosedDeletedError) continue;
                }

                // For UPDATE classes: Skip learners already in this class
                if (classExists && existingClass != null) {
                    boolean alreadyInClass = classMemberRepository
                            .findByClassIdAndUserId(existingClass.getId(), learner.getId())
                            .map(member -> member.getRemovedAt() == null) // Check if not soft-deleted
                            .orElse(false);

                    if (alreadyInClass) {
                        log.debug("Learner {} already in class {}, skipping", learner.getEmail(), className);
                        continue;
                    }
                }

                // Validate learner status — must be INACTIVE (not enrolled in any other class)
                if (learner.getStatus() == UserStatus.ACTIVE) {
                    errors.add(ClassImportError.builder()
                            .rowNumber(excelRow.getRowNumber())
                            .className(className)
                            .errorMessage(String.format("Học sinh %s đang ở lớp khác (trạng thái ACTIVE), không thể thêm vào lớp này",
                                    learnerExcelRow.getEmail()))
                            .build());
                    continue;
                }

                // Check information of learner in excel file is different from database
                // Use Objects.equals() for null-safe comparison
                // Normalize blank/null strings to null before comparing to treat "" and null as equal
                String excelName = safeGetTrimmedValue(learnerExcelRow.getName());
                String excelPhone = safeGetTrimmedValue(learnerExcelRow.getPhoneNumber());
                String dbPhone = StringUtils.hasText(learner.getPhoneNumber()) ? learner.getPhoneNumber().trim() : null;
                String normalizedExcelPhone = StringUtils.hasText(excelPhone) ? excelPhone : null;

                if (!Objects.equals(learner.getFullName(), excelName) ||
                        !Objects.equals(learner.getDateOfBirth(), learnerExcelRow.getDateOfBirth()) ||
                        !Objects.equals(dbPhone, normalizedExcelPhone)) {
                    errors.add(ClassImportError.builder()
                            .rowNumber(excelRow.getRowNumber())
                            .className(className)
                            .errorMessage(String.format("Thông tin học sinh %s không khớp với dữ liệu hiện tại. Vui lòng cập nhật thông tin học sinh trước khi import. (Excel: name=%s, dob=%s, phone=%s; DB: name=%s, dob=%s, phone=%s)",
                                    learnerEmail,
                                    excelName, learnerExcelRow.getDateOfBirth(), excelPhone,
                                    learner.getFullName(), learner.getDateOfBirth(), learner.getPhoneNumber()))
                            .build());
                    continue;
                }
                // Build LearnerInfo for preview
                LearnerInfo learnerInfo = LearnerInfo.builder()
                        .id(learner.getId())
                        .email(learnerExcelRow.getEmail())
                        .fullName(learnerExcelRow.getName())
                        .dateOfBirth(learnerExcelRow.getDateOfBirth())
                        .phoneNumber(learnerExcelRow.getPhoneNumber())
                        .build();
                learnersToAdd.add(learnerInfo);
                // Mark learner as assigned to this class in this batch
                assignedLearnersInBatch.put(learnerEmail, className);
            }

            // Validate max students not exceeded
            // For UPDATE classes: Include existing members in the count
            int totalLearnersAfterImport = learnersToAdd.size();
            if (classExists && existingClass != null) {
                long existingMemberCount = classMemberRepository.countActiveByClassId(existingClass.getId());
                totalLearnersAfterImport += (int) existingMemberCount;
                log.debug("Class {} UPDATE validation: existing={}, new={}, total={}, max={}",
                        className, existingMemberCount, learnersToAdd.size(), totalLearnersAfterImport, excelRow.getMaxStudents());
            }

            // Build learnersToRemove for UPDATE classes:
            // Active members currently in the class whose email is NOT in the file
            List<LearnerInfo> learnersToRemove = new ArrayList<>();
            if (classExists && existingClass != null) {
                Set<String> emailsInFile = learnerExcelRows.stream()
                        .map(r -> r.getEmail().trim().toLowerCase())
                        .collect(Collectors.toSet());

                List<UUID> currentMemberIds = classMemberRepository.findActiveUserIdsByClassId(existingClass.getId());
                for (UUID memberId : currentMemberIds) {
                    userRepository.findById(memberId).ifPresent(user -> {
                        if (!emailsInFile.contains(user.getEmail().toLowerCase())) {
                            learnersToRemove.add(LearnerInfo.builder()
                                    .id(user.getId())
                                    .email(user.getEmail())
                                    .fullName(user.getFullName())
                                    .dateOfBirth(user.getDateOfBirth())
                                    .phoneNumber(user.getPhoneNumber())
                                    .build());
                        }
                    });
                }
                log.debug("Class {} UPDATE: {} learners to remove (not in file)", className, learnersToRemove.size());
            }
            Optional<ClassImportError> maxStudentsError = ExcelClassImportUtil.validateMaxStudentsNotExceeded(
                    totalLearnersAfterImport, excelRow.getMaxStudents(), className, excelRow.getRowNumber());
            if (maxStudentsError.isPresent()) {
                errors.add(maxStudentsError.get());
                continue;
            }
            if (existingClass != null) {
                long currentNumberOfLearners = classMemberRepository.countActiveByClassId(existingClass.getId());
                if (excelRow.getMaxStudents() < currentNumberOfLearners) {
                    errors.add(ClassImportError.builder()
                            .rowNumber(excelRow.getRowNumber())
                            .className(excelRow.getName())
                            .errorMessage("Số học viên tối đa không được nhỏ hơn số học viên hiện tại đang theo học.")
                            .build());
                }
            }
            // Create ClassPreview with both IDs and full details
            ClassPreview preview = ClassPreview.builder()
                    .name(className)
                    .description(excelRow.getDescription())
                    .courseId(course.getId())
                    .courseName(course.getName())
                    .teacherId(teacher.getId())
                    .teacherName(excelRow.getTeacherName())
                    .teacherEmail(teacher.getEmail())
                    .startDate(excelRow.getStartDate())
                    .expectedEndDate(excelRow.getExpectedEndDate())
                    .status(status)
                    .maxStudents(excelRow.getMaxStudents())
                    .learnersToAdd(learnersToAdd)
                    .learnersToRemove(learnersToRemove)
                    .build();

            validClasses.add(preview);
            log.debug("Valid class for import: {} ({})", className, classExists ? "UPDATE" : "NEW");
        }

        // Separate classes into CREATE and UPDATE lists
        List<ClassPreview> createdClasses = new ArrayList<>();
        List<ClassPreview> updatedClasses = new ArrayList<>();
        for (ClassPreview preview : validClasses) {
            if (classRepository.existsByNameIgnoreCase(preview.getName())) {
                // For UPDATE classes, enrich with old values and validations
                ClassPreview enrichedPreview = enrichUpdateClassPreview(preview, preview.getName());
                Clazz existingClass = classRepository.findByNameIgnoreCase(preview.getName()).get();

                // Check for validation errors - filter out if found
                boolean hasTeacherError = enrichedPreview.getTeacherValidationError() != null;
                boolean hasCourseError = enrichedPreview.getCourseValidationError() != null;
                boolean hasTeacherInfoError = enrichedPreview.getTeacherInfoValidationError() != null;
                boolean invalidStatusError = existingClass.isDeleted() || existingClass.getStatus().equals(ClassStatus.CLOSED);
                if (hasTeacherError || hasCourseError || hasTeacherInfoError) {
                    // Add validation errors to error list
                    if (hasTeacherError) {
                        errors.add(ClassImportError.builder()
                                .className(preview.getName())
                                .errorMessage(enrichedPreview.getTeacherValidationError())
                                .build());
                    }
                    if (hasCourseError) {
                        errors.add(ClassImportError.builder()
                                .className(preview.getName())
                                .errorMessage(enrichedPreview.getCourseValidationError())
                                .build());
                    }
                    if (hasTeacherInfoError) {
                        errors.add(ClassImportError.builder()
                                .className(preview.getName())
                                .errorMessage(enrichedPreview.getTeacherInfoValidationError())
                                .build());
                    }
                    log.debug("Filtering out UPDATE class {} - has validation error", preview.getName());
                    continue;
                }

                // Check if there are any actual changes by comparing current vs old values
                boolean hasFieldChanges = hasFieldChanges(enrichedPreview);
                boolean hasNewLearners = enrichedPreview.getLearnersToAdd() != null && !enrichedPreview.getLearnersToAdd().isEmpty();
                boolean hasLearnersToRemove = enrichedPreview.getLearnersToRemove() != null && !enrichedPreview.getLearnersToRemove().isEmpty();
                if (hasFieldChanges && invalidStatusError) {
                    errors.add(ClassImportError.builder()
                            .className(preview.getName())
                            .errorMessage("Lớp học " + preview.getName() + " đã bị xóa hoặc đóng, không thể cập nhật thông tin.")
                            .build());
                }
                // Only include if has changes or new learners or learners to remove
                if (hasFieldChanges || hasNewLearners || hasLearnersToRemove) {
                    updatedClasses.add(enrichedPreview);
                } else {
                    log.debug("Filtering out UPDATE class {} - no actual changes detected", preview.getName());
                }
            } else {
                createdClasses.add(preview);
            }
        }

        ImportClassesPreview result = ImportClassesPreview.builder()
                .totalRows(excelRows.size())
                .validRows(validClasses.size())
                .invalidRows(errors.size())
                .newClasses(createdClasses.size())
                .updateClasses(updatedClasses.size())
                .createdClasses(createdClasses)
                .updatedClasses(updatedClasses)
                .errors(errors)
                .build();

        log.info("Class import preview complete: {} valid, {} errors, {} new, {} updates",
                validClasses.size(), errors.size(), createdClasses.size(), updatedClasses.size());

        return result;
    }

    /**
     * Step 2: Approve and confirm class import
     * Creates new classes and updates existing ones based on preview data from Step 1.
     *
     * @param request   ApproveImportClassesRequest containing classes to create and update
     * @param managerId ID of the manager confirming import
     * @return ImportClassesResult with final counts
     */
    @Override
    @Transactional
    public ImportClassesResult approveImportClasses(ApproveImportClassesRequest request, UUID managerId) {
        int totalRequested = (request.getClassesToCreate() != null ? request.getClassesToCreate().size() : 0) +
                (request.getClassesToUpdate() != null ? request.getClassesToUpdate().size() : 0);
        log.info("Manager {} approving class import for {} classes", managerId, totalRequested);

        List<ClassImportError> errors = new ArrayList<>();
        int createdCount = 0;
        int updatedCount = 0;

        // Process classes to CREATE (NEW)
        if (request.getClassesToCreate() != null) {
            for (ClassPreview preview : request.getClassesToCreate()) {
                try {
                    // Create new class - use IDs from preview
                    Clazz newClass = Clazz.builder()
                            .name(preview.getName())
                            .description(preview.getDescription())
                            .courseId(preview.getCourseId())
                            .startDate(preview.getStartDate())
                            .expectedEndDate(preview.getExpectedEndDate())
                            .status(preview.getStatus())
                            .maxStudents(preview.getMaxStudents() != null ? preview.getMaxStudents() : 8)
                            .build();

                    Clazz savedClass = classRepository.save(newClass);
                    log.debug("Created new class: {}", preview.getName());
                    //If teacher is changing, add new ClassTeacherHistory record
                    //Check if teacher is different from current teacher
                    try {
                        if (preview.getTeacherId() != null) {
                            ClassTeacherHistory classTeacherHistory = ClassTeacherHistory.builder()
                                    .classId(savedClass.getId())
                                    .teacherId(preview.getTeacherId())
                                    .assignedAt(LocalDate.now())
                                    .build();
                            classTeacherHistoryRepository.save(classTeacherHistory);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to assign teacher {} to class {}: {}",
                                preview.getTeacherEmail(), preview.getName(), e.getMessage());
                        errors.add(ClassImportError.builder()
                                .className(preview.getName())
                                .errorMessage(String.format("Lỗi gán giảng viên %s: %s",
                                        preview.getTeacherEmail(), e.getMessage()))
                                .build());
                    }
                    // Add learners to new class
                    if (preview.getLearnersToAdd() != null) {
                        int learnerRowNum = 0;
                        for (LearnerInfo learnerInfo : preview.getLearnersToAdd()) {
                            learnerRowNum++;

                            // Validate learner before adding
                            ClassImportError validationError = validateLearnerForImport(
                                    learnerInfo, preview.getName(), learnerRowNum);

                            if (validationError != null) {
                                errors.add(validationError);
                                continue; // Skip invalid learner
                            }

                            try {
                                // Use learner ID directly from LearnerInfo
                                ClassMember member = ClassMember.builder()
                                        .classId(savedClass.getId())
                                        .userId(learnerInfo.getId())
                                        .joinedAt(LocalDateTime.now())
                                        .build();
                                classMemberRepository.save(member);

                                // Update learner status to ACTIVE
                                User learner = userRepository.findById(learnerInfo.getId()).get();
                                learner.setStatus(UserStatus.ACTIVE);
                                userRepository.save(learner);

                            } catch (Exception e) {
                                log.warn("Failed to add learner {} to class {}: {}",
                                        learnerInfo.getEmail(), preview.getName(), e.getMessage());
                                errors.add(ClassImportError.builder()
                                        .className(preview.getName())
                                        .errorMessage(String.format("Lỗi thêm học viên %s: %s",
                                                learnerInfo.getEmail(), e.getMessage()))
                                        .build());
                            }
                        }
                    }

                    createdCount++;

                } catch (Exception e) {
                    log.error("Error creating class {}: {}", preview.getName(), e.getMessage(), e);
                }
            }
        }

        // Process classes to UPDATE
        if (request.getClassesToUpdate() != null) {
            for (ClassPreview preview : request.getClassesToUpdate()) {
                try {
                    // Find existing class by name
                    Clazz existingClass = classRepository.findAll().stream()
                            .filter(c -> c.getName().equalsIgnoreCase(preview.getName()) && c.getDeletedAt() == null)
                            .findFirst()
                            .orElse(null);

                    if (existingClass != null) {
                        final Clazz finalExistingClass = existingClass;  // Make final for lambda

                        // Update class fields - use IDs from preview
                        existingClass.setDescription(preview.getDescription());
                        existingClass.setCourseId(preview.getCourseId());
                        existingClass.setStartDate(preview.getStartDate());
                        existingClass.setExpectedEndDate(preview.getExpectedEndDate());
                        existingClass.setStatus(preview.getStatus());

                        classRepository.save(existingClass);
                        log.debug("Updated existing class: {}", preview.getName());
                        //If teacher is changing, add new ClassTeacherHistory record
                        //Check if teacher is different from current teacher
                        try {
                            UUID currentTeacherId = classTeacherHistoryRepository.findCurrentTeacherIdByClassId(finalExistingClass.getId()).orElse(null);
                            if (preview.getTeacherId() != null && currentTeacherId == null || !currentTeacherId.equals(preview.getTeacherId())) {
                                ClassTeacherHistory classTeacherHistory = ClassTeacherHistory.builder()
                                        .classId(finalExistingClass.getId())
                                        .teacherId(preview.getTeacherId())
                                        .assignedAt(LocalDate.now())
                                        .build();
                                ClassTeacherHistory oldTeacherHistory = classTeacherHistoryRepository.findActiveByClassIdAndTeacherId(finalExistingClass.getId(), currentTeacherId).orElse(null);
                                oldTeacherHistory.setReleasedAt(LocalDate.now());
                                classTeacherHistoryRepository.save(oldTeacherHistory);
                                classTeacherHistoryRepository.save(classTeacherHistory);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to assign teacher {} to class {}: {}",
                                    preview.getTeacherEmail(), preview.getName(), e.getMessage());
                            errors.add(ClassImportError.builder()
                                    .className(preview.getName())
                                    .errorMessage(String.format("Lỗi gán giảng viên %s: %s",
                                            preview.getTeacherEmail(), e.getMessage()))
                                    .build());
                            continue;
                        }
                        // Add new learners to existing class
                        if (preview.getLearnersToAdd() != null) {
                            int learnerRowNum = 0;
                            for (LearnerInfo learnerInfo : preview.getLearnersToAdd()) {
                                learnerRowNum++;

                                // Validate learner before adding
                                ClassImportError validationError = validateLearnerForImport(
                                        learnerInfo, preview.getName(), learnerRowNum);

                                if (validationError != null) {
                                    errors.add(validationError);
                                    continue; // Skip invalid learner
                                }

                                try {
                                    // Use learner ID directly from LearnerInfo
                                    final UUID learnerId = learnerInfo.getId();
                                    Optional<ClassMember> existingMember = classMemberRepository.findAll().stream()
                                            .filter(m -> m.getClassId().equals(finalExistingClass.getId()) &&
                                                    m.getUserId().equals(learnerId))
                                            .findFirst();

                                    if (existingMember.isPresent()) {
                                        ClassMember member = existingMember.get();
                                        if (member.getRemovedAt() != null) {
                                            member.setRemovedAt(null);  // re-join
                                            classMemberRepository.save(member);
                                        }
                                    } else {
                                        ClassMember newMember = ClassMember.builder()
                                                .classId(finalExistingClass.getId())
                                                .userId(learnerId)
                                                .joinedAt(LocalDateTime.now())
                                                .build();
                                        classMemberRepository.save(newMember);
                                    }

                                    // Update learner status to ACTIVE
                                    User learner = userRepository.findById(learnerId).get();
                                    learner.setStatus(UserStatus.ACTIVE);
                                    userRepository.save(learner);

                                } catch (Exception e) {
                                    log.warn("Failed to add learner {} to class {}: {}",
                                            learnerInfo.getEmail(), preview.getName(), e.getMessage());
                                    errors.add(ClassImportError.builder()
                                            .className(preview.getName())
                                            .errorMessage(String.format("Lỗi thêm học viên %s: %s",
                                                    learnerInfo.getEmail(), e.getMessage()))
                                            .build());
                                }
                            }
                        }

                        // Remove learners not present in the file
                        if (preview.getLearnersToRemove() != null) {
                            for (LearnerInfo learnerInfo : preview.getLearnersToRemove()) {
                                try {
                                    classMemberRepository.findByClassIdAndUserId(
                                            finalExistingClass.getId(), learnerInfo.getId())
                                            .ifPresent(member -> {
                                                if (member.getRemovedAt() == null) {
                                                    member.remove();
                                                    classMemberRepository.save(member);
                                                    log.debug("Removed learner {} from class {}",
                                                            learnerInfo.getEmail(), preview.getName());
                                                }
                                            });
                                    // Revert learner status to INACTIVE
                                    userRepository.findById(learnerInfo.getId()).ifPresent(user -> {
                                        if (user.getRole() == UserRole.LEARNER && user.getStatus() == UserStatus.ACTIVE) {
                                            user.setStatus(UserStatus.INACTIVE);
                                            userRepository.save(user);
                                        }
                                    });
                                } catch (Exception e) {
                                    log.warn("Failed to remove learner {} from class {}: {}",
                                            learnerInfo.getEmail(), preview.getName(), e.getMessage());
                                    errors.add(ClassImportError.builder()
                                            .className(preview.getName())
                                            .errorMessage(String.format("Lỗi xóa học viên %s: %s",
                                                    learnerInfo.getEmail(), e.getMessage()))
                                            .build());
                                }
                            }
                        }

                        updatedCount++;
                    }
                } catch (Exception e) {
                    log.error("Error updating class {}: {}", preview.getName(), e.getMessage(), e);
                }
            }
        }

        ImportClassesResult result = ImportClassesResult.builder()
                .totalRows(totalRequested)
                .createdCount(createdCount)
                .updatedCount(updatedCount)
                .failureCount(totalRequested - createdCount - updatedCount)
                .errors(errors)
                .build();

        log.info("Class import approval complete: {} created, {} updated, {} failures",
                createdCount, updatedCount, result.getFailureCount());

        return result;
    }

    /**
     * Validate learner during import approval
     * Checks: role (must be LEARNER), status (must be INACTIVE), not already in another class
     *
     * @param learner   LearnerInfo to validate
     * @param className Name of class for error reporting
     * @param rowNum    Row number for error reporting
     * @return ClassImportError if validation fails, null if passes
     */
    private ClassImportError validateLearnerForImport(LearnerInfo learner, String className, int rowNum) {
        try {
            // Fetch full user entity
            User user = userRepository.findById(learner.getId())
                    .orElseThrow(() -> new Exception("Học viên không tồn tại"));

            // Check role - must be LEARNER
            if (user.getRole() != UserRole.LEARNER) {
                return ClassImportError.builder()
                        .rowNumber(rowNum)
                        .className(className)
                        .errorMessage(String.format("Học viên %s phải có role LEARNER, hiện có: %s",
                                learner.getEmail(), user.getRole()))
                        .build();
            }

            // Check status - must be INACTIVE
            if (user.getStatus() != UserStatus.INACTIVE) {
                return ClassImportError.builder()
                        .rowNumber(rowNum)
                        .className(className)
                        .errorMessage(String.format("Chỉ có thể thêm học viên %s với status INACTIVE, hiện có: %s",
                                learner.getEmail(), user.getStatus()))
                        .build();
            }

            // Check not in any other class
            boolean inOtherClass = classMemberRepository.existsByUserIdAndRemovedAtNull(learner.getId());
            if (inOtherClass) {
                return ClassImportError.builder()
                        .rowNumber(rowNum)
                        .className(className)
                        .errorMessage(String.format("Học viên %s đang ở trong lớp khác, không thể thêm", learner.getEmail()))
                        .build();
            }

            return null; // No error - validation passed

        } catch (Exception e) {
            return ClassImportError.builder()
                    .rowNumber(rowNum)
                    .className(className)
                    .errorMessage(String.format("Lỗi validate học viên %s: %s", learner.getEmail(), e.getMessage()))
                    .build();
        }
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

    /**
     * Validate and enrich UPDATE class preview with old vs new values
     * Checks teacher information and populates comparison fields
     * Also detects if class has any actual changes worth importing
     *
     * @param preview ClassPreview to enrich
     * @param className Name of class to find in DB
     * @return Updated preview with old/new values and any validation errors
     */
    /**
     * Validate and enrich UPDATE class preview with old vs new values
     * Checks teacher and course information and populates comparison fields
     *
     * @param preview   ClassPreview to enrich
     * @param className Name of class to find in DB
     * @return Updated preview with old/new values and any validation errors
     */
    private ClassPreview enrichUpdateClassPreview(ClassPreview preview, String className) {
        try {
            // Find existing class in DB
            Clazz existingClass = classRepository.findByNameIgnoreCaseAndNotDeleted(className).orElse(null);
            if (existingClass == null) {
                return preview; // Should not happen if we're in UPDATE path, but be safe
            }

            // Get existing teacher (if any)
            User existingTeacher = null;
            UUID currentTeacherId = classTeacherHistoryRepository.findCurrentTeacherIdByClassId(existingClass.getId()).orElse(null);
            if (currentTeacherId != null) {
                existingTeacher = userRepository.findById(currentTeacherId).orElse(null);
            }

            // Populate old values from DB
            preview.setOldClassName(existingClass.getName());
            preview.setOldDescription(existingClass.getDescription());
            preview.setOldStartDate(existingClass.getStartDate());
            preview.setOldExpectedEndDate(existingClass.getExpectedEndDate());
            preview.setOldStatus(existingClass.getStatus());
            preview.setOldMaxStudents(existingClass.getMaxStudents());
            preview.setOldCourseId(existingClass.getCourseId());
            
            // Get old course name
            if (existingClass.getCourseId() != null) {
                courseRepository.findById(existingClass.getCourseId()).ifPresent(course -> 
                    preview.setOldCourseName(course.getName())
                );
            }

            if (existingTeacher != null) {
                preview.setOldTeacherEmail(existingTeacher.getEmail());
                preview.setOldTeacherFullName(existingTeacher.getFullName());
            }

            // Validate course hasn't changed (courses cannot be changed on UPDATE)
            String courseValidationError = validateCourseForUpdate(preview, existingClass);
            if (courseValidationError != null) {
                preview.setCourseValidationError(courseValidationError);
            }

            // Validate teacher info in Excel matches expected data
            String teacherValidationError = validateTeacherForUpdate(preview, existingTeacher);
            if (teacherValidationError != null) {
                preview.setTeacherValidationError(teacherValidationError);
            }

            // Validate teacher information (full name, etc.) matches DB if teacher exists
            if (existingTeacher != null) {
                String teacherInfoError = validateTeacherInfoForUpdate(preview, existingTeacher);
                if (teacherInfoError != null) {
                    preview.setTeacherInfoValidationError(teacherInfoError);
                }
            }

            return preview;

        } catch (Exception e) {
            log.error("Error enriching UPDATE class preview for {}: {}", className, e.getMessage(), e);
            return preview; // Return preview even if error occurs
        }
    }

    /**
     * Check if any field has changed by comparing old vs new values in preview
     * Old values are populated during enrichment from DB
     *
     * @param preview ClassPreview with old/new values populated
     * @return true if any field changed, false if all identical
     */
    private boolean hasFieldChanges(ClassPreview preview) {
        // Check each field for changes using Objects.equals for null-safety
        if (!Objects.equals(preview.getName(), preview.getOldClassName())) return true;
        if (!Objects.equals(preview.getDescription(), preview.getOldDescription())) return true;
        if (!Objects.equals(preview.getStartDate(), preview.getOldStartDate())) return true;
        if (!Objects.equals(preview.getStatus(), preview.getOldStatus())) return true;
        if (!Objects.equals(preview.getMaxStudents(), preview.getOldMaxStudents())) return true;
        if (!Objects.equals(preview.getCourseId(), preview.getOldCourseId())) return true;
        return !Objects.equals(preview.getTeacherEmail(), preview.getOldTeacherEmail());
    }

    /**
     * Validate course hasn't changed for UPDATE classes
     * Courses cannot be changed on existing classes
     *
     * @param preview       ClassPreview with course from Excel
     * @param existingClass Current class in DB
     * @return Error message if validation fails, null if passes
     */
    private String validateCourseForUpdate(ClassPreview preview, Clazz existingClass) {
        if (preview.getCourseId() == null || existingClass.getCourseId() == null) {
            return null; // Skip validation if either is null
        }

        // Course cannot be changed on UPDATE
        if (!Objects.equals(preview.getCourseId(), existingClass.getCourseId())) {
            return String.format("Không thể thay đổi khóa học của lớp đã tồn tại. Khóa học hiện tại: %s, khóa học trong file: %s",
                    preview.getOldCourseName(), preview.getCourseName());
        }

        return null;
    }

    /**
     * Validate teacher information for UPDATE classes
     * Checks if teacher in Excel matches DB (same email, exists, has TEACHER role)
     *
     * @param preview         ClassPreview with teacher info from Excel
     * @param existingTeacher Current teacher in DB (if any)
     * @return Error message if validation fails, null if passes
     */
    private String validateTeacherForUpdate(ClassPreview preview, User existingTeacher) {
        // If no teacher provided in Excel, no validation needed
        if (preview.getTeacherEmail() == null || preview.getTeacherEmail().trim().isEmpty()) {
            return null;
        }

        String excelTeacherEmail = preview.getTeacherEmail().trim().toLowerCase();

        // Verify the teacher in Excel exists in DB (should have been done in earlier validation)
        // This is just a safety check
        User teacherInExcel = userRepository.findByEmail(excelTeacherEmail).orElse(null);
        if (teacherInExcel == null) {
            return "Giảng viên " + preview.getTeacherEmail() + " không tồn tại trong hệ thống";
        }

        if (teacherInExcel.getRole() != UserRole.TEACHER) {
            return "Người dùng " + preview.getTeacherEmail() + " không có vai trò TEACHER";
        }

        return null; // Validation passed
    }

    /**
     * Validate teacher information matches between Excel and DB
     * Checks if teacher's full name in Excel matches the teacher in DB
     *
     * @param preview         ClassPreview with teacher info from Excel
     * @param existingTeacher Teacher from DB
     * @return Error message if validation fails, null if passes
     */
    private String validateTeacherInfoForUpdate(ClassPreview preview, User existingTeacher) {
        // If no teacher full name in Excel, skip validation
        if (preview.getTeacherName() == null || preview.getTeacherName().trim().isEmpty()) {
            return null;
        }

        // Existing teacher should not be null (this is called only if existingTeacher != null)
        if (existingTeacher == null) {
            return null;
        }

        String excelTeacherFullName = preview.getTeacherName().trim();
        String dbTeacherFullName = existingTeacher.getFullName();
        String excelTeacherEmail = preview.getTeacherEmail().trim();
        String dbTeacherEmail = existingTeacher.getEmail().trim();
        // If teacher in DB has no full name, can't validate
        if (dbTeacherFullName == null || dbTeacherFullName.trim().isEmpty()) {
            return null;
        }

        dbTeacherFullName = dbTeacherFullName.trim();

        // Compare full names (case-sensitive, exact match)
        if (!excelTeacherFullName.equals(dbTeacherFullName) && excelTeacherEmail.equals(dbTeacherEmail) ) {
            return String.format("Thông tin giảng viên không khớp với DB: Tên Excel = '%s', Tên DB = '%s'",
                    excelTeacherFullName, dbTeacherFullName);
        }

        return null; // Validation passed
    }

    /**
     * Validate learner in multiple classes (v2: stricter membership requirement)
     * ⚡ NEW (v2): All CLOSED/DELETED classes MUST have ClassMember records to prevent ghost learners
     * <p>
     * Logic:
     * 1. Count ACTIVE/NOT_STARTED classes learner appears in (N)
     * 2. If N > 1: ERROR - conflicting active classes
     * 3. If N >= 1 OR N == 0: For each CLOSED/DELETED class:
     * - MUST have ClassMember record (no exceptions)
     * - If DELETED: ERROR - can't re-enroll in deleted class
     * 4. If all checks pass: OK
     */
    private Optional<ClassImportError> validateLearnerInMultipleClassesV2(
            String learnerEmail,
            User learner,
            Map<String, Map<String, ClassStatus>> learnerToClassesWithStatus,
            Map<String, String> classNameToIdMap,
            Map<String, ClassMember> classMemberLookup,
            int rowNumber) {

        Map<String, ClassStatus> classMap = learnerToClassesWithStatus.get(learnerEmail);
        if (classMap == null || classMap.size() <= 1) {
            return Optional.empty(); // Not in multiple classes
        }

        // Count ACTIVE/NOT_STARTED classes
        long activeNotStartedCount = classMap.values().stream()
                .filter(status -> status == ClassStatus.ACTIVE || status == ClassStatus.NOT_STARTED)
                .count();

        log.debug("Learner {} in {} classes: {} ACTIVE/NOT_STARTED", learnerEmail, classMap.size(), activeNotStartedCount);

        // Edge case 1: Multiple ACTIVE/NOT_STARTED classes
        if (activeNotStartedCount > 1) {
            String classNames = classMap.entrySet().stream()
                    .filter(e -> e.getValue() == ClassStatus.ACTIVE || e.getValue() == ClassStatus.NOT_STARTED)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.joining(", "));
            return Optional.of(ClassImportError.builder()
                    .rowNumber(rowNumber)
                    .className("Multiple Classes")
                    .errorMessage(String.format("Học sinh %s nằm ở nhiều lớp hoạt động: %s. Không thể thêm vào nhiều lớp cùng lúc",
                            learnerEmail, classNames))
                    .build());
        }

        // Edge case 2-3: Check CLOSED/DELETED classes (v2: ALWAYS require membership record)
        for (Map.Entry<String, ClassStatus> entry : classMap.entrySet()) {
            String className = entry.getKey();
            ClassStatus status = entry.getValue();

            if (status == ClassStatus.CLOSED || status == ClassStatus.DELETED) {
                String classId = classNameToIdMap.get(className);
                if (classId != null) {
                    String memberKey = classId + ":" + learner.getId();
                    ClassMember membership = classMemberLookup.get(memberKey);

                    // ⚡ NEW (v2): ALWAYS check membership for CLOSED/DELETED - no exceptions
                    if (membership == null) {
                        return Optional.of(ClassImportError.builder()
                                .rowNumber(rowNumber)
                                .className(className)
                                .errorMessage(String.format("Không tìm thấy ghi nhận học viên %s từng học ở lớp %s (Lớp này đã bị xóa hoặc đã đóng).",
                                        learnerEmail, className))
                                .build());
                    }

                    // Edge case 4: Can't re-enroll in DELETED class
                    if (status == ClassStatus.DELETED) {
                        return Optional.of(ClassImportError.builder()
                                .rowNumber(rowNumber)
                                .className(className)
                                .errorMessage(String.format("Không thể thêm học viên %s vào lớp %s đã bị xóa",
                                        learnerEmail, className))
                                .build());
                    }
                }
            }
        }

        return Optional.empty(); // All checks passed
    }

    // =====================================================
    // CLASS PROGRESS & LEARNING ANALYTICS
    // =====================================================

    @Override
    public ClassProgressResponse getClassProgress(UUID classId) {
        log.info("Fetching class progress dashboard data for classId: {}", classId);

        // 1. Get and validate class
        Clazz clazz = findClassOrThrow(classId);
        Course course = courseRepository.findByIdAndNotDeleted(clazz.getCourseId())
                .orElseThrow(() -> new ResourceNotFoundException("Khóa học không tồn tại."));

        // 2. Get class stats
        ClassProgressStatsResponse classStats = calculateClassStats(classId, course.getId());

        // 3. Get active class members with user info via repository
        List<ClassMemberInfo> activeMembersRaw = analyticsRepository.getActiveClassMembersWithUserInfo(classId);

        // 4. Get learner progress metrics from repository
        List<LearnerProgressMetrics> learnerMetricsRaw = analyticsRepository.getProgressMetricsForClass(classId);

        // 5. Build metrics map for quick lookup
        Map<UUID, LearnerProgressResponse> learnerMetricsMap = new HashMap<>();
        for (LearnerProgressMetrics metrics : learnerMetricsRaw) {
            UUID userId = metrics.getUserId();
            Long totalAssignments = metrics.getTotalAssignments() != null ? metrics.getTotalAssignments() : 0L;
            Long completedAssignments = metrics.getCompletedAssignments() != null ? metrics.getCompletedAssignments() : 0L;
            Long submittedAssignments = metrics.getSubmittedAssignments() != null ? metrics.getSubmittedAssignments() : 0L;
            Double scoreDouble = metrics.getAvgScore();
            BigDecimal avgScore = scoreDouble != null && scoreDouble != 0 ? BigDecimal.valueOf(scoreDouble) : BigDecimal.ZERO;

            Double completionPercentage = totalAssignments > 0 ? (completedAssignments * 100.0 / totalAssignments) : 0.0;
            Double submissionPercentage = totalAssignments > 0 ? (submittedAssignments * 100.0 / totalAssignments) : 0.0;

            learnerMetricsMap.put(userId, LearnerProgressResponse.builder()
                    .userId(userId)
                    .totalAssignments(totalAssignments)
                    .completedAssignments(completedAssignments)
                    .submittedAssignments(submittedAssignments)
                    .completionPercentage(completionPercentage)
                    .submissionPercentage(submissionPercentage)
                    .averageScore(avgScore)
                    .build());
        }

        // 6. Enhance learner data with user info and calculate status
        List<LearnerProgressResponse> learnerMetrics = new ArrayList<>();
        for (ClassMemberInfo memberInfo : activeMembersRaw) {
            UUID userId = memberInfo.getUserId();
            String fullName = memberInfo.getFullName();
            String email = memberInfo.getEmail();
            String avatarUrl = memberInfo.getAvatarUrl();
            LocalDateTime joinedAt = memberInfo.getJoinedAt();

            LearnerProgressResponse metrics = learnerMetricsMap.getOrDefault(userId, LearnerProgressResponse.builder()
                    .userId(userId)
                    .totalAssignments(0L)
                    .completedAssignments(0L)
                    .submittedAssignments(0L)
                    .completionPercentage(0.0)
                    .submissionPercentage(0.0)
                    .averageScore(BigDecimal.ZERO)
                    .build());

            metrics.setFullName(fullName);
            metrics.setEmail(email);
            metrics.setAvatarUrl(avatarUrl);
            metrics.setJoinedAt(joinedAt);
            metrics.setStatus(calculateLearnerStatus(metrics.getCompletionPercentage(), metrics.getAverageScore()));

            learnerMetrics.add(metrics);
        }

        // 7. Calculate learning trend data
        List<LearningTrendDataPoint> trendData = calculateLearningTrend(classId, clazz);

        // 8. Build and return response
        return ClassProgressResponse.builder()
                .classId(classId)
                .className(clazz.getName())
                .courseId(clazz.getCourseId())
                .courseName(course.getName())
                .courseLevel(course.getJlptLevel() != null ? course.getJlptLevel().toString() : null)
                .classStats(classStats)
                .learningTrendData(trendData)
                .learnerMetrics(learnerMetrics)
                .learnerCount(learnerMetrics.size())
                .build();
    }

    /**
     * Calculate learning trend data by grouping submissions into custom weeks.
     * Weeks are calculated relative to class start date.
     * Generates all weeks from class start to today, filling gaps with zero-value entries.
     */
    private List<LearningTrendDataPoint> calculateLearningTrend(UUID classId, Clazz clazz) {
        List<LearningTrendMetrics> trendData = analyticsRepository.getWeeklyTrendDataForClass(classId);
        
        LocalDate classStartDate = clazz.getStartDate();
        LocalDate today = LocalDate.now();
        
        // Build a map for quick lookup of trend data by week start date
        Map<LocalDate, LearningTrendMetrics> trendDataMap = new HashMap<>();
        if (trendData != null && !trendData.isEmpty()) {
            for (LearningTrendMetrics metric : trendData) {
                trendDataMap.put(metric.getPeriodStart(), metric);
            }
        }
        
        // Get total assignments count for completion percentage calculation
        Long totalAssignments = analyticsRepository.countAssignmentsInClass(classId);
        
        List<LearningTrendDataPoint> result = new ArrayList<>();
        
        // Generate all weeks from class start date to today
        for (LocalDate weekStart = classStartDate; 
             !weekStart.isAfter(today); 
             weekStart = weekStart.plusDays(7)) {
            
            LocalDate periodEnd = weekStart.plusDays(6);
            
            // Calculate week number relative to class start date
            long daysSinceStart = java.time.temporal.ChronoUnit.DAYS.between(classStartDate, weekStart);
            int weekNumber = (int) (daysSinceStart / 7) + 1;
            
            // Get metric data if it exists, otherwise use zero values
            LearningTrendMetrics metric = trendDataMap.get(weekStart);
            
            Long submissionsCount = metric != null ? metric.getSubmissionsCount() : 0L;
            Double completionPercentage = calculateCompletionPercentageForPeriod(totalAssignments, submissionsCount);
            BigDecimal averageScore = metric != null && metric.getAverageScore() != null ? 
                    BigDecimal.valueOf(metric.getAverageScore()) : BigDecimal.ZERO;
            Long activeLearnersCount = metric != null ? metric.getActiveLearnersCount() : 0L;
            
            result.add(LearningTrendDataPoint.builder()
                    .periodLabel(String.format("Week %d: %s - %s", weekNumber, weekStart, periodEnd))
                    .periodStart(weekStart.atStartOfDay())
                    .periodEnd(periodEnd.atTime(23, 59, 59))
                    .completionPercentage(completionPercentage)
                    .averageScore(averageScore)
                    .activeLearnersCount(activeLearnersCount)
                    .submissionsCount(submissionsCount)
                    .build());
        }
        
        return result;
    }
    
    /**
     * Calculate completion percentage for a week.
     * Completion % = (submissions in period / total assignments in class) * 100
     * 
     * @param totalAssignments total number of assignments in the class
     * @param submissionsInPeriod number of submissions in the specific week
     * @return completion percentage (0-100), or 0 if no assignments
     */
    private Double calculateCompletionPercentageForPeriod(Long totalAssignments, Long submissionsInPeriod) {
        if (totalAssignments == null || totalAssignments == 0) {
            return 0.0;
        }
        
        if (submissionsInPeriod == null || submissionsInPeriod == 0) {
            return 0.0;
        }
        
        return (submissionsInPeriod.doubleValue() / totalAssignments.doubleValue()) * 100.0;
    }

    /**
     * Calculate class-level progress statistics using repository queries.
     */
    private ClassProgressStatsResponse calculateClassStats(UUID classId, UUID courseId) {
        // Count active members
        Long totalLearners = classMemberRepository.countActiveByClassId(classId);

        // Get class entity for max students and date information
        Clazz clazz = classRepository.findByIdAndNotDeleted(classId)
                .orElseThrow(() -> new ResourceNotFoundException("Lớp học không tồn tại."));

        Integer maxStudents = clazz.getMaxStudents();
        Double enrollmentPercentage = maxStudents > 0 ? (totalLearners * 100.0 / maxStudents) : 0.0;

        // Calculate class progress percentage based on time elapsed
        // Formula: (today - startDate) / (expectedEndDate - startDate) * 100
        LocalDate startDate = clazz.getStartDate();
        LocalDate expectedEndDate = clazz.getExpectedEndDate();
        LocalDate today = LocalDate.now();

        Double classProgressPercentage = 0.0;
        
        if (startDate != null && expectedEndDate != null) {
            long daysElapsed = ChronoUnit.DAYS.between(startDate, today);
            long totalDays = ChronoUnit.DAYS.between(startDate, expectedEndDate);
            
            if (totalDays > 0) {
                classProgressPercentage = Math.min(100.0, (daysElapsed * 100.0 / totalDays));
            } else if (totalDays == 0 && !today.isBefore(startDate)) {
                classProgressPercentage = 100.0;
            }
            
            // Ensure percentage is between 0-100
            classProgressPercentage = Math.max(0.0, classProgressPercentage);
        }

        // Count total lessons in course (for reference)
        Long totalLessonsInCourse = lessonRepository.countByCourseIdForAnalytics(courseId);

        // Count lessons with assignments in class via repository (for reference)
        Long lessonsWithAssignments = analyticsRepository.countLessonsWithAssignmentsInClass(classId);
        if (lessonsWithAssignments == null) {
            lessonsWithAssignments = 0L;
        }

        // Calculate average score via repository
        Double avgScoreDouble = analyticsRepository.getAverageScoreForClass(classId);
        BigDecimal averageScore = avgScoreDouble != null && avgScoreDouble != 0 ? BigDecimal.valueOf(avgScoreDouble) : BigDecimal.ZERO;

        // Count total assignments via repository
        Long totalAssignments = analyticsRepository.countAssignmentsInClass(classId);
        if (totalAssignments == null) {
            totalAssignments = 0L;
        }

        return ClassProgressStatsResponse.builder()
                .totalLearners(totalLearners)
                .maxStudents(maxStudents)
                .enrollmentPercentage(enrollmentPercentage)
                .classProgressPercentage(classProgressPercentage)
                .averageScore(averageScore)
                .totalAssignments(totalAssignments)
                .lessonsWithAssignments(lessonsWithAssignments)
                .totalLessonsInCourse(totalLessonsInCourse)
                .build();
    }

    /**
     * Determine learner status based on completion and performance.
     * Rules:
     * - 100% completion → "Hoàn thành" (Completed)
     * - 0% completion → "Chưa bắt đầu" (Not started)
     * - Completion >= 80% → "Đang học" (Learning)
     * - Otherwise → "Chậm tiến độ" (Behind)
     */
    private String calculateLearnerStatus(Double completionPercentage, BigDecimal averageScore) {
        if (completionPercentage == null || completionPercentage < 0) {
            completionPercentage = 0.0;
        }

        if (completionPercentage >= 100) {
            return "Hoàn thành";
        } else if (completionPercentage == 0) {
            return "Chưa bắt đầu";
        } else if (completionPercentage >= 80) {
            return "Đang học";
        } else {
            return "Chậm tiến độ";
        }
    }
}
