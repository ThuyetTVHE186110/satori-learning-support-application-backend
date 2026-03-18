package com.fu.conversation.service;

import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.ai.ConversationGenerationService;
import com.fu.conversation.dto.request.CreateUserTopicRequest;
import com.fu.conversation.dto.request.UpdateUserTopicRequest;
import com.fu.conversation.dto.response.UserConversationTopicResponse;
import com.fu.conversation.entity.UserConversationTopic;
import com.fu.conversation.mapper.UserConversationTopicMapper;
import com.fu.conversation.repository.UserConversationTopicRepository;
import com.fu.conversation.service.impl.UserConversationTopicServiceImpl;
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
@DisplayName("UserConversationTopicServiceImpl Tests")
class UserConversationTopicServiceImplTest {

    @Mock private UserConversationTopicRepository userTopicRepository;
    @Mock private UserConversationTopicMapper userTopicMapper;
    @Mock private ConversationGenerationService generationService;
    @Mock private CourseRepository courseRepository;

    @InjectMocks
    private UserConversationTopicServiceImpl userTopicService;

    private UUID userId;
    private UUID topicId;
    private UUID courseId;
    private UserConversationTopic ownedTopic;
    private UserConversationTopicResponse topicResponse;
    private Course course;

    @BeforeEach
    void setUp() {
        userId   = UUID.randomUUID();
        topicId  = UUID.randomUUID();
        courseId = UUID.randomUUID();

        ownedTopic = UserConversationTopic.builder()
                .userId(userId)
                .title("My Shopping Conversation")
                .scenario("Practice buying clothes in a Japanese mall")
                .jlptLevel(Course.JlptLevel.N4)
                .courseId(courseId)
                .aiGeneratedContext("{}")
                .status(Course.ContentStatus.DRAFT)
                .build();
        ReflectionTestUtils.setField(ownedTopic, "id", topicId);

        course = Course.builder().name("N4 Course").build();

        topicResponse = UserConversationTopicResponse.builder()
                .id(topicId)
                .userId(userId)
                .title("My Shopping Conversation")
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("createTopic")
    class CreateTopicTests {

        @Test
        @DisplayName("Should save topic with userId set correctly")
        void shouldCreateTopicWithUserIdSet() {
            // Given
            CreateUserTopicRequest request = CreateUserTopicRequest.builder()
                    .title("My Shopping Conversation")
                    .scenario("Practice buying clothes")
                    .courseId(courseId)
                    .build();
            when(userTopicMapper.toEntity(request)).thenReturn(ownedTopic);
            when(userTopicRepository.save(ownedTopic)).thenReturn(ownedTopic);
            when(userTopicMapper.toResponse(ownedTopic)).thenReturn(topicResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            // When
            UserConversationTopicResponse result =
                    userTopicService.createTopic(request, userId);

            // Then
            assertThat(result).isNotNull();
            assertThat(ownedTopic.getUserId()).isEqualTo(userId);
            verify(userTopicRepository).save(ownedTopic);
        }

        @Test
        @DisplayName("Should populate courseName when courseId is present")
        void shouldPopulateCourseNameWhenCourseExists() {
            CreateUserTopicRequest request = CreateUserTopicRequest.builder()
                    .title("Test").courseId(courseId).build();
            when(userTopicMapper.toEntity(request)).thenReturn(ownedTopic);
            when(userTopicRepository.save(any())).thenReturn(ownedTopic);
            when(userTopicMapper.toResponse(ownedTopic)).thenReturn(topicResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            UserConversationTopicResponse result =
                    userTopicService.createTopic(request, userId);

            assertThat(result.getCourseName()).isEqualTo("N4 Course");
        }

        @Test
        @DisplayName("Should not query courseRepository when courseId is null")
        void shouldSkipCourseQueryWhenCourseIdNull() {
            UserConversationTopic topicNoCourse = UserConversationTopic.builder()
                    .userId(userId).title("No Course").build();
            ReflectionTestUtils.setField(topicNoCourse, "id", topicId);

            CreateUserTopicRequest request = CreateUserTopicRequest.builder()
                    .title("No Course").build();
            when(userTopicMapper.toEntity(request)).thenReturn(topicNoCourse);
            when(userTopicRepository.save(any())).thenReturn(topicNoCourse);
            when(userTopicMapper.toResponse(topicNoCourse)).thenReturn(topicResponse);

            userTopicService.createTopic(request, userId);

            verify(courseRepository, never()).findById(any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("updateTopic")
    class UpdateTopicTests {

        @Test
        @DisplayName("Should update topic fields and save")
        void shouldUpdateTopicSuccessfully() {
            // Given
            UpdateUserTopicRequest request = UpdateUserTopicRequest.builder()
                    .title("Updated Title")
                    .scenario("Updated scenario")
                    .build();
            when(userTopicRepository.findByIdAndUserId(topicId, userId))
                    .thenReturn(Optional.of(ownedTopic));
            when(userTopicRepository.save(ownedTopic)).thenReturn(ownedTopic);
            when(userTopicMapper.toResponse(ownedTopic)).thenReturn(topicResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            // When
            UserConversationTopicResponse result =
                    userTopicService.updateTopic(topicId, request, userId);

            // Then
            assertThat(result).isNotNull();
            verify(userTopicMapper).updateEntity(ownedTopic, request);
            verify(userTopicRepository).save(ownedTopic);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when topic not owned by user")
        void shouldThrowWhenTopicNotOwned() {
            when(userTopicRepository.findByIdAndUserId(topicId, userId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    userTopicService.updateTopic(topicId, new UpdateUserTopicRequest(), userId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("UserConversationTopic");
        }

        @Test
        @DisplayName("Should throw when topic belongs to a different user")
        void shouldThrowWhenTopicBelongsToDifferentUser() {
            UUID anotherUser = UUID.randomUUID();
            when(userTopicRepository.findByIdAndUserId(topicId, anotherUser))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    userTopicService.updateTopic(topicId, new UpdateUserTopicRequest(), anotherUser))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("deleteTopic")
    class DeleteTopicTests {

        @Test
        @DisplayName("Should hard-delete topic (not soft delete)")
        void shouldHardDeleteTopic() {
            // Given
            when(userTopicRepository.findByIdAndUserId(topicId, userId))
                    .thenReturn(Optional.of(ownedTopic));

            // When
            userTopicService.deleteTopic(topicId, userId);

            // Then — must call delete(), NOT save()
            verify(userTopicRepository).delete(ownedTopic);
            verify(userTopicRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when topic not owned by user")
        void shouldThrowWhenTopicNotOwned() {
            when(userTopicRepository.findByIdAndUserId(topicId, userId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> userTopicService.deleteTopic(topicId, userId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Should not delete a topic owned by another user")
        void shouldNotDeleteAnotherUsersTopicl() {
            UUID otherId = UUID.randomUUID();
            when(userTopicRepository.findByIdAndUserId(topicId, otherId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> userTopicService.deleteTopic(topicId, otherId))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(userTopicRepository, never()).delete(any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("generateContext")
    class GenerateContextTests {

        @Test
        @DisplayName("Should generate context and persist aiGeneratedContext")
        void shouldGenerateAndSaveContext() {
            // Given
            String generatedJson = "{\"vocabularyHints\":[\"ショッピング\"]}";
            when(userTopicRepository.findByIdAndUserId(topicId, userId))
                    .thenReturn(Optional.of(ownedTopic));
            when(generationService.generateUserTopicContext(
                    ownedTopic.getScenario(), courseId))
                    .thenReturn(generatedJson);
            when(userTopicRepository.save(ownedTopic)).thenReturn(ownedTopic);
            when(userTopicMapper.toResponse(ownedTopic)).thenReturn(topicResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            // When
            userTopicService.generateContext(topicId, userId);

            // Then
            assertThat(ownedTopic.getAiGeneratedContext()).isEqualTo(generatedJson);
            verify(generationService).generateUserTopicContext(
                    ownedTopic.getScenario(), courseId);
            verify(userTopicRepository).save(ownedTopic);
        }

        @Test
        @DisplayName("Should throw BusinessException when scenario is null")
        void shouldThrowWhenScenarioIsNull() {
            UserConversationTopic topicNoScenario = UserConversationTopic.builder()
                    .userId(userId).title("No Scenario").scenario(null).build();
            ReflectionTestUtils.setField(topicNoScenario, "id", topicId);
            when(userTopicRepository.findByIdAndUserId(topicId, userId))
                    .thenReturn(Optional.of(topicNoScenario));

            assertThatThrownBy(() -> userTopicService.generateContext(topicId, userId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("scenario");
            verify(generationService, never()).generateUserTopicContext(any(), any());
        }

        @Test
        @DisplayName("Should throw BusinessException when scenario is blank")
        void shouldThrowWhenScenarioIsBlank() {
            UserConversationTopic topicBlankScenario = UserConversationTopic.builder()
                    .userId(userId).title("Blank Scenario").scenario("   ").build();
            ReflectionTestUtils.setField(topicBlankScenario, "id", topicId);
            when(userTopicRepository.findByIdAndUserId(topicId, userId))
                    .thenReturn(Optional.of(topicBlankScenario));

            assertThatThrownBy(() -> userTopicService.generateContext(topicId, userId))
                    .isInstanceOf(BusinessException.class);
            verify(generationService, never()).generateUserTopicContext(any(), any());
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when topic not found")
        void shouldThrowWhenTopicNotFound() {
            when(userTopicRepository.findByIdAndUserId(topicId, userId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> userTopicService.generateContext(topicId, userId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Should pass null courseId to generation service when topic has no course")
        void shouldPassNullCourseIdWhenNoCourse() {
            UserConversationTopic topicNoCourse = UserConversationTopic.builder()
                    .userId(userId)
                    .title("No Course")
                    .scenario("Just a scenario")
                    .courseId(null)
                    .aiGeneratedContext("{}")
                    .build();
            ReflectionTestUtils.setField(topicNoCourse, "id", topicId);

            String generatedJson = "{\"vocabularyHints\":[]}";
            when(userTopicRepository.findByIdAndUserId(topicId, userId))
                    .thenReturn(Optional.of(topicNoCourse));
            when(generationService.generateUserTopicContext("Just a scenario", null))
                    .thenReturn(generatedJson);
            when(userTopicRepository.save(topicNoCourse)).thenReturn(topicNoCourse);
            when(userTopicMapper.toResponse(topicNoCourse)).thenReturn(topicResponse);

            userTopicService.generateContext(topicId, userId);

            verify(generationService).generateUserTopicContext("Just a scenario", null);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getMyTopics")
    class GetMyTopicsTests {

        @Test
        @DisplayName("Should return paginated personal topics for user")
        void shouldReturnPaginatedTopicsForUser() {
            Pageable pageable = PageRequest.of(0, 10);
            when(userTopicRepository.findByUserId(userId, pageable))
                    .thenReturn(new PageImpl<>(List.of(ownedTopic)));
            when(userTopicMapper.toResponse(ownedTopic)).thenReturn(topicResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            Page<UserConversationTopicResponse> result =
                    userTopicService.getMyTopics(userId, pageable);

            assertThat(result.getContent()).hasSize(1);
            verify(userTopicRepository).findByUserId(userId, pageable);
        }

        @Test
        @DisplayName("Should return empty page when user has no personal topics")
        void shouldReturnEmptyPageWhenNoTopics() {
            Pageable pageable = PageRequest.of(0, 10);
            when(userTopicRepository.findByUserId(userId, pageable))
                    .thenReturn(Page.empty());

            Page<UserConversationTopicResponse> result =
                    userTopicService.getMyTopics(userId, pageable);

            assertThat(result.getContent()).isEmpty();
        }

        @Test
        @DisplayName("Should only return topics belonging to the requesting user")
        void shouldOnlyReturnTopicsForCorrectUser() {
            UUID otherUser = UUID.randomUUID();
            Pageable pageable = PageRequest.of(0, 10);
            when(userTopicRepository.findByUserId(otherUser, pageable))
                    .thenReturn(Page.empty());

            Page<UserConversationTopicResponse> result =
                    userTopicService.getMyTopics(otherUser, pageable);

            assertThat(result.getContent()).isEmpty();
            verify(userTopicRepository).findByUserId(otherUser, pageable);
            verify(userTopicRepository, never()).findByUserId(eq(userId), any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getMyTopic")
    class GetMyTopicTests {

        @Test
        @DisplayName("Should return topic response when user owns it")
        void shouldReturnTopicWhenOwned() {
            when(userTopicRepository.findByIdAndUserId(topicId, userId))
                    .thenReturn(Optional.of(ownedTopic));
            when(userTopicMapper.toResponse(ownedTopic)).thenReturn(topicResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            UserConversationTopicResponse result =
                    userTopicService.getMyTopic(topicId, userId);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(topicId);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when topic not found or not owned")
        void shouldThrowWhenTopicNotOwnedByUser() {
            when(userTopicRepository.findByIdAndUserId(topicId, userId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> userTopicService.getMyTopic(topicId, userId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("UserConversationTopic");
        }
    }
}
