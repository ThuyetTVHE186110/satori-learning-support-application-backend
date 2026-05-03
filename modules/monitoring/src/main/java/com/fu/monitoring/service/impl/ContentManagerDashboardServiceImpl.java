package com.fu.monitoring.service.impl;

import com.fu.conversation.repository.ConversationRepository;
import com.fu.conversation.repository.ConversationTopicRepository;
import com.fu.course.entity.Course;
import com.fu.course.entity.Lesson;
import com.fu.course.repository.GrammarPointRepository;
import com.fu.course.repository.LessonRepository;
import com.fu.course.repository.VocabularyRepository;
import com.fu.monitoring.dto.response.ContentManagerDashboardResponse;
import com.fu.monitoring.dto.response.ContentManagerDashboardResponse.PerformanceMetric;
import com.fu.monitoring.dto.response.ContentManagerDashboardResponse.PerformanceMetricsSection;
import com.fu.monitoring.dto.response.ContentManagerDashboardResponse.StatusDistributionChart;
import com.fu.monitoring.dto.response.ContentManagerDashboardResponse.SummarySection;
import com.fu.monitoring.dto.response.ContentImprovementItem;
import com.fu.monitoring.dto.response.ContentImprovementSection;
import com.fu.monitoring.dto.response.ContentMetric;
import com.fu.monitoring.dto.response.DailyPracticeMetric;
import com.fu.monitoring.dto.response.MostPracticedContentSection;
import com.fu.monitoring.dto.response.PracticeTrendSection;
import com.fu.monitoring.service.ContentManagerDashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;
import java.sql.Date;

@Service
@Slf4j
@RequiredArgsConstructor
public class ContentManagerDashboardServiceImpl implements ContentManagerDashboardService {

    private final LessonRepository lessonRepository;
    private final VocabularyRepository vocabularyRepository;
    private final GrammarPointRepository grammarPointRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationTopicRepository conversationTopicRepository;

    @Override
    @Transactional(readOnly = true)
    public ContentManagerDashboardResponse getDashboard() {
        try {
            SummarySection summary = getSummarySection();
            List<StatusDistributionChart> statusDistributions = getStatusDistributions();
            PerformanceMetricsSection performance = getPerformanceMetrics();
            MostPracticedContentSection mostPracticedContent = getMostPracticedContent(5);
            PracticeTrendSection practiceTrend = getPracticeTrend();
            ContentImprovementSection contentNeedsImprovement = getContentNeedingImprovement(7.0, 10);

            return ContentManagerDashboardResponse.builder()
                    .summary(summary)
                    .statusDistributions(statusDistributions)
                    .performance(performance)
                    .mostPracticedContent(mostPracticedContent)
                    .practiceTrend(practiceTrend)
                    .contentNeedsImprovement(contentNeedsImprovement)
                    .build();
        } catch (Exception e) {
            log.error("Error generating content manager dashboard", e);
            throw new RuntimeException("Failed to generate dashboard", e);
        }
    }

    private SummarySection getSummarySection() {
        long totalLessons = lessonRepository.countByStatusAndDeletedAtIsNull(Lesson.ContentStatus.PUBLISHED) +
                lessonRepository.countByStatusAndDeletedAtIsNull(Lesson.ContentStatus.DRAFT);
        long totalVocabulary = vocabularyRepository.countByDeletedAtIsNull();
        long totalGrammarPoints = grammarPointRepository.countByDeletedAtIsNull();
        long totalConversations = conversationRepository.countByDeletedAtIsNull();
        long totalTopics = conversationTopicRepository.countByDeletedAtIsNull();

        return SummarySection.builder()
                .totalLessons(totalLessons)
                .totalVocabulary(totalVocabulary)
                .totalGrammarPoints(totalGrammarPoints)
                .totalConversations(totalConversations)
                .totalTopics(totalTopics)
                .build();
    }

    private List<StatusDistributionChart> getStatusDistributions() {
        List<StatusDistributionChart> distributions = new ArrayList<>();

        // Lesson Status Distribution
        StatusDistributionChart lessonStatus = getStatusDistributionForLessons();
        distributions.add(lessonStatus);

        // Conversation Topic Status Distribution
        StatusDistributionChart topicStatus = getStatusDistributionForTopics();
        distributions.add(topicStatus);

        // Overall Content Status Distribution (Aggregate)
        StatusDistributionChart overallStatus = getOverallStatusDistribution();
        distributions.add(overallStatus);

        return distributions;
    }

    private StatusDistributionChart getStatusDistributionForLessons() {
        long published = lessonRepository.countByStatusAndDeletedAtIsNull(Lesson.ContentStatus.PUBLISHED);
        long draft = lessonRepository.countByStatusAndDeletedAtIsNull(Lesson.ContentStatus.DRAFT);
        long archived = lessonRepository.countByStatusAndDeletedAtIsNull(Lesson.ContentStatus.ARCHIVED);
        long total = published + draft + archived;

        return StatusDistributionChart.builder()
                .title("Trạng thái bài học")
                .total(total)
                .published(published)
                .archived(archived)
                .draft(draft)
                .build();
    }

