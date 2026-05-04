package com.fu.roleplay.repository;

import com.fu.roleplay.entity.ConversationSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationSessionRepository extends JpaRepository<ConversationSession, UUID> {

    @Query(value = "SELECT * FROM ai_conversation.conversation_sessions WHERE user_id = :userId AND session_type = CAST(:sessionType AS session_type) ORDER BY created_at DESC",
           countQuery = "SELECT COUNT(*) FROM ai_conversation.conversation_sessions WHERE user_id = :userId AND session_type = CAST(:sessionType AS session_type)",
           nativeQuery = true)
    Page<ConversationSession> findByUserIdAndSessionTypeOrderByCreatedAtDesc(
            @Param("userId") UUID userId, @Param("sessionType") String sessionType, Pageable pageable);

    Optional<ConversationSession> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Acquires a pessimistic write lock on the session row, preventing concurrent
     * sendMessage calls from producing duplicate sequence numbers.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ConversationSession s WHERE s.id = :id AND s.userId = :userId")
    Optional<ConversationSession> findByIdAndUserIdForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query(value = "SELECT COUNT(*) FROM ai_conversation.conversation_sessions WHERE user_id = :userId AND status = CAST(:status AS session_status) AND session_type IN (CAST('ROLEPLAY' AS session_type), CAST('FREE_TALK' AS session_type))",
           nativeQuery = true)
    long countByUserIdAndStatus(@Param("userId") UUID userId, @Param("status") String status);

    @Query(value = "SELECT * FROM ai_conversation.conversation_sessions WHERE user_id = :userId AND status = CAST(:status AS session_status) ORDER BY created_at DESC",
           countQuery = "SELECT COUNT(*) FROM ai_conversation.conversation_sessions WHERE user_id = :userId AND status = CAST(:status AS session_status)",
           nativeQuery = true)
    Page<ConversationSession> findByUserIdAndStatusOrderByCreatedAtDesc(
            @Param("userId") UUID userId, @Param("status") String status, Pageable pageable);

    @Query(value = "SELECT * FROM ai_conversation.conversation_sessions WHERE user_id = :userId AND conversation_id = :conversationId AND session_type = CAST('ROLEPLAY' AS session_type) AND status = CAST('COMPLETED' AS session_status) ORDER BY completed_at DESC LIMIT 1",
           nativeQuery = true)
    Optional<ConversationSession> findLatestCompletedByUserIdAndConversationId(
            @Param("userId") UUID userId, @Param("conversationId") UUID conversationId);

    /**
     * Auto-abandon stale ACTIVE sessions whose last message was sent before {@code cutoff}.
     * Sessions with no messages at all use created_at as fallback.
     */
    @Modifying
    @Query(value = """
            UPDATE ai_conversation.conversation_sessions cs
               SET status      = CAST('ABANDONED' AS session_status),
                   completed_at = NOW(),
                   updated_at   = NOW()
             WHERE cs.status = CAST('ACTIVE' AS session_status)
               AND COALESCE(
                       (SELECT MAX(m.created_at)
                          FROM ai_conversation.conversation_messages m
                         WHERE m.session_id = cs.id),
                       cs.created_at
                   ) < :cutoff
            """, nativeQuery = true)
    int markStaleSessionsAbandoned(@Param("cutoff") LocalDateTime cutoff);

    @Query(value = "SELECT * FROM ai_conversation.conversation_sessions WHERE user_id = :userId AND status = CAST('ACTIVE' AS session_status) AND session_type IN (CAST('ROLEPLAY' AS session_type), CAST('FREE_TALK' AS session_type)) ORDER BY created_at DESC",
           nativeQuery = true)
    List<ConversationSession> findActiveSessionsByUserId(@Param("userId") UUID userId);

    @Query(value = "SELECT * FROM ai_conversation.conversation_sessions WHERE user_id = :userId AND session_type = CAST(:sessionType AS session_type) AND (CAST(:status AS TEXT) IS NULL OR status = CAST(:status AS session_status)) ORDER BY created_at DESC",
           countQuery = "SELECT COUNT(*) FROM ai_conversation.conversation_sessions WHERE user_id = :userId AND session_type = CAST(:sessionType AS session_type) AND (CAST(:status AS TEXT) IS NULL OR status = CAST(:status AS session_status))",
           nativeQuery = true)
    Page<ConversationSession> findByUserIdAndSessionTypeAndOptionalStatus(
            @Param("userId") UUID userId, @Param("sessionType") String sessionType,
            @Param("status") String status, Pageable pageable);

    /**
     * Completed speaking sessions for dashboard timeline aggregation.
     * Excludes CURRICULUM_QA chatbot sessions; covers GUIDED_CONVERSATION, ROLEPLAY,
     * FREE_TALK, PRACTICE_ASSIGNMENT.
     */
    @Query(value = """
            SELECT * FROM ai_conversation.conversation_sessions
             WHERE user_id = :userId
               AND status = CAST('COMPLETED' AS session_status)
               AND completed_at BETWEEN :start AND :end
            """, nativeQuery = true)
    List<ConversationSession> findCompletedSpeakingInRange(
            @Param("userId") UUID userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    // ── Admin dashboard queries ──────────────────────────────────────────────

    /**
     * Count roleplay/conversation sessions (non-CURRICULUM_QA) created within a date range.
     */
    @Query(value = """
            SELECT COUNT(*) FROM ai_conversation.conversation_sessions
            WHERE session_type != CAST('CURRICULUM_QA' AS session_type)
              AND created_at >= :start AND created_at < :end
            """, nativeQuery = true)
    long countConversationSessionsBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * Daily roleplay/conversation session trend for last N days.
     * Returns list of [date, count].
     */
    @Query(value = """
            SELECT DATE(created_at) AS day, COUNT(*) AS cnt
            FROM ai_conversation.conversation_sessions
            WHERE session_type != CAST('CURRICULUM_QA' AS session_type)
              AND created_at >= :start
            GROUP BY DATE(created_at)
            ORDER BY day ASC
            """, nativeQuery = true)
    List<Object[]> findDailyConversationTrend(@Param("start") LocalDateTime start);
}
