package com.fu.conversation.service;

import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.ai.ConversationGenerationService;
import com.fu.conversation.dto.request.CreateTopicRequest;
import com.fu.conversation.dto.request.GenerateTopicRequest;
import com.fu.conversation.dto.request.UpdateTopicRequest;
import com.fu.conversation.dto.response.ConversationTopicResponse;
import com.fu.conversation.dto.response.ConversationTopicSummaryResponse;
import com.fu.conversation.dto.response.GeneratedTopicSuggestionResponse;
import com.fu.conversation.entity.Conversation;
import com.fu.conversation.entity.ConversationTopic;
import com.fu.conversation.mapper.ConversationTopicMapper;
import com.fu.conversation.repository.ConversationRepository;
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
    @Mock private ConversationRepository conversationRepository;
    @Mock private ConversationTopicMapper topicMapper;
    @Mock private ConversationGenerationService generationService;
    @Mock private CourseRepository courseRepository;

    @InjectMocks
    private ConversationTopicServiceImpl topicService;

    private UUID managerId;
    private UUID topicId;
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
        courseId  = UUID.randomUUID();

        activeTopic = ConversationTopic.builder()
                .courseId(courseId)
                .title("Daily Shopping")
                .category("shopping")
                .jlptLevel(Course.JlptLevel.N5)
                .status(Course.ContentStatus.DRAFT)
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
                .title("Daily Shopping")
                .status(Course.ContentStatus.DRAFT)
                .build();

        summaryResponse = ConversationTopicSummaryResponse.builder()
                .id(topicId)
                .title("Daily Shopping")
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("generateTopicSuggestions")
    class GenerateTopicSuggestionsTests {

        @Test
        @DisplayName("Should delegate to generationService.generateTopics")
        void shouldDelegateToGenerationService() {
            GenerateTopicRequest request = GenerateTopicRequest.builder()
                    .jlptLevel(Course.JlptLevel.N5)
                    .category("shopping")
                    .count(3)
                    .build();
            List<GeneratedTopicSuggestionResponse> suggestions = List.of(
                    GeneratedTopicSuggestionResponse.builder().title("Shopping").build());
            when(generationService.generateTopics(Course.JlptLevel.N5, "shopping", null, 3))
                    .thenReturn(suggestions);

            List<GeneratedTopicSuggestionResponse> result =
                    topicService.generateTopicSuggestions(request);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("Shopping");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("createTopic")
    class CreateTopicTests {

        @Test
        @DisplayName("Should save topic and set createdBy / updatedBy")
        void shouldCreateTopicAndSetAuditFields() {
            CreateTopicRequest request = CreateTopicRequest.builder()
                    .courseId(courseId)
                    .title("Daily Shopping")
                    .build();
            when(topicMapper.toEntity(request)).thenReturn(activeTopic);
            when(topicRepository.save(activeTopic)).thenReturn(activeTopic);
            when(topicMapper.toResponse(activeTopic)).thenReturn(topicResponse);
            when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
            when(conversationRepository.countByTopicIdAndDeletedAtIsNull(topicId)).thenReturn(0L);

            ConversationTopicResponse result = topicService.createTopic(request, managerId);

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
            when(conversationRepository.countByTopicIdAndDeletedAtIsNull(topicId)).thenReturn(0L);

            topicService.createTopic(request, managerId);

            verify(courseRepository).findById(courseId);
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
            when(conversationRepository.countByTopicIdAndDeletedAtIsNull(topicId)).thenReturn(0L);

            topicService.createTopic(request, managerId);

            verify(courseRepository, never()).findById(any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("updateTopic")
    class UpdateTopicTests {

        @Test
        @DisplayName("Should update topic and set updatedBy")
        void shouldUpdateTopicAndSetUpdatedBy() {
            UpdateTopicRequest request = UpdateTopicRequest.builder()
                    .title("Updated Shopping").build();
            when(topicRepository.findById(topicId)).thenReturn(Optional.of(activeTopic));
            when(topicRepository.save(activeTopic)).thenReturn(activeTopic);
            when(topicMapper.toResponse(activeTopic)).thenReturn(topicResponse);
            when(conversationRepository.countByTopicIdAndDeletedAtIsNull(topicId)).thenReturn(2L);

            ConversationTopicResponse result = topicService.updateTopic(topicId, request, managerId);

            assertThat(result).isNotNull();
            assertThat(activeTopic.getUpdatedBy()).isEqualTo(managerId);
            verify(topicMapper).updateEntity(activeTopic, request);
        }

        @Test
        @DisplayName("Should throw BusinessException when title is blank")
        void shouldThrowWhenTitleIsBlank() {
            UpdateTopicRequest request = UpdateTopicRequest.builder().title("   ").build();

            assertThatThrownBy(() -> topicService.updateTopic(topicId, request, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException for non-existent topic")
        void shouldThrowWhenTopicNotFound() {
            UpdateTopicRequest request = UpdateTopicRequest.builder().title("Valid").build();
            when(topicRepository.findById(topicId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> topicService.updateTopic(topicId, request, managerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException for soft-deleted topic")
        void shouldThrowWhenTopicSoftDeleted() {
            UpdateTopicRequest request = UpdateTopicRequest.builder().title("Valid").build();
            UUID deletedTopicId = deletedTopic.getId();
            when(topicRepository.findById(deletedTopicId)).thenReturn(Optional.of(deletedTopic));

            assertThatThrownBy(() -> topicService.updateTopic(deletedTopicId, request, managerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("deleteTopic")
    class DeleteTopicTests {

        @Test
        @DisplayName("Should soft-delete topic and cascade to active conversations")
        void shouldSoftDeleteTopicAndCascadeConversations() {
            Conversation activeConversation = Conversation.builder().title("Conv 1").build();
            ReflectionTestUtils.setField(activeConversation, "id", UUID.randomUUID());

            when(topicRepository.findById(topicId)).thenReturn(Optional.of(activeTopic));
            when(conversationRepository.findByTopicIdAndDeletedAtIsNull(topicId))
                    .thenReturn(List.of(activeConversation));

            topicService.deleteTopic(topicId, managerId);

            assertThat(activeTopic.getDeletedAt()).isNotNull();
            assertThat(activeTopic.getDeletedBy()).isEqualTo(managerId);
            assertThat(activeConversation.getDeletedAt()).isNotNull();
            assertThat(activeConversation.getDeletedBy()).isEqualTo(managerId);
            verify(topicRepository).save(activeTopic);
            verify(conversationRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("Should soft-delete topic with no conversations")
        void shouldSoftDeleteTopicWithNoConversations() {
            when(topicRepository.findById(topicId)).thenReturn(Optional.of(activeTopic));
            when(conversationRepository.findByTopicIdAndDeletedAtIsNull(topicId))
                    .thenReturn(List.of());

            topicService.deleteTopic(topicId, managerId);

            assertThat(activeTopic.getDeletedAt()).isNotNull();
            verify(conversationRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when deleting non-existent topic")
        void shouldThrowWhenTopicNotFound() {
            when(topicRepository.findById(topicId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> topicService.deleteTopic(topicId, managerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("updateStatus")
    class UpdateStatusTests {

        @Test
        @DisplayName("Should update status to PUBLISHED")
        void shouldUpdateStatusToPublished() {
            when(topicRepository.findById(topicId)).thenReturn(Optional.of(activeTopic));
            when(topicRepository.save(activeTopic)).thenReturn(activeTopic);
            when(topicMapper.toResponse(activeTopic)).thenReturn(topicResponse);
            when(conversationRepository.countByTopicIdAndDeletedAtIsNull(topicId)).thenReturn(0L);

            topicService.updateStatus(topicId, Course.ContentStatus.PUBLISHED, managerId);

            assertThat(activeTopic.getStatus()).isEqualTo(Course.ContentStatus.PUBLISHED);
            assertThat(activeTopic.getUpdatedBy()).isEqualTo(managerId);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getTopic")
    class GetTopicTests {

        @Test
        @DisplayName("Should return topic response when topic is active")
        void shouldReturnTopicWhenActive() {
            when(topicRepository.findById(topicId)).thenReturn(Optional.of(activeTopic));
            when(topicMapper.toResponse(activeTopic)).thenReturn(topicResponse);
            when(conversationRepository.countByTopicIdAndDeletedAtIsNull(topicId)).thenReturn(3L);

            ConversationTopicResponse result = topicService.getTopic(topicId);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(topicId);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when topic is soft-deleted")
        void shouldThrowWhenTopicSoftDeleted() {
            when(topicRepository.findById(deletedTopic.getId()))
                    .thenReturn(Optional.of(deletedTopic));

            assertThatThrownBy(() -> topicService.getTopic(deletedTopic.getId()))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("ConversationTopic");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getAllTopics / searchTopics")
    class GetAllTopicsTests {

        @Test
        @DisplayName("Should return paginated topics from repository")
        void shouldReturnPaginatedTopics() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<ConversationTopic> page = new PageImpl<>(List.of(activeTopic), pageable, 1);
            when(topicRepository.findByDeletedAtIsNull(pageable)).thenReturn(page);
            when(topicMapper.toSummaryResponse(activeTopic)).thenReturn(summaryResponse);
            when(conversationRepository.countByTopicIdAndDeletedAtIsNull(topicId)).thenReturn(2L);

            Page<ConversationTopicSummaryResponse> result = topicService.getAllTopics(pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
            verify(topicRepository).findByDeletedAtIsNull(pageable);
        }

        @Test
        @DisplayName("Should delegate keyword search to repository")
        void shouldDelegateKeywordSearch() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<ConversationTopic> page = new PageImpl<>(List.of(activeTopic), pageable, 1);
            when(topicRepository.searchByKeyword("shop", pageable)).thenReturn(page);
            when(topicMapper.toSummaryResponse(activeTopic)).thenReturn(summaryResponse);
            when(conversationRepository.countByTopicIdAndDeletedAtIsNull(topicId)).thenReturn(0L);

            Page<ConversationTopicSummaryResponse> result = topicService.searchTopics("shop", pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
            verify(topicRepository).searchByKeyword("shop", pageable);
        }
    }
}
