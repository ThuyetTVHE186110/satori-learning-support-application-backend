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
}
