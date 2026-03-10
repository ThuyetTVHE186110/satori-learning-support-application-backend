package com.fu.conversation.service;

import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.ai.ConversationGenerationService;
import com.fu.conversation.dto.request.CreateThemeRequest;
import com.fu.conversation.dto.request.GenerateThemeRequest;
import com.fu.conversation.dto.request.UpdateThemeRequest;
import com.fu.conversation.dto.response.ConversationThemeResponse;
import com.fu.conversation.dto.response.ConversationThemeSummaryResponse;
import com.fu.conversation.dto.response.GeneratedThemeSuggestionResponse;
import com.fu.conversation.entity.ConversationTheme;
import com.fu.conversation.entity.ConversationTopic;
import com.fu.conversation.mapper.ConversationThemeMapper;
import com.fu.conversation.repository.ConversationThemeRepository;
import com.fu.conversation.repository.ConversationTopicRepository;
import com.fu.conversation.service.impl.ConversationThemeServiceImpl;
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
@DisplayName("ConversationThemeServiceImpl Tests")
class ConversationThemeServiceImplTest {

    @Mock private ConversationThemeRepository themeRepository;
    @Mock private ConversationTopicRepository topicRepository;
    @Mock private ConversationThemeMapper themeMapper;
    @Mock private ConversationGenerationService generationService;
    @Mock private CourseRepository courseRepository;

    @InjectMocks
    private ConversationThemeServiceImpl themeService;

    private UUID managerId;
    private UUID themeId;
    private UUID courseId;
    private ConversationTheme activeTheme;
    private ConversationTheme deletedTheme;
    private Course course;
    private ConversationThemeResponse themeResponse;
    private ConversationThemeSummaryResponse summaryResponse;

    @BeforeEach
    void setUp() {
        managerId = UUID.randomUUID();
        themeId   = UUID.randomUUID();
        courseId  = UUID.randomUUID();

        activeTheme = ConversationTheme.builder()
                .title("Daily Life Conversations")
                .category("daily_life")
                .jlptLevel(Course.JlptLevel.N5)
                .courseId(courseId)
                .status(Course.ContentStatus.DRAFT)
                .orderIndex(0)
                .build();
        ReflectionTestUtils.setField(activeTheme, "id", themeId);

        deletedTheme = ConversationTheme.builder()
                .title("Deleted Theme")
                .status(Course.ContentStatus.DRAFT)
                .build();
        UUID deletedId = UUID.randomUUID();
        ReflectionTestUtils.setField(deletedTheme, "id", deletedId);
        deletedTheme.setDeletedAt(LocalDateTime.now().minusDays(1));

        course = Course.builder().name("N5 Beginner Course").build();

        themeResponse = ConversationThemeResponse.builder()
                .id(themeId)
                .title("Daily Life Conversations")
                .status(Course.ContentStatus.DRAFT)
                .build();

        summaryResponse = ConversationThemeSummaryResponse.builder()
                .id(themeId)
                .title("Daily Life Conversations")
                .build();
    }

