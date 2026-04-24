package com.fu.conversation.service;

import com.fu.conversation.ai.ConversationGenerationService;
import com.fu.conversation.entity.Conversation;
import com.fu.conversation.mapper.ConversationMapper;
import com.fu.conversation.mapper.ConversationMissionMapper;
import com.fu.conversation.repository.ConversationMissionRepository;
import com.fu.conversation.repository.ConversationRepository;
import com.fu.conversation.repository.ConversationTopicRepository;
import com.fu.conversation.service.impl.ConversationServiceImpl;
import com.fu.course.entity.Course;
import com.fu.course.repository.CourseRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationServiceImpl — getConversationsByTopic filter routing tests")
class ConversationServiceImplTopicFilterTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private ConversationMapper conversationMapper;
    @Mock private ConversationTopicRepository topicRepository;
    @Mock private CourseRepository courseRepository;
    @Mock private ConversationMissionRepository missionRepository;
    @Mock private ConversationMissionMapper missionMapper;
    @Mock private ConversationGenerationService generationService;

    @InjectMocks
    private ConversationServiceImpl conversationService;

    /** A simple unsorted pageable used as the incoming request pageable in most tests. */
    private Pageable pageable;

    /** An empty page returned by the mocked repository — avoids NPE in the mapper step. */
    private Page<Conversation> emptyPage;

    /** A stable topicId shared across all tests. */
    private UUID topicId;

    @BeforeEach
    void setUp() {
        pageable  = PageRequest.of(0, 10);
        emptyPage = Page.empty(pageable);
        topicId   = UUID.randomUUID();
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Filter routing — correct repository method is called")
    class RoutingTests {

        @Test
        @DisplayName("5.1 Level + status → findByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNull")
        void levelAndStatus_routesToCorrectMethod() {
            // Requirements: 7.1, 1.2, 3.1
            when(conversationRepository.findByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNull(
                    eq(topicId), eq("N3"), eq("PUBLISHED"), any(Pageable.class)))
                    .thenReturn(emptyPage);

            conversationService.getConversationsByTopic(
                    topicId, Course.JlptLevel.N3, Course.ContentStatus.PUBLISHED, pageable);

            verify(conversationRepository, times(1))
                    .findByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNull(
                            eq(topicId), eq("N3"), eq("PUBLISHED"), any(Pageable.class));

            // Assert no other filter method is called
            verify(conversationRepository, never())
                    .findByTopicIdAndJlptLevelAndDeletedAtIsNull(any(), any(), any());
            verify(conversationRepository, never())
                    .findByTopicIdAndStatusAndDeletedAtIsNull(any(), any(), any());
            verify(conversationRepository, never())
                    .findByTopicIdAndDeletedAtIsNull(any(UUID.class), any(Pageable.class));
        }

        @Test
        @DisplayName("5.2 Level only → findByTopicIdAndJlptLevelAndDeletedAtIsNull")
        void levelOnly_routesToCorrectMethod() {
            // Requirements: 7.2, 1.1
            when(conversationRepository.findByTopicIdAndJlptLevelAndDeletedAtIsNull(
                    eq(topicId), eq("N3"), any(Pageable.class)))
                    .thenReturn(emptyPage);

            conversationService.getConversationsByTopic(
                    topicId, Course.JlptLevel.N3, null, pageable);

            verify(conversationRepository, times(1))
                    .findByTopicIdAndJlptLevelAndDeletedAtIsNull(
                            eq(topicId), eq("N3"), any(Pageable.class));

            // Assert no other filter method is called
            verify(conversationRepository, never())
                    .findByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNull(any(), any(), any(), any());
            verify(conversationRepository, never())
                    .findByTopicIdAndStatusAndDeletedAtIsNull(any(), any(), any());
            verify(conversationRepository, never())
                    .findByTopicIdAndDeletedAtIsNull(any(UUID.class), any(Pageable.class));
        }

        @Test
        @DisplayName("5.3 Status only → findByTopicIdAndStatusAndDeletedAtIsNull")
        void statusOnly_routesToCorrectMethod() {
            // Requirements: 7.3, 2.1
            when(conversationRepository.findByTopicIdAndStatusAndDeletedAtIsNull(
                    eq(topicId), eq("PUBLISHED"), any(Pageable.class)))
                    .thenReturn(emptyPage);

            conversationService.getConversationsByTopic(
                    topicId, null, Course.ContentStatus.PUBLISHED, pageable);

            verify(conversationRepository, times(1))
                    .findByTopicIdAndStatusAndDeletedAtIsNull(
                            eq(topicId), eq("PUBLISHED"), any(Pageable.class));
        }

        @Test
        @DisplayName("5.4 No filters → findByTopicIdAndDeletedAtIsNull")
        void noFilters_routesToCorrectMethod() {
            // Requirements: 7.4, 3.2
            when(conversationRepository.findByTopicIdAndDeletedAtIsNull(
                    eq(topicId), any(Pageable.class)))
                    .thenReturn(emptyPage);

            conversationService.getConversationsByTopic(topicId, null, null, pageable);

            verify(conversationRepository, times(1))
                    .findByTopicIdAndDeletedAtIsNull(eq(topicId), any(Pageable.class));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Sort stripping")
    class SortStrippingTests {

        @Test
        @DisplayName("5.5 Incoming sorted Pageable is replaced with unsorted PageRequest")
        void sortedPageable_isReplacedWithUnsorted() {
            // Requirements: 7.5, 5.4
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            when(conversationRepository.findByTopicIdAndDeletedAtIsNull(
                    eq(topicId), pageableCaptor.capture()))
                    .thenReturn(emptyPage);

            Pageable sortedPageable = PageRequest.of(0, 10, Sort.by("title").descending());
            conversationService.getConversationsByTopic(topicId, null, null, sortedPageable);

            Pageable captured = pageableCaptor.getValue();
            assertThat(captured.getSort().isUnsorted())
                    .as("Sort criteria should be stripped — service always passes unsorted Pageable to repository")
                    .isTrue();
        }
    }
}
