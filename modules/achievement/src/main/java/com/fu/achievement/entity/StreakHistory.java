package com.fu.achievement.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "streak_history", schema = "gamification", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "streak_date"})
})
@FieldDefaults(level = AccessLevel.PRIVATE)
public class StreakHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;

    @Column(name = "user_id", nullable = false)
    UUID userId;

    @Column(name = "streak_date", nullable = false)
    LocalDate streakDate;

    @Column(name = "streak_count", nullable = false)
    Integer streakCount;

    @Column(name = "activity_type")
    String activityType;

    @Column(name = "exp_earned")
    @Builder.Default
    Integer expEarned = 0;

    @Column(name = "freeze_used")
    @Builder.Default
    Boolean freezeUsed = false;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    LocalDateTime createdAt;
}
