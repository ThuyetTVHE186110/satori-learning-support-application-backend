package com.fu.clazz.service;

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
import com.fu.clazz.service.impl.ClazzServiceImpl;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ClazzServiceImpl.
 * Covers: CRUD, activate/deactivate, soft-delete, membership.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClazzService Unit Tests")
class ClazzServiceImplTest {

    @Mock
    private ClazzRepository clazzRepository;

    @Mock
    private ClassMemberRepository classMemberRepository;

    @Mock
    private ClazzMapper clazzMapper;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ClazzServiceImpl clazzService;

    @Captor
    private ArgumentCaptor<Clazz> clazzCaptor;

    @Captor
    private ArgumentCaptor<ClassMember> memberCaptor;

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
    private User testTeacher;
    private User testStudent;

    @BeforeEach
    void setUp() {
        managerId = UUID.randomUUID();
        classId = UUID.randomUUID();
        teacherId = UUID.randomUUID();
        studentId = UUID.randomUUID();

        testClazz = Clazz.builder()
                .name("N5 Morning Class")
                .description("Beginner Japanese class")
                .code("ABC12345")
                .teacherId(teacherId)
                .jlptLevel(JlptLevel.N5)
                .maxStudents(30)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 6, 30))
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(testClazz, "id", classId);

        testClassResponse = ClassResponse.builder()
                .id(classId)
                .name("N5 Morning Class")
                .description("Beginner Japanese class")
                .code("ABC12345")
                .teacherId(teacherId)
                .jlptLevel(JlptLevel.N5)
                .maxStudents(30)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 6, 30))
                .isActive(true)
                .memberCount(0L)
                .build();

        testSummaryResponse = ClassSummaryResponse.builder()
                .id(classId)
                .name("N5 Morning Class")
                .code("ABC12345")
                .teacherId(teacherId)
                .jlptLevel(JlptLevel.N5)
                .isActive(true)
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
                .jlptLevel(JlptLevel.N5)
                .maxStudents(30)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 6, 30))
                .build();

        updateRequest = UpdateClassRequest.builder()
                .name("Updated N5 Class")
                .description("Updated description")
                .build();

        testTeacher = User.builder()
                .email("teacher@example.com")
                .firstName("Test")
                .lastName("Teacher")
                .role(UserRole.TEACHER)
                .status(UserStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(testTeacher, "id", teacherId);

        testStudent = User.builder()
                .email("student@example.com")
                .firstName("Test")
                .lastName("Student")
                .role(UserRole.LEARNER)
                .status(UserStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(testStudent, "id", studentId);
    }

    // =====================================================
    // CREATE CLASS
    // =====================================================

    @Nested
    @DisplayName("Create Class Tests")
    class CreateClassTests {

        @Test
        @DisplayName("Should create class successfully")
        void shouldCreateClassSuccessfully() {
            // Given
            given(userRepository.findById(teacherId)).willReturn(Optional.of(testTeacher));
            given(clazzRepository.existsByCode(anyString())).willReturn(false);
            given(clazzMapper.toEntity(createRequest)).willReturn(testClazz);
            given(clazzRepository.save(any(Clazz.class))).willReturn(testClazz);
            given(clazzMapper.toResponse(testClazz)).willReturn(testClassResponse);

            // When
            ClassResponse result = clazzService.createClass(createRequest, managerId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("N5 Morning Class");
            assertThat(result.getJlptLevel()).isEqualTo(JlptLevel.N5);
            assertThat(result.getMemberCount()).isEqualTo(0L);

            verify(clazzRepository).save(clazzCaptor.capture());
            Clazz savedClazz = clazzCaptor.getValue();
            assertThat(savedClazz.getCode()).isNotBlank();
            assertThat(savedClazz.getCreatedBy()).isEqualTo(managerId);
            assertThat(savedClazz.getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should generate unique code with retry")
        void shouldRetryCodeGenerationOnConflict() {
            // Given - first code exists, second does not
            given(userRepository.findById(teacherId)).willReturn(Optional.of(testTeacher));
            given(clazzRepository.existsByCode(anyString())).willReturn(true, false);
            given(clazzMapper.toEntity(createRequest)).willReturn(testClazz);
            given(clazzRepository.save(any(Clazz.class))).willReturn(testClazz);
            given(clazzMapper.toResponse(testClazz)).willReturn(testClassResponse);

            // When
            ClassResponse result = clazzService.createClass(createRequest, managerId);

            // Then
            assertThat(result).isNotNull();
            verify(clazzRepository, atLeast(2)).existsByCode(anyString());
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
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(clazzRepository.existsByNameIgnoreCaseAndIdNot("Updated N5 Class", classId)).willReturn(false);
            given(clazzRepository.save(any(Clazz.class))).willReturn(testClazz);
            given(clazzMapper.toResponse(testClazz)).willReturn(testClassResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);

            // When
            ClassResponse result = clazzService.updateClass(classId, updateRequest, managerId);

            // Then
            assertThat(result).isNotNull();
            verify(clazzMapper).updateEntity(testClazz, updateRequest);
            verify(clazzRepository).save(clazzCaptor.capture());
            assertThat(clazzCaptor.getValue().getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should throw exception when class not found for update")
        void shouldThrowExceptionWhenClassNotFound() {
            // Given
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> clazzService.updateClass(classId, updateRequest, managerId))
                    .isInstanceOf(ClazzNotFoundException.class);

            verify(clazzRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw exception when new name already exists")
        void shouldThrowExceptionWhenNewNameExists() {
            // Given
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(clazzRepository.existsByNameIgnoreCaseAndIdNot("Updated N5 Class", classId)).willReturn(true);

            // When/Then
            assertThatThrownBy(() -> clazzService.updateClass(classId, updateRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already exists");

            verify(clazzRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should skip name-uniqueness check when name is null")
        void shouldSkipNameCheckWhenNull() {
            // Given
            UpdateClassRequest requestWithoutName = UpdateClassRequest.builder()
                    .description("Only update description")
                    .build();

            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(clazzRepository.save(any(Clazz.class))).willReturn(testClazz);
            given(clazzMapper.toResponse(testClazz)).willReturn(testClassResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);

            // When
            clazzService.updateClass(classId, requestWithoutName, managerId);

            // Then
            verify(clazzRepository, never()).existsByNameIgnoreCaseAndIdNot(anyString(), any());
        }
    }

    // =====================================================
    // GET CLASS
    // =====================================================

    @Nested
    @DisplayName("Get Class Tests")
    class GetClassTests {

        @Test
        @DisplayName("Should get class by ID successfully")
        void shouldGetClassByIdSuccessfully() {
            // Given
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(clazzMapper.toResponse(testClazz)).willReturn(testClassResponse);
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
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> clazzService.getClassById(classId))
                    .isInstanceOf(ClazzNotFoundException.class);
        }
    }

    // =====================================================
    // LIST & SEARCH CLASSES
    // =====================================================

    @Nested
    @DisplayName("List & Search Class Tests")
    class ListAndSearchTests {

        @Test
        @DisplayName("Should get all classes with pagination")
        void shouldGetAllClassesWithPagination() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Clazz> classPage = new PageImpl<>(List.of(testClazz), pageable, 1);
            given(clazzRepository.findAllNotDeleted(pageable)).willReturn(classPage);
            given(clazzMapper.toSummaryResponse(testClazz)).willReturn(testSummaryResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);

            // When
            Page<ClassSummaryResponse> result = clazzService.getAllClasses(pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().getFirst().getName()).isEqualTo("N5 Morning Class");
        }

        @Test
        @DisplayName("Should get classes by active status")
        void shouldGetClassesByActiveStatus() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Clazz> classPage = new PageImpl<>(List.of(testClazz), pageable, 1);
            given(clazzRepository.findByIsActiveAndDeletedAtIsNull(true, pageable)).willReturn(classPage);
            given(clazzMapper.toSummaryResponse(testClazz)).willReturn(testSummaryResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);

            // When
            Page<ClassSummaryResponse> result = clazzService.getClassesByActiveStatus(true, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should get classes by JLPT level")
        void shouldGetClassesByJlptLevel() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Clazz> classPage = new PageImpl<>(List.of(testClazz), pageable, 1);
            given(clazzRepository.findByJlptLevelAndDeletedAtIsNull(JlptLevel.N5, pageable)).willReturn(classPage);
            given(clazzMapper.toSummaryResponse(testClazz)).willReturn(testSummaryResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);

            // When
            Page<ClassSummaryResponse> result = clazzService.getClassesByJlptLevel(JlptLevel.N5, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should search classes by keyword")
        void shouldSearchClassesByKeyword() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<Clazz> classPage = new PageImpl<>(List.of(testClazz), pageable, 1);
            given(clazzRepository.searchByName("N5", pageable)).willReturn(classPage);
            given(clazzMapper.toSummaryResponse(testClazz)).willReturn(testSummaryResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);

            // When
            Page<ClassSummaryResponse> result = clazzService.searchClasses("N5", pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
        }
    }

    // =====================================================
    // ACTIVATE / DEACTIVATE / DELETE
    // =====================================================

    @Nested
    @DisplayName("Status Management Tests")
    class StatusManagementTests {

        @Test
        @DisplayName("Should activate class successfully")
        void shouldActivateClassSuccessfully() {
            // Given
            testClazz.setIsActive(false);
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(clazzRepository.save(any(Clazz.class))).willReturn(testClazz);

            ClassResponse activatedResponse = ClassResponse.builder()
                    .id(classId).isActive(true).build();
            given(clazzMapper.toResponse(testClazz)).willReturn(activatedResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);

            // When
            ClassResponse result = clazzService.activateClass(classId, managerId);

            // Then
            verify(clazzRepository).save(clazzCaptor.capture());
            assertThat(clazzCaptor.getValue().getIsActive()).isTrue();
            assertThat(clazzCaptor.getValue().getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should deactivate class successfully")
        void shouldDeactivateClassSuccessfully() {
            // Given
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(clazzRepository.save(any(Clazz.class))).willReturn(testClazz);

            ClassResponse deactivatedResponse = ClassResponse.builder()
                    .id(classId).isActive(false).build();
            given(clazzMapper.toResponse(testClazz)).willReturn(deactivatedResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);

            // When
            ClassResponse result = clazzService.deactivateClass(classId, managerId);

            // Then
            verify(clazzRepository).save(clazzCaptor.capture());
            assertThat(clazzCaptor.getValue().getIsActive()).isFalse();
            assertThat(clazzCaptor.getValue().getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should soft-delete class successfully")
        void shouldSoftDeleteClassSuccessfully() {
            // Given
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));

            // When
            clazzService.deleteClass(classId, managerId);

            // Then
            verify(clazzRepository).save(clazzCaptor.capture());
            Clazz deleted = clazzCaptor.getValue();
            assertThat(deleted.isDeleted()).isTrue();
            assertThat(deleted.getDeletedAt()).isNotNull();
            assertThat(deleted.getDeletedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should throw exception when deleting non-existent class")
        void shouldThrowWhenDeletingNonExistentClass() {
            // Given
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> clazzService.deleteClass(classId, managerId))
                    .isInstanceOf(ClazzNotFoundException.class);
        }
    }

    // =====================================================
    // TEACHER ASSIGNMENT
    // =====================================================

    @Nested
    @DisplayName("Assign Teacher Tests")
    class AssignTeacherTests {

        @Test
        @DisplayName("Should assign teacher successfully")
        void shouldAssignTeacherSuccessfully() {
            // Given
            UUID newTeacherId = UUID.randomUUID();
            User newTeacher = User.builder()
                    .email("newteacher@example.com")
                    .firstName("New")
                    .lastName("Teacher")
                    .role(UserRole.TEACHER)
                    .status(UserStatus.ACTIVE)
                    .build();
            ReflectionTestUtils.setField(newTeacher, "id", newTeacherId);

            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(userRepository.findById(newTeacherId)).willReturn(Optional.of(newTeacher));
            given(clazzRepository.save(any(Clazz.class))).willReturn(testClazz);
            given(clazzMapper.toResponse(testClazz)).willReturn(testClassResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);

            // When
            ClassResponse result = clazzService.assignTeacher(classId, newTeacherId, managerId);

            // Then
            verify(clazzRepository).save(clazzCaptor.capture());
            assertThat(clazzCaptor.getValue().getTeacherId()).isEqualTo(newTeacherId);
            assertThat(clazzCaptor.getValue().getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should throw when assigning teacher to non-existent class")
        void shouldThrowWhenClassNotFound() {
            // Given
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> clazzService.assignTeacher(classId, teacherId, managerId))
                    .isInstanceOf(ClazzNotFoundException.class);
        }

        @Test
        @DisplayName("Should throw when assigning non-teacher user as teacher")
        void shouldThrowWhenUserIsNotTeacher() {
            // Given
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(userRepository.findById(studentId)).willReturn(Optional.of(testStudent)); // LEARNER role

            // When/Then
            assertThatThrownBy(() -> clazzService.assignTeacher(classId, studentId, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("TEACHER role");
        }

        @Test
        @DisplayName("Should throw when assigned user not found")
        void shouldThrowWhenTeacherUserNotFound() {
            // Given
            UUID unknownId = UUID.randomUUID();
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(userRepository.findById(unknownId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> clazzService.assignTeacher(classId, unknownId, managerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // =====================================================
    // MEMBERSHIP MANAGEMENT
    // =====================================================

    @Nested
    @DisplayName("Add Member Tests")
    class AddMemberTests {

        @Test
        @DisplayName("Should add member successfully")
        void shouldAddMemberSuccessfully() {
            // Given
            AddMemberRequest request = AddMemberRequest.builder().userId(studentId).build();
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(userRepository.findById(studentId)).willReturn(Optional.of(testStudent));
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(5L);
            given(classMemberRepository.findByClassIdAndUserId(classId, studentId)).willReturn(Optional.empty());
            given(classMemberRepository.save(any(ClassMember.class))).willReturn(testMember);
            given(clazzMapper.toMemberResponse(testMember)).willReturn(testMemberResponse);

            // When
            ClassMemberResponse result = clazzService.addMember(classId, request, managerId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getUserId()).isEqualTo(studentId);
            assertThat(result.getClassId()).isEqualTo(classId);

            verify(classMemberRepository).save(memberCaptor.capture());
            assertThat(memberCaptor.getValue().getClassId()).isEqualTo(classId);
            assertThat(memberCaptor.getValue().getUserId()).isEqualTo(studentId);
        }

        @Test
        @DisplayName("Should throw exception when class is full")
        void shouldThrowWhenClassIsFull() {
            // Given
            AddMemberRequest request = AddMemberRequest.builder().userId(studentId).build();
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(userRepository.findById(studentId)).willReturn(Optional.of(testStudent));
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(30L); // maxStudents = 30

            // When/Then
            assertThatThrownBy(() -> clazzService.addMember(classId, request, managerId))
                    .isInstanceOf(ClassFullException.class);

            verify(classMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw exception when member already exists")
        void shouldThrowWhenMemberAlreadyExists() {
            // Given
            AddMemberRequest request = AddMemberRequest.builder().userId(studentId).build();
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(userRepository.findById(studentId)).willReturn(Optional.of(testStudent));
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(5L);
            given(classMemberRepository.findByClassIdAndUserId(classId, studentId)).willReturn(Optional.of(testMember));

            // When/Then
            assertThatThrownBy(() -> clazzService.addMember(classId, request, managerId))
                    .isInstanceOf(MemberAlreadyExistsException.class);
        }

        @Test
        @DisplayName("Should re-join previously removed member")
        void shouldReJoinRemovedMember() {
            // Given
            AddMemberRequest request = AddMemberRequest.builder().userId(studentId).build();
            ClassMember removedMember = ClassMember.builder()
                    .classId(classId).userId(studentId)
                    .joinedAt(LocalDateTime.now().minusDays(10))
                    .removedAt(LocalDateTime.now().minusDays(1))
                    .build();
            ReflectionTestUtils.setField(removedMember, "id", UUID.randomUUID());

            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(userRepository.findById(studentId)).willReturn(Optional.of(testStudent));
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(5L);
            given(classMemberRepository.findByClassIdAndUserId(classId, studentId)).willReturn(Optional.of(removedMember));
            given(classMemberRepository.save(any(ClassMember.class))).willReturn(removedMember);
            given(clazzMapper.toMemberResponse(removedMember)).willReturn(testMemberResponse);

            // When
            ClassMemberResponse result = clazzService.addMember(classId, request, managerId);

            // Then
            assertThat(result).isNotNull();
            verify(classMemberRepository).save(memberCaptor.capture());
            assertThat(memberCaptor.getValue().getRemovedAt()).isNull(); // restored
        }

        @Test
        @DisplayName("Should throw when adding to inactive class")
        void shouldThrowWhenClassIsInactive() {
            // Given
            testClazz.setIsActive(false);
            AddMemberRequest request = AddMemberRequest.builder().userId(studentId).build();
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(userRepository.findById(studentId)).willReturn(Optional.of(testStudent));

            // When/Then
            assertThatThrownBy(() -> clazzService.addMember(classId, request, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("inactive");

            verify(classMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw when adding user with TEACHER role as member")
        void shouldThrowWhenAddingTeacherAsMember() {
            // Given
            AddMemberRequest request = AddMemberRequest.builder().userId(teacherId).build();
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(userRepository.findById(teacherId)).willReturn(Optional.of(testTeacher));

            // When/Then
            assertThatThrownBy(() -> clazzService.addMember(classId, request, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("LEARNER role");

            verify(classMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw when adding non-existent user as member")
        void shouldThrowWhenUserNotFound() {
            // Given
            UUID unknownId = UUID.randomUUID();
            AddMemberRequest request = AddMemberRequest.builder().userId(unknownId).build();
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(userRepository.findById(unknownId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> clazzService.addMember(classId, request, managerId))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(classMemberRepository, never()).save(any());
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
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classMemberRepository.findByClassIdAndUserId(classId, studentId)).willReturn(Optional.of(testMember));

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
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classMemberRepository.findByClassIdAndUserId(classId, studentId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> clazzService.removeMember(classId, studentId, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not a member");
        }

        @Test
        @DisplayName("Should throw when member already removed")
        void shouldThrowWhenMemberAlreadyRemoved() {
            // Given
            ClassMember removedMember = ClassMember.builder()
                    .classId(classId).userId(studentId)
                    .removedAt(LocalDateTime.now())
                    .build();
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classMemberRepository.findByClassIdAndUserId(classId, studentId)).willReturn(Optional.of(removedMember));

            // When/Then
            assertThatThrownBy(() -> clazzService.removeMember(classId, studentId, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already been removed");
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
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classMemberRepository.findActiveByClassId(classId, pageable)).willReturn(memberPage);
            given(clazzMapper.toMemberResponse(testMember)).willReturn(testMemberResponse);

            // When
            Page<ClassMemberResponse> result = clazzService.getClassMembers(classId, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().getFirst().getUserId()).isEqualTo(studentId);
        }

        @Test
        @DisplayName("Should throw when class not found for member listing")
        void shouldThrowWhenClassNotFoundForMembers() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            given(clazzRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> clazzService.getClassMembers(classId, pageable))
                    .isInstanceOf(ClazzNotFoundException.class);
        }
    }
}

