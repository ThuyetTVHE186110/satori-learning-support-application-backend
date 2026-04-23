package com.fu.achievement.repository;

import com.fu.achievement.dto.response.BadgeSummaryResponse;
import com.fu.achievement.entity.Badge;
import com.fu.achievement.enums.BadgeStatus;
import com.fu.achievement.enums.BadgeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
@Repository
public interface BadgeRepository extends JpaRepository<Badge, UUID> {

    @Query("SELECT new com.fu.achievement.dto.response.BadgeSummaryResponse(" +
           "b.id, b.name, b.description, b.badgeType, " +
           "b.expReward, b.requirementValue, b.iconUrl, b.status, b.createdAt, COUNT(ub.id)) " +
           "FROM Badge b LEFT JOIN UserBadges ub ON b.id = ub.badgeId " +
           "WHERE (cast(:keyword as string) IS NULL OR LOWER(b.name) LIKE LOWER(CONCAT('%', cast(:keyword as string), '%'))) " +
           "AND (cast(:status as string) IS NULL OR b.status = :status) " +
           "AND (cast(:badgeType as string) IS NULL OR b.badgeType = :badgeType) " +
           "GROUP BY b.id")
    Page<BadgeSummaryResponse> searchBadges(
            @Param("keyword") String keyword,
            @Param("status") BadgeStatus status,
            @Param("badgeType") BadgeType badgeType,
            Pageable pageable
    );
    
    /**
     * Find all active badges of a specific type
     */
    @Query("SELECT b FROM Badge b WHERE b.badgeType = :badgeType AND b.status = 'ACTIVE' ORDER BY b.requirementValue ASC")
    List<Badge> findActiveBadgesByType(@Param("badgeType") BadgeType badgeType);

    /**
     * Find all active badges with pagination
     */
    @Query("SELECT b FROM Badge b WHERE b.status = 'ACTIVE' ORDER BY b.name ASC")
    Page<Badge> findAllActiveBadges(Pageable pageable);

    /**
     * Find all active badges filtered by type with pagination
     */
    @Query("SELECT b FROM Badge b WHERE b.status = 'ACTIVE' AND b.badgeType = :badgeType ORDER BY b.name ASC")
    Page<Badge> findAllActiveBadgesByType(@Param("badgeType") BadgeType badgeType, Pageable pageable);

    /**
     * Count how many users earned a specific badge
     */
    @Query("SELECT COUNT(ub) FROM UserBadges ub WHERE ub.badgeId = :badgeId")
    Long countEarnersByBadgeId(@Param("badgeId") UUID badgeId);

    /**
     * Find top 5 most awarded badges with their award counts.
     * Returns rows: [id (UUID), name (String), awardCount (Long)]
     */
    @Query(value = "SELECT b.id, b.name, COUNT(ub.id) AS award_count " +
                   "FROM gamification.badges b " +
                   "LEFT JOIN gamification.user_badges ub ON b.id = ub.badge_id " +
                   "WHERE b.status = 'ACTIVE' " +
                   "GROUP BY b.id, b.name " +
                   "ORDER BY award_count DESC " +
                   "LIMIT 5",
           nativeQuery = true)
    List<Object[]> findTop5PopularBadges();

    /**
     * Count total badge awards across all users
     */
    @Query("SELECT COUNT(ub) FROM UserBadges ub")
    Long countTotalBadgeAwards();

    /**
     * Find bottom 5 rarest badges (fewest earners) among active badges that have been awarded at least once.
     * Returns rows: [id (UUID), name (String), awardCount (Long)]
     */
    @Query(value = "SELECT b.id, b.name, COUNT(ub.id) AS award_count " +
                   "FROM gamification.badges b " +
                   "LEFT JOIN gamification.user_badges ub ON b.id = ub.badge_id " +
                   "WHERE b.status = 'ACTIVE' " +
                   "GROUP BY b.id, b.name " +
                   "HAVING COUNT(ub.id) > 0 " +
                   "ORDER BY award_count ASC " +
                   "LIMIT 5",
           nativeQuery = true)
    List<Object[]> findBottom5RareBadges();
}
