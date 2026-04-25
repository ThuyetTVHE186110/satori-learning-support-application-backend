package com.fu.conversation.service;

import com.fu.clazz.entity.Clazz;
import com.fu.clazz.repository.ClassMemberRepository;
import com.fu.clazz.repository.ClassRepository;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.ai.ConversationGenerationService;
import com.fu.conversation.dto.response.ConversationSummaryResponse;
import com.fu.conversation.entity.Conversation;
import com.fu.conversation.mapper.ConversationMapper;
import com.fu.conversation.mapper.ConversationMissionMapper;
import com.fu.conversation.repository.ConversationMissionRepository;
import com.fu.conversation.repository.ConversationRepository;
import com.fu.conversation.repository.ConversationTopicRepository;
import com.fu.conversation.service.impl.ConversationServiceImpl;
import com.fu.course.entity.Course;
import com.fu.course.exception.CourseNotFoundException;
import com.fu.course.repository.CourseRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationServiceImpl — getPublishedConversationsByTopicAndUserJlptLevel JLPT filtering tests")
class ConversationServiceImplJlptFilteringTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private ConversationMapper conversationMapper;
    @Mock private ConversationTopicRepository topicRepository;
    @Mock private CourseRepository courseRepository;
    @Mock private ConversationMissionRepository missionRepository;
    @Mock private ConversationMissionMapper missionMapper;
    @Mock private ConversationGenerationService generationService;
    @Mock private ClassMemberRepository classMemberRepository;
    @Mock private ClassRepository classRepository;

    @InjectMocks
    private ConversationServiceImpl conversationService;

    private UUID userId;
    private UUID topicId;
    private UUID classId;
    private UUID courseId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        topicId = UUID.randomUUID();
        classId = UUID.randomUUID();
        courseId = UUID.randomUUID();
    }

    @Nested
    @DisplayName("With valid enrollment")
    class WithValidEnrollment {

        @Test
        @DisplayName("Should return conversations matching enrolled course's JLPT level")
        void withValidEnrollment_ReturnFilteredConversations() {
            // Arrange
            UUID conversationId1 = UUID.randomUUID();
            UUID conversationId2 = UUID.randomUUID();

            Conversation conv1 = Conversation.builder()
                    .id(conversationId1)
                    .topicId(topicId)
                    .title("Conversation 1")
                    .jlptLevel(Course.JlptLevel.N4)
                    .status(Course.ContentStatus.PUBLISHED)
                    .build();

            Conversation conv2 = Conversation.builder()
                    .id(conversationId2)
                    .topicId(topicId)
                    .title("Conversation 2")
                    .jlptLevel(Course.JlptLevel.N4)
                    .status(Course.ContentStatus.PUBLISHED)
                    .build();

            ConversationSummaryResponse resp1 = ConversationSummaryResponse.builder()
                    .id(conversationId1)
                    .title("Conversation 1")
                    .jlptLevel(Course.JlptLevel.N4)
                    .build();

            ConversationSummaryResponse resp2 = ConversationSummaryResponse.builder()
                    .id(conversationId2)
                    .title("Conversation 2")
                    .jlptLevel(Course.JlptLevel.N4)
                    .build();

            Course course = Course.builder()
                    .id(courseId)
                    .jlptLevel(Course.JlptLevel.N4)
                    .build();

            Clazz clazz = Clazz.builder()
                    .id(classId)
                    .courseId(courseId)
                    .build();

            // Mock class enrollment lookup
            when(classMemberRepository.findActiveClassIdsByUserId(userId))
                    .thenReturn(List.of(classId));

            // Mock class and course lookup
            when(classRepository.findByIdAndNotDeleted(classId))
                    .thenReturn(Optional.of(clazz));
            when(courseRepository.findByIdAndNotDeleted(courseId))
                    .thenReturn(Optional.of(course));

            // Mock conversation query with JLPT level filter
            when(conversationRepository.findByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNullOrderByOrderIndexAsc(
                    topicId, "N4", "PUBLISHED"))
                    .thenReturn(List.of(conv1, conv2));

            // Mock mapper
            when(conversationMapper.toSummaryResponse(conv1)).thenReturn(resp1);
            when(conversationMapper.toSummaryResponse(conv2)).thenReturn(resp2);

            // Mock practice stats (empty for simplicity)
            when(conversationRepository.findPracticeStatsByUserIdAndConversationIds(userId, List.of(conversationId1, conversationId2)))
                    .thenReturn(Collections.emptyList());

            // Act
            List<ConversationSummaryResponse> result = conversationService
                    .getPublishedConversationsByTopicAndUserJlptLevel(topicId, userId);

            // Assert
            assertThat(result).hasSize(2);
            assertThat(result).extracting("id").containsExactly(conversationId1, conversationId2);
            
            // Verify the repository was called with correct JLPT level
            verify(conversationRepository).findByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNullOrderByOrderIndexAsc(
                    topicId, "N4", "PUBLISHED");
        }

        @Test
        @DisplayName("Should enrich conversations with practice stats")
        void withValidEnrollment_EnrichWithPracticeStats() {
            // Arrange
            UUID conversationId = UUID.randomUUID();
            Conversation conv = Conversation.builder()
                    .id(conversationId)
                    .topicId(topicId)
                    .title("Test Conversation")
                    .jlptLevel(Course.JlptLevel.N3)
                    .status(Course.ContentStatus.PUBLISHED)
                    .build();

            Course course = Course.builder()
                    .id(courseId)
                    .jlptLevel(Course.JlptLevel.N3)
                    .build();

            Clazz clazz = Clazz.builder()
                    .id(classId)
                    .courseId(courseId)
                    .build();

            ConversationSummaryResponse resp = ConversationSummaryResponse.builder()
                    .id(conversationId)
                    .title("Test Conversation")
                    .jlptLevel(Course.JlptLevel.N3)
                    .build();

            ConversationRepository.PracticeStatsProjection stats = mock(ConversationRepository.PracticeStatsProjection.class);
            when(stats.getConversationId()).thenReturn(conversationId);
            when(stats.getPracticeCount()).thenReturn(3L);
            when(stats.getLastPracticedAt()).thenReturn(LocalDateTime.now());
            when(stats.getIsCompleted()).thenReturn(true);

            // Mock lookups
            when(classMemberRepository.findActiveClassIdsByUserId(userId))
                    .thenReturn(List.of(classId));
            when(classRepository.findByIdAndNotDeleted(classId))
                    .thenReturn(Optional.of(clazz));
            when(courseRepository.findByIdAndNotDeleted(courseId))
                    .thenReturn(Optional.of(course));

            // Mock conversation query
            when(conversationRepository.findByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNullOrderByOrderIndexAsc(
                    topicId, "N3", "PUBLISHED"))
                    .thenReturn(List.of(conv));

            // Mock mapper
            when(conversationMapper.toSummaryResponse(conv)).thenReturn(resp);

            // Mock practice stats lookup
            when(conversationRepository.findPracticeStatsByUserIdAndConversationIds(userId, List.of(conversationId)))
                    .thenReturn(List.of(stats));

            // Act
            List<ConversationSummaryResponse> result = conversationService
                    .getPublishedConversationsByTopicAndUserJlptLevel(topicId, userId);

            // Assert
            assertThat(result).hasSize(1);
            ConversationSummaryResponse returned = result.get(0);
            assertThat(returned.isPracticed()).isTrue();
            assertThat(returned.getPracticeCount()).isEqualTo(3);
            assertThat(returned.getPracticeStatus())
                    .isEqualTo(ConversationSummaryResponse.PracticeStatus.COMPLETED);
        }

        @Test
        @DisplayName("Should filter conversations by exact JLPT level (not by <=)")
        void withValidEnrollment_ExactLevelMatchOnly() {
            // Arrange: User enrolled in N4 course, we should NOT get N3 conversations
            UUID n4ConvId = UUID.randomUUID();
            UUID n3ConvId = UUID.randomUUID();

            Conversation n4Conv = Conversation.builder()
                    .id(n4ConvId)
                    .topicId(topicId)
                    .title("N4 Conversation")
                    .jlptLevel(Course.JlptLevel.N4)
                    .status(Course.ContentStatus.PUBLISHED)
                    .build();

            // N3 conversation should NOT be returned
            Conversation n3Conv = Conversation.builder()
                    .id(n3ConvId)
                    .topicId(topicId)
                    .title("N3 Conversation")
                    .jlptLevel(Course.JlptLevel.N3)
                    .status(Course.ContentStatus.PUBLISHED)
                    .build();

            Course course = Course.builder()
                    .id(courseId)
                    .jlptLevel(Course.JlptLevel.N4)  // User in N4 course
                    .build();

            Clazz clazz = Clazz.builder()
                    .id(classId)
                    .courseId(courseId)
                    .build();

            ConversationSummaryResponse resp = ConversationSummaryResponse.builder()
                    .id(n4ConvId)
                    .title("N4 Conversation")
                    .jlptLevel(Course.JlptLevel.N4)
                    .build();

            // Mock lookups
            when(classMemberRepository.findActiveClassIdsByUserId(userId))
                    .thenReturn(List.of(classId));
            when(classRepository.findByIdAndNotDeleted(classId))
                    .thenReturn(Optional.of(clazz));
            when(courseRepository.findByIdAndNotDeleted(courseId))
                    .thenReturn(Optional.of(course));

            // Repository should be called with N4, and return only N4 conversation
            when(conversationRepository.findByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNullOrderByOrderIndexAsc(
                    topicId, "N4", "PUBLISHED"))
                    .thenReturn(List.of(n4Conv));  // Only N4, not N3

            when(conversationMapper.toSummaryResponse(n4Conv)).thenReturn(resp);
            when(conversationRepository.findPracticeStatsByUserIdAndConversationIds(userId, List.of(n4ConvId)))
                    .thenReturn(Collections.emptyList());

            // Act
            List<ConversationSummaryResponse> result = conversationService
                    .getPublishedConversationsByTopicAndUserJlptLevel(topicId, userId);

            // Assert: Only N4 conversation returned, verify repository was called with exact level
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo(n4ConvId);

            // Verify that the repository was called with exact "N4", not a range
            verify(conversationRepository).findByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNullOrderByOrderIndexAsc(
                    topicId, "N4", "PUBLISHED");
        }
    }

    @Nested
    @DisplayName("Without class enrollment")
    class WithoutClassEnrollment {

        @Test
        @DisplayName("Should return empty list if user is not enrolled in any class")
        void noEnrollment_ReturnsEmptyList() {
            // Arrange
            when(classMemberRepository.findActiveClassIdsByUserId(userId))
                    .thenReturn(Collections.emptyList());

            // Act
            List<ConversationSummaryResponse> result = conversationService
                    .getPublishedConversationsByTopicAndUserJlptLevel(topicId, userId);

            // Assert
            assertThat(result).isEmpty();

            // Verify that no further lookups happened
            verify(classRepository, never()).findByIdAndNotDeleted(any());
            verify(courseRepository, never()).findByIdAndNotDeleted(any());
            verify(conversationRepository, never()).findByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNullOrderByOrderIndexAsc(
                    any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("Should throw ResourceNotFoundException if class not found")
        void enrollmentFound_ButClassNotFound_ThrowsException() {
            // Arrange
            when(classMemberRepository.findActiveClassIdsByUserId(userId))
                    .thenReturn(List.of(classId));
            when(classRepository.findByIdAndNotDeleted(classId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> conversationService
                    .getPublishedConversationsByTopicAndUserJlptLevel(topicId, userId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Should throw CourseNotFoundException if course not found")
        void classFound_ButCourseNotFound_ThrowsException() {
            // Arrange
            Clazz clazz = Clazz.builder()
                    .id(classId)
                    .courseId(courseId)
                    .build();

            when(classMemberRepository.findActiveClassIdsByUserId(userId))
                    .thenReturn(List.of(classId));
            when(classRepository.findByIdAndNotDeleted(classId))
                    .thenReturn(Optional.of(clazz));
            when(courseRepository.findByIdAndNotDeleted(courseId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> conversationService
                    .getPublishedConversationsByTopicAndUserJlptLevel(topicId, userId))
                    .isInstanceOf(CourseNotFoundException.class);
        }

        @Test
        @DisplayName("Should return empty list if no conversations match JLPT level")
        void noConversationsMatchLevel_ReturnsEmpty() {
            // Arrange
            Course course = Course.builder()
                    .id(courseId)
                    .jlptLevel(Course.JlptLevel.N1)
                    .build();

            Clazz clazz = Clazz.builder()
                    .id(classId)
                    .courseId(courseId)
                    .build();

            when(classMemberRepository.findActiveClassIdsByUserId(userId))
                    .thenReturn(List.of(classId));
            when(classRepository.findByIdAndNotDeleted(classId))
                    .thenReturn(Optional.of(clazz));
            when(courseRepository.findByIdAndNotDeleted(courseId))
                    .thenReturn(Optional.of(course));
            when(conversationRepository.findByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNullOrderByOrderIndexAsc(
                    topicId, "N1", "PUBLISHED"))
                    .thenReturn(Collections.emptyList());

            // Act
            List<ConversationSummaryResponse> result = conversationService
                    .getPublishedConversationsByTopicAndUserJlptLevel(topicId, userId);

            // Assert
            assertThat(result).isEmpty();
        }
    }
}
