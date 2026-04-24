package com.fu.conversation.service;

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
import com.fu.course.repository.CourseRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationServiceImpl — getAllConversations filter routing tests")
class ConversationServiceImplFilterTest {

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

    @BeforeEach
    void setUp() {
        pageable  = PageRequest.of(0, 10);
        emptyPage = Page.empty(pageable);
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Filter routing — correct repository method is called")
    class RoutingTests {

        @Test
        @DisplayName("2.1 All three filters (keyword + level + status) → searchByKeywordAndJlptLevelAndStatusAndDeletedAtIsNull")
        void allThreeFilters_routesToCorrectMethod() {
            // Requirements: 1.4, 4.1
            when(conversationRepository.searchByKeywordAndJlptLevelAndStatusAndDeletedAtIsNull(
                    eq("会話"), eq("N3"), eq("PUBLISHED"), any(Pageable.class)))
                    .thenReturn(emptyPage);

            conversationService.getAllConversations("会話", Course.JlptLevel.N3, Course.ContentStatus.PUBLISHED, pageable);

            verify(conversationRepository, times(1))
                    .searchByKeywordAndJlptLevelAndStatusAndDeletedAtIsNull(
                            eq("会話"), eq("N3"), eq("PUBLISHED"), any(Pageable.class));

            // Assert no other filter method is called
            verify(conversationRepository, never())
                    .searchByKeywordAndJlptLevelAndDeletedAtIsNull(any(), any(), any());
            verify(conversationRepository, never())
                    .searchByKeywordAndStatusAndDeletedAtIsNull(any(), any(), any());
            verify(conversationRepository, never())
                    .findByJlptLevelAndStatusAndDeletedAtIsNull(any(), any(), any());
            verify(conversationRepository, never())
                    .searchByKeywordAndDeletedAtIsNull(any(), any());
            verify(conversationRepository, never())
                    .findByJlptLevelAndDeletedAtIsNull(any(), any());
            verify(conversationRepository, never())
                    .findByStatusAndDeletedAtIsNull(any(), any());
            verify(conversationRepository, never())
                    .findByDeletedAtIsNull(any(Pageable.class));
        }

        @Test
        @DisplayName("2.2 Keyword + level only → searchByKeywordAndJlptLevelAndDeletedAtIsNull")
        void keywordAndLevel_routesToCorrectMethod() {
            // Requirements: 1.3
            when(conversationRepository.searchByKeywordAndJlptLevelAndDeletedAtIsNull(
                    eq("会話"), eq("N3"), any(Pageable.class)))
                    .thenReturn(emptyPage);

            conversationService.getAllConversations("会話", Course.JlptLevel.N3, null, pageable);

            verify(conversationRepository, times(1))
                    .searchByKeywordAndJlptLevelAndDeletedAtIsNull(
                            eq("会話"), eq("N3"), any(Pageable.class));
        }

        @Test
        @DisplayName("2.3 Keyword + status only → searchByKeywordAndStatusAndDeletedAtIsNull")
        void keywordAndStatus_routesToCorrectMethod() {
            // Requirements: 2.2
            when(conversationRepository.searchByKeywordAndStatusAndDeletedAtIsNull(
                    eq("会話"), eq("PUBLISHED"), any(Pageable.class)))
                    .thenReturn(emptyPage);

            conversationService.getAllConversations("会話", null, Course.ContentStatus.PUBLISHED, pageable);

            verify(conversationRepository, times(1))
                    .searchByKeywordAndStatusAndDeletedAtIsNull(
                            eq("会話"), eq("PUBLISHED"), any(Pageable.class));
        }

        @Test
        @DisplayName("2.4 Level + status only → findByJlptLevelAndStatusAndDeletedAtIsNull")
        void levelAndStatus_routesToCorrectMethod() {
            // Requirements: 1.2
            when(conversationRepository.findByJlptLevelAndStatusAndDeletedAtIsNull(
                    eq("N3"), eq("PUBLISHED"), any(Pageable.class)))
                    .thenReturn(emptyPage);

            conversationService.getAllConversations(null, Course.JlptLevel.N3, Course.ContentStatus.PUBLISHED, pageable);

            verify(conversationRepository, times(1))
                    .findByJlptLevelAndStatusAndDeletedAtIsNull(
                            eq("N3"), eq("PUBLISHED"), any(Pageable.class));
        }

        @Test
        @DisplayName("2.5 Keyword only → searchByKeywordAndDeletedAtIsNull")
        void keywordOnly_routesToCorrectMethod() {
            // Requirements: 3.1
            when(conversationRepository.searchByKeywordAndDeletedAtIsNull(
                    eq("会話"), any(Pageable.class)))
                    .thenReturn(emptyPage);

            conversationService.getAllConversations("会話", null, null, pageable);

            verify(conversationRepository, times(1))
                    .searchByKeywordAndDeletedAtIsNull(eq("会話"), any(Pageable.class));
        }

        @Test
        @DisplayName("2.6 Level only → findByJlptLevelAndDeletedAtIsNull")
        void levelOnly_routesToCorrectMethod() {
            // Requirements: 1.1
            when(conversationRepository.findByJlptLevelAndDeletedAtIsNull(
                    eq("N3"), any(Pageable.class)))
                    .thenReturn(emptyPage);

            conversationService.getAllConversations(null, Course.JlptLevel.N3, null, pageable);

            verify(conversationRepository, times(1))
                    .findByJlptLevelAndDeletedAtIsNull(eq("N3"), any(Pageable.class));
        }

        @Test
        @DisplayName("2.7 Status only → findByStatusAndDeletedAtIsNull")
        void statusOnly_routesToCorrectMethod() {
            // Requirements: 2.1
            when(conversationRepository.findByStatusAndDeletedAtIsNull(
                    eq("PUBLISHED"), any(Pageable.class)))
                    .thenReturn(emptyPage);

            conversationService.getAllConversations(null, null, Course.ContentStatus.PUBLISHED, pageable);

            verify(conversationRepository, times(1))
                    .findByStatusAndDeletedAtIsNull(eq("PUBLISHED"), any(Pageable.class));
        }

        @Test
        @DisplayName("2.8 No filters → findByDeletedAtIsNull")
        void noFilters_routesToCorrectMethod() {
            // Requirements: 4.2
            when(conversationRepository.findByDeletedAtIsNull(any(Pageable.class)))
                    .thenReturn(emptyPage);

            conversationService.getAllConversations(null, null, null, pageable);

            verify(conversationRepository, times(1))
                    .findByDeletedAtIsNull(any(Pageable.class));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Whitespace keyword normalization")
    class WhitespaceKeywordTests {

        @Test
        @DisplayName("2.9 Spaces-only keyword is treated as no keyword → findByDeletedAtIsNull")
        void spacesOnlyKeyword_treatedAsNoKeyword() {
            // Requirements: 3.3
            when(conversationRepository.findByDeletedAtIsNull(any(Pageable.class)))
                    .thenReturn(emptyPage);

            conversationService.getAllConversations("   ", null, null, pageable);

            verify(conversationRepository, times(1))
                    .findByDeletedAtIsNull(any(Pageable.class));
            verify(conversationRepository, never())
                    .searchByKeywordAndDeletedAtIsNull(any(), any());
        }

        @Test
        @DisplayName("2.10 Tab/mixed-whitespace keyword with level is treated as level-only → findByJlptLevelAndDeletedAtIsNull")
        void tabMixedWhitespaceKeywordWithLevel_treatedAsLevelOnly() {
            // Requirements: 3.3
            when(conversationRepository.findByJlptLevelAndDeletedAtIsNull(
                    eq("N3"), any(Pageable.class)))
                    .thenReturn(emptyPage);

            conversationService.getAllConversations("\t \t", Course.JlptLevel.N3, null, pageable);

            verify(conversationRepository, times(1))
                    .findByJlptLevelAndDeletedAtIsNull(eq("N3"), any(Pageable.class));
            verify(conversationRepository, never())
                    .searchByKeywordAndJlptLevelAndDeletedAtIsNull(any(), any(), any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Sort stripping and keyword trimming")
    class SortAndTrimTests {

        @Test
        @DisplayName("2.11 Incoming sorted Pageable is replaced with unsorted PageRequest")
        void sortedPageable_isReplacedWithUnsorted() {
            // Requirements: 4.3
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            when(conversationRepository.findByDeletedAtIsNull(pageableCaptor.capture()))
                    .thenReturn(emptyPage);

            Pageable sortedPageable = PageRequest.of(0, 10, Sort.by("title").descending());
            conversationService.getAllConversations(null, null, null, sortedPageable);

            Pageable captured = pageableCaptor.getValue();
            assertThat(captured.getSort().isUnsorted())
                    .as("Sort criteria should be stripped — service always passes unsorted Pageable to repository")
                    .isTrue();
        }

        @Test
        @DisplayName("2.12 Keyword with surrounding whitespace is trimmed before being passed to repository")
        void keywordWithWhitespace_isTrimmedBeforePassingToRepository() {
            // Requirements: 3.1
            ArgumentCaptor<String> keywordCaptor = ArgumentCaptor.forClass(String.class);
            when(conversationRepository.searchByKeywordAndDeletedAtIsNull(
                    keywordCaptor.capture(), any(Pageable.class)))
                    .thenReturn(emptyPage);

            conversationService.getAllConversations("  会話  ", null, null, pageable);

            assertThat(keywordCaptor.getValue())
                    .as("Keyword should be trimmed before being passed to the repository")
                    .isEqualTo("会話");
        }
    }
}
