package com.fu.user.mapper;

import com.fu.user.dto.CreateUserRequest;
import com.fu.user.dto.UserResponse;
import com.fu.user.entity.User;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for UserMapper (MapStruct generated implementation).
 * 
 * Tests the mapping logic between DTOs and Entities.
 * Updated for firstName/lastName structure instead of fullName.
 */
@DisplayName("UserMapper Unit Tests - Updated")
class UserMapperTest {

    private UserMapper userMapper;

    @BeforeEach
    void setUp() {
        userMapper = Mappers.getMapper(UserMapper.class);
    }

    @Nested
    @DisplayName("toEntity - CreateUserRequest to User")
    class ToEntityTests {

        @Test
        @DisplayName("Should map CreateUserRequest with firstName/lastName to User entity")
        void toEntity_validRequest_mapsCorrectly() {
            // Given
            CreateUserRequest request = CreateUserRequest.builder()
                    .email("test@example.com")
                    .firstName("John")
                    .lastName("Doe")
                    .role(UserRole.LEARNER)
                    .phoneNumber("0123456789")
                    .dateOfBirth(LocalDate.of(2000, 5, 15))
                    .build();

            // When
            User user = userMapper.toEntity(request);

            // Then
            assertThat(user).isNotNull();
            assertThat(user.getEmail()).isEqualTo("test@example.com");
            assertThat(user.getFirstName()).isEqualTo("John");
            assertThat(user.getLastName()).isEqualTo("Doe");
            assertThat(user.getPhoneNumber()).isEqualTo("0123456789");
            assertThat(user.getDateOfBirth()).isEqualTo(LocalDate.of(2000, 5, 15));
            
            // Ignored fields should not be set by mapper - service layer handles these
            assertThat(user.getId()).isNull();
            assertThat(user.getPasswordHash()).isNull();
            assertThat(user.getRole()).isNull(); // Service validates permissions and sets role
            assertThat(user.getStatus()).isNull(); // Service sets status
        }

        @Test
        @DisplayName("Should map firstName and lastName directly without extraction")
        void toEntity_firstNameLastName_mapDirectly() {
            // Given
            CreateUserRequest request = CreateUserRequest.builder()
                    .email("test@example.com")
                    .firstName("Jane")
                    .lastName("Smith")
                    .role(UserRole.TEACHER)
                    .build();

            // When
            User user = userMapper.toEntity(request);

            // Then
            assertThat(user.getFirstName()).isEqualTo("Jane");
            assertThat(user.getLastName()).isEqualTo("Smith");
        }

        @Test
        @DisplayName("Should map all optional fields when present")
        void toEntity_allFieldsPresent_mapsAll() {
            // Given
            CreateUserRequest request = CreateUserRequest.builder()
                    .email("test@example.com")
                    .firstName("Test")
                    .lastName("User")
                    .phoneNumber("0987654321")
                    .dateOfBirth(LocalDate.of(1995, 3, 20))
                    .role(UserRole.TEACHER)
                    .build();

            // When
            User user = userMapper.toEntity(request);

            // Then
            assertThat(user.getEmail()).isEqualTo("test@example.com");
            assertThat(user.getFirstName()).isEqualTo("Test");
            assertThat(user.getLastName()).isEqualTo("User");
            assertThat(user.getPhoneNumber()).isEqualTo("0987654321");
            assertThat(user.getDateOfBirth()).isEqualTo(LocalDate.of(1995, 3, 20));
        }
    }

    @Nested
    @DisplayName("toResponse - User to UserResponse")
    class ToResponseTests {

        @Test
        @DisplayName("Should map User entity to UserResponse")
        void toResponse_validUser_mapsCorrectly() {
            // Given
            UUID userId = UUID.randomUUID();
            User user = User.builder()
                    .email("test@example.com")
                    .firstName("John")
                    .lastName("Doe")
                    .displayName("Johnny D")
                    .role(UserRole.TEACHER)
                    .status(UserStatus.ACTIVE)
                    .phoneNumber("0123456789")
                    .dateOfBirth(LocalDate.of(1990, 1, 1))
                    .avatarUrl("https://example.com/avatar.jpg")
                    .build();
            ReflectionTestUtils.setField(user, "id", userId);
            ReflectionTestUtils.setField(user, "createdAt", LocalDateTime.of(2026, 1, 1, 10, 0));
            ReflectionTestUtils.setField(user, "updatedAt", LocalDateTime.of(2026, 1, 15, 14, 30));

            // When
            UserResponse response = userMapper.toResponse(user);

            // Then
            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo(userId);
            assertThat(response.getEmail()).isEqualTo("test@example.com");
            assertThat(response.getFirstName()).isEqualTo("John");
            assertThat(response.getLastName()).isEqualTo("Doe");
            assertThat(response.getDisplayName()).isEqualTo("Johnny D");
            assertThat(response.getRole()).isEqualTo(UserRole.TEACHER);
            assertThat(response.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(response.getPhoneNumber()).isEqualTo("0123456789");
            assertThat(response.getAvatarUrl()).isEqualTo("https://example.com/avatar.jpg");
            assertThat(response.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 10, 0));
            assertThat(response.getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 15, 14, 30));
        }

        @Test
        @DisplayName("Should generate fullName from firstName and lastName using User.getFullName()")
        void toResponse_user_generatesFullName() {
            // Given
            User user = User.builder()
                    .email("test@example.com")
                    .firstName("John")
                    .lastName("Doe")
                    .role(UserRole.LEARNER)
                    .status(UserStatus.ACTIVE)
                    .build();
            ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

            // When
            UserResponse response = userMapper.toResponse(user);

            // Then
            assertThat(response.getFullName()).isEqualTo("John Doe");
            assertThat(response.getFirstName()).isEqualTo("John");
            assertThat(response.getLastName()).isEqualTo("Doe");
        }

