package com.fu.monitoring.service.impl;

import com.fu.conversation.repository.ConversationRepository;
import com.fu.conversation.repository.ConversationTopicRepository;
import com.fu.course.entity.Course;
import com.fu.course.entity.Lesson;
import com.fu.course.repository.GrammarPointRepository;
import com.fu.course.repository.LessonRepository;
import com.fu.course.repository.VocabularyRepository;
import com.fu.monitoring.dto.response.ContentImprovementItem;
import com.fu.monitoring.dto.response.ContentImprovementSection;
import com.fu.monitoring.dto.response.ContentManagerDashboardResponse;
import com.fu.monitoring.dto.response.DailyPracticeMetric;
import com.fu.monitoring.dto.response.MostPracticedContentSection;
import com.fu.monitoring.dto.response.PracticeTrendSection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("ContentManagerDashboardService Tests")
class ContentManagerDashboardServiceImplTest {

    private ContentManagerDashboardServiceImpl service;
    private ConversationRepository conversationRepository;
    private ConversationTopicRepository conversationTopicRepository;
    private LessonRepository lessonRepository;
    private VocabularyRepository vocabularyRepository;
    private GrammarPointRepository grammarPointRepository;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        conversationTopicRepository = mock(ConversationTopicRepository.class);
        lessonRepository = mock(LessonRepository.class);
        vocabularyRepository = mock(VocabularyRepository.class);
        grammarPointRepository = mock(GrammarPointRepository.class);