    private StatusDistributionChart getStatusDistributionForTopics() {
        long published = conversationTopicRepository.countByStatusAndDeletedAtIsNull(Course.ContentStatus.PUBLISHED.name());
        long draft = conversationTopicRepository.countByStatusAndDeletedAtIsNull(Course.ContentStatus.DRAFT.name());
        long archived = conversationTopicRepository.countByStatusAndDeletedAtIsNull(Course.ContentStatus.ARCHIVED.name());
        long total = published + draft + archived;

        return StatusDistributionChart.builder()
                .title("Trạng thái chủ đề hội thoại")
                .total(total)
                .published(published)
                .archived(archived)
                .draft(draft)
                .build();
    }

    private StatusDistributionChart getOverallStatusDistribution() {
        // Aggregate status from conversations (roleplay scenarios)
        long conversationPublished = conversationRepository.countByStatusAndDeletedAtIsNull(Course.ContentStatus.PUBLISHED.name());
        long conversationDraft = conversationRepository.countByStatusAndDeletedAtIsNull(Course.ContentStatus.DRAFT.name());
        long conversationArchived = conversationRepository.countByStatusAndDeletedAtIsNull(Course.ContentStatus.ARCHIVED.name());

        long total = conversationPublished + conversationDraft + conversationArchived;

        return StatusDistributionChart.builder()
                .title("Trạng thái nội dung hội thoại")
                .total(total)
                .published(conversationPublished)
                .archived(conversationArchived)
                .draft(conversationDraft)
                .build();
    }

    private PerformanceMetricsSection getPerformanceMetrics() {
        PerformanceMetric totalSpeakingSessions = PerformanceMetric.builder()
                .label("Tổng lượt luyện tập speaking")
                .value(String.valueOf(conversationRepository.getTotalCompletedSpeakingSessions()))
                .unit("lượt")
                .build();

        PerformanceMetric averageSystemScore = getAverageSystemScore();

        PerformanceMetric averagePracticeDuration = getAveragePracticeDuration();

        return PerformanceMetricsSection.builder()
                .totalSpeakingSessions(totalSpeakingSessions)
                .averageSystemScore(averageSystemScore)
                .averagePracticeSessionDuration(averagePracticeDuration)
                .build();
    }

    private PerformanceMetric getAverageSystemScore() {
        // Get average overall_score from user_conversation_progress
        Double avgScore = conversationRepository.getAverageOverallScore();
        String scoreValue = avgScore != null ? String.format("%.1f", avgScore) : "0.0";

        return PerformanceMetric.builder()
                .label("Điểm số trung bình hệ thống")
                .value(scoreValue)
                .unit("/100")
                .build();
    }

    private PerformanceMetric getAveragePracticeDuration() {
        // Get average duration from user_conversation_progress
        Double avgDuration = conversationRepository.getAveragePracticeDuration();
        String durationValue = avgDuration != null ? String.format("%.1f", avgDuration) : "0.0";

        return PerformanceMetric.builder()
                .label("Thời gian luyện tập trung bình")
                .value(durationValue)
                .unit("giờ")
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public MostPracticedContentSection getMostPracticedContent(int topN) {
        List<Object[]> topTopicsData = conversationRepository.getTopPracticedConversationTopics(topN);
        List<Object[]> topConversationsData = conversationRepository.getTopPracticedConversations(topN);

        List<ContentMetric> topTopics = new ArrayList<>();
        for (Object[] row : topTopicsData) {
            topTopics.add(ContentMetric.builder()
                    .id((UUID) row[0])
                    .title((String) row[1])
                    .practiceCount(((Number) row[2]).longValue())
                    .build());
        }

        List<ContentMetric> topConversations = new ArrayList<>();
        for (Object[] row : topConversationsData) {
            topConversations.add(ContentMetric.builder()
                    .id((UUID) row[0])
                    .title((String) row[1])
                    .practiceCount(((Number) row[2]).longValue())
                    .build());
        }

        return MostPracticedContentSection.builder()
                .topTopics(topTopics)
                .topConversations(topConversations)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PracticeTrendSection getPracticeTrend() {
        List<Object[]> trendData = conversationRepository.getPracticeTrendLast30Days();
        List<DailyPracticeMetric> dailyMetrics = new ArrayList<>();

        // Create map of existing data by date
        Map<LocalDate, Long> dataMap = new HashMap<>();
        for (Object[] row : trendData) {
            LocalDate date = ((Date) row[0]).toLocalDate();
            long count = ((Number) row[1]).longValue();
            dataMap.put(date, count);
        }

        // Generate all 30 dates and fill with 0 if missing
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(29);

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            long count = dataMap.getOrDefault(date, 0L);
            dailyMetrics.add(DailyPracticeMetric.builder()
                    .practiceDate(date)
                    .practiceCount(count)
                    .build());
        }

        return PracticeTrendSection.builder()
                .dailyMetrics(dailyMetrics)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public ContentImprovementSection getContentNeedingImprovement(double threshold, int limit) {
        List<Object[]> improvementData = conversationRepository.getConversationsNeedingImprovement(threshold, limit);
        List<ContentImprovementItem> items = new ArrayList<>();

        for (Object[] row : improvementData) {
            BigDecimal scoreDecimal = (BigDecimal) row[5];
            Double avgScore = scoreDecimal != null ? scoreDecimal.doubleValue() : null;

            items.add(ContentImprovementItem.builder()
                    .id((UUID) row[0])
                    .contentName((String) row[1])
                    .courseName((String) row[2])
                    .jlptLevel(Course.JlptLevel.valueOf((String) row[3]))
                    .difficultyLevel(((Number) row[4]).intValue())
                    .avgScore(avgScore)
                    .build());
        }

        return ContentImprovementSection.builder()
                .items(items)
                .build();
    }
}

