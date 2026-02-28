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
import com.fu.clazz.exception.ClassNotFoundException;
import com.fu.clazz.exception.MemberAlreadyExistsException;
import com.fu.clazz.mapper.ClassMapper;
import com.fu.clazz.repository.ClassMemberRepository;
import com.fu.clazz.repository.ClassRepository;
import com.fu.clazz.service.impl.ClassServiceImpl;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ClazzServiceImpl.
 * Covers: CRUD, activate/deactivate, soft-delete, membership.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClazzService Unit Tests")
class ClassServiceImplTest {

    @Mock
    private ClassRepository classRepository;

    @Mock
    private ClassMemberRepository classMemberRepository;

    @Mock
    private ClassMapper classMapper;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ClassServiceImpl clazzService;

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
            given(classMapper.toEntity(createRequest)).willReturn(testClazz);
            given(classRepository.save(any(Clazz.class))).willReturn(testClazz);
            given(classMapper.toResponse(testClazz)).willReturn(testClassResponse);

            // When
            ClassResponse result = clazzService.createClass(createRequest, managerId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("N5 Morning Class");
            assertThat(result.getJlptLevel()).isEqualTo(JlptLevel.N5);
            assertThat(result.getMemberCount()).isEqualTo(0L);

            verify(classRepository).save(clazzCaptor.capture());
            Clazz savedClazz = clazzCaptor.getValue();
            assertThat(savedClazz.getCode()).isNotBlank();
            assertThat(savedClazz.getCreatedBy()).isEqualTo(managerId);
            assertThat(savedClazz.getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should throw exception when teacher is inactive or not found")
        void shouldThrowExceptionWhenTeacherInactive() {
            // Given
            testTeacher.setStatus(UserStatus.INACTIVE);
            given(userRepository.findById(teacherId)).willReturn(Optional.of(testTeacher));
            given(classRepository.existsByNameIgnoreCase(anyString())).willReturn(false);

            // When/Then
            assertThatThrownBy(() -> clazzService.createClass(createRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Giáo viên không tồn tại hoặc không hợp lệ");

            verify(classRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw exception when description exceeds 5000 characters")
        void shouldThrowExceptionWhenDescriptionTooLong() {
            // Given
            String longDescription = "A".repeat(5001);
            CreateClassRequest badRequest = CreateClassRequest.builder()
                    .name("N5 Morning Class")
                    .description(longDescription)
                    .teacherId(teacherId)
                    .jlptLevel(JlptLevel.N5)
                    .maxStudents(30)
                    .startDate(LocalDate.of(2026, 3, 1))
                    .endDate(LocalDate.of(2026, 6, 30))
                    .build();

            given(classRepository.existsByNameIgnoreCase(anyString())).willReturn(false);
            given(userRepository.findById(teacherId)).willReturn(Optional.of(testTeacher));

            // When/Then
            assertThatThrownBy(() -> clazzService.createClass(badRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Mô tả không được vượt quá 5000 ký tự");

            verify(classRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw exception when start date is in the past (E4)")
        void shouldThrowExceptionWhenStartDateInPast() {
            // Given
            CreateClassRequest badDateRequest = CreateClassRequest.builder()
                    .name("N5 Morning Class")
                    .description("Beginner Japanese class")
                    .teacherId(teacherId)
                    .jlptLevel(JlptLevel.N5)
                    .maxStudents(30)
                    .startDate(LocalDate.now().minusDays(1)) // Past date
                    .endDate(LocalDate.now().plusMonths(3))
                    .build();

            // When/Then
            assertThatThrownBy(() -> clazzService.createClass(badDateRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Ngày bắt đầu phải ở tương lai.");

            verify(classRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw exception when end date is before start date (E3)")
        void shouldThrowExceptionWhenEndDateBeforeStartDate() {
            // Given
            CreateClassRequest badDateRequest = CreateClassRequest.builder()
                    .name("N5 Morning Class")
                    .description("Beginner Japanese class")
                    .teacherId(teacherId)
                    .jlptLevel(JlptLevel.N5)
                    .maxStudents(30)
                    .startDate(LocalDate.now().plusDays(10))
                    .endDate(LocalDate.now().plusDays(5)) // Before start date
                    .build();

            // When/Then
            assertThatThrownBy(() -> clazzService.createClass(badDateRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Ngày kết thúc phải sau ngày bắt đầu.");

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
            given(classMapper.toResponse(testClazz)).willReturn(testClassResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);

            // When
            ClassResponse result = clazzService.updateClass(classId, updateRequest, managerId);

            // Then
            assertThat(result).isNotNull();
            verify(classMapper).updateEntity(testClazz, updateRequest);
            verify(classRepository).save(clazzCaptor.capture());
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
        @DisplayName("Should throw when endDate is before startDate on update (E3)")
        void shouldThrowWhenEndDateBeforeStartDateOnUpdate() {
            // Given
            UpdateClassRequest dateRequest = UpdateClassRequest.builder()
                    .startDate(LocalDate.of(2026, 6, 1))
                    .endDate(LocalDate.of(2026, 3, 1))
                    .build();

            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));

            // When/Then
            assertThatThrownBy(() -> clazzService.updateClass(classId, dateRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Ngày bắt đầu phải trước ngày kết thúc");

            verify(classRepository, never()).save(any());
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

        @Test
        @DisplayName("Should throw exception when description exceeds 5000 characters on update")
        void shouldThrowExceptionWhenDescriptionTooLongOnUpdate() {
            // Given
            String longDescription = "A".repeat(5001);
            UpdateClassRequest badRequest = UpdateClassRequest.builder()
                    .description(longDescription)
                    .build();

            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));

            // When/Then
            assertThatThrownBy(() -> clazzService.updateClass(classId, badRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Mô tả không được vượt quá 5000 ký tự");

            verify(classRepository, never()).save(any());
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
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
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
            Page<ClassSummaryResponse> result = clazzService.getClasses("N5", teacherId, true, JlptLevel.N5, pageable);

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
        @DisplayName("Should delete class successfully (soft delete only, isActive unchanged)")
        void shouldDeleteClassSuccessfully() {
            // Given
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classRepository.save(any(Clazz.class))).willReturn(testClazz);

            ClassResponse deletedResponse = ClassResponse.builder()
                    .id(classId).isActive(true).build();
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
            // isActive should NOT be changed by deleteClass
            assertThat(captured.getIsActive()).isTrue();
        }

        @Test
        @DisplayName("Should deactivate class via updateClass with isActive=false")
        void shouldDeactivateClassViaUpdate() {
            // Given
            UpdateClassRequest deactivateRequest = UpdateClassRequest.builder()
                    .isActive(false)
                    .build();

            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classRepository.save(any(Clazz.class))).willReturn(testClazz);
            given(classMapper.toResponse(testClazz)).willReturn(testClassResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);

            // When
            clazzService.updateClass(classId, deactivateRequest, managerId);

            // Then
            verify(classMapper).updateEntity(testClazz, deactivateRequest);
            verify(classRepository).save(clazzCaptor.capture());
            assertThat(clazzCaptor.getValue().getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should activate class via updateClass with isActive=true")
        void shouldActivateClassViaUpdate() {
            // Given
            testClazz.setIsActive(false);
            UpdateClassRequest activateRequest = UpdateClassRequest.builder()
                    .isActive(true)
                    .build();

            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classRepository.save(any(Clazz.class))).willReturn(testClazz);
            given(classMapper.toResponse(testClazz)).willReturn(testClassResponse);
            given(classMemberRepository.countActiveByClassId(classId)).willReturn(0L);

            // When
            clazzService.updateClass(classId, activateRequest, managerId);

            // Then
            verify(classMapper).updateEntity(testClazz, activateRequest);
            verify(classRepository).save(clazzCaptor.capture());
            assertThat(clazzCaptor.getValue().getUpdatedBy()).isEqualTo(managerId);
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
                given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
                given(userRepository.findById(studentId)).willReturn(Optional.of(testStudent));
                given(classMemberRepository.countActiveByClassId(classId)).willReturn(5L);
                given(classMemberRepository.findByClassIdAndUserId(classId, studentId)).willReturn(Optional.empty());
                given(classMemberRepository.save(any(ClassMember.class))).willReturn(testMember);
                given(classMapper.toMemberResponse(testMember)).willReturn(testMemberResponse);

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
                given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
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
                given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
                given(userRepository.findById(studentId)).willReturn(Optional.of(testStudent));
                given(classMemberRepository.countActiveByClassId(classId)).willReturn(5L);
                given(classMemberRepository.findByClassIdAndUserId(classId, studentId))
                        .willReturn(Optional.of(testMember));

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

                given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
                given(userRepository.findById(studentId)).willReturn(Optional.of(testStudent));
                given(classMemberRepository.countActiveByClassId(classId)).willReturn(5L);
                given(classMemberRepository.findByClassIdAndUserId(classId, studentId))
                        .willReturn(Optional.of(removedMember));
                given(classMemberRepository.save(any(ClassMember.class))).willReturn(removedMember);
                given(classMapper.toMemberResponse(removedMember)).willReturn(testMemberResponse);

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
                given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
                given(userRepository.findById(studentId)).willReturn(Optional.of(testStudent));

                // When/Then
                assertThatThrownBy(() -> clazzService.addMember(classId, request, managerId))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("Không thể thêm học viên vào lớp ");

                verify(classMemberRepository, never()).save(any());
            }

            @Test
            @DisplayName("Should throw when adding user with TEACHER role as member")
            void shouldThrowWhenAddingTeacherAsMember() {
                // Given
                AddMemberRequest request = AddMemberRequest.builder().userId(teacherId).build();
                given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
                given(userRepository.findById(teacherId)).willReturn(Optional.of(testTeacher));

                // When/Then
                assertThatThrownBy(() -> clazzService.addMember(classId, request, managerId))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("HỌC VIÊN");

                verify(classMemberRepository, never()).save(any());
            }

            @Test
            @DisplayName("Should throw when adding non-existent user as member")
            void shouldThrowWhenUserNotFound() {
                // Given
                UUID unknownId = UUID.randomUUID();
                AddMemberRequest request = AddMemberRequest.builder().userId(unknownId).build();
                given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
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
                given(classMemberRepository.findActiveByClassId(classId, pageable)).willReturn(memberPage);
                given(classMapper.toMemberResponse(testMember)).willReturn(testMemberResponse);

                // When
                Page<ClassMemberResponse> result = clazzService.getClassMembers(classId, pageable);

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
                assertThatThrownBy(() -> clazzService.getClassMembers(classId, pageable))
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
            @DisplayName("Should throw when startDate is in the past")
            void shouldThrowWhenStartDateInPast() {
                CreateClassRequest req = CreateClassRequest.builder()
                        .name("Lớp kiểm thử")
                        .teacherId(teacherId)
                        .jlptLevel(JlptLevel.N5)
                        .maxStudents(30)
                        .startDate(java.time.LocalDate.now().minusDays(1))
                        .endDate(java.time.LocalDate.now().plusDays(10))
                        .build();
                // date validation occurs before any user lookup, so no stubbing needed
                assertThatThrownBy(() -> clazzService.createClass(req, managerId))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("Ngày bắt đầu phải ở tương lai");
            }

            @Test
            @DisplayName("Should throw when endDate is before startDate")
            void shouldThrowWhenEndDateBeforeStartDate() {
                CreateClassRequest req = CreateClassRequest.builder()
                        .name("Lớp kiểm thử")
                        .teacherId(teacherId)
                        .jlptLevel(JlptLevel.N5)
                        .maxStudents(30)
                        .startDate(java.time.LocalDate.now().plusDays(5))
                        .endDate(java.time.LocalDate.now().plusDays(2))
                        .build();
                // date validation occurs before any user lookup, so no stubbing needed
                assertThatThrownBy(() -> clazzService.createClass(req, managerId))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("Ngày kết thúc phải sau ngày bắt đầu");
            }

            @Test
            @DisplayName("Should not throw when startDate is today and endDate is after startDate")
            void shouldNotThrowWhenValidDates() {
                CreateClassRequest req = CreateClassRequest.builder()
                        .name("Lớp kiểm thử")
                        .teacherId(teacherId)
                        .jlptLevel(JlptLevel.N5)
                        .maxStudents(30)
                        .startDate(java.time.LocalDate.now().plusDays(1))
                        .endDate(java.time.LocalDate.now().plusDays(10))
                        .build();
                given(userRepository.findById(teacherId)).willReturn(Optional.of(testTeacher));
                given(classMapper.toEntity(any())).willReturn(testClazz);
                given(classRepository.save(any(Clazz.class))).willReturn(testClazz);
                given(classMapper.toResponse(any())).willReturn(testClassResponse);
                assertThatNoException().isThrownBy(() -> clazzService.createClass(req, managerId));
            }
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
            given(classMemberRepository.findActiveByClassId(classId, pageable)).willReturn(memberPage);
            given(classMapper.toMemberResponse(testMember)).willReturn(testMemberResponse);
            given(userRepository.findAllById(List.of(studentId))).willReturn(List.of(testStudent));

            // When
            Page<ClassMemberResponse> result = clazzService.getClassMembers(classId, pageable);

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
            given(classMemberRepository.findActiveByClassId(classId, pageable)).willReturn(memberPage);
            given(classMapper.toMemberResponse(testMember)).willReturn(testMemberResponse);
            given(userRepository.findAllById(List.of(studentId))).willReturn(List.of());

            // When
            Page<ClassMemberResponse> result = clazzService.getClassMembers(classId, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
            ClassMemberResponse response = result.getContent().get(0);
            assertThat(response.getFullName()).isNull();
            assertThat(response.getEmail()).isNull();
        }
    }

    // =====================================================
    // TEACHER-SPECIFIC CLASS QUERIES
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
            Page<ClassSummaryResponse> result = clazzService.getClasses(null, teacherId, true, null, pageable);

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
            Page<ClassSummaryResponse> result = clazzService.getClasses(null, teacherId, true, null, pageable);

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

            // When / Then
            assertThatThrownBy(() -> clazzService.getClassByIdForTeacher(classId, otherTeacherId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Bạn không có quyền xem lớp học này.");
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

        @Test
        @DisplayName("Should return class members when teacher owns the class")
        void shouldReturnMembersWhenTeacherOwns() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<ClassMember> memberPage = new PageImpl<>(List.of(testMember), pageable, 1);
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));
            given(classMemberRepository.findActiveByClassId(classId, pageable)).willReturn(memberPage);
            given(classMapper.toMemberResponse(testMember)).willReturn(testMemberResponse);
            given(userRepository.findAllById(List.of(studentId))).willReturn(List.of(testStudent));

            // When
            Page<ClassMemberResponse> result = clazzService.getClassMembersForTeacher(classId, teacherId, pageable);

            // Then
            assertThat(result.getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should throw ACCESS_DENIED when teacher views other class members")
        void shouldThrowAccessDeniedWhenTeacherViewsOtherClassMembers() {
            // Given
            UUID otherTeacherId = UUID.randomUUID();
            Pageable pageable = PageRequest.of(0, 10);
            given(classRepository.findByIdAndNotDeleted(classId)).willReturn(Optional.of(testClazz));

            // When / Then
            assertThatThrownBy(() -> clazzService.getClassMembersForTeacher(classId, otherTeacherId, pageable))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Bạn không có quyền xem thành viên của lớp học này.");
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
            given(userRepository.findById(teacherId)).willReturn(Optional.empty());

            // When
            ClassResponse result = clazzService.getClassById(classId);

            // Then
            assertThat(result.getTeacherName()).isNull();
            assertThat(result.getTeacherEmail()).isNull();
        }
    }
}