        service = new ContentManagerDashboardServiceImpl(
                lessonRepository,
                vocabularyRepository,
                grammarPointRepository,
                conversationRepository,
                conversationTopicRepository
        );
    }

    @Test
    @DisplayName("getPracticeTrend - Should fill 30 days with 0 count for missing dates")
    void getPracticeTrend_shouldFillAllDaysWithZeroCount() {
        // Arrange
        LocalDate today = LocalDate.now();
        LocalDate nineAgoDate = today.minusDays(9);
        LocalDate tenAgoDate = today.minusDays(10);

        List<Object[]> mockData = new ArrayList<>();
        mockData.add(new Object[]{Date.valueOf(tenAgoDate), 9L});
        mockData.add(new Object[]{Date.valueOf(nineAgoDate), 4L});

        when(conversationRepository.getPracticeTrendLast30Days()).thenReturn(mockData);

        // Act
        PracticeTrendSection result = service.getPracticeTrend();

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getDailyMetrics()).hasSize(30);

        // Check first date (30 days ago)
        assertThat(result.getDailyMetrics().get(0).getPracticeDate()).isEqualTo(today.minusDays(29));
        assertThat(result.getDailyMetrics().get(0).getPracticeCount()).isEqualTo(0L);

        // Check today
        assertThat(result.getDailyMetrics().get(29).getPracticeDate()).isEqualTo(today);
        assertThat(result.getDailyMetrics().get(29).getPracticeCount()).isEqualTo(0L);

        // Check existing data points
        DailyPracticeMetric nineAgoMetric = result.getDailyMetrics().stream()
                .filter(m -> m.getPracticeDate().equals(nineAgoDate))
                .findFirst()
                .orElse(null);
        assertThat(nineAgoMetric).isNotNull();
        assertThat(nineAgoMetric.getPracticeCount()).isEqualTo(4L);

        verify(conversationRepository, times(1)).getPracticeTrendLast30Days();
    }

    @Test
    @DisplayName("getPracticeTrend - Should return complete date range when all days have data")
    void getPracticeTrend_shouldReturnCompleteRangeWithAllData() {
        // Arrange
        LocalDate today = LocalDate.now();
        List<Object[]> mockData = new ArrayList<>();

        for (int i = 29; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            long count = (long) ((i + 1) % 10); // Various counts
            mockData.add(new Object[]{Date.valueOf(date), count});
        }

        when(conversationRepository.getPracticeTrendLast30Days()).thenReturn(mockData);

        // Act
        PracticeTrendSection result = service.getPracticeTrend();

        // Assert
        assertThat(result.getDailyMetrics()).hasSize(30);
        assertThat(result.getDailyMetrics()).allMatch(m -> m.getPracticeDate() != null);
        assertThat(result.getDailyMetrics()).allMatch(m -> m.getPracticeCount() >= 0);

        verify(conversationRepository, times(1)).getPracticeTrendLast30Days();
    }

    @Test
    @DisplayName("getContentNeedingImprovement - Should return conversations with low scores")
    void getContentNeedingImprovement_shouldReturnConversationsWithLowScores() {
        // Arrange
        UUID conversationId = UUID.randomUUID();
        double threshold = 7.0;
        int limit = 10;

        List<Object[]> mockData = new ArrayList<>();
        // [id, title, topicTitle, jlptLevel, difficultyLevel, avgScore]
        mockData.add(new Object[]{
                conversationId,
                "Hỏi lịch hẹn mua hàng",
                "Tiếng Nhật N5 cơ bản",
                "N5",
                3,
                BigDecimal.valueOf(4.5)
        });

        when(conversationRepository.getConversationsNeedingImprovement(threshold, limit))
                .thenReturn(mockData);

        // Act
        ContentImprovementSection result = service.getContentNeedingImprovement(threshold, limit);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getItems()).hasSize(1);

        ContentImprovementItem item = result.getItems().get(0);
        assertThat(item.getId()).isEqualTo(conversationId);
        assertThat(item.getContentName()).isEqualTo("Hỏi lịch hẹn mua hàng");
        assertThat(item.getCourseName()).isEqualTo("Tiếng Nhật N5 cơ bản");
        assertThat(item.getJlptLevel()).isEqualTo(Course.JlptLevel.N5);
        assertThat(item.getDifficultyLevel()).isEqualTo(3);
        assertThat(item.getAvgScore()).isEqualTo(4.5);

        verify(conversationRepository, times(1)).getConversationsNeedingImprovement(threshold, limit);
    }

    @Test
    @DisplayName("getContentNeedingImprovement - Should handle empty results")
    void getContentNeedingImprovement_shouldHandleEmptyResults() {
        // Arrange
        when(conversationRepository.getConversationsNeedingImprovement(7.0, 10))
                .thenReturn(new ArrayList<>());

        // Act
        ContentImprovementSection result = service.getContentNeedingImprovement(7.0, 10);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getItems()).isEmpty();

        verify(conversationRepository, times(1)).getConversationsNeedingImprovement(7.0, 10);
    }

    @Test
    @DisplayName("getMostPracticedContent - Should return top topics and conversations")
    void getMostPracticedContent_shouldReturnTopTopicsAndConversations() {
        // Arrange
        int topN = 5;

        List<Object[]> topicsMockData = new ArrayList<>();
        topicsMockData.add(new Object[]{UUID.randomUUID(), "Tiếng Anh Du Lịch", 2400L});
        topicsMockData.add(new Object[]{UUID.randomUUID(), "Tiếng Anh Kinh Tế", 1900L});

        List<Object[]> conversationsMockData = new ArrayList<>();
        conversationsMockData.add(new Object[]{UUID.randomUUID(), "Phoning văn xn...", 1800L});
        conversationsMockData.add(new Object[]{UUID.randomUUID(), "Đặt mớ cơ m...", 1400L});

        when(conversationRepository.getTopPracticedConversationTopics(topN))
                .thenReturn(topicsMockData);
        when(conversationRepository.getTopPracticedConversations(topN))
                .thenReturn(conversationsMockData);

        // Act
        MostPracticedContentSection result = service.getMostPracticedContent(topN);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getTopTopics()).hasSize(2);
        assertThat(result.getTopConversations()).hasSize(2);

        assertThat(result.getTopTopics().get(0).getTitle()).isEqualTo("Tiếng Anh Du Lịch");
        assertThat(result.getTopTopics().get(0).getPracticeCount()).isEqualTo(2400L);

        assertThat(result.getTopConversations().get(0).getTitle()).isEqualTo("Phoning văn xn...");
        assertThat(result.getTopConversations().get(0).getPracticeCount()).isEqualTo(1800L);

        verify(conversationRepository, times(1)).getTopPracticedConversationTopics(topN);
        verify(conversationRepository, times(1)).getTopPracticedConversations(topN);
    }

    @Test
    @DisplayName("getDashboard - Should include speaking sessions in performance metrics")
    void getDashboard_shouldIncludeSpeakingSessionsMetric() {
        // Arrange - Mock all repository methods
        when(lessonRepository.countByStatusAndDeletedAtIsNull(Lesson.ContentStatus.PUBLISHED)).thenReturn(8L);
        when(lessonRepository.countByStatusAndDeletedAtIsNull(Lesson.ContentStatus.DRAFT)).thenReturn(2L);
        when(lessonRepository.countByStatusAndDeletedAtIsNull(Lesson.ContentStatus.ARCHIVED)).thenReturn(1L);
        when(vocabularyRepository.countByDeletedAtIsNull()).thenReturn(50L);
        when(grammarPointRepository.countByDeletedAtIsNull()).thenReturn(30L);
        when(conversationRepository.countByDeletedAtIsNull()).thenReturn(5L);
        when(conversationTopicRepository.countByDeletedAtIsNull()).thenReturn(3L);

        when(conversationRepository.getTotalCompletedSpeakingSessions()).thenReturn(42L);
        when(conversationRepository.getAverageOverallScore()).thenReturn(7.5);
        when(conversationRepository.getAveragePracticeDuration()).thenReturn(1.2);

        when(conversationTopicRepository.countByStatusAndDeletedAtIsNull("PUBLISHED")).thenReturn(2L);
        when(conversationTopicRepository.countByStatusAndDeletedAtIsNull("DRAFT")).thenReturn(1L);
        when(conversationTopicRepository.countByStatusAndDeletedAtIsNull("ARCHIVED")).thenReturn(0L);
        when(conversationRepository.countByStatusAndDeletedAtIsNull("PUBLISHED")).thenReturn(3L);
        when(conversationRepository.countByStatusAndDeletedAtIsNull("DRAFT")).thenReturn(2L);
        when(conversationRepository.countByStatusAndDeletedAtIsNull("ARCHIVED")).thenReturn(1L);

        when(conversationRepository.getTopPracticedConversationTopics(5)).thenReturn(new ArrayList<>());
        when(conversationRepository.getTopPracticedConversations(5)).thenReturn(new ArrayList<>());
        when(conversationRepository.getPracticeTrendLast30Days()).thenReturn(new ArrayList<>());
        when(conversationRepository.getConversationsNeedingImprovement(7.0, 10)).thenReturn(new ArrayList<>());

        // Act
        ContentManagerDashboardResponse result = service.getDashboard();

        // Assert - Check performance metrics has speaking sessions (not lessons count)
        assertThat(result.getPerformance()).isNotNull();
        assertThat(result.getPerformance().getTotalSpeakingSessions()).isNotNull();
        assertThat(result.getPerformance().getTotalSpeakingSessions().getLabel())
                .isEqualTo("Tổng lượt luyện tập speaking");
        assertThat(result.getPerformance().getTotalSpeakingSessions().getValue())
                .isEqualTo("42");
        assertThat(result.getPerformance().getTotalSpeakingSessions().getUnit())
                .isEqualTo("lượt");

        // Also verify other metrics are present
        assertThat(result.getPerformance().getAverageSystemScore()).isNotNull();
        assertThat(result.getPerformance().getAveragePracticeSessionDuration()).isNotNull();

        verify(conversationRepository, times(1)).getTotalCompletedSpeakingSessions();
        verify(conversationRepository, times(1)).getAverageOverallScore();
        verify(conversationRepository, times(1)).getAveragePracticeDuration();
    }

    @Test
    @DisplayName("getDashboard - Should assemble all sections without errors")
    void getDashboard_shouldAssembleAllSectionsWithoutErrors() {
        // Arrange - Mock all repository methods for Summary
        when(lessonRepository.countByStatusAndDeletedAtIsNull(Lesson.ContentStatus.PUBLISHED)).thenReturn(8L);
        when(lessonRepository.countByStatusAndDeletedAtIsNull(Lesson.ContentStatus.DRAFT)).thenReturn(2L);
        when(lessonRepository.countByStatusAndDeletedAtIsNull(Lesson.ContentStatus.ARCHIVED)).thenReturn(1L);
        when(vocabularyRepository.countByDeletedAtIsNull()).thenReturn(50L);
        when(grammarPointRepository.countByDeletedAtIsNull()).thenReturn(30L);
        when(conversationRepository.countByDeletedAtIsNull()).thenReturn(5L);
        when(conversationTopicRepository.countByDeletedAtIsNull()).thenReturn(3L);

        // Mock status distributions
        when(conversationTopicRepository.countByStatusAndDeletedAtIsNull("PUBLISHED")).thenReturn(2L);
        when(conversationTopicRepository.countByStatusAndDeletedAtIsNull("DRAFT")).thenReturn(1L);
        when(conversationTopicRepository.countByStatusAndDeletedAtIsNull("ARCHIVED")).thenReturn(0L);
        when(conversationRepository.countByStatusAndDeletedAtIsNull("PUBLISHED")).thenReturn(3L);
        when(conversationRepository.countByStatusAndDeletedAtIsNull("DRAFT")).thenReturn(2L);
        when(conversationRepository.countByStatusAndDeletedAtIsNull("ARCHIVED")).thenReturn(1L);

        // Mock performance metrics
        when(conversationRepository.getTotalCompletedSpeakingSessions()).thenReturn(42L);
        when(conversationRepository.getAverageOverallScore()).thenReturn(7.5);
        when(conversationRepository.getAveragePracticeDuration()).thenReturn(1.2);

        // Mock most practiced content
        when(conversationRepository.getTopPracticedConversationTopics(5)).thenReturn(new ArrayList<>());
        when(conversationRepository.getTopPracticedConversations(5)).thenReturn(new ArrayList<>());

        // Mock practice trend and improvements
        when(conversationRepository.getPracticeTrendLast30Days()).thenReturn(new ArrayList<>());
        when(conversationRepository.getConversationsNeedingImprovement(7.0, 10)).thenReturn(new ArrayList<>());

        // Act
        ContentManagerDashboardResponse result = service.getDashboard();

        // Assert - All sections should be present
        assertThat(result).isNotNull();
        assertThat(result.getSummary()).isNotNull();
        assertThat(result.getStatusDistributions()).isNotNull();
        assertThat(result.getPerformance()).isNotNull();
        assertThat(result.getMostPracticedContent()).isNotNull();
        assertThat(result.getPracticeTrend()).isNotNull();
        assertThat(result.getContentNeedsImprovement()).isNotNull();

        // Verify summary section
        assertThat(result.getSummary().getTotalLessons()).isEqualTo(10);
        assertThat(result.getSummary().getTotalVocabulary()).isEqualTo(50);
        assertThat(result.getSummary().getTotalGrammarPoints()).isEqualTo(30);
        assertThat(result.getSummary().getTotalConversations()).isEqualTo(5);
        assertThat(result.getSummary().getTotalTopics()).isEqualTo(3);
    }
}
