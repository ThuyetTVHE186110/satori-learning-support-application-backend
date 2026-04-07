package com.fu.achievement.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_stats", schema = "gamification", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id"})
})
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UserStats {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;

    @Column(name = "user_id", nullable = false)
    UUID userId;

    // Points
    @Column(name = "total_exp")
    @Builder.Default
    Integer totalExp = 0;

    @Column(name = "current_level")
    @Builder.Default
    Integer currentLevel = 1;

    @Column(name = "exp_to_next_level")
    @Builder.Default
    Integer expToNextLevel = 100;

    // Currency
    @Column(name = "coins")
    @Builder.Default
    Integer coins = 0;

    // Streaks
    @Column(name = "current_streak")
    @Builder.Default
    Integer currentStreak = 0;

    @Column(name = "longest_streak")
    @Builder.Default
    Integer longestStreak = 0;

    @Column(name = "streak_last_date")
    LocalDate streakLastDate;

    @Column(name = "streak_freeze_count")
    @Builder.Default
    Integer streakFreezeCount = 0;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    LocalDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    LocalDateTime updatedAt;
}
