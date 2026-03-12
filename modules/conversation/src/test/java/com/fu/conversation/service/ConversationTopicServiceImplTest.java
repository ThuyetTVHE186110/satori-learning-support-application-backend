package com.fu.conversation.service;

import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.dto.request.CreateTopicRequest;
import com.fu.conversation.dto.request.UpdateTopicRequest;
import com.fu.conversation.dto.response.ConversationTopicResponse;
import com.fu.conversation.dto.response.ConversationTopicSummaryResponse;
import com.fu.conversation.entity.ConversationTheme;
import com.fu.conversation.entity.ConversationTopic;
import com.fu.conversation.mapper.ConversationTopicMapper;
import com.fu.conversation.repository.ConversationThemeRepository;
import com.fu.conversation.repository.ConversationTopicRepository;
import com.fu.conversation.service.impl.ConversationTopicServiceImpl;
import com.fu.course.entity.Course;
import com.fu.course.repository.CourseRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationTopicServiceImpl Tests")
class ConversationTopicServiceImplTest {

    @Mock private ConversationTopicRepository topicRepository;
    @Mock private ConversationTopicMapper topicMapper;
    @Mock private ConversationThemeRepository themeRepository;
    @Mock private CourseRepository courseRepository;

    @InjectMocks
    private ConversationTopicServiceImpl topicService;

    private UUID managerId;
    private UUID topicId;
    private UUID themeId;
    private UUID courseId;
    private ConversationTopic activeTopic;
    private ConversationTopic deletedTopic;
    private Course course;
    private ConversationTopicResponse topicResponse;
    private ConversationTopicSummaryResponse summaryResponse;

