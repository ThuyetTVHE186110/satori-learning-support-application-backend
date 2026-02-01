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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for UserMapper (MapStruct generated implementation).
 * 
 * Tests the mapping logic between DTOs and Entities.
 * Uses Mappers.getMapper() for standalone testing without Spring context.
 */
@DisplayName("UserMapper Unit Tests")
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
        @DisplayName("Should map CreateUserRequest to User entity")
        void toEntity_validRequest_mapsCorrectly() {
            // Given
            CreateUserRequest request = new CreateUserRequest();
            request.setEmail("test@example.com");
            request.setFullName("John Doe");
            request.setRole(UserRole.LEARNER);
            request.setPhoneNumber("0123456789");
            request.setDateOfBirth(LocalDate.of(2000, 5, 15));

            // When
            User user = userMapper.toEntity(request);

            // Then
            assertThat(user).isNotNull();
            assertThat(user.getEmail()).isEqualTo("test@example.com");
            assertThat(user.getDisplayName()).isEqualTo("John Doe");
            assertThat(user.getPhoneNumber()).isEqualTo("0123456789");
            assertThat(user.getDateOfBirth()).isEqualTo(LocalDate.of(2000, 5, 15));
            
            // ID should not be set (ignored in mapping)
            assertThat(user.getId()).isNull();
            // passwordHash should not be set (ignored in mapping)
            assertThat(user.getPasswordHash()).isNull();
        }

        @Test
        @DisplayName("Should extract firstName from fullName")
        void toEntity_fullName_extractsFirstName() {
            // Given
            CreateUserRequest request = new CreateUserRequest();
            request.setEmail("test@example.com");
            request.setFullName("John Michael Doe");
            request.setRole(UserRole.LEARNER);

            // When
            User user = userMapper.toEntity(request);

            // Then
            assertThat(user.getFirstName()).isEqualTo("John");
        }

        @Test
        @DisplayName("Should extract lastName from fullName")
        void toEntity_fullName_extractsLastName() {
            // Given
            CreateUserRequest request = new CreateUserRequest();
            request.setEmail("test@example.com");
            request.setFullName("John Michael Doe");
            request.setRole(UserRole.LEARNER);

            // When
            User user = userMapper.toEntity(request);

            // Then
            assertThat(user.getLastName()).isEqualTo("Michael Doe");
        }

        @Test
        @DisplayName("Should handle single word name (no lastName)")
        void toEntity_singleWordName_handlesCorrectly() {
            // Given
            CreateUserRequest request = new CreateUserRequest();
            request.setEmail("test@example.com");
            request.setFullName("John");
            request.setRole(UserRole.LEARNER);

            // When
            User user = userMapper.toEntity(request);

            // Then
            assertThat(user.getFirstName()).isEqualTo("John");
            assertThat(user.getLastName()).isNull();
        }

        @Test
        @DisplayName("Should handle null fullName")
        void toEntity_nullFullName_handlesGracefully() {
            // Given
            CreateUserRequest request = new CreateUserRequest();
            request.setEmail("test@example.com");
            request.setFullName(null);
            request.setRole(UserRole.LEARNER);

            // When
            User user = userMapper.toEntity(request);

            // Then
            assertThat(user.getFirstName()).isNull();
            assertThat(user.getLastName()).isNull();
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
            User user = new User();
            user.setId(userId);
            user.setEmail("test@example.com");
            user.setFirstName("John");
            user.setLastName("Doe");
            user.setDisplayName("Johnny D");
            user.setRole(UserRole.TEACHER);
            user.setStatus(UserStatus.ACTIVE);
            user.setPhoneNumber("0123456789");
            user.setDateOfBirth(LocalDate.of(1990, 1, 1));
            user.setAvatarUrl("https://example.com/avatar.jpg");
            user.setCreatedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
            user.setUpdatedAt(LocalDateTime.of(2026, 1, 15, 14, 30));

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
        @DisplayName("Should generate fullName from firstName and lastName")
        void toResponse_user_generatesFullName() {
            // Given
            User user = new User();
            user.setId(UUID.randomUUID());
            user.setFirstName("John");
            user.setLastName("Doe");
            user.setEmail("test@example.com");

            // When
            UserResponse response = userMapper.toResponse(user);

            // Then
            assertThat(response.getFullName()).isEqualTo("John Doe");
        }
    }

    @Nested
    @DisplayName("toResponseList - List<User> to List<UserResponse>")
    class ToResponseListTests {

        @Test
        @DisplayName("Should map list of users to list of responses")
        void toResponseList_multipleUsers_mapsAll() {
            // Given
            User user1 = createTestUser("user1@example.com", "John", "Doe");
            User user2 = createTestUser("user2@example.com", "Jane", "Smith");
            User user3 = createTestUser("user3@example.com", "Bob", "Brown");
            
            List<User> users = List.of(user1, user2, user3);

            // When
            List<UserResponse> responses = userMapper.toResponseList(users);

            // Then
            assertThat(responses).hasSize(3);
            assertThat(responses.get(0).getEmail()).isEqualTo("user1@example.com");
            assertThat(responses.get(1).getEmail()).isEqualTo("user2@example.com");
            assertThat(responses.get(2).getEmail()).isEqualTo("user3@example.com");
        }

        @Test
        @DisplayName("Should return empty list for empty input")
        void toResponseList_emptyList_returnsEmptyList() {
            // When
            List<UserResponse> responses = userMapper.toResponseList(List.of());

            // Then
            assertThat(responses).isEmpty();
        }

        private User createTestUser(String email, String firstName, String lastName) {
            User user = new User();
            user.setId(UUID.randomUUID());
            user.setEmail(email);
            user.setFirstName(firstName);
            user.setLastName(lastName);
            return user;
        }
    }

    @Nested
    @DisplayName("Helper Methods - extractFirstName, extractLastName")
    class HelperMethodTests {

        @Test
        @DisplayName("extractFirstName should handle whitespace correctly")
        void extractFirstName_withWhitespace_trimsCorrectly() {
            // When
            String firstName = userMapper.extractFirstName("  John   Doe  ");

            // Then
            assertThat(firstName).isEqualTo("John");
        }

        @Test
        @DisplayName("extractLastName should handle whitespace correctly")
        void extractLastName_withWhitespace_trimsCorrectly() {
            // When
            String lastName = userMapper.extractLastName("  John   Doe  ");

            // Then
            assertThat(lastName).isEqualTo("Doe");
        }

        @Test
        @DisplayName("extractFirstName should return null for blank string")
        void extractFirstName_blankString_returnsNull() {
            // When
            String firstName = userMapper.extractFirstName("   ");

            // Then
            assertThat(firstName).isNull();
        }

        @Test
        @DisplayName("extractLastName should return null for single word")
        void extractLastName_singleWord_returnsNull() {
            // When
            String lastName = userMapper.extractLastName("John");

            // Then
            assertThat(lastName).isNull();
        }
    }
}