    // ── helper ──────────────────────────────────────────────────────────────
    private void mockTopicCount(UUID id, long count) {
        when(topicRepository.countByThemeIdAndDeletedAtIsNull(eq(id)))
                .thenReturn(count);
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("generateThemeSuggestions")
    class GenerateThemeSuggestionsTests {

        @Test
        @DisplayName("Should delegate to ConversationGenerationService and return results")
        void shouldReturnSuggestionsFromGenerationService() {
            // Given
            GenerateThemeRequest request = GenerateThemeRequest.builder()
                    .courseId(courseId).count(3).build();
            List<GeneratedThemeSuggestionResponse> expected = List.of(
                    GeneratedThemeSuggestionResponse.builder().title("Theme A").build(),
                    GeneratedThemeSuggestionResponse.builder().title("Theme B").build()
            );
            when(generationService.generateThemesForCourse(courseId, 3)).thenReturn(expected);

            // When
            List<GeneratedThemeSuggestionResponse> result =
                    themeService.generateThemeSuggestions(request);

            // Then
            assertThat(result).hasSize(2).isEqualTo(expected);
            verify(generationService).generateThemesForCourse(courseId, 3);
        }

        @Test
        @DisplayName("Should return empty list when generation service finds nothing")
        void shouldReturnEmptyListWhenNoSuggestions() {
            GenerateThemeRequest request = GenerateThemeRequest.builder()
                    .courseId(courseId).count(3).build();
            when(generationService.generateThemesForCourse(any(), anyInt()))
                    .thenReturn(Collections.emptyList());

            List<GeneratedThemeSuggestionResponse> result =
                    themeService.generateThemeSuggestions(request);

            assertThat(result).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("createTheme")
    class CreateThemeTests {

        @Test
        @DisplayName("Should save theme and set createdBy / updatedBy")
        void shouldCreateThemeAndSetAuditFields() {
            // Given
            CreateThemeRequest request = CreateThemeRequest.builder()
                    .title("Daily Life Conversations")
                    .courseId(courseId)
                    .build();
            when(themeMapper.toEntity(request)).thenReturn(activeTheme);
            when(themeRepository.save(activeTheme)).thenReturn(activeTheme);
            when(themeMapper.toResponse(activeTheme)).thenReturn(themeResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            mockTopicCount(themeId, 0L);

            // When
            ConversationThemeResponse result = themeService.createTheme(request, managerId);

            // Then
            assertThat(result).isNotNull();
            assertThat(activeTheme.getCreatedBy()).isEqualTo(managerId);
            assertThat(activeTheme.getUpdatedBy()).isEqualTo(managerId);
            verify(themeRepository).save(activeTheme);
        }

        @Test
        @DisplayName("Should populate courseName when courseId is present")
        void shouldPopulateCourseNameWhenCourseExists() {
            CreateThemeRequest request = CreateThemeRequest.builder()
                    .title("Test").courseId(courseId).build();
            when(themeMapper.toEntity(request)).thenReturn(activeTheme);
            when(themeRepository.save(any())).thenReturn(activeTheme);
            when(themeMapper.toResponse(activeTheme)).thenReturn(themeResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            mockTopicCount(themeId, 5L);

            ConversationThemeResponse result = themeService.createTheme(request, managerId);

            assertThat(result.getCourseName()).isEqualTo("N5 Beginner Course");
            assertThat(result.getTopicCount()).isEqualTo(5L);
        }

        @Test
        @DisplayName("Should not query courseRepository when courseId is null")
        void shouldSkipCourseQueryWhenCourseIdNull() {
            ConversationTheme themeNoCourse = ConversationTheme.builder()
                    .title("No Course").build();
            ReflectionTestUtils.setField(themeNoCourse, "id", themeId);

            CreateThemeRequest request = CreateThemeRequest.builder().title("No Course").build();
            when(themeMapper.toEntity(request)).thenReturn(themeNoCourse);
            when(themeRepository.save(any())).thenReturn(themeNoCourse);
            when(themeMapper.toResponse(themeNoCourse)).thenReturn(themeResponse);
            mockTopicCount(themeId, 0L);

            themeService.createTheme(request, managerId);

            verify(courseRepository, never()).findById(any());
        }

        @Test
        @DisplayName("Should gracefully handle missing course (courseId present but course not found)")
        void shouldHandleMissingCourseGracefully() {
            CreateThemeRequest request = CreateThemeRequest.builder()
                    .title("Test").courseId(courseId).build();
            when(themeMapper.toEntity(request)).thenReturn(activeTheme);
            when(themeRepository.save(any())).thenReturn(activeTheme);
            when(themeMapper.toResponse(activeTheme)).thenReturn(themeResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.empty());
            mockTopicCount(themeId, 0L);

            ConversationThemeResponse result = themeService.createTheme(request, managerId);

            assertThat(result.getCourseName()).isNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("updateTheme")
    class UpdateThemeTests {

        @Test
        @DisplayName("Should update theme fields and set updatedBy")
        void shouldUpdateThemeSuccessfully() {
            // Given
            UpdateThemeRequest request = UpdateThemeRequest.builder()
                    .title("Updated Title").build();
            when(themeRepository.findById(themeId)).thenReturn(Optional.of(activeTheme));
            when(themeRepository.save(activeTheme)).thenReturn(activeTheme);
            when(themeMapper.toResponse(activeTheme)).thenReturn(themeResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            mockTopicCount(themeId, 0L);

            // When
            ConversationThemeResponse result =
                    themeService.updateTheme(themeId, request, managerId);

            // Then
            assertThat(result).isNotNull();
            assertThat(activeTheme.getUpdatedBy()).isEqualTo(managerId);
            verify(themeMapper).updateEntity(activeTheme, request);
            verify(themeRepository).save(activeTheme);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when theme does not exist")
        void shouldThrowWhenThemeNotFound() {
            when(themeRepository.findById(themeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    themeService.updateTheme(themeId, new UpdateThemeRequest(), managerId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("ConversationTheme");
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when theme is soft-deleted")
        void shouldThrowWhenThemeIsSoftDeleted() {
            when(themeRepository.findById(deletedTheme.getId()))
                    .thenReturn(Optional.of(deletedTheme));

            assertThatThrownBy(() ->
                    themeService.updateTheme(deletedTheme.getId(), new UpdateThemeRequest(), managerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
        @Test
        @DisplayName("Should throw BusinessException when title is blank (non-null)")
        void shouldThrowWhenTitleIsBlank() {
            UpdateThemeRequest request = UpdateThemeRequest.builder()
                    .title("   ").build(); // blank, not null

            assertThatThrownBy(() ->
                    themeService.updateTheme(themeId, request, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Title must not be blank");
        }

        @Test
        @DisplayName("Should allow null title (partial update, no change)")
        void shouldAllowNullTitle() {
            UpdateThemeRequest request = UpdateThemeRequest.builder()
                    .category("travel").build(); // title is null
            when(themeRepository.findById(themeId)).thenReturn(Optional.of(activeTheme));
            when(themeRepository.save(activeTheme)).thenReturn(activeTheme);
            when(themeMapper.toResponse(activeTheme)).thenReturn(themeResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            mockTopicCount(themeId, 0L);

            ConversationThemeResponse result =
                    themeService.updateTheme(themeId, request, managerId);

            assertThat(result).isNotNull();
            verify(themeMapper).updateEntity(activeTheme, request);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("deleteTheme")
    class DeleteThemeTests {

        @Test
        @DisplayName("Should soft-delete by setting deletedAt and deletedBy")
        void shouldSoftDeleteTheme() {
            // Given
            when(themeRepository.findById(themeId)).thenReturn(Optional.of(activeTheme));

            // When
            themeService.deleteTheme(themeId, managerId);

            // Then
            assertThat(activeTheme.getDeletedAt()).isNotNull();
            assertThat(activeTheme.getDeletedBy()).isEqualTo(managerId);
            verify(themeRepository).save(activeTheme);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when theme not found")
        void shouldThrowWhenThemeNotFound() {
            when(themeRepository.findById(themeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> themeService.deleteTheme(themeId, managerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Should throw when theme is already soft-deleted")
        void shouldThrowWhenThemeAlreadyDeleted() {
            when(themeRepository.findById(deletedTheme.getId()))
                    .thenReturn(Optional.of(deletedTheme));

            assertThatThrownBy(() ->
                    themeService.deleteTheme(deletedTheme.getId(), managerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Should cascade soft-delete active child topics when theme is deleted")
        void shouldCascadeSoftDeleteChildTopics() {
            // Given
            ConversationTopic child1 = ConversationTopic.builder()
                    .title("Topic A").build();
            ConversationTopic child2 = ConversationTopic.builder()
                    .title("Topic B").build();
            ReflectionTestUtils.setField(child1, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(child2, "id", UUID.randomUUID());

            when(themeRepository.findById(themeId)).thenReturn(Optional.of(activeTheme));
            when(topicRepository.findByThemeIdAndDeletedAtIsNull(themeId))
                    .thenReturn(List.of(child1, child2));

            // When
            themeService.deleteTheme(themeId, managerId);

            // Then: both child topics should be soft-deleted
            assertThat(child1.getDeletedAt()).isNotNull();
            assertThat(child1.getDeletedBy()).isEqualTo(managerId);
            assertThat(child2.getDeletedAt()).isNotNull();
            assertThat(child2.getDeletedBy()).isEqualTo(managerId);
            verify(topicRepository).saveAll(List.of(child1, child2));
        }

        @Test
        @DisplayName("Should soft-delete theme successfully when it has no topics")
        void shouldSoftDeleteThemeWithNoTopics() {
            when(themeRepository.findById(themeId)).thenReturn(Optional.of(activeTheme));
            when(topicRepository.findByThemeIdAndDeletedAtIsNull(themeId))
                    .thenReturn(Collections.emptyList());

            themeService.deleteTheme(themeId, managerId);

            assertThat(activeTheme.getDeletedAt()).isNotNull();
            verify(topicRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("Should stamp theme and topics with the same deletedAt timestamp")
        void shouldStampSameTimestampOnThemeAndTopics() {
            ConversationTopic child = ConversationTopic.builder().title("Child").build();
            ReflectionTestUtils.setField(child, "id", UUID.randomUUID());

            when(themeRepository.findById(themeId)).thenReturn(Optional.of(activeTheme));
            when(topicRepository.findByThemeIdAndDeletedAtIsNull(themeId))
                    .thenReturn(List.of(child));

            themeService.deleteTheme(themeId, managerId);

            // Both deletedAt values should be non-null and equal (same reference: LocalDateTime.now())
            assertThat(activeTheme.getDeletedAt()).isNotNull();
            assertThat(child.getDeletedAt()).isNotNull();
            assertThat(child.getDeletedAt()).isEqualTo(activeTheme.getDeletedAt());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("updateStatus")
    class UpdateStatusTests {

        @Test
        @DisplayName("Should change status to PUBLISHED and update updatedBy")
        void shouldPublishTheme() {
            when(themeRepository.findById(themeId)).thenReturn(Optional.of(activeTheme));
            when(themeRepository.save(activeTheme)).thenReturn(activeTheme);
            when(themeMapper.toResponse(activeTheme)).thenReturn(themeResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            mockTopicCount(themeId, 0L);

            themeService.updateStatus(themeId, Course.ContentStatus.PUBLISHED, managerId);

            assertThat(activeTheme.getStatus()).isEqualTo(Course.ContentStatus.PUBLISHED);
            assertThat(activeTheme.getUpdatedBy()).isEqualTo(managerId);
            verify(themeRepository).save(activeTheme);
        }

        @Test
        @DisplayName("Should change status to ARCHIVED")
        void shouldArchiveTheme() {
            when(themeRepository.findById(themeId)).thenReturn(Optional.of(activeTheme));
            when(themeRepository.save(activeTheme)).thenReturn(activeTheme);
            when(themeMapper.toResponse(activeTheme)).thenReturn(themeResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            mockTopicCount(themeId, 0L);

            themeService.updateStatus(themeId, Course.ContentStatus.ARCHIVED, managerId);

            assertThat(activeTheme.getStatus()).isEqualTo(Course.ContentStatus.ARCHIVED);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when theme not found")
        void shouldThrowWhenThemeNotFound() {
            when(themeRepository.findById(themeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    themeService.updateStatus(themeId, Course.ContentStatus.PUBLISHED, managerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Should throw when theme is soft-deleted")
        void shouldThrowWhenThemeSoftDeleted() {
            when(themeRepository.findById(deletedTheme.getId()))
                    .thenReturn(Optional.of(deletedTheme));

            assertThatThrownBy(() ->
                    themeService.updateStatus(deletedTheme.getId(),
                            Course.ContentStatus.PUBLISHED, managerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getTheme")
    class GetThemeTests {

        @Test
        @DisplayName("Should return full theme response with courseName and topicCount")
        void shouldReturnThemeWithDetails() {
            when(themeRepository.findById(themeId)).thenReturn(Optional.of(activeTheme));
            when(themeMapper.toResponse(activeTheme)).thenReturn(themeResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            mockTopicCount(themeId, 3L);

            ConversationThemeResponse result = themeService.getTheme(themeId);

            assertThat(result).isNotNull();
            assertThat(result.getCourseName()).isEqualTo("N5 Beginner Course");
            assertThat(result.getTopicCount()).isEqualTo(3L);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when theme not found")
        void shouldThrowForNonExistentTheme() {
            when(themeRepository.findById(themeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> themeService.getTheme(themeId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("ConversationTheme");
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when theme is soft-deleted")
        void shouldThrowForDeletedTheme() {
            when(themeRepository.findById(deletedTheme.getId()))
                    .thenReturn(Optional.of(deletedTheme));

            assertThatThrownBy(() -> themeService.getTheme(deletedTheme.getId()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getAllThemes")
    class GetAllThemesTests {

        @Test
        @DisplayName("Should return paginated themes as summary responses with topicCount")
        void shouldReturnPaginatedThemes() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<ConversationTheme> page = new PageImpl<>(List.of(activeTheme));
            when(themeRepository.findByDeletedAtIsNull(pageable)).thenReturn(page);
            when(themeMapper.toSummaryResponse(activeTheme)).thenReturn(summaryResponse);
            mockTopicCount(themeId, 2L);

            Page<ConversationThemeSummaryResponse> result = themeService.getAllThemes(pageable);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getTopicCount()).isEqualTo(2L);
            verify(themeRepository).findByDeletedAtIsNull(pageable);
        }

        @Test
        @DisplayName("Should return empty page when no themes exist")
        void shouldReturnEmptyPage() {
            Pageable pageable = PageRequest.of(0, 10);
            when(themeRepository.findByDeletedAtIsNull(pageable)).thenReturn(Page.empty());

            Page<ConversationThemeSummaryResponse> result = themeService.getAllThemes(pageable);

            assertThat(result.getContent()).isEmpty();
        }

        @Test
        @DisplayName("Should map multiple themes correctly")
        void shouldMapMultipleThemes() {
            UUID otherId = UUID.randomUUID();
            ConversationTheme other = ConversationTheme.builder().title("Other").build();
            ReflectionTestUtils.setField(other, "id", otherId);

            ConversationThemeSummaryResponse otherSummary =
                    ConversationThemeSummaryResponse.builder().id(otherId).title("Other").build();

            Pageable pageable = PageRequest.of(0, 10);
            when(themeRepository.findByDeletedAtIsNull(pageable))
                    .thenReturn(new PageImpl<>(List.of(activeTheme, other)));
            when(themeMapper.toSummaryResponse(activeTheme)).thenReturn(summaryResponse);
            when(themeMapper.toSummaryResponse(other)).thenReturn(otherSummary);
            mockTopicCount(themeId, 1L);
            mockTopicCount(otherId, 0L);

            Page<ConversationThemeSummaryResponse> result = themeService.getAllThemes(pageable);

            assertThat(result.getContent()).hasSize(2);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getPublishedThemes")
    class GetPublishedThemesTests {

        @Test
        @DisplayName("Should filter by JLPT level when provided")
        void shouldFilterByJlptLevel() {
            Pageable pageable = PageRequest.of(0, 10);
            when(themeRepository.findByJlptLevelAndStatusAndDeletedAtIsNull(
                    "N5", "PUBLISHED", pageable))
                    .thenReturn(new PageImpl<>(List.of(activeTheme)));
            when(themeMapper.toSummaryResponse(activeTheme)).thenReturn(summaryResponse);
            mockTopicCount(themeId, 0L);

            themeService.getPublishedThemes(Course.JlptLevel.N5, pageable);

            verify(themeRepository).findByJlptLevelAndStatusAndDeletedAtIsNull(
                    "N5", "PUBLISHED", pageable);
            verify(themeRepository, never())
                    .findByStatusAndDeletedAtIsNull(any(), any());
        }

        @Test
        @DisplayName("Should return all published when jlptLevel is null")
        void shouldReturnAllPublishedWhenNoLevelFilter() {
            Pageable pageable = PageRequest.of(0, 10);
            when(themeRepository.findByStatusAndDeletedAtIsNull(
                    "PUBLISHED", pageable))
                    .thenReturn(new PageImpl<>(List.of(activeTheme)));
            when(themeMapper.toSummaryResponse(activeTheme)).thenReturn(summaryResponse);
            mockTopicCount(themeId, 0L);

            themeService.getPublishedThemes(null, pageable);

            verify(themeRepository).findByStatusAndDeletedAtIsNull(
                    "PUBLISHED", pageable);
            verify(themeRepository, never())
                    .findByJlptLevelAndStatusAndDeletedAtIsNull(any(), any(), any());
        }

        @Test
        @DisplayName("Should return empty page when no published themes")
        void shouldReturnEmptyWhenNoPublishedThemes() {
            Pageable pageable = PageRequest.of(0, 10);
            when(themeRepository.findByStatusAndDeletedAtIsNull(
                    "PUBLISHED", pageable))
                    .thenReturn(Page.empty());

            Page<ConversationThemeSummaryResponse> result =
                    themeService.getPublishedThemes(null, pageable);

            assertThat(result.getContent()).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("searchThemes")
    class SearchThemesTests {

        @Test
        @DisplayName("Should return paginated results matching keyword")
        void shouldReturnSearchResults() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<ConversationTheme> page = new PageImpl<>(List.of(activeTheme));
            when(themeRepository.searchByKeyword("Daily", pageable)).thenReturn(page);
            when(themeMapper.toSummaryResponse(activeTheme)).thenReturn(summaryResponse);
            mockTopicCount(themeId, 1L);

            Page<ConversationThemeSummaryResponse> result =
                    themeService.searchThemes("Daily", pageable);

            assertThat(result.getContent()).hasSize(1);
            verify(themeRepository).searchByKeyword("Daily", pageable);
        }

        @Test
        @DisplayName("Should return empty page when no matches")
        void shouldReturnEmptyWhenNoMatches() {
            Pageable pageable = PageRequest.of(0, 10);
            when(themeRepository.searchByKeyword("xyz", pageable)).thenReturn(Page.empty());

            Page<ConversationThemeSummaryResponse> result =
                    themeService.searchThemes("xyz", pageable);

            assertThat(result.getContent()).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("searchPublishedThemes")
    class SearchPublishedThemesTests {

        @Test
        @DisplayName("Should return published themes matching keyword")
        void shouldReturnPublishedSearchResults() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<ConversationTheme> page = new PageImpl<>(List.of(activeTheme));
            when(themeRepository.searchPublishedByKeyword("Shopping", pageable)).thenReturn(page);
            when(themeMapper.toSummaryResponse(activeTheme)).thenReturn(summaryResponse);
            mockTopicCount(themeId, 0L);

            Page<ConversationThemeSummaryResponse> result =
                    themeService.searchPublishedThemes("Shopping", pageable);

            assertThat(result.getContent()).hasSize(1);
            verify(themeRepository).searchPublishedByKeyword("Shopping", pageable);
        }

        @Test
        @DisplayName("Should return empty page when no published matches")
        void shouldReturnEmptyWhenNoPublishedMatches() {
            Pageable pageable = PageRequest.of(0, 10);
            when(themeRepository.searchPublishedByKeyword("xyz", pageable)).thenReturn(Page.empty());

            Page<ConversationThemeSummaryResponse> result =
                    themeService.searchPublishedThemes("xyz", pageable);

            assertThat(result.getContent()).isEmpty();
        }
    }
}
