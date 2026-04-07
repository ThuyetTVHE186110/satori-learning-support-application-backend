package com.fu.conversation.service;

import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.ai.ConversationGenerationService;
import com.fu.conversation.dto.request.CreateUserConversationRequest;
import com.fu.conversation.dto.request.UpdateUserConversationRequest;
import com.fu.conversation.dto.response.UserConversationResponse;
import com.fu.conversation.entity.UserConversation;
import com.fu.conversation.mapper.UserConversationMapper;
import com.fu.conversation.repository.UserConversationRepository;
import com.fu.conversation.service.impl.UserConversationServiceImpl;
import com.fu.course.entity.Course;
import com.fu.course.repository.CourseRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserConversationServiceImpl Tests")
class UserConversationServiceImplTest {

    @Mock private UserConversationRepository userConversationRepository;
    @Mock private UserConversationMapper userConversationMapper;
    @Mock private ConversationGenerationService generationService;
    @Mock private CourseRepository courseRepository;

    @InjectMocks
    private UserConversationServiceImpl userConversationService;

    private UUID userId;
    private UUID conversationId;
    private UUID courseId;
    private UserConversation ownedConversation;
    private UserConversationResponse conversationResponse;
    private Course course;

    @BeforeEach
    void setUp() {
        userId         = UUID.randomUUID();
        conversationId = UUID.randomUUID();
        courseId       = UUID.randomUUID();

        ownedConversation = UserConversation.builder()
                .userId(userId)
                .title("My Shopping Conversation")
                .scenario("Practice buying clothes in Harajuku")
                .jlptLevel(Course.JlptLevel.N4)
                .courseId(courseId)
                .aiGeneratedContext("{}")
                .status(Course.ContentStatus.DRAFT)
                .build();
        ReflectionTestUtils.setField(ownedConversation, "id", conversationId);

        course = Course.builder().name("N4 Course").build();

        conversationResponse = UserConversationResponse.builder()
                .id(conversationId)
                .userId(userId)
                .title("My Shopping Conversation")
                .status(Course.ContentStatus.DRAFT)
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("createConversation")
    class CreateConversationTests {

        @Test
        @DisplayName("Should save conversation with userId set correctly")
        void shouldCreateConversationWithUserIdSet() {
            CreateUserConversationRequest request = CreateUserConversationRequest.builder()
                    .title("My Shopping Conversation")
                    .scenario("Practice buying clothes")
                    .courseId(courseId)
                    .build();
            when(userConversationMapper.toEntity(request)).thenReturn(ownedConversation);
            when(userConversationRepository.save(ownedConversation)).thenReturn(ownedConversation);
            when(userConversationMapper.toResponse(ownedConversation)).thenReturn(conversationResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            UserConversationResponse result = userConversationService.createConversation(request, userId);

            assertThat(result).isNotNull();
            assertThat(ownedConversation.getUserId()).isEqualTo(userId);
            verify(userConversationRepository).save(ownedConversation);
        }

        @Test
        @DisplayName("Should populate courseName when courseId is present")
        void shouldPopulateCourseNameWhenCourseExists() {
            CreateUserConversationRequest request = CreateUserConversationRequest.builder()
                    .title("Test").courseId(courseId).build();
            when(userConversationMapper.toEntity(request)).thenReturn(ownedConversation);
            when(userConversationRepository.save(any())).thenReturn(ownedConversation);
            UserConversationResponse responseWithCourse = UserConversationResponse.builder()
                    .id(conversationId).userId(userId).title("Test").courseName("N4 Course").build();
            when(userConversationMapper.toResponse(ownedConversation)).thenReturn(responseWithCourse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            UserConversationResponse result = userConversationService.createConversation(request, userId);

            assertThat(result.getCourseName()).isEqualTo("N4 Course");
        }

        @Test
        @DisplayName("Should not query courseRepository when courseId is null")
        void shouldSkipCourseQueryWhenCourseIdNull() {
            UserConversation noCourseConversation = UserConversation.builder()
                    .userId(userId).title("No Course").build();
            ReflectionTestUtils.setField(noCourseConversation, "id", conversationId);

            CreateUserConversationRequest request = CreateUserConversationRequest.builder()
                    .title("No Course").build();
            when(userConversationMapper.toEntity(request)).thenReturn(noCourseConversation);
            when(userConversationRepository.save(any())).thenReturn(noCourseConversation);
            when(userConversationMapper.toResponse(noCourseConversation)).thenReturn(conversationResponse);

            userConversationService.createConversation(request, userId);

            verify(courseRepository, never()).findById(any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("updateConversation")
    class UpdateConversationTests {

        @Test
        @DisplayName("Should update conversation and save")
        void shouldUpdateConversationSuccessfully() {
            UpdateUserConversationRequest request = UpdateUserConversationRequest.builder()
                    .title("Updated Title").scenario("New scenario").build();
            when(userConversationRepository.findByIdAndUserId(conversationId, userId))
                    .thenReturn(Optional.of(ownedConversation));
            when(userConversationRepository.save(ownedConversation)).thenReturn(ownedConversation);
            when(userConversationMapper.toResponse(ownedConversation)).thenReturn(conversationResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            UserConversationResponse result =
                    userConversationService.updateConversation(conversationId, request, userId);

            assertThat(result).isNotNull();
            verify(userConversationMapper).updateEntity(ownedConversation, request);
            verify(userConversationRepository).save(ownedConversation);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when conversation not owned by user")
        void shouldThrowWhenConversationNotOwned() {
            when(userConversationRepository.findByIdAndUserId(conversationId, userId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    userConversationService.updateConversation(
                            conversationId, new UpdateUserConversationRequest(), userId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("UserConversation");
        }

        @Test
        @DisplayName("Should throw when conversation belongs to a different user")
        void shouldThrowWhenConversationBelongsToDifferentUser() {
            UUID anotherUser = UUID.randomUUID();
            when(userConversationRepository.findByIdAndUserId(conversationId, anotherUser))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    userConversationService.updateConversation(
                            conversationId, new UpdateUserConversationRequest(), anotherUser))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("deleteConversation")
    class DeleteConversationTests {

        @Test
        @DisplayName("Should hard-delete conversation (not soft delete)")
        void shouldHardDeleteConversation() {
            when(userConversationRepository.findByIdAndUserId(conversationId, userId))
                    .thenReturn(Optional.of(ownedConversation));

            userConversationService.deleteConversation(conversationId, userId);

            verify(userConversationRepository).delete(ownedConversation);
            verify(userConversationRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when conversation not owned by user")
        void shouldThrowWhenConversationNotOwned() {
            when(userConversationRepository.findByIdAndUserId(conversationId, userId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    userConversationService.deleteConversation(conversationId, userId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Should not delete a conversation owned by another user")
        void shouldNotDeleteAnotherUsersConversation() {
            UUID otherId = UUID.randomUUID();
            when(userConversationRepository.findByIdAndUserId(conversationId, otherId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    userConversationService.deleteConversation(conversationId, otherId))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(userConversationRepository, never()).delete(any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("generateContext")
    class GenerateContextTests {

        @Test
        @DisplayName("Should generate context and persist aiGeneratedContext")
        void shouldGenerateAndSaveContext() {
            String generatedJson = "{\"vocabularyHints\":[\"ショッピング\"]}";
            when(userConversationRepository.findByIdAndUserId(conversationId, userId))
                    .thenReturn(Optional.of(ownedConversation));
            when(generationService.generateUserConversationContext(
                    ownedConversation.getScenario(), courseId))
                    .thenReturn(generatedJson);
            when(userConversationRepository.save(ownedConversation)).thenReturn(ownedConversation);
            when(userConversationMapper.toResponse(ownedConversation)).thenReturn(conversationResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            userConversationService.generateContext(conversationId, userId);

            assertThat(ownedConversation.getAiGeneratedContext()).isEqualTo(generatedJson);
            verify(generationService).generateUserConversationContext(
                    ownedConversation.getScenario(), courseId);
            verify(userConversationRepository).save(ownedConversation);
        }

        @Test
        @DisplayName("Should throw BusinessException when scenario is null")
        void shouldThrowWhenScenarioIsNull() {
            UserConversation noScenario = UserConversation.builder()
                    .userId(userId).title("No Scenario").scenario(null).build();
            ReflectionTestUtils.setField(noScenario, "id", conversationId);
            when(userConversationRepository.findByIdAndUserId(conversationId, userId))
                    .thenReturn(Optional.of(noScenario));

            assertThatThrownBy(() ->
                    userConversationService.generateContext(conversationId, userId))
                    .isInstanceOf(BusinessException.class);
            verify(generationService, never()).generateUserConversationContext(any(), any());
        }

        @Test
        @DisplayName("Should throw BusinessException when scenario is blank")
        void shouldThrowWhenScenarioIsBlank() {
            UserConversation blankScenario = UserConversation.builder()
                    .userId(userId).title("Blank").scenario("   ").build();
            ReflectionTestUtils.setField(blankScenario, "id", conversationId);
            when(userConversationRepository.findByIdAndUserId(conversationId, userId))
                    .thenReturn(Optional.of(blankScenario));

            assertThatThrownBy(() ->
                    userConversationService.generateContext(conversationId, userId))
                    .isInstanceOf(BusinessException.class);
            verify(generationService, never()).generateUserConversationContext(any(), any());
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when conversation not found")
        void shouldThrowWhenConversationNotFound() {
            when(userConversationRepository.findByIdAndUserId(conversationId, userId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    userConversationService.generateContext(conversationId, userId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Should pass null courseId to generation service when conversation has no course")
        void shouldPassNullCourseIdWhenNoCourse() {
            UserConversation noCourse = UserConversation.builder()
                    .userId(userId).title("No Course")
                    .scenario("Just a scenario").courseId(null).aiGeneratedContext("{}").build();
            ReflectionTestUtils.setField(noCourse, "id", conversationId);

            String generatedJson = "{\"vocabularyHints\":[]}";
            when(userConversationRepository.findByIdAndUserId(conversationId, userId))
                    .thenReturn(Optional.of(noCourse));
            when(generationService.generateUserConversationContext("Just a scenario", null))
                    .thenReturn(generatedJson);
            when(userConversationRepository.save(noCourse)).thenReturn(noCourse);
            when(userConversationMapper.toResponse(noCourse)).thenReturn(conversationResponse);

            userConversationService.generateContext(conversationId, userId);

            verify(generationService).generateUserConversationContext("Just a scenario", null);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getMyConversations")
    class GetMyConversationsTests {

        @Test
        @DisplayName("Should return paginated personal conversations for user")
        void shouldReturnPaginatedConversationsForUser() {
            Pageable pageable = PageRequest.of(0, 10);
            when(userConversationRepository.findByUserId(userId, pageable))
                    .thenReturn(new PageImpl<>(List.of(ownedConversation)));
            when(userConversationMapper.toResponse(ownedConversation)).thenReturn(conversationResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            Page<UserConversationResponse> result =
                    userConversationService.getMyConversations(userId, pageable);

            assertThat(result.getContent()).hasSize(1);
            verify(userConversationRepository).findByUserId(userId, pageable);
        }

        @Test
        @DisplayName("Should return empty page when user has no personal conversations")
        void shouldReturnEmptyPageWhenNoConversations() {
            Pageable pageable = PageRequest.of(0, 10);
            when(userConversationRepository.findByUserId(userId, pageable))
                    .thenReturn(Page.empty());

            Page<UserConversationResponse> result =
                    userConversationService.getMyConversations(userId, pageable);

            assertThat(result.getContent()).isEmpty();
        }

        @Test
        @DisplayName("Should only return conversations belonging to the requesting user")
        void shouldOnlyReturnConversationsForCorrectUser() {
            UUID otherUser = UUID.randomUUID();
            Pageable pageable = PageRequest.of(0, 10);
            when(userConversationRepository.findByUserId(otherUser, pageable))
                    .thenReturn(Page.empty());

            Page<UserConversationResponse> result =
                    userConversationService.getMyConversations(otherUser, pageable);

            assertThat(result.getContent()).isEmpty();
            verify(userConversationRepository).findByUserId(otherUser, pageable);
            verify(userConversationRepository, never()).findByUserId(eq(userId), any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getMyConversation")
    class GetMyConversationTests {

        @Test
        @DisplayName("Should return conversation response when user owns it")
        void shouldReturnConversationWhenOwned() {
            when(userConversationRepository.findByIdAndUserId(conversationId, userId))
                    .thenReturn(Optional.of(ownedConversation));
            when(userConversationMapper.toResponse(ownedConversation)).thenReturn(conversationResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            UserConversationResponse result =
                    userConversationService.getMyConversation(conversationId, userId);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(conversationId);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when conversation not found or not owned")
        void shouldThrowWhenConversationNotOwnedByUser() {
            when(userConversationRepository.findByIdAndUserId(conversationId, userId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    userConversationService.getMyConversation(conversationId, userId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("UserConversation");
        }
    }
}
