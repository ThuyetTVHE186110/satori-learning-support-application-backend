package com.fu.conversation.repository;

import com.fu.conversation.entity.Conversation;
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
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /**
     * Projection for practice statistics per conversation.
     * Used to enrich ConversationSummaryResponse with learner progress data.
     */
    interface PracticeStatsProjection {
        UUID getConversationId();
        Long getPracticeCount();
        LocalDateTime getLastPracticedAt();
        Boolean getIsCompleted();
    }

    /**
     * Returns practice statistics from user_conversation_progress for the given conversations.
     * Returns only conversations that have at least one session (unpracticed ones are absent).
     */
    @Query(value = """
            SELECT p.conversation_id  AS conversationId,
                   p.total_sessions   AS practiceCount,
                   p.last_practiced_at AS lastPracticedAt,
                   p.is_completed      AS isCompleted
            FROM speaking.user_conversation_progress p
            WHERE p.user_id          = :userId
              AND p.conversation_id IN (:conversationIds)
            """, nativeQuery = true)
    List<PracticeStatsProjection> findPracticeStatsByUserIdAndConversationIds(
            @Param("userId") UUID userId,
            @Param("conversationIds") List<UUID> conversationIds);

    Page<Conversation> findByTopicIdAndDeletedAtIsNull(UUID topicId, Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversations WHERE topic_id = :topicId AND status = CAST(:status AS content_status) AND deleted_at IS NULL ORDER BY order_index",
           countQuery = "SELECT COUNT(*) FROM speaking.conversations WHERE topic_id = :topicId AND status = CAST(:status AS content_status) AND deleted_at IS NULL",
           nativeQuery = true)
    Page<Conversation> findByTopicIdAndStatusAndDeletedAtIsNull(
            @Param("topicId") UUID topicId, @Param("status") String status, Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversations WHERE topic_id = :topicId AND status = CAST(:status AS content_status) AND deleted_at IS NULL ORDER BY order_index ASC",
           nativeQuery = true)
    List<Conversation> findByTopicIdAndStatusAndDeletedAtIsNullOrderByOrderIndexAsc(
            @Param("topicId") UUID topicId, @Param("status") String status);

    /** Used for cascade soft-delete when a topic is deleted. */
    List<Conversation> findByTopicIdAndDeletedAtIsNull(UUID topicId);

    Page<Conversation> findByDeletedAtIsNull(Pageable pageable);

    long countByTopicIdAndDeletedAtIsNull(UUID topicId);
}
