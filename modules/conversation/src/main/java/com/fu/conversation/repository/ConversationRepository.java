package com.fu.conversation.repository;

import com.fu.conversation.entity.Conversation;
import com.fu.course.entity.Course;
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
    @Query(
            value = "SELECT COUNT(*) FROM speaking.conversations WHERE topic_id = :topicId AND jlpt_level = CAST(:jlptLevel AS jlpt_level) AND deleted_at IS NULL",
            nativeQuery = true
    )
    long countByTopicIdAndJlptLevelAndDeletedAtIsNull(@Param("topicId") UUID topicId, @Param("jlptLevel") String jlptLevel);

    @Query(
            value = "SELECT COUNT(*) FROM speaking.conversations WHERE topic_id = :topicId AND status = CAST(:status AS content_status) AND deleted_at IS NULL",
            nativeQuery = true
    )
    long countByTopicIdAndStatusAndDeletedAtIsNull(@Param("topicId") UUID topicId, @Param("status") String status);

    @Query(
            value = "SELECT COUNT(*) FROM speaking.conversations WHERE topic_id = :topicId AND jlpt_level = CAST(:jlptLevel AS jlpt_level) AND status = CAST(:status AS content_status) AND deleted_at IS NULL",
            nativeQuery = true
    )
    long countByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNull(@Param("topicId") UUID topicId, @Param("jlptLevel") String jlptLevel, @Param("status") String status);

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

    @Query(value = "SELECT * FROM speaking.conversations WHERE topic_id = :topicId AND deleted_at IS NULL AND (" +
            "LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            countQuery = "SELECT COUNT(*) FROM speaking.conversations WHERE topic_id = :topicId AND deleted_at IS NULL AND (" +
                    "LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                    "LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                    "LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            nativeQuery = true)
    Page<Conversation> findByTopicIdAndKeywordAndDeletedAtIsNull(
            @Param("topicId") UUID topicId, @Param("keyword") String keyword, Pageable pageable);

    @Query(
            value = "SELECT * FROM speaking.conversations " +
                    "WHERE topic_id = :topicId " +
                    "  AND jlpt_level = CAST(:jlptLevel AS jlpt_level) " +
                    "  AND deleted_at IS NULL " +
                    "ORDER BY order_index ASC",
            countQuery = "SELECT COUNT(*) FROM speaking.conversations " +
                         "WHERE topic_id = :topicId " +
                         "  AND jlpt_level = CAST(:jlptLevel AS jlpt_level) " +
                         "  AND deleted_at IS NULL",
            nativeQuery = true
    )
    Page<Conversation> findByTopicIdAndJlptLevelAndDeletedAtIsNull(
            @Param("topicId") UUID topicId,
            @Param("jlptLevel") String jlptLevel,
            Pageable pageable);

    @Query(
            value = "SELECT * FROM speaking.conversations " +
                    "WHERE topic_id = :topicId " +
                    "  AND jlpt_level = CAST(:jlptLevel AS jlpt_level) " +
                    "  AND status = CAST(:status AS content_status) " +
                    "  AND deleted_at IS NULL " +
                    "ORDER BY order_index ASC",
            countQuery = "SELECT COUNT(*) FROM speaking.conversations " +
                         "WHERE topic_id = :topicId " +
                         "  AND jlpt_level = CAST(:jlptLevel AS jlpt_level) " +
                         "  AND status = CAST(:status AS content_status) " +
                         "  AND deleted_at IS NULL",
            nativeQuery = true
    )
    Page<Conversation> findByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNull(
            @Param("topicId") UUID topicId,
            @Param("jlptLevel") String jlptLevel,
            @Param("status") String status,
            Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversations WHERE topic_id = :topicId AND status = CAST(:status AS content_status) AND deleted_at IS NULL ORDER BY order_index ASC",
            nativeQuery = true)
    List<Conversation> findByTopicIdAndStatusAndDeletedAtIsNullOrderByOrderIndexAsc(
            @Param("topicId") UUID topicId, @Param("status") String status);

    /**
     * Used for cascade soft-delete when a topic is deleted.
     */
    List<Conversation> findByTopicIdAndDeletedAtIsNull(UUID topicId);

    Page<Conversation> findByDeletedAtIsNull(Pageable pageable);

    long countByTopicIdAndDeletedAtIsNull(UUID topicId);

    // ════════════════════════════════════════════════════════════════════════
    // Multi-filter queries for manager conversations listing

    @Query(value = "SELECT * FROM speaking.conversations WHERE status = CAST(:status AS content_status) AND deleted_at IS NULL ORDER BY order_index ASC",
            countQuery = "SELECT COUNT(*) FROM speaking.conversations WHERE status = CAST(:status AS content_status) AND deleted_at IS NULL",
            nativeQuery = true)
    Page<Conversation> findByStatusAndDeletedAtIsNull(
            @Param("status") String status, Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversations WHERE jlpt_level = CAST(:jlptLevel AS jlpt_level) AND deleted_at IS NULL ORDER BY order_index ASC",
            countQuery = "SELECT COUNT(*) FROM speaking.conversations WHERE jlpt_level = CAST(:jlptLevel AS jlpt_level) AND deleted_at IS NULL",
            nativeQuery = true)
    Page<Conversation> findByJlptLevelAndDeletedAtIsNull(
            @Param("jlptLevel") String jlptLevel, Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversations WHERE jlpt_level = CAST(:jlptLevel AS jlpt_level) AND status = CAST(:status AS content_status) AND deleted_at IS NULL ORDER BY order_index ASC",
            countQuery = "SELECT COUNT(*) FROM speaking.conversations WHERE jlpt_level = CAST(:jlptLevel AS jlpt_level) AND status = CAST(:status AS content_status) AND deleted_at IS NULL",
            nativeQuery = true)
    Page<Conversation> findByJlptLevelAndStatusAndDeletedAtIsNull(
            @Param("jlptLevel") String jlptLevel, @Param("status") String status, Pageable pageable);

    @Query("SELECT c FROM Conversation c WHERE c.deletedAt IS NULL AND (" +
            "LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(c.titleJapanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(c.category) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Conversation> searchByKeywordAndDeletedAtIsNull(
            @Param("keyword") String keyword, Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversations WHERE status = CAST(:status AS content_status) AND deleted_at IS NULL AND (" +
            "LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            countQuery = "SELECT COUNT(*) FROM speaking.conversations WHERE status = CAST(:status AS content_status) AND deleted_at IS NULL AND (" +
                    "LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                    "LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                    "LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            nativeQuery = true)
    Page<Conversation> searchByKeywordAndStatusAndDeletedAtIsNull(
            @Param("keyword") String keyword, @Param("status") String status, Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversations WHERE jlpt_level = CAST(:jlptLevel AS jlpt_level) AND deleted_at IS NULL AND (" +
            "LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            countQuery = "SELECT COUNT(*) FROM speaking.conversations WHERE jlpt_level = CAST(:jlptLevel AS jlpt_level) AND deleted_at IS NULL AND (" +
                    "LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                    "LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                    "LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            nativeQuery = true)
    Page<Conversation> searchByKeywordAndJlptLevelAndDeletedAtIsNull(
            @Param("keyword") String keyword, @Param("jlptLevel") String jlptLevel, Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversations WHERE jlpt_level = CAST(:jlptLevel AS jlpt_level) AND status = CAST(:status AS content_status) AND deleted_at IS NULL AND (" +
            "LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            countQuery = "SELECT COUNT(*) FROM speaking.conversations WHERE jlpt_level = CAST(:jlptLevel AS jlpt_level) AND status = CAST(:status AS content_status) AND deleted_at IS NULL AND (" +
                    "LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                    "LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                    "LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            nativeQuery = true)
    Page<Conversation> searchByKeywordAndJlptLevelAndStatusAndDeletedAtIsNull(
            @Param("keyword") String keyword, @Param("jlptLevel") String jlptLevel,
            @Param("status") String status, Pageable pageable);

    /**
     * Find published conversations by topic and JLPT level, ordered by index.
     * Used for learner endpoint to show only conversations matching their enrolled course's JLPT level.
     */
    @Query(value = "SELECT * FROM speaking.conversations " +
            "WHERE topic_id = :topicId " +
            "  AND jlpt_level = CAST(:jlptLevel AS jlpt_level) " +
            "  AND status = CAST(:status AS content_status) " +
            "  AND deleted_at IS NULL " +
            "ORDER BY order_index ASC",
            nativeQuery = true)
    List<Conversation> findByTopicIdAndJlptLevelAndStatusAndDeletedAtIsNullOrderByOrderIndexAsc(
            @Param("topicId") UUID topicId,
            @Param("jlptLevel") String jlptLevel,
            @Param("status") String status);
}
