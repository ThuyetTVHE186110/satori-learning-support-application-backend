package com.fu.clazz.service;

import com.fu.clazz.dto.request.AddMemberRequest;
import com.fu.clazz.dto.request.CreateClassRequest;
import com.fu.clazz.dto.request.UpdateClassRequest;
import com.fu.clazz.dto.response.ClassMemberResponse;
import com.fu.clazz.dto.response.ClassResponse;
import com.fu.clazz.dto.response.ClassSummaryResponse;
import com.fu.clazz.dto.response.LearnerClassResponse;
import com.fu.clazz.entity.ClassMember;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.entity.ClassStatus;
import com.fu.clazz.entity.ClassTeacherHistory;
import com.fu.clazz.exception.ClassFullException;
import com.fu.clazz.exception.ClassNotFoundException;
import com.fu.clazz.exception.MemberAlreadyExistsException;
import com.fu.clazz.mapper.ClassMapper;
import com.fu.clazz.repository.ClassMemberRepository;
import com.fu.clazz.repository.ClassRepository;
import com.fu.clazz.repository.ClassTeacherHistoryRepository;
import com.fu.clazz.repository.specification.ClassSpecification;
import com.fu.clazz.scheduler.ClassScheduleManager;
import com.fu.clazz.service.impl.ClassServiceImpl;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.course.entity.Course;
import com.fu.course.repository.CourseRepository;
import com.fu.user.entity.User;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import com.fu.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ClazzServiceImpl.
 * Covers: CRUD, activate/deactivate, soft-delete, membership.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClazzService Unit Tests")
@SuppressWarnings("unchecked")
class ClassServiceImplTest {

    @Mock
    private ClassRepository classRepository;

    @Mock
    private ClassMemberRepository classMemberRepository;

    @Mock
    private ClassMapper classMapper;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CourseRepository courseRepository;

    @Mock
    private ClassTeacherHistoryRepository classTeacherHistoryRepository;

    @Mock
    private ClassScheduleManager classScheduleManager;

    @InjectMocks
    private ClassServiceImpl clazzService;

    @Captor
    private ArgumentCaptor<Clazz> clazzCaptor;

    @Captor
    private ArgumentCaptor<ClassMember> memberCaptor;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    // Test fixtures
    private Clazz testClazz;
    private ClassResponse testClassResponse;
    private ClassSummaryResponse testSummaryResponse;
    private ClassMember testMember;
    private ClassMemberResponse testMemberResponse;
    private CreateClassRequest createRequest;
    private UpdateClassRequest updateRequest;
    private UUID managerId;
    private UUID classId;
    private UUID teacherId;
    private UUID studentId;
    private UUID courseId;
    private User testTeacher;
    private User testStudent;
    private Course testCourse;

