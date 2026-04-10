package com.fu.analytics.insight;

import com.fu.infrastructure.learner.LearningInsightPoint;
import com.fu.infrastructure.learner.SkillScore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Generates actionable Vietnamese recommendations based on current weak/strong points
 * and skill scores. These are displayed to the learner in the insights dashboard.
 */
@Slf4j
@Service
public class InsightRecommendationService {

    public List<String> generateRecommendations(List<LearningInsightPoint> weakPoints,
                                                  List<LearningInsightPoint> strongPoints,
                                                  List<SkillScore> skillScores) {
        List<String> recommendations = new ArrayList<>();

        if (skillScores != null) {
            skillScores.stream()
                    .filter(s -> s.dataPoints() > 0 && s.score() < 50)
                    .sorted(Comparator.comparingDouble(SkillScore::score))
                    .limit(2)
                    .forEach(s -> recommendations.add(buildSkillRecommendation(s)));
        }

        if (weakPoints != null) {
            weakPoints.stream()
                    .sorted(Comparator.comparingDouble(LearningInsightPoint::confidence).reversed())
                    .limit(2)
                    .forEach(wp -> {
                        if (recommendations.size() < 5) {
                            recommendations.add(buildWeakPointRecommendation(wp));
                        }
                    });
        }

        if (strongPoints != null && !strongPoints.isEmpty()) {
            long masteredCount = strongPoints.stream()
                    .filter(sp -> sp.confidence() > 0.8).count();
            if (masteredCount > 0 && recommendations.size() < 5) {
                recommendations.add(String.format(
                        "Bạn đã thành thạo %d mục — tiếp tục phát huy!", masteredCount));
            }
        }

        if (skillScores != null) {
            skillScores.stream()
                    .filter(s -> "UP".equals(s.trend()))
                    .findFirst()
                    .ifPresent(s -> {
                        if (recommendations.size() < 5) {
                            recommendations.add(String.format(
                                    "Kỹ năng %s đang tiến bộ (+%.0f điểm) — giữ đà nhé!",
                                    translateSkill(s.skill()), s.score() - s.previousScore()));
                        }
                    });
        }

        return recommendations;
    }

    private String buildSkillRecommendation(SkillScore skill) {
        String name = translateSkill(skill.skill());
        if (skill.score() < 30) {
            return String.format("Kỹ năng %s còn yếu (%.0f/100) — hãy luyện tập thêm mỗi ngày!", name, skill.score());
        }
        return String.format("Kỹ năng %s cần cải thiện (%.0f/100) — thử luyện thêm bài tập nhé!", name, skill.score());
    }

    private String buildWeakPointRecommendation(LearningInsightPoint wp) {
        return switch (wp.category()) {
            case "VOCABULARY" -> "Ôn lại từ vựng — " + wp.description();
            case "GRAMMAR" -> "Luyện ngữ pháp — " + wp.description();
            case "PRONUNCIATION" -> "Cải thiện phát âm — " + wp.description();
            case "FLUENCY" -> "Tập nói lưu loát hơn — " + wp.description();
            default -> "Cần chú ý — " + wp.description();
        };
    }

    private String translateSkill(String skill) {
        return switch (skill) {
            case "VOCABULARY" -> "Từ vựng";
            case "GRAMMAR" -> "Ngữ pháp";
            case "PRONUNCIATION" -> "Phát âm";
            case "FLUENCY" -> "Lưu loát";
            case "SPEAKING" -> "Hội thoại";
            default -> skill;
        };
    }
}
