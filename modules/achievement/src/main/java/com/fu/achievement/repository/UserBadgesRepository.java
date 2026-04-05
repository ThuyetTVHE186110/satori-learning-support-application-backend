package com.fu.achievement.repository;

import com.fu.achievement.entity.UserBadges;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserBadgesRepository extends JpaRepository<UserBadges, UUID> {
    
    long countByBadgeId(UUID badgeId);
    
    /**
     * Find all badges earned by a user
     */
    List<UserBadges> findByUserId(UUID userId);
    
    /**
     * Check if user has earned a specific badge
     */
    boolean existsByUserIdAndBadgeId(UUID userId, UUID badgeId);
    
    /**
     * Count total badges earned by user
     */
    long countByUserId(UUID userId);
    
    /**
     * Find badges earned by user in the last N hours
     */
    @Query("SELECT ub FROM UserBadges ub WHERE ub.userId = :userId AND ub.earedAt >= :since ORDER BY ub.earedAt DESC")
    List<UserBadges> findRecentBadges(UUID userId, LocalDateTime since);
}