    @BeforeEach
    void setUp() {
        managerId = UUID.randomUUID();
        topicId   = UUID.randomUUID();
        themeId   = UUID.randomUUID();
        courseId  = UUID.randomUUID();

        activeTopic = ConversationTopic.builder()
                .themeId(themeId)
                .courseId(courseId)
                .title("At the Restaurant")
                .scenario("Ordering food in Japanese")
                .jlptLevel(Course.JlptLevel.N5)
                .status(Course.ContentStatus.DRAFT)
                .difficultyScore(2)
                .orderIndex(1)
                .build();
        ReflectionTestUtils.setField(activeTopic, "id", topicId);

        deletedTopic = ConversationTopic.builder()
                .title("Deleted Topic")
                .status(Course.ContentStatus.DRAFT)
                .build();
        ReflectionTestUtils.setField(deletedTopic, "id", UUID.randomUUID());
        deletedTopic.setDeletedAt(LocalDateTime.now().minusDays(1));

        course = Course.builder().name("N5 Course").build();

        topicResponse = ConversationTopicResponse.builder()
                .id(topicId)
                .title("At the Restaurant")
                .status(Course.ContentStatus.DRAFT)
                .build();

        summaryResponse = ConversationTopicSummaryResponse.builder()
                .id(topicId)
                .title("At the Restaurant")
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("createTopic")
    class CreateTopicTests {

        @Test
        @DisplayName("Should save topic and set createdBy / updatedBy")
        void shouldCreateTopicAndSetAuditFields() {
            // Given
            ConversationTheme parentTheme = ConversationTheme.builder().title("Parent").build();
            ReflectionTestUtils.setField(parentTheme, "id", themeId);

            CreateTopicRequest request = CreateTopicRequest.builder()
                    .themeId(themeId)
                    .courseId(courseId)
                    .title("At the Restaurant")
                    .build();
            when(themeRepository.findById(themeId)).thenReturn(Optional.of(parentTheme));
            when(topicMapper.toEntity(request)).thenReturn(activeTopic);
            when(topicRepository.save(activeTopic)).thenReturn(activeTopic);
            when(topicMapper.toResponse(activeTopic)).thenReturn(topicResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            // When
            ConversationTopicResponse result = topicService.createTopic(request, managerId);

            // Then
            assertThat(result).isNotNull();
            assertThat(activeTopic.getCreatedBy()).isEqualTo(managerId);
            assertThat(activeTopic.getUpdatedBy()).isEqualTo(managerId);
            verify(topicRepository).save(activeTopic);
        }

        @Test
        @DisplayName("Should populate courseName when courseId is present")
        void shouldPopulateCourseNameWhenCourseExists() {
            CreateTopicRequest request = CreateTopicRequest.builder()
                    .title("Test").courseId(courseId).build();
            when(topicMapper.toEntity(request)).thenReturn(activeTopic);
            when(topicRepository.save(any())).thenReturn(activeTopic);
            when(topicMapper.toResponse(activeTopic)).thenReturn(topicResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            ConversationTopicResponse result = topicService.createTopic(request, managerId);

            assertThat(result.getCourseName()).isEqualTo("N5 Course");
        }

        @Test
        @DisplayName("Should not query courseRepository when courseId is null")
        void shouldSkipCourseQueryWhenCourseIdNull() {
            ConversationTopic topicNoCourse = ConversationTopic.builder()
                    .title("No Course").build();
            ReflectionTestUtils.setField(topicNoCourse, "id", topicId);

            CreateTopicRequest request = CreateTopicRequest.builder().title("No Course").build();
            when(topicMapper.toEntity(request)).thenReturn(topicNoCourse);
            when(topicRepository.save(any())).thenReturn(topicNoCourse);
            when(topicMapper.toResponse(topicNoCourse)).thenReturn(topicResponse);

            topicService.createTopic(request, managerId);

            verify(courseRepository, never()).findById(any());
        }

        @Test
        @DisplayName("Should handle missing course gracefully when courseId present but not found")
        void shouldHandleMissingCourseGracefully() {
            CreateTopicRequest request = CreateTopicRequest.builder()
                    .title("Test").courseId(courseId).build();
            when(topicMapper.toEntity(request)).thenReturn(activeTopic);
            when(topicRepository.save(any())).thenReturn(activeTopic);
            when(topicMapper.toResponse(activeTopic)).thenReturn(topicResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.empty());

            ConversationTopicResponse result = topicService.createTopic(request, managerId);

            assertThat(result.getCourseName()).isNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("updateTopic")
    class UpdateTopicTests {

        @Test
        @DisplayName("Should update topic fields and set updatedBy")
        void shouldUpdateTopicSuccessfully() {
            // Given
            UpdateTopicRequest request = UpdateTopicRequest.builder()
                    .title("Updated Title").build();
            when(topicRepository.findById(topicId)).thenReturn(Optional.of(activeTopic));
            when(topicRepository.save(activeTopic)).thenReturn(activeTopic);
            when(topicMapper.toResponse(activeTopic)).thenReturn(topicResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            // When
            ConversationTopicResponse result =
                    topicService.updateTopic(topicId, request, managerId);

            // Then
            assertThat(result).isNotNull();
            assertThat(activeTopic.getUpdatedBy()).isEqualTo(managerId);
            verify(topicMapper).updateEntity(activeTopic, request);
            verify(topicRepository).save(activeTopic);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when topic not found")
        void shouldThrowWhenTopicNotFound() {
            when(topicRepository.findById(topicId)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    topicService.updateTopic(topicId, new UpdateTopicRequest(), managerId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("ConversationTopic");
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when topic is soft-deleted")
        void shouldThrowWhenTopicIsSoftDeleted() {
            when(topicRepository.findById(deletedTopic.getId()))
                    .thenReturn(Optional.of(deletedTopic));

            assertThatThrownBy(() ->
                    topicService.updateTopic(deletedTopic.getId(), new UpdateTopicRequest(), managerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("deleteTopic")
    class DeleteTopicTests {

        @Test
        @DisplayName("Should soft-delete topic by setting deletedAt and deletedBy")
        void shouldSoftDeleteTopic() {
            // Given
            when(topicRepository.findById(topicId)).thenReturn(Optional.of(activeTopic));

            // When
            topicService.deleteTopic(topicId, managerId);

            // Then
            assertThat(activeTopic.getDeletedAt()).isNotNull();
            assertThat(activeTopic.getDeletedBy()).isEqualTo(managerId);
            verify(topicRepository).save(activeTopic);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when topic not found")
        void shouldThrowWhenTopicNotFound() {
            when(topicRepository.findById(topicId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> topicService.deleteTopic(topicId, managerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Should throw when topic is already soft-deleted")
        void shouldThrowWhenAlreadyDeleted() {
            when(topicRepository.findById(deletedTopic.getId()))
                    .thenReturn(Optional.of(deletedTopic));

            assertThatThrownBy(() ->
                    topicService.deleteTopic(deletedTopic.getId(), managerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("updateStatus")
    class UpdateStatusTests {

        @Test
        @DisplayName("Should change status to PUBLISHED")
        void shouldPublishTopic() {
            when(topicRepository.findById(topicId)).thenReturn(Optional.of(activeTopic));
            when(topicRepository.save(activeTopic)).thenReturn(activeTopic);
            when(topicMapper.toResponse(activeTopic)).thenReturn(topicResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            topicService.updateStatus(topicId, Course.ContentStatus.PUBLISHED, managerId);

            assertThat(activeTopic.getStatus()).isEqualTo(Course.ContentStatus.PUBLISHED);
            assertThat(activeTopic.getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should change status to ARCHIVED")
        void shouldArchiveTopic() {
            when(topicRepository.findById(topicId)).thenReturn(Optional.of(activeTopic));
            when(topicRepository.save(activeTopic)).thenReturn(activeTopic);
            when(topicMapper.toResponse(activeTopic)).thenReturn(topicResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            topicService.updateStatus(topicId, Course.ContentStatus.ARCHIVED, managerId);

            assertThat(activeTopic.getStatus()).isEqualTo(Course.ContentStatus.ARCHIVED);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when topic not found")
        void shouldThrowWhenTopicNotFound() {
            when(topicRepository.findById(topicId)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    topicService.updateStatus(topicId, Course.ContentStatus.PUBLISHED, managerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Should throw when topic is soft-deleted")
        void shouldThrowWhenTopicSoftDeleted() {
            when(topicRepository.findById(deletedTopic.getId()))
                    .thenReturn(Optional.of(deletedTopic));

            assertThatThrownBy(() ->
                    topicService.updateStatus(deletedTopic.getId(),
                            Course.ContentStatus.PUBLISHED, managerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getTopic")
    class GetTopicTests {

        @Test
        @DisplayName("Should return full topic response with courseName")
        void shouldReturnTopicWithDetails() {
            when(topicRepository.findById(topicId)).thenReturn(Optional.of(activeTopic));
            when(topicMapper.toResponse(activeTopic)).thenReturn(topicResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            ConversationTopicResponse result = topicService.getTopic(topicId);

            assertThat(result).isNotNull();
            assertThat(result.getCourseName()).isEqualTo("N5 Course");
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException for non-existent topic")
        void shouldThrowForNonExistentTopic() {
            when(topicRepository.findById(topicId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> topicService.getTopic(topicId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("ConversationTopic");
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException for soft-deleted topic")
        void shouldThrowForDeletedTopic() {
            when(topicRepository.findById(deletedTopic.getId()))
                    .thenReturn(Optional.of(deletedTopic));

            assertThatThrownBy(() -> topicService.getTopic(deletedTopic.getId()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getTopicsByTheme")
    class GetTopicsByThemeTests {

        @Test
        @DisplayName("Should return paginated topics belonging to given theme")
        void shouldReturnPaginatedTopics() {
            Pageable pageable = PageRequest.of(0, 10);
            when(topicRepository.findByThemeIdAndDeletedAtIsNull(themeId, pageable))
                    .thenReturn(new PageImpl<>(List.of(activeTopic)));
            when(topicMapper.toSummaryResponse(activeTopic)).thenReturn(summaryResponse);

            Page<ConversationTopicSummaryResponse> result =
                    topicService.getTopicsByTheme(themeId, pageable);

            assertThat(result.getContent()).hasSize(1);
            verify(topicRepository).findByThemeIdAndDeletedAtIsNull(themeId, pageable);
        }

        @Test
        @DisplayName("Should return empty page when theme has no topics")
        void shouldReturnEmptyPageForThemeWithNoTopics() {
            Pageable pageable = PageRequest.of(0, 10);
            when(topicRepository.findByThemeIdAndDeletedAtIsNull(themeId, pageable))
                    .thenReturn(Page.empty());

            Page<ConversationTopicSummaryResponse> result =
                    topicService.getTopicsByTheme(themeId, pageable);

            assertThat(result.getContent()).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getPublishedTopicsByTheme")
    class GetPublishedTopicsByThemeTests {

        @Test
        @DisplayName("Should return ordered list of published topics for a theme")
        void shouldReturnPublishedTopicsOrdered() {
            ConversationTopic topic2 = ConversationTopic.builder()
                    .title("Topic 2").orderIndex(2).build();
            ReflectionTestUtils.setField(topic2, "id", UUID.randomUUID());

            ConversationTopicSummaryResponse summary2 =
                    ConversationTopicSummaryResponse.builder().title("Topic 2").build();

            when(topicRepository.findByThemeIdAndStatusAndDeletedAtIsNullOrderByOrderIndexAsc(
                    themeId, Course.ContentStatus.PUBLISHED.name()))
                    .thenReturn(List.of(activeTopic, topic2));
            when(topicRepository.findPracticeStatsByUserIdAndTopicIds(any(), any()))
                    .thenReturn(List.of());
            when(topicMapper.toSummaryResponse(activeTopic)).thenReturn(summaryResponse);
            when(topicMapper.toSummaryResponse(topic2)).thenReturn(summary2);

            List<ConversationTopicSummaryResponse> result =
                    topicService.getPublishedTopicsByTheme(themeId, UUID.randomUUID());

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getTitle()).isEqualTo("At the Restaurant");
            assertThat(result.get(1).getTitle()).isEqualTo("Topic 2");
        }

        @Test
        @DisplayName("Should return empty list when no published topics for theme")
        void shouldReturnEmptyListWhenNoPublishedTopics() {
            when(topicRepository.findByThemeIdAndStatusAndDeletedAtIsNullOrderByOrderIndexAsc(
                    themeId, Course.ContentStatus.PUBLISHED.name()))
                    .thenReturn(Collections.emptyList());

            List<ConversationTopicSummaryResponse> result =
                    topicService.getPublishedTopicsByTheme(themeId, null);

            assertThat(result).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getAllTopics")
    class GetAllTopicsTests {

        @Test
        @DisplayName("Should return paginated topics across all themes")
        void shouldReturnAllTopicsPaginated() {
            Pageable pageable = PageRequest.of(0, 10);
            when(topicRepository.findByDeletedAtIsNull(pageable))
                    .thenReturn(new PageImpl<>(List.of(activeTopic)));
            when(topicMapper.toSummaryResponse(activeTopic)).thenReturn(summaryResponse);

            Page<ConversationTopicSummaryResponse> result = topicService.getAllTopics(pageable);

            assertThat(result.getContent()).hasSize(1);
            verify(topicRepository).findByDeletedAtIsNull(pageable);
        }

        @Test
        @DisplayName("Should return empty page when no topics exist")
        void shouldReturnEmptyPage() {
            Pageable pageable = PageRequest.of(0, 10);
            when(topicRepository.findByDeletedAtIsNull(pageable)).thenReturn(Page.empty());

            Page<ConversationTopicSummaryResponse> result = topicService.getAllTopics(pageable);

            assertThat(result.getContent()).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("createTopic — themeId validation")
    class ThemeValidationTests {

        @Test
        @DisplayName("Should succeed when themeId is null (no theme validation)")
        void shouldSucceedWhenThemeIdNull() {
            ConversationTopic topicNoTheme = ConversationTopic.builder()
                    .title("Standalone").build();
            ReflectionTestUtils.setField(topicNoTheme, "id", topicId);

            CreateTopicRequest request = CreateTopicRequest.builder().title("Standalone").build();
            when(topicMapper.toEntity(request)).thenReturn(topicNoTheme);
            when(topicRepository.save(any())).thenReturn(topicNoTheme);
            when(topicMapper.toResponse(topicNoTheme)).thenReturn(topicResponse);

            ConversationTopicResponse result = topicService.createTopic(request, managerId);

            assertThat(result).isNotNull();
            verify(themeRepository, never()).findById(any());
        }

        @Test
        @DisplayName("Should succeed when themeId refers to an active theme")
        void shouldSucceedWhenThemeExists() {
            UUID tid = UUID.randomUUID();
            ConversationTheme activeThemeObj = ConversationTheme.builder()
                    .title("Parent Theme").build();
            ReflectionTestUtils.setField(activeThemeObj, "id", tid);

            CreateTopicRequest request = CreateTopicRequest.builder()
                    .themeId(tid).title("Child Topic").build();
            when(themeRepository.findById(tid)).thenReturn(Optional.of(activeThemeObj));
            when(topicMapper.toEntity(request)).thenReturn(activeTopic);
            when(topicRepository.save(any())).thenReturn(activeTopic);
            when(topicMapper.toResponse(activeTopic)).thenReturn(topicResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            ConversationTopicResponse result = topicService.createTopic(request, managerId);

            assertThat(result).isNotNull();
            verify(themeRepository).findById(tid);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when themeId not found")
        void shouldThrowWhenThemeNotFound() {
            UUID missingThemeId = UUID.randomUUID();
            CreateTopicRequest request = CreateTopicRequest.builder()
                    .themeId(missingThemeId).title("Test").build();
            when(themeRepository.findById(missingThemeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> topicService.createTopic(request, managerId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("ConversationTheme");
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when theme is soft-deleted")
        void shouldThrowWhenThemeIsSoftDeleted() {
            ConversationTheme softDeletedTheme = ConversationTheme.builder()
                    .title("Deleted").build();
            UUID tid = UUID.randomUUID();
            ReflectionTestUtils.setField(softDeletedTheme, "id", tid);
            softDeletedTheme.setDeletedAt(LocalDateTime.now().minusDays(1));

            CreateTopicRequest request = CreateTopicRequest.builder()
                    .themeId(tid).title("Test").build();
            when(themeRepository.findById(tid)).thenReturn(Optional.of(softDeletedTheme));

            assertThatThrownBy(() -> topicService.createTopic(request, managerId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("ConversationTheme");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getPublishedTopic")
    class GetPublishedTopicTests {

        @Test
        @DisplayName("Should return topic when active and PUBLISHED")
        void shouldReturnPublishedTopic() {
            activeTopic.setStatus(Course.ContentStatus.PUBLISHED);
            when(topicRepository.findById(topicId)).thenReturn(Optional.of(activeTopic));
            when(topicMapper.toResponse(activeTopic)).thenReturn(topicResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

            ConversationTopicResponse result = topicService.getPublishedTopic(topicId);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when topic is DRAFT")
        void shouldThrowWhenTopicIsDraft() {
            activeTopic.setStatus(Course.ContentStatus.DRAFT);
            when(topicRepository.findById(topicId)).thenReturn(Optional.of(activeTopic));

            assertThatThrownBy(() -> topicService.getPublishedTopic(topicId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("ConversationTopic");
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when topic is ARCHIVED")
        void shouldThrowWhenTopicIsArchived() {
            activeTopic.setStatus(Course.ContentStatus.ARCHIVED);
            when(topicRepository.findById(topicId)).thenReturn(Optional.of(activeTopic));

            assertThatThrownBy(() -> topicService.getPublishedTopic(topicId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when topic is soft-deleted")
        void shouldThrowWhenTopicSoftDeleted() {
            when(topicRepository.findById(deletedTopic.getId()))
                    .thenReturn(Optional.of(deletedTopic));

            assertThatThrownBy(() -> topicService.getPublishedTopic(deletedTopic.getId()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when topic does not exist")
        void shouldThrowWhenTopicNotFound() {
            when(topicRepository.findById(topicId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> topicService.getPublishedTopic(topicId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
