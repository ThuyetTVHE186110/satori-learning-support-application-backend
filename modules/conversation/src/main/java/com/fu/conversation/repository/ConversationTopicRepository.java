package com.fu.conversation.repository;

import com.fu.conversation.entity.ConversationTopic;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ConversationTopicRepository extends JpaRepository<ConversationTopic, UUID> {

    /**
     * Projection for practice statistics per topic.
     * Used to enrich ConversationTopicSummaryResponse with learner progress data.
     */
    interface PracticeStatsProjection {
        UUID getTopicId();
        Long getPracticeCount();
        LocalDateTime getLastPracticedAt();
        Boolean getIsCompleted();
    }

    /**
     * Returns practice statistics from user_conversation_progress for the given topics.
     * Returns only topics that have at least one session (unpracticed topics are absent).
     */
    @Query(value = """
            SELECT p.topic_id           AS topicId,
                   p.total_sessions     AS practiceCount,
                   p.last_practiced_at  AS lastPracticedAt,
                   p.is_completed       AS isCompleted
            FROM speaking.user_conversation_progress p
            WHERE p.user_id     = :userId
              AND p.topic_id IN (:topicIds)
            """, nativeQuery = true)
    List<PracticeStatsProjection> findPracticeStatsByUserIdAndTopicIds(
            @Param("userId") UUID userId,
            @Param("topicIds") List<UUID> topicIds);

    Page<ConversationTopic> findByThemeIdAndDeletedAtIsNull(UUID themeId, Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversation_topics WHERE theme_id = :themeId AND status = CAST(:status AS content_status) AND deleted_at IS NULL ORDER BY order_index",
           countQuery = "SELECT COUNT(*) FROM speaking.conversation_topics WHERE theme_id = :themeId AND status = CAST(:status AS content_status) AND deleted_at IS NULL",
           nativeQuery = true)
    Page<ConversationTopic> findByThemeIdAndStatusAndDeletedAtIsNull(
            @Param("themeId") UUID themeId, @Param("status") String status, Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversation_topics WHERE theme_id = :themeId AND status = CAST(:status AS content_status) AND deleted_at IS NULL ORDER BY order_index ASC",
           nativeQuery = true)
    List<ConversationTopic> findByThemeIdAndStatusAndDeletedAtIsNullOrderByOrderIndexAsc(
            @Param("themeId") UUID themeId, @Param("status") String status);

    /** Used for cascade soft-delete when a theme is deleted. */
    List<ConversationTopic> findByThemeIdAndDeletedAtIsNull(UUID themeId);

    Page<ConversationTopic> findByDeletedAtIsNull(Pageable pageable);

    long countByThemeIdAndDeletedAtIsNull(UUID themeId);
}
