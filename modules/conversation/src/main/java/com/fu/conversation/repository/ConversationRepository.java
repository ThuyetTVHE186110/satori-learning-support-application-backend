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

    @Query(value = "SELECT * FROM speaking.conversations " +
            "WHERE topic_id = :topicId " +
            "  AND deleted_at IS NULL " +
            "  AND (LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "       LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "       LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "ORDER BY order_index ASC",
            countQuery = "SELECT COUNT(*) FROM speaking.conversations " +
                    "WHERE topic_id = :topicId " +
                    "  AND deleted_at IS NULL " +
                    "  AND (LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                    "       LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                    "       LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            nativeQuery = true)
    Page<Conversation> findByTopicIdAndKeywordAndDeletedAtIsNull(
            @Param("topicId") UUID topicId,
            @Param("keyword") String keyword,
            Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversations " +
            "WHERE topic_id = :topicId " +
            "  AND jlpt_level = CAST(:jlptLevel AS jlpt_level) " +
            "  AND deleted_at IS NULL " +
            "  AND (LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "       LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "       LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "ORDER BY order_index ASC",
            countQuery = "SELECT COUNT(*) FROM speaking.conversations " +
                    "WHERE topic_id = :topicId " +
                    "  AND jlpt_level = CAST(:jlptLevel AS jlpt_level) " +
                    "  AND deleted_at IS NULL " +
                    "  AND (LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                    "       LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                    "       LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            nativeQuery = true)
    Page<Conversation> findByTopicIdAndKeywordAndJlptLevelAndDeletedAtIsNull(
            @Param("topicId") UUID topicId,
            @Param("keyword") String keyword,
            @Param("jlptLevel") String jlptLevel,
            Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversations " +
            "WHERE topic_id = :topicId " +
            "  AND status = CAST(:status AS content_status) " +
            "  AND deleted_at IS NULL " +
            "  AND (LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "       LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "       LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "ORDER BY order_index ASC",
            countQuery = "SELECT COUNT(*) FROM speaking.conversations " +
                    "WHERE topic_id = :topicId " +
                    "  AND status = CAST(:status AS content_status) " +
                    "  AND deleted_at IS NULL " +
                    "  AND (LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                    "       LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                    "       LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            nativeQuery = true)
    Page<Conversation> findByTopicIdAndKeywordAndStatusAndDeletedAtIsNull(
            @Param("topicId") UUID topicId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversations " +
            "WHERE topic_id = :topicId " +
            "  AND jlpt_level = CAST(:jlptLevel AS jlpt_level) " +
            "  AND status = CAST(:status AS content_status) " +
            "  AND deleted_at IS NULL " +
            "  AND (LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "       LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "       LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "ORDER BY order_index ASC",
            countQuery = "SELECT COUNT(*) FROM speaking.conversations " +
                    "WHERE topic_id = :topicId " +
                    "  AND jlpt_level = CAST(:jlptLevel AS jlpt_level) " +
                    "  AND status = CAST(:status AS content_status) " +
                    "  AND deleted_at IS NULL " +
                    "  AND (LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                    "       LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                    "       LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            nativeQuery = true)
    Page<Conversation> findByTopicIdAndKeywordAndJlptLevelAndStatusAndDeletedAtIsNull(
            @Param("topicId") UUID topicId,
            @Param("keyword") String keyword,
            @Param("jlptLevel") String jlptLevel,
            @Param("status") String status,
            Pageable pageable);

    long countByDeletedAtIsNull();

    @Query(
            value = "SELECT COUNT(*) FROM speaking.conversations WHERE status = CAST(:status AS content_status) AND deleted_at IS NULL",
            nativeQuery = true
    )
    long countByStatusAndDeletedAtIsNull(@Param("status") String status);

    @Query(
            value = "SELECT CAST(AVG(CAST(overall_score AS FLOAT)) AS FLOAT) FROM ai_conversation.conversation_sessions WHERE overall_score IS NOT NULL",
            nativeQuery = true
    )
    Double getAverageOverallScore();

    @Query(
            value = "SELECT CAST(AVG(EXTRACT(EPOCH FROM (updated_at - created_at))/3600) AS FLOAT) FROM ai_conversation.conversation_sessions WHERE status = CAST('COMPLETED' AS session_status)",
            nativeQuery = true
    )
    Double getAveragePracticeDuration();

    @Query(value = "SELECT ct.id, ct.title, COALESCE(SUM(ucp.total_sessions), 0) as practice_count " +
            "FROM speaking.conversation_topics ct " +
            "INNER JOIN speaking.conversations c ON ct.id = c.topic_id " +
            "LEFT JOIN speaking.user_conversation_progress ucp ON c.id = ucp.conversation_id " +
            "WHERE ct.deleted_at IS NULL " +
            "GROUP BY ct.id, ct.title " +
            "ORDER BY practice_count DESC NULLS LAST LIMIT :limit",
            nativeQuery = true)
    List<Object[]> getTopPracticedConversationTopics(@Param("limit") int limit);

    @Query(value = "SELECT ct.id, ct.title, COUNT(cs.id) as practice_count " +
            "FROM speaking.conversations ct " +
            "LEFT JOIN ai_conversation.conversation_sessions cs ON ct.id = cs.conversation_id " +
            "WHERE ct.deleted_at IS NULL " +
            "GROUP BY ct.id, ct.title " +
            "ORDER BY practice_count DESC NULLS LAST LIMIT :limit",
            nativeQuery = true)
    List<Object[]> getTopPracticedConversations(@Param("limit") int limit);

    @Query(value = "SELECT DATE_TRUNC('day', cs.created_at)::date as practice_date, COUNT(*) as count " +
            "FROM ai_conversation.conversation_sessions cs " +
            "WHERE cs.created_at >= NOW() - INTERVAL '30 days' " +
            "GROUP BY DATE_TRUNC('day', cs.created_at) " +
            "ORDER BY practice_date",
            nativeQuery = true)
    List<Object[]> getPracticeTrendLast30Days();

    @Query(value = "SELECT c.id, c.title, ct.title, c.jlpt_level, c.difficulty_score, " +
            "ROUND(AVG(CAST(cs.overall_score AS NUMERIC)), 1) as avg_score " +
            "FROM speaking.conversations c " +
            "INNER JOIN speaking.conversation_topics ct ON c.topic_id = ct.id " +
            "LEFT JOIN ai_conversation.conversation_sessions cs ON c.id = cs.conversation_id " +
            "WHERE c.deleted_at IS NULL AND cs.overall_score IS NOT NULL " +
            "GROUP BY c.id, c.title, ct.title, c.jlpt_level, c.difficulty_score " +
            "HAVING AVG(CAST(cs.overall_score AS NUMERIC)) < :threshold " +
            "ORDER BY avg_score ASC " +
            "LIMIT :limit",
            nativeQuery = true)
    List<Object[]> getConversationsNeedingImprovement(@Param("threshold") double threshold, @Param("limit") int limit);

    @Query(
            value = "SELECT COUNT(*) FROM ai_conversation.conversation_sessions WHERE status = CAST('COMPLETED' AS session_status)",
            nativeQuery = true
    )
    Long getTotalCompletedSpeakingSessions();
}