    @BeforeEach
    void setUp() {
        managerId = UUID.randomUUID();
        classId = UUID.randomUUID();
        teacherId = UUID.randomUUID();
        studentId = UUID.randomUUID();
        courseId = UUID.randomUUID();

        testCourse = Course.builder()
                .name("N5 Beginners")
                .status(Course.CourseStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(testCourse, "id", courseId);

        testClazz = Clazz.builder()
                .name("N5 Morning Class")
                .description("Beginner Japanese class")
                .courseId(courseId)
                .maxStudents(30)
                .startDate(LocalDate.now().plusMonths(1))
                .status(ClassStatus.NOT_STARTED)
                .build();
        ReflectionTestUtils.setField(testClazz, "id", classId);

        testClassResponse = ClassResponse.builder()
                .id(classId)
                .name("N5 Morning Class")
                .description("Beginner Japanese class")
                .teacherId(teacherId)
                .courseId(courseId)
                .courseName("N5 Beginners")
                .courseLevel("N5")
                .courseStatus("PUBLISHED")
                .maxStudents(30)
                .startDate(LocalDate.now().plusMonths(1))
                .status(ClassStatus.NOT_STARTED)
                .memberCount(0L)
                .teacherPhoneNumber("0123456789")
                .build();

        testSummaryResponse = ClassSummaryResponse.builder()
                .id(classId)
                .name("N5 Morning Class")
                .teacherId(teacherId)
                .courseId(courseId)
                .courseName("N5 Beginners")
                .courseLevel("N5")
                .courseStatus("PUBLISHED")
                .status(ClassStatus.NOT_STARTED)
                .memberCount(0L)
                .build();

        testMember = ClassMember.builder()
                .classId(classId)
                .userId(studentId)
                .joinedAt(LocalDateTime.now())
                .build();
        ReflectionTestUtils.setField(testMember, "id", UUID.randomUUID());

        testMemberResponse = ClassMemberResponse.builder()
                .id(testMember.getId())
                .classId(classId)
                .userId(studentId)
                .joinedAt(testMember.getJoinedAt())
                .build();

        createRequest = CreateClassRequest.builder()
                .name("N5 Morning Class")
                .description("Beginner Japanese class")
                .teacherId(teacherId)
                .courseId(courseId)
                .maxStudents(30)
                .startDate(LocalDate.now().plusMonths(1))
                .build();

        updateRequest = UpdateClassRequest.builder()
                .name("Updated N5 Class")
                .description("Updated description")
                .build();

        testTeacher = User.builder()
                .email("teacher@example.com")
                .firstName("Test")
                .lastName("Teacher")
                .phoneNumber("0123456789")
                .role(UserRole.TEACHER)
                .status(UserStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(testTeacher, "id", teacherId);

        testStudent = User.builder()
                .email("student@example.com")
                .firstName("Test")
                .lastName("Student")
                .role(UserRole.LEARNER)
                .status(UserStatus.INACTIVE)
                .build();
        ReflectionTestUtils.setField(testStudent, "id", studentId);
    }

    // =====================================================
    // CLASS MANAGEMENT USE CASES (Create, Update, Status)
    // =====================================================

    @Nested
    @DisplayName("Create Class Tests")
    class CreateClassTests {

        @Test
        @DisplayName("TC01 & TC12 & TC13: Should create class successfully with full data")
        void shouldCreateClassSuccessfully() {
            // Given
            given(classRepository.existsByNameIgnoreCase(createRequest.getName())).willReturn(false);
            given(userRepository.findById(teacherId)).willReturn(Optional.of(testTeacher));
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
            given(classMapper.toEntity(createRequest)).willReturn(testClazz);
            given(classRepository.save(any(Clazz.class))).willReturn(testClazz);
            given(classTeacherHistoryRepository.save(any(ClassTeacherHistory.class))).willReturn(new ClassTeacherHistory());
            given(classMapper.toResponse(any(Clazz.class))).willReturn(testClassResponse);
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(any())).willReturn(Optional.of(teacherId));
            given(classMemberRepository.countActiveByClassId(any())).willReturn(0L);

            // When
            ClassResponse result = clazzService.createClass(createRequest, managerId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("N5 Morning Class");
            assertThat(result.getMemberCount()).isEqualTo(0L);

            verify(classMapper).toEntity(createRequest);
            verify(classRepository).save(clazzCaptor.capture());
            verify(classScheduleManager).scheduleActivation(any(Clazz.class));
            Clazz savedClazz = clazzCaptor.getValue();
            assertThat(savedClazz.getCreatedBy()).isEqualTo(managerId);
            assertThat(savedClazz.getUpdatedBy()).isEqualTo(managerId);
            assertThat(savedClazz.getCourseId()).isEqualTo(courseId);
        }

        @Test
        @DisplayName("TC02: Should set default maxStudents to 8 when null")
        void shouldSetDefaultMaxStudentsWhenNull() {
            // Given
            createRequest.setMaxStudents(null);
            given(classRepository.existsByNameIgnoreCase(createRequest.getName())).willReturn(false);
            given(userRepository.findById(teacherId)).willReturn(Optional.of(testTeacher));
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
            given(classMapper.toEntity(createRequest)).willReturn(testClazz);
            given(classRepository.save(any(Clazz.class))).willReturn(testClazz);
            given(classTeacherHistoryRepository.save(any(ClassTeacherHistory.class))).willReturn(new ClassTeacherHistory());
            given(classMapper.toResponse(any(Clazz.class))).willReturn(testClassResponse);
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(any())).willReturn(Optional.of(teacherId));
            given(classMemberRepository.countActiveByClassId(any())).willReturn(0L);

            // When
            clazzService.createClass(createRequest, managerId);

            // Then
            verify(classRepository).save(clazzCaptor.capture());
            verify(classScheduleManager).scheduleActivation(any(Clazz.class));
            assertThat(clazzCaptor.getValue().getMaxStudents()).isEqualTo(8);
        }

        @Test
        @DisplayName("TC03: Should set status to NOT_STARTED when startDate is in the future")
        void shouldSetStatusNotStartedWhenStartDateIsFuture() {
            // Given
            createRequest.setStartDate(LocalDate.now().plusDays(5));
            given(classRepository.existsByNameIgnoreCase(createRequest.getName())).willReturn(false);
            given(userRepository.findById(teacherId)).willReturn(Optional.of(testTeacher));
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
            given(classMapper.toEntity(createRequest)).willReturn(testClazz);
            given(classRepository.save(any(Clazz.class))).willReturn(testClazz);
            given(classMapper.toResponse(any(Clazz.class))).willReturn(testClassResponse);
            given(classMemberRepository.countActiveByClassId(any())).willReturn(0L);

            // When
            clazzService.createClass(createRequest, managerId);

            // Then
            verify(classRepository).save(clazzCaptor.capture());
            verify(classScheduleManager).scheduleActivation(any(Clazz.class));
            assertThat(clazzCaptor.getValue().getStatus()).isEqualTo(ClassStatus.NOT_STARTED);
        }

        @Test
        @DisplayName("TC06: Should throw exception when class name already exists")
        void shouldThrowWhenClassNameExists() {
            // Given
            given(classRepository.existsByNameIgnoreCase(createRequest.getName())).willReturn(true);

            // When/Then
            assertThatThrownBy(() -> clazzService.createClass(createRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo("CLASS_NAME_EXISTS");

            verify(classRepository, never()).save(any());
        }

        @Test
        @DisplayName("TC07: Should throw exception when teacher not found")
        void shouldThrowWhenTeacherNotFound() {
            // Given
            given(classRepository.existsByNameIgnoreCase(createRequest.getName())).willReturn(false);
            given(userRepository.findById(teacherId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> clazzService.createClass(createRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo("INVALID_TEACHER");

            verify(classRepository, never()).save(any());
        }

        @Test
        @DisplayName("TC08: Should throw exception when teacher has wrong role")
        void shouldThrowWhenTeacherHasWrongRole() {
            // Given
            testTeacher.setRole(UserRole.LEARNER);
            given(classRepository.existsByNameIgnoreCase(createRequest.getName())).willReturn(false);
            given(userRepository.findById(teacherId)).willReturn(Optional.of(testTeacher));

            // When/Then
            assertThatThrownBy(() -> clazzService.createClass(createRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo("INVALID_TEACHER");
        }

        @Test
        @DisplayName("TC09: Should throw exception when teacher is inactive")
        void shouldThrowExceptionWhenTeacherInactive() {
            // Given
            testTeacher.setStatus(UserStatus.INACTIVE);
            given(classRepository.existsByNameIgnoreCase(anyString())).willReturn(false);
            given(userRepository.findById(teacherId)).willReturn(Optional.of(testTeacher));

            // When/Then
            assertThatThrownBy(() -> clazzService.createClass(createRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Giáo viên không tồn tại hoặc không hợp lệ");

            verify(classRepository, never()).save(any());
        }

        @Test
        @DisplayName("TC10: Should throw exception when course not found or deleted")
        void shouldThrowWhenCourseNotFound() {
            given(classRepository.existsByNameIgnoreCase(createRequest.getName())).willReturn(false);
            given(userRepository.findById(teacherId)).willReturn(Optional.of(testTeacher));
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.empty());

            assertThatThrownBy(() -> clazzService.createClass(createRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Khóa học không tồn tại");

            verify(classRepository, never()).save(any());
        }

        @Test
        @DisplayName("TC11: Should throw exception when course is not active")
        void shouldThrowWhenCourseNotPublished() {
            testCourse.setStatus(Course.CourseStatus.INACTIVE);
            given(classRepository.existsByNameIgnoreCase(createRequest.getName())).willReturn(false);
            given(userRepository.findById(teacherId)).willReturn(Optional.of(testTeacher));
            given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));

            assertThatThrownBy(() -> clazzService.createClass(createRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("đang hoạt động");

            verify(classRepository, never()).save(any());
        }
    }

    // =====================================================
    // UPDATE CLASS
    // =====================================================

    @Nested
    @DisplayName("Update Class Tests")
    class UpdateClassTests {

        @Test
        @DisplayName("Should update class successfully")
        void shouldUpdateClassSuccessfully() {
            // Given
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classRepository.existsByNameIgnoreCaseAndIdNot("Updated N5 Class", classId)).willReturn(false);
            given(classRepository.save(any(Clazz.class))).willReturn(testClazz);
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId)).willReturn(Optional.of(teacherId));
            given(classMapper.toResponse(testClazz)).willReturn(testClassResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);

            // When
            ClassResponse result = clazzService.updateClass(classId, updateRequest, managerId);

            // Then
            assertThat(result).isNotNull();
            verify(classMapper).updateEntity(testClazz, updateRequest);
            verify(classRepository).save(clazzCaptor.capture());
            verify(classScheduleManager).scheduleActivation(any(Clazz.class));
            assertThat(clazzCaptor.getValue().getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should throw exception when class not found for update")
        void shouldThrowExceptionWhenClassNotFound() {
            // Given
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> clazzService.updateClass(classId, updateRequest, managerId))
                    .isInstanceOf(ClassNotFoundException.class);

            verify(classRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw exception when new name already exists")
        void shouldThrowExceptionWhenNewNameExists() {
            // Given
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classRepository.existsByNameIgnoreCaseAndIdNot("Updated N5 Class", classId)).willReturn(true);

            // When/Then
            assertThatThrownBy(() -> clazzService.updateClass(classId, updateRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("đã tồn tại");

            verify(classRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should skip name-uniqueness check when name is null")
        void shouldSkipNameCheckWhenNull() {
            // Given
            UpdateClassRequest requestWithoutName = UpdateClassRequest.builder()
                    .description("Only update description")
                    .build();

            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classRepository.save(any(Clazz.class))).willReturn(testClazz);
            given(classMapper.toResponse(testClazz)).willReturn(testClassResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);

            // When
            clazzService.updateClass(classId, requestWithoutName, managerId);

            // Then
            verify(classRepository, never()).existsByNameIgnoreCaseAndIdNot(anyString(), any());
        }

        @Test
        @DisplayName("Should throw when maxStudents is less than current members (E4)")
        void shouldThrowWhenCapacityViolation() {
            // Given
            UpdateClassRequest capacityRequest = UpdateClassRequest.builder()
                    .maxStudents(3)
                    .build();

            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(5L);

            // When/Then
            assertThatThrownBy(() -> clazzService.updateClass(classId, capacityRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Số lượng học viên tối đa không thể nhỏ hơn");

            verify(classRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw exception when new teacher is inactive")
        void shouldThrowExceptionWhenNewTeacherInactive() {
            // Given
            UUID newTeacherId = UUID.randomUUID();
            UpdateClassRequest newTeacherRequest = UpdateClassRequest.builder()
                    .teacherId(newTeacherId)
                    .build();

            User inactiveTeacher = User.builder()
                    .role(UserRole.TEACHER)
                    .status(UserStatus.INACTIVE)
                    .build();

            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(userRepository.findById(newTeacherId)).willReturn(Optional.of(inactiveTeacher));

            // When/Then
            assertThatThrownBy(() -> clazzService.updateClass(classId, newTeacherRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Giáo viên không tồn tại hoặc không hợp lệ");

            verify(classRepository, never()).save(any());
        }

    }

    // =====================================================
    // CLASS QUERYING USE CASES (Get, Search, View Members)
    // =====================================================

    @Nested
    @DisplayName("Get Class Tests")
    class GetClassTests {

        @Test
        @DisplayName("Should get class by ID successfully")
        void shouldGetClassByIdSuccessfully() {
            // Given
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId)).willReturn(Optional.of(teacherId));
            given(classMapper.toResponse(testClazz)).willReturn(testClassResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(5L);

            // When
            ClassResponse result = clazzService.getClassById(classId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(classId);
            assertThat(result.getMemberCount()).isEqualTo(5L);
        }

        @Test
        @DisplayName("Should throw exception when class not found")
        void shouldThrowWhenClassNotFound() {
            // Given
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> clazzService.getClassById(classId))
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    // =====================================================
    // LIST & SEARCH CLASSES
    // =====================================================

    @Nested
    @DisplayName("List & Search Class Tests")
    class ListAndSearchTests {

        @Test
        @DisplayName("Should get classes with dynamic filters")
        void shouldGetClassesWithDynamicFilters() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Clazz> classPage = new PageImpl<>(List.of(testClazz), pageable, 1);
            given(classRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), eq(pageable)))
                    .willReturn(classPage);
            given(classMapper.toSummaryResponse(testClazz)).willReturn(testSummaryResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);

            // When
            Page<ClassSummaryResponse> result = clazzService.getClasses("N5", teacherId, courseId, ClassStatus.ACTIVE, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().get(0).getName()).isEqualTo("N5 Morning Class");
            verify(classRepository).findAll(any(org.springframework.data.jpa.domain.Specification.class), eq(pageable));
        }
    }

    // =====================================================
    // ACTIVATE / DEACTIVATE / DELETE
    // =====================================================

    @Nested
    @DisplayName("Status Management Tests")
    class StatusManagementTests {

        @Test
        @DisplayName("Should delete class successfully (soft delete only, status is deleted)")
        void shouldDeleteClassSuccessfully() {
            // Given
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classRepository.save(any(Clazz.class))).willReturn(testClazz);

            ClassResponse deletedResponse = ClassResponse.builder()
                    .id(classId).status(ClassStatus.DELETED).build();
            given(classMapper.toResponse(testClazz)).willReturn(deletedResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);

            // When
            clazzService.deleteClass(classId, managerId);

            // Then
            verify(classRepository).save(clazzCaptor.capture());
            Clazz captured = clazzCaptor.getValue();
            assertThat(captured.getDeletedAt()).isNotNull();
            assertThat(captured.getDeletedBy()).isEqualTo(managerId);
            assertThat(captured.getUpdatedBy()).isEqualTo(managerId);
            // status should NOT be changed by deleteClass
            assertThat(captured.getStatus()).isEqualTo(ClassStatus.DELETED);
        }
    }

    // =====================================================
    // MEMBERSHIP MANAGEMENT USE CASES (Add, Remove)
    // =====================================================

    @Nested
    @DisplayName("Add Member Tests")
    class AddMemberTests {

        @Test
        @DisplayName("TC01 & TC04: Should add new member successfully and enrich response (ACTIVE user)")
        void shouldAddMemberSuccessfully() {
            // Given
            AddMemberRequest request = AddMemberRequest.builder().userId(studentId).build();
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(userRepository.findById(studentId)).willReturn(Optional.of(testStudent)); // ACTIVE
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(5L);
            given(classMemberRepository.findByClassIdAndUserId(classId, studentId)).willReturn(Optional.empty());
            given(classMemberRepository.save(any(ClassMember.class))).willReturn(testMember);
            given(classMapper.toMemberResponse(testMember)).willReturn(testMemberResponse);
            given(userRepository.save(any(User.class))).willReturn(testStudent);

            // When
            ClassMemberResponse result = clazzService.addMember(classId, request, managerId);

            // Then
            assertThat(result).isNotNull();
            // Verify TC04 Enrichment
            assertThat(result.getFullName()).isEqualTo(testStudent.getFullName());
            assertThat(result.getEmail()).isEqualTo(testStudent.getEmail());
            assertThat(result.getAvatarUrl()).isEqualTo(testStudent.getAvatarUrl());
            assertThat(result.getRole()).isEqualTo(testStudent.getRole().name());

            verify(classMemberRepository).save(any(ClassMember.class));
            verify(userRepository).save(any(User.class));
        }

        @Test
        @DisplayName("TC02: Should activate INACTIVE user when adding to class")
        void shouldActivateInactiveUser() {
            // Given
            User inactiveUser = User.builder()
                    .role(UserRole.LEARNER)
                    .status(UserStatus.INACTIVE)
                    .build();
            ReflectionTestUtils.setField(inactiveUser, "id", studentId);

            AddMemberRequest request = AddMemberRequest.builder().userId(studentId).build();
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(userRepository.findById(studentId)).willReturn(Optional.of(inactiveUser));
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(5L);
            given(classMemberRepository.findByClassIdAndUserId(classId, studentId)).willReturn(Optional.empty());
            given(classMemberRepository.save(any(ClassMember.class))).willReturn(testMember);
            given(classMapper.toMemberResponse(testMember)).willReturn(testMemberResponse);
            given(userRepository.save(any(User.class))).willReturn(inactiveUser);

            // When
            clazzService.addMember(classId, request, managerId);

            // Then
            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        @DisplayName("TC03: Should re-join member (restore inactive member)")
        void shouldRestoreInactiveMember() {
            // Given
            AddMemberRequest request = AddMemberRequest.builder().userId(studentId).build();
            ClassMember inactiveMember = ClassMember.builder()
                    .classId(classId).userId(studentId)
                    .removedAt(LocalDateTime.now())
                    .build();

            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(userRepository.findById(studentId)).willReturn(Optional.of(testStudent));
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(5L);
            given(classMemberRepository.findByClassIdAndUserId(classId, studentId)).willReturn(Optional.of(inactiveMember));
            given(classMemberRepository.save(any(ClassMember.class))).willReturn(inactiveMember);
            given(classMapper.toMemberResponse(inactiveMember)).willReturn(testMemberResponse);
            given(userRepository.save(any(User.class))).willReturn(testStudent);

            // When
            clazzService.addMember(classId, request, managerId);

            // Then
            verify(classMemberRepository).save(memberCaptor.capture());
            assertThat(memberCaptor.getValue().getRemovedAt()).isNull(); // logic RESTORE
        }

        @Test
        @DisplayName("TC05: Should throw ResourceNotFoundException when class not found")
        void shouldThrowWhenClassNotFound() {
            // Given
            AddMemberRequest request = AddMemberRequest.builder().userId(studentId).build();
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> clazzService.addMember(classId, request, managerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("TC06: Should throw exception when class is invalid/full and NOT call user repository")
        void shouldThrowWhenClassInvalidAndShortCircuit() {
            // Given
            AddMemberRequest request = AddMemberRequest.builder().userId(studentId).build();
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(30L); // Max Students = 30

            // When/Then
            assertThatThrownBy(() -> clazzService.addMember(classId, request, managerId))
                    .isInstanceOf(ClassFullException.class);

            // Verify short-circuit
            verify(userRepository, never()).findById(any());
        }

        @Test
        @DisplayName("TC07: Should throw ResourceNotFoundException when user not found")
        void shouldThrowWhenUserNotFound() {
            // Given
            AddMemberRequest request = AddMemberRequest.builder().userId(studentId).build();
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(userRepository.findById(studentId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> clazzService.addMember(classId, request, managerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("TC08: Should throw BusinessException when user is not a LEARNER")
        void shouldThrowWhenUserNotLearner() {
            // Given
            AddMemberRequest request = AddMemberRequest.builder().userId(teacherId).build();
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(userRepository.findById(teacherId)).willReturn(Optional.of(testTeacher)); // testTeacher has TEACHER role

            // When/Then
            assertThatThrownBy(() -> clazzService.addMember(classId, request, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_MEMBER_ROLE");
        }

        @Test
        @DisplayName("TC09: Should throw MemberAlreadyExistsException when member already active and NOT call save()")
        void shouldThrowWhenMemberAlreadyActiveAndNoSave() {
            // Given
            AddMemberRequest request = AddMemberRequest.builder().userId(studentId).build();
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(userRepository.findById(studentId)).willReturn(Optional.of(testStudent));
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(5L);
            given(classMemberRepository.findByClassIdAndUserId(classId, studentId)).willReturn(Optional.of(testMember)); // Active member

            // When/Then
            assertThatThrownBy(() -> clazzService.addMember(classId, request, managerId))
                    .isInstanceOf(MemberAlreadyExistsException.class);

            // Verify no save
            verify(classMemberRepository, never()).save(any());
            verify(userRepository, never()).save(any());
        }
    }

    // =====================================================
        // REMOVE MEMBER
        // =====================================================

    @Nested
    @DisplayName("Remove Member Tests")
    class RemoveMemberTests {

        @Test
        @DisplayName("Should remove member successfully")
        void shouldRemoveMemberSuccessfully() {
            // Given
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classMemberRepository.findByClassIdAndUserId(classId, studentId))
                    .willReturn(Optional.of(testMember));

            // When
            clazzService.removeMember(classId, studentId, managerId);

            // Then
            verify(classMemberRepository).save(memberCaptor.capture());
            assertThat(memberCaptor.getValue().getRemovedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should throw when member not found")
        void shouldThrowWhenMemberNotFound() {
            // Given
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classMemberRepository.findByClassIdAndUserId(classId, studentId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> clazzService.removeMember(classId, studentId, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Học viên không phải là thành viên");
        }

        @Test
        @DisplayName("Should throw when member already removed")
        void shouldThrowWhenMemberAlreadyRemoved() {
            // Given
            ClassMember removedMember = ClassMember.builder()
                    .classId(classId).userId(studentId)
                    .removedAt(LocalDateTime.now())
                    .build();
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classMemberRepository.findByClassIdAndUserId(classId, studentId))
                    .willReturn(Optional.of(removedMember));

            // When/Then
            assertThatThrownBy(() -> clazzService.removeMember(classId, studentId, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Học viên đã bị xóa khỏi lớp này trước đó");
        }
    }

    // =====================================================
    // GET CLASS MEMBERS
    // =====================================================

    @Nested
    @DisplayName("Get Class Members Tests")
    class GetMembersTests {

        @Test
        @DisplayName("Should get class members with pagination")
        void shouldGetClassMembersWithPagination() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<ClassMember> memberPage = new PageImpl<>(List.of(testMember), pageable, 1);
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classMemberRepository.findActiveByClassIdAndKeyword(classId, null, pageable)).willReturn(memberPage);
            given(classMapper.toMemberResponse(testMember)).willReturn(testMemberResponse);

            // When
            Page<ClassMemberResponse> result = clazzService.getClassMembers(classId, null, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().get(0).getUserId()).isEqualTo(studentId);
        }

        @Test
        @DisplayName("Should throw when class not found for member listing")
        void shouldThrowWhenClassNotFoundForMembers() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> clazzService.getClassMembers(classId, null, pageable))
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    // =====================================================
    // CREATE CLASS DATE VALIDATION
    // =====================================================

        @Nested
        @DisplayName("Create Class Date Validation Tests")
        class CreateClassDateValidationTests {

            @Test
            @DisplayName("Should not throw when startDate is today and endDate is after startDate")
            void shouldNotThrowWhenValidDates() {
                CreateClassRequest req = CreateClassRequest.builder()
                        .name("Lớp kiểm thử")
                        .teacherId(teacherId)
                        .courseId(courseId)
                        .maxStudents(30)
                        .startDate(java.time.LocalDate.now().plusDays(1))
                        .build();
                given(classRepository.existsByNameIgnoreCase(req.getName())).willReturn(false);
                given(userRepository.findById(teacherId)).willReturn(Optional.of(testTeacher));
                given(courseRepository.findByIdAndNotDeleted(courseId)).willReturn(Optional.of(testCourse));
                given(classMapper.toEntity(any())).willReturn(testClazz);
                given(classRepository.save(any(Clazz.class))).willReturn(testClazz);
                given(classMapper.toResponse(any(Clazz.class))).willReturn(testClassResponse);
                given(classMemberRepository.countActiveByClassId(any())).willReturn(0L);
                assertThatNoException().isThrownBy(() -> clazzService.createClass(req, managerId));
            }
        }

    // =====================================================
    // GET CLASS MEMBERS (with user info enrichment)
    // =====================================================

    @Nested
    @DisplayName("Get Class Members Tests")
    class GetClassMembersTests {

        @Test
        @DisplayName("Should get class members with user info")
        void shouldGetClassMembersWithUserInfo() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<ClassMember> memberPage = new PageImpl<>(List.of(testMember), pageable, 1);
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classMemberRepository.findActiveByClassIdAndKeyword(classId, null, pageable)).willReturn(memberPage);
            given(classMapper.toMemberResponse(testMember)).willReturn(testMemberResponse);
            given(userRepository.findAllById(List.of(studentId))).willReturn(List.of(testStudent));

            // When
            Page<ClassMemberResponse> result = clazzService.getClassMembers(classId, null, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
            ClassMemberResponse response = result.getContent().get(0);
            assertThat(response.getFullName()).isEqualTo(testStudent.getFullName());
            assertThat(response.getEmail()).isEqualTo("student@example.com");
            assertThat(response.getRole()).isEqualTo("LEARNER");
        }

        @Test
        @DisplayName("Should handle missing user gracefully")
        void shouldHandleMissingUserGracefully() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<ClassMember> memberPage = new PageImpl<>(List.of(testMember), pageable, 1);
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classMemberRepository.findActiveByClassIdAndKeyword(classId, null, pageable)).willReturn(memberPage);
            given(classMapper.toMemberResponse(testMember)).willReturn(testMemberResponse);
            given(userRepository.findAllById(List.of(studentId))).willReturn(List.of());

            // When
            Page<ClassMemberResponse> result = clazzService.getClassMembers(classId, null, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
            ClassMemberResponse response = result.getContent().get(0);
            assertThat(response.getFullName()).isNull();
            assertThat(response.getEmail()).isNull();
        }
    }

    // =====================================================
    // TEACHER SPECIFIC USE CASES 
    // =====================================================

    @Nested
    @DisplayName("Teacher Class Query Tests")
    class TeacherClassQueryTests {

        @Test
        @DisplayName("Should get teacher classes with dynamic filters")
        void shouldGetTeacherClassesWithDynamicFilters() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Clazz> classPage = new PageImpl<>(List.of(testClazz), pageable, 1);
            given(classRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), eq(pageable)))
                    .willReturn(classPage);
            given(classMapper.toSummaryResponse(testClazz)).willReturn(testSummaryResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);

            // When
            Page<ClassSummaryResponse> result = clazzService.getClasses(null, teacherId, null, ClassStatus.ACTIVE, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().get(0).getName()).isEqualTo("N5 Morning Class");
            verify(classRepository).findAll(any(org.springframework.data.jpa.domain.Specification.class), eq(pageable));
        }

        @Test
        @DisplayName("Should return empty page when teacher has no classes")
        void shouldReturnEmptyPageWhenNoActiveClasses() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Clazz> emptyPage = new PageImpl<>(List.of(), pageable, 0);
            given(classRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), eq(pageable)))
                    .willReturn(emptyPage);

            // When
            Page<ClassSummaryResponse> result = clazzService.getClasses(null, teacherId, null, ClassStatus.ACTIVE, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(0);
            assertThat(result.getContent()).isEmpty();
        }
    }

    // =====================================================
    // TEACHER OWNERSHIP VALIDATION
    // =====================================================

    @Nested
    @DisplayName("Teacher Ownership Validation Tests")
    class TeacherOwnershipTests {

        @Test
        @DisplayName("Should return class detail when teacher owns the class")
        void shouldReturnClassDetailWhenTeacherOwns() {
            // Given
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classMapper.toResponse(testClazz)).willReturn(testClassResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(5L);
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId)).willReturn(Optional.of(teacherId));
            given(userRepository.findById(teacherId)).willReturn(Optional.of(testTeacher));

            // When
            ClassResponse result = clazzService.getClassByIdForTeacher(classId, teacherId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("N5 Morning Class");
            assertThat(result.getTeacherId()).isEqualTo(teacherId);
        }

        @Test
        @DisplayName("Should throw ACCESS_DENIED when teacher does not own the class")
        void shouldThrowAccessDeniedWhenTeacherDoesNotOwnClass() {
            // Given
            UUID otherTeacherId = UUID.randomUUID();
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId)).willReturn(Optional.of(teacherId)); // Current teacher is NOT otherTeacherId

            // When / Then
            assertThatThrownBy(() -> clazzService.getClassByIdForTeacher(classId, otherTeacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Bạn không phải là giáo viên của lớp này.");
        }

        @Test
        @DisplayName("Should throw exception when class not found for teacher detail")
        void shouldThrowWhenClassNotFoundForTeacher() {
            // Given
            UUID unknownClassId = UUID.randomUUID();
            given(classRepository.findByIdAndNotDeleted(unknownClassId)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> clazzService.getClassByIdForTeacher(unknownClassId, teacherId))
                    .isInstanceOf(ClassNotFoundException.class);
        }

//        @Test
//        @DisplayName("Should return class members when teacher owns the class")
//        void shouldReturnMembersWhenTeacherOwns() {
//            // Given
//            Pageable pageable = PageRequest.of(0, 10);
//            Page<ClassMember> memberPage = new PageImpl<>(List.of(testMember), pageable, 1);
//            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
//            given(classMemberRepository.findActiveByClassIdAndKeyword(classId, null, pageable)).willReturn(memberPage);
//            given(classMapper.toMemberResponse(testMember)).willReturn(testMemberResponse);
//            given(userRepository.findAllById(List.of(studentId))).willReturn(List.of(testStudent));
//
//            // When
//            Page<ClassMemberResponse> result = clazzService.getClassMembersForTeacher(classId, teacherId, null, pageable);
//
//            // Then
//            assertThat(result.getTotalElements()).isEqualTo(1);
//        }

        @Test
        @DisplayName("Should throw ACCESS_DENIED when teacher views other class members")
        void shouldThrowAccessDeniedWhenTeacherViewsOtherClassMembers() {
            // Given
            UUID otherTeacherId = UUID.randomUUID();
            Pageable pageable = PageRequest.of(0, 10);
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));

            // When / Then
            assertThatThrownBy(() -> clazzService.getClassMembersForTeacher(classId, otherTeacherId, null, pageable))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Bạn không phải là giáo viên của lớp này.");
        }
    }

    // =====================================================
    // TEACHER INFO ENRICHMENT IN CLASS RESPONSE
    // =====================================================

    @Nested
    @DisplayName("Teacher Info Enrichment Tests")
    class TeacherInfoEnrichmentTests {

        @Test
        @DisplayName("Should enrich ClassResponse with teacher info")
        void shouldEnrichClassResponseWithTeacherInfo() {
            // Given
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classMapper.toResponse(testClazz)).willReturn(testClassResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId)).willReturn(Optional.of(teacherId));
            given(userRepository.findById(teacherId)).willReturn(Optional.of(testTeacher));

            // When
            ClassResponse result = clazzService.getClassById(classId);

            // Then
            assertThat(result.getTeacherName()).isEqualTo(testTeacher.getFullName());
            assertThat(result.getTeacherEmail()).isEqualTo("teacher@example.com");
            assertThat(result.getTeacherAvatarUrl()).isNull();
        }

        @Test
        @DisplayName("Should handle missing teacher gracefully")
        void shouldHandleMissingTeacherGracefully() {
            // Given
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classMapper.toResponse(testClazz)).willReturn(testClassResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId)).willReturn(Optional.of(teacherId));
            given(userRepository.findById(teacherId)).willReturn(Optional.empty());

            // When
            ClassResponse result = clazzService.getClassById(classId);

            // Then
            assertThat(result.getTeacherName()).isNull();
            assertThat(result.getTeacherEmail()).isNull();
        }
    }

    // =====================================================
    // ELIGIBLE LEARNERS TESTS
    // =====================================================

    @Nested
    @DisplayName("Get Eligible Learners Tests")
    class GetEligibleLearnersTests {

        @Test
        @DisplayName("Should get eligible learners successfully")
        void shouldGetEligibleLearnersSuccessfully() {
            // Given
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            Pageable pageable = PageRequest.of(0, 10);
            Page<User> usersPage = new PageImpl<>(List.of(testStudent), pageable, 1);
            given(userRepository.findEligibleLearnersForClass(anyString(), anyList(), any(Pageable.class))).willReturn(usersPage);

            // When
            Page<com.fu.user.dto.UserResponse> result = clazzService.getEligibleLearners(classId, "Test", pageable);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().get(0).getId()).isEqualTo(studentId);
            assertThat(result.getContent().get(0).getEmail()).isEqualTo("student@example.com");
            verify(userRepository).findEligibleLearnersForClass(anyString(), anyList(), any(Pageable.class));
        }

        @Test
        @DisplayName("Should throw exception when class not found")
        void shouldThrowExceptionWhenClassNotFound() {
            // Given
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.empty());
            Pageable pageable = PageRequest.of(0, 10);

            // When/Then
            assertThatThrownBy(() -> clazzService.getEligibleLearners(classId, null, pageable))
                    .isInstanceOf(ClassNotFoundException.class);
            verify(userRepository, never()).findEligibleLearnersForClass(any(), any(), any());
        }
    }

    // =====================================================
    // CLOSE CLASS TESTS
    // =====================================================

    @Nested
    @DisplayName("Close Class Tests")
    class CloseClassTests {

        @Test
        @DisplayName("Should close class successfully")
        void shouldCloseClassSuccessfully() {
            // Given
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classRepository.save(any(Clazz.class))).willReturn(testClazz);
            given(classMapper.toResponse(any())).willReturn(testClassResponse);
            given(classMemberRepository.findActiveUserIdsByClassId(classId)).willReturn(List.of(studentId));
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId)).willReturn(Optional.of(teacherId));

            // When
            ClassResponse result = clazzService.closeClass(classId, managerId);

            // Then
            assertThat(result).isNotNull();
            verify(classRepository).save(clazzCaptor.capture());
            assertThat(clazzCaptor.getValue().getStatus()).isEqualTo(ClassStatus.CLOSED);
            assertThat(clazzCaptor.getValue().getEndDate()).isEqualTo(LocalDate.now());
            verify(userRepository).updateStatusByUserIdIn(anyList(), eq(UserStatus.INACTIVE));
        }

        @Test
        @DisplayName("Should throw exception when closing already inactive class")
        void shouldThrowWhenClosingInactiveClass() {
            testClazz.setStatus(ClassStatus.CLOSED);
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));

            assertThatThrownBy(() -> clazzService.closeClass(classId, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Lớp học đã ngưng hoạt động");
        }
    }

    // =====================================================
    // ADDITIONAL DATE LOGIC TESTS
    // =====================================================

    @Nested
    @DisplayName("Additional Date Logic Tests")
    class AdditionalDateLogicTests {

        @Test
        @DisplayName("Should throw exception when moving startDate later with members")
        void shouldThrowWhenMovingStartDateLaterWithMembers() {
            // Given
            testClazz.setStartDate(LocalDate.now().plusDays(10));
            UpdateClassRequest req = UpdateClassRequest.builder()
                    .startDate(LocalDate.now().plusDays(15)) // Later
                    .build();
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(5L);

            // When/Then
            assertThatThrownBy(() -> clazzService.updateClass(classId, req, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Không thể cập nhật ngày bắt đầu sau ngày bắt đầu cũ");
        }

        @Test
        @DisplayName("Should allow moving startDate earlier with members")
        void shouldAllowMovingStartDateEarlierWithMembers() {
            // Given
            testClazz.setStartDate(LocalDate.now().plusDays(10));
            UpdateClassRequest req = UpdateClassRequest.builder()
                    .startDate(LocalDate.now().plusDays(5)) // Earlier
                    .build();
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(5L);
            given(classRepository.save(any(Clazz.class))).willReturn(testClazz);
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId)).willReturn(Optional.of(teacherId));
            given(classMapper.toResponse(testClazz)).willReturn(testClassResponse);

            // When
            clazzService.updateClass(classId, req, managerId);

            // Then
            verify(classRepository).save(any(Clazz.class));
            verify(classScheduleManager).scheduleActivation(any(Clazz.class));
        }

        @Test
        @DisplayName("Should auto-activate when moving startDate to today with no members")
        void shouldAutoActivateWhenMovingStartDateToTodayNoMembers() {
            // Given
            testClazz.setStartDate(LocalDate.now().plusDays(10));
            testClazz.setStatus(ClassStatus.NOT_STARTED);
            UpdateClassRequest req = UpdateClassRequest.builder()
                    .startDate(LocalDate.now()) // Today
                    .build();
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);
            given(classRepository.save(any(Clazz.class))).willReturn(testClazz);
            given(classTeacherHistoryRepository.findCurrentTeacherIdByClassId(classId)).willReturn(Optional.of(teacherId));
            given(classMapper.toResponse(testClazz)).willReturn(testClassResponse);

            // When
            clazzService.updateClass(classId, req, managerId);

            // Then
            verify(classRepository).save(clazzCaptor.capture());
            verify(classScheduleManager).scheduleActivation(any(Clazz.class));
            assertThat(clazzCaptor.getValue().getStatus()).isEqualTo(ClassStatus.ACTIVE);
        }
    }

    // =====================================================
    // LEARNER QUERIES
    // =====================================================
    @Nested
    @DisplayName("Get Learner Classes Tests")
    class GetLearnerClassesTests {
        private UUID learnerId;
        private Clazz activeClass;
        private Clazz closedClass;
        private Clazz notStartedClass;
        private LearnerClassResponse activeResponse;
        private LearnerClassResponse closedResponse;

        @BeforeEach
        void setUp() {
            learnerId = UUID.randomUUID();
            
            activeClass = Clazz.builder()
                    .id(UUID.randomUUID())
                    .name("Active Class")
                    .status(ClassStatus.ACTIVE)
                    .build();
            
            closedClass = Clazz.builder()
                    .id(UUID.randomUUID())
                    .name("Closed Class")
                    .status(ClassStatus.CLOSED)
                    .build();
            
            notStartedClass = Clazz.builder()
                    .id(UUID.randomUUID())
                    .name("Not Started Class")
                    .status(ClassStatus.NOT_STARTED)
                    .build();
            
            activeResponse = LearnerClassResponse.builder()
                    .id(activeClass.getId())
                    .name(activeClass.getName())
                    .status(ClassStatus.ACTIVE)
                    .build();
            
            closedResponse = LearnerClassResponse.builder()
                    .id(closedClass.getId())
                    .name(closedClass.getName())
                    .status(ClassStatus.CLOSED)
                    .build();
        }

        @Test
        @DisplayName("Should return active and closed classes for learner")
        void shouldReturnActiveAndClosedClasses() {
            // Given
            List<ClassStatus> allowedStatuses = List.of(ClassStatus.ACTIVE, ClassStatus.CLOSED);
            List<Clazz> classes = List.of(activeClass, closedClass);
            
            given(classRepository.findLearnerClasses(learnerId, allowedStatuses))
                    .willReturn(classes);
            given(classMapper.toLearnerClassResponse(activeClass)).willReturn(activeResponse);
            given(classMapper.toLearnerClassResponse(closedClass)).willReturn(closedResponse);

            // When
            List<LearnerClassResponse> result = clazzService.getLearnerClasses(learnerId);

            // Then
            assertThat(result)
                    .hasSize(2)
                    .containsExactly(activeResponse, closedResponse);
            verify(classRepository).findLearnerClasses(learnerId, allowedStatuses);
            verify(classMapper, times(2)).toLearnerClassResponse(any(Clazz.class));
        }

        @Test
        @DisplayName("Should return empty list when learner has no classes")
        void shouldReturnEmptyListWhenNoClasses() {
            // Given
            List<ClassStatus> allowedStatuses = List.of(ClassStatus.ACTIVE, ClassStatus.CLOSED);
            given(classRepository.findLearnerClasses(learnerId, allowedStatuses))
                    .willReturn(List.of());

            // When
            List<LearnerClassResponse> result = clazzService.getLearnerClasses(learnerId);

            // Then
            assertThat(result).isEmpty();
            verify(classRepository).findLearnerClasses(learnerId, allowedStatuses);
            verify(classMapper, never()).toLearnerClassResponse(any());
        }

        @Test
        @DisplayName("Should only return active class when learner has only active enrollment")
        void shouldReturnOnlyActiveClass() {
            // Given
            List<ClassStatus> allowedStatuses = List.of(ClassStatus.ACTIVE, ClassStatus.CLOSED);
            List<Clazz> classes = List.of(activeClass);
            
            given(classRepository.findLearnerClasses(learnerId, allowedStatuses))
                    .willReturn(classes);
            given(classMapper.toLearnerClassResponse(activeClass)).willReturn(activeResponse);

            // When
            List<LearnerClassResponse> result = clazzService.getLearnerClasses(learnerId);

            // Then
            assertThat(result)
                    .hasSize(1)
                    .containsExactly(activeResponse);
            assertThat(result.get(0).getStatus()).isEqualTo(ClassStatus.ACTIVE);
        }

        @Test
        @DisplayName("Should only return closed class when learner has only historical enrollment")
        void shouldReturnOnlyClosedClass() {
            // Given
            List<ClassStatus> allowedStatuses = List.of(ClassStatus.ACTIVE, ClassStatus.CLOSED);
            List<Clazz> classes = List.of(closedClass);
            
            given(classRepository.findLearnerClasses(learnerId, allowedStatuses))
                    .willReturn(classes);
            given(classMapper.toLearnerClassResponse(closedClass)).willReturn(closedResponse);

            // When
            List<LearnerClassResponse> result = clazzService.getLearnerClasses(learnerId);

            // Then
            assertThat(result)
                    .hasSize(1)
                    .containsExactly(closedResponse);
            assertThat(result.get(0).getStatus()).isEqualTo(ClassStatus.CLOSED);
        }
    }
}
