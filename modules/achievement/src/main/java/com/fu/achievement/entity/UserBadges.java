package com.fu.achievement.entity;

import com.fu.infrastructure.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Builder
@Entity
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "user_badges",schema = "gamification")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UserBadges {
    @Id
    UUID id;
    @Column(name = "user_id", nullable = false)
    UUID userId;
    @Column(name = "badge_id", nullable = false)
    UUID badgeId;

    @Column(name = "earned_at")
    @CreationTimestamp
    LocalDateTime earedAt;

    @Column(name = "is_featured")
    @Builder.Default
    Boolean isFeatured = false;

}