        @Test
        @DisplayName("Should handle user with only firstName")
        void toResponse_onlyFirstName_generatesFullNameCorrectly() {
            // Given
            User user = User.builder()
                    .email("test@example.com")
                    .firstName("John")
                    .lastName(null)
                    .role(UserRole.LEARNER)
                    .status(UserStatus.ACTIVE)
                    .build();
            ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

            // When
            UserResponse response = userMapper.toResponse(user);

            // Then
            assertThat(response.getFullName()).isEqualTo("John");
            assertThat(response.getFirstName()).isEqualTo("John");
            assertThat(response.getLastName()).isNull();
        }

        @Test
        @DisplayName("Should map learner with displayName")
        void toResponse_learnerWithDisplayName_mapsDisplayName() {
            // Given
            User user = User.builder()
                    .email("learner@example.com")
                    .firstName("Student")
                    .lastName("One")
                    .displayName("Stu")
                    .role(UserRole.LEARNER)
                    .status(UserStatus.ACTIVE)
                    .build();
            ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

            // When
            UserResponse response = userMapper.toResponse(user);

            // Then
            assertThat(response.getDisplayName()).isEqualTo("Stu");
            assertThat(response.getFullName()).isEqualTo("Student One");
        }
    }

    @Nested
    @DisplayName("toResponseList - List<User> to List<UserResponse>")
    class ToResponseListTests {

        @Test
        @DisplayName("Should map list of users to list of responses")
        void toResponseList_multipleUsers_mapsAll() {
            // Given
            User user1 = createTestUser("user1@example.com", "John", "Doe", UserRole.LEARNER);
            User user2 = createTestUser("user2@example.com", "Jane", "Smith", UserRole.TEACHER);
            User user3 = createTestUser("user3@example.com", "Bob", "Brown", UserRole.MANAGER);
            
            List<User> users = List.of(user1, user2, user3);

            // When
            List<UserResponse> responses = userMapper.toResponseList(users);

            // Then
            assertThat(responses).hasSize(3);
            assertThat(responses.get(0).getEmail()).isEqualTo("user1@example.com");
            assertThat(responses.get(0).getFullName()).isEqualTo("John Doe");
            assertThat(responses.get(1).getEmail()).isEqualTo("user2@example.com");
            assertThat(responses.get(1).getFullName()).isEqualTo("Jane Smith");
            assertThat(responses.get(2).getEmail()).isEqualTo("user3@example.com");
            assertThat(responses.get(2).getFullName()).isEqualTo("Bob Brown");
        }

        @Test
        @DisplayName("Should return empty list for empty input")
        void toResponseList_emptyList_returnsEmptyList() {
            // When
            List<UserResponse> responses = userMapper.toResponseList(List.of());

            // Then
            assertThat(responses).isEmpty();
        }

        @Test
        @DisplayName("Should handle mixed roles in list")
        void toResponseList_mixedRoles_mapsCorrectly() {
            // Given
            User learner = createTestUser("learner@example.com", "Learn", "Er", UserRole.LEARNER);
            learner.setDisplayName("Leo");
            
            User teacher = createTestUser("teacher@example.com", "Teach", "Er", UserRole.TEACHER);
            
            List<User> users = List.of(learner, teacher);

            // When
            List<UserResponse> responses = userMapper.toResponseList(users);

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getRole()).isEqualTo(UserRole.LEARNER);
            assertThat(responses.get(0).getDisplayName()).isEqualTo("Leo");
            assertThat(responses.get(1).getRole()).isEqualTo(UserRole.TEACHER);
        }

        private User createTestUser(String email, String firstName, String lastName, UserRole role) {
            User user = User.builder()
                    .email(email)
                    .firstName(firstName)
                    .lastName(lastName)
                    .role(role)
                    .status(UserStatus.ACTIVE)
                    .build();
            ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
            return user;
        }
    }

    @Nested
    @DisplayName("Edge Cases and Null Handling")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle minimal CreateUserRequest")
        void toEntity_minimalRequest_mapsRequiredFieldsOnly() {
            // Given
            CreateUserRequest request = CreateUserRequest.builder()
                    .email("minimal@example.com")
                    .firstName("Min")
                    .lastName("User")
                    .role(UserRole.LEARNER)
                    .build();

            // When
            User user = userMapper.toEntity(request);

            // Then
            assertThat(user.getEmail()).isEqualTo("minimal@example.com");
            assertThat(user.getFirstName()).isEqualTo("Min");
            assertThat(user.getLastName()).isEqualTo("User");
            assertThat(user.getPhoneNumber()).isNull();
            assertThat(user.getDateOfBirth()).isNull();
        }

        @Test
        @DisplayName("Should handle User with minimal fields in toResponse")
        void toResponse_minimalUser_mapsRequiredFieldsOnly() {
            // Given
            User user = User.builder()
                    .email("minimal@example.com")
                    .firstName("Min")
                    .lastName("User")
                    .role(UserRole.LEARNER)
                    .status(UserStatus.PENDING_ACTIVATION)
                    .build();
            ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

            // When
            UserResponse response = userMapper.toResponse(user);

            // Then
            assertThat(response.getEmail()).isEqualTo("minimal@example.com");
            assertThat(response.getFullName()).isEqualTo("Min User");
            assertThat(response.getPhoneNumber()).isNull();
            assertThat(response.getAvatarUrl()).isNull();
        }
    }
}
