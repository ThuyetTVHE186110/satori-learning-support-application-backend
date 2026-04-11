package com.fu.user.entity;

import com.fu.infrastructure.learner.LearningInsightPoint;
import com.fu.infrastructure.learner.SkillScore;
import com.fu.infrastructure.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalTime;
import java.util.List;

/**
 * User learning preferences entity.
 * Maps to user_management.user_learning_preferences table.
 * Created lazily on first profile update; may not exist for all users.
 */
@Entity
@Table(name = "user_learning_preferences", schema = "user_management")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class UserLearningPreferences extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // Learning goals
    @Column(name = "target_jlpt_level", columnDefinition = "jlpt_level")
    @ColumnTransformer(write = "?::jlpt_level")
    private String targetJlptLevel;

    @Column(name = "daily_study_goal_minutes")
    @Builder.Default
    private Integer dailyStudyGoalMinutes = 30;

    @Column(name = "preferred_study_time", length = 20)
    private String preferredStudyTime; // morning | afternoon | evening | night

    // AI practice context
    @Column(name = "preferred_topics", columnDefinition = "text[]")
    private String[] preferredTopics;

    @Column(name = "learning_pace", length = 20)
    @Builder.Default
    private String learningPace = "normal"; // slow | normal | fast

    @Column(name = "preferred_formality", length = 20)
    @Builder.Default
    private String preferredFormality = "polite"; // casual | polite | formal

    @Column(name = "conversation_style", length = 50)
    private String conversationStyle; // practical | creative | business | travel

    // Notification preferences
    @Column(name = "streak_reminder_enabled")
    @Builder.Default
    private Boolean streakReminderEnabled = true;

    @Column(name = "reminder_time")
    private LocalTime reminderTime;

    // AI personalization (JSONB — typed schemas)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "weak_points", columnDefinition = "jsonb")
    private List<LearningInsightPoint> weakPoints;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strong_points", columnDefinition = "jsonb")
    private List<LearningInsightPoint> strongPoints;

    // Satori Pentagon — 5-dimension skill radar (VOCABULARY, GRAMMAR, PRONUNCIATION, FLUENCY, SPEAKING)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_scores", columnDefinition = "jsonb")
    private List<SkillScore> skillScores;
}
