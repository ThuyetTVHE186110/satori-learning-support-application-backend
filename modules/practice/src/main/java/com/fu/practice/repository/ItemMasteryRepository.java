package com.fu.practice.repository;

import com.fu.practice.entity.ItemMastery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ItemMasteryRepository extends JpaRepository<ItemMastery, UUID> {

    Optional<ItemMastery> findByUserIdAndEntityIdAndEntityType(
            UUID userId, UUID entityId, String entityType);

    /**
     * Batch load cho picker API và dimension calculation.
     * Một query duy nhất cho N items thay vì N queries.
     */
    List<ItemMastery> findByUserIdAndEntityIdIn(UUID userId, Collection<UUID> entityIds);

    /**
     * Load mastery của tất cả vocab hoặc grammar items trong một lesson
     * để tính aggregate vocabMastery / grammarMastery cho LessonProgress.
     */
    @Query("""
            SELECT im FROM ItemMastery im
            WHERE im.userId = :userId
              AND im.entityType = :entityType
              AND im.entityId IN :entityIds
            """)
    List<ItemMastery> findForLessonAggregate(
            @Param("userId") UUID userId,
            @Param("entityType") String entityType,
            @Param("entityIds") Collection<UUID> entityIds);

    /**
     * Load mastery records có lastDimension để tính dimension vote.
     * Chỉ lấy các item trong danh sách selectedIds.
     */
    @Query("""
            SELECT im FROM ItemMastery im
            WHERE im.userId = :userId
              AND im.entityId IN :entityIds
            ORDER BY im.lastPracticedAt DESC NULLS LAST
            """)
    List<ItemMastery> findForDimensionVote(
            @Param("userId") UUID userId,
            @Param("entityIds") Collection<UUID> entityIds);

    /** Weak items: mastery level 0 or 1 (needs work / familiar). */
    List<ItemMastery> findByUserIdAndMasteryLevelLessThanEqual(UUID userId, int level);

    /** Strong items: mastery level 2 or 3 (proficient / mastered). */
    List<ItemMastery> findByUserIdAndMasteryLevelGreaterThanEqual(UUID userId, int level);

    /** All mastery records for a user, ordered by EMA score ascending (weakest first). */
    List<ItemMastery> findByUserIdOrderByEmaScoreAsc(UUID userId);

    /**
     * Count mastery records grouped by entityType and masteryLevel for the dashboard
     * mastery tier breakdown. Two variants — one global, one scoped by entityIds —
     * because JPQL cannot conditionally suppress an `IN` clause.
     */
    @Query("""
            SELECT im.entityType  AS entityType,
                   im.masteryLevel AS masteryLevel,
                   COUNT(im)       AS cnt
              FROM ItemMastery im
             WHERE im.userId = :userId
             GROUP BY im.entityType, im.masteryLevel
            """)
    List<MasteryTierCount> countTiersByUser(@Param("userId") UUID userId);

    @Query("""
            SELECT im.entityType  AS entityType,
                   im.masteryLevel AS masteryLevel,
                   COUNT(im)       AS cnt
              FROM ItemMastery im
             WHERE im.userId = :userId
               AND im.entityId IN :entityIds
             GROUP BY im.entityType, im.masteryLevel
            """)
    List<MasteryTierCount> countTiersByUserAndEntityIds(
            @Param("userId") UUID userId,
            @Param("entityIds") Collection<UUID> entityIds);

    /** Top weak items ordered by oldest practice first; drives the "Cần ôn lại" CTA. */
    @Query("""
            SELECT im FROM ItemMastery im
             WHERE im.userId = :userId
               AND im.masteryLevel <= :maxLevel
             ORDER BY im.lastPracticedAt ASC NULLS FIRST, im.emaScore ASC
            """)
    List<ItemMastery> findNeedsReviewGlobal(@Param("userId") UUID userId,
                                             @Param("maxLevel") int maxLevel,
                                             org.springframework.data.domain.Pageable pageable);

    @Query("""
            SELECT im FROM ItemMastery im
             WHERE im.userId = :userId
               AND im.masteryLevel <= :maxLevel
               AND im.entityId IN :entityIds
             ORDER BY im.lastPracticedAt ASC NULLS FIRST, im.emaScore ASC
            """)
    List<ItemMastery> findNeedsReviewScoped(@Param("userId") UUID userId,
                                             @Param("maxLevel") int maxLevel,
                                             @Param("entityIds") Collection<UUID> entityIds,
                                             org.springframework.data.domain.Pageable pageable);

    /** Projection for grouped tier counts. */
    interface MasteryTierCount {
        String getEntityType();
        int getMasteryLevel();
        long getCnt();
    }

    /** Most-recent practice timestamp across the user's mastery records (any tier). */
    @Query("SELECT MAX(im.lastPracticedAt) FROM ItemMastery im WHERE im.userId = :userId")
    java.time.LocalDateTime findMostRecentPracticedGlobal(@Param("userId") UUID userId);

    @Query("""
            SELECT MAX(im.lastPracticedAt) FROM ItemMastery im
             WHERE im.userId = :userId
               AND im.entityId IN :entityIds
            """)
    java.time.LocalDateTime findMostRecentPracticedScoped(
            @Param("userId") UUID userId,
            @Param("entityIds") Collection<UUID> entityIds);
}
