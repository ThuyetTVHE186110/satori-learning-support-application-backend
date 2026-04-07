package com.fu.conversation.repository;

import com.fu.conversation.entity.ChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    /**
     * Newest-first slice for sliding-window history.
     * Pass {@code PageRequest.of(0, N)} to limit to N messages.
     * Native query required: JPQL string literals in IN() are bound as character varying,
     * which PostgreSQL cannot compare against message_role without an explicit cast.
     */
    @Query(value = """
            SELECT * FROM ai_conversation.conversation_messages
            WHERE session_id = :sessionId
              AND role IN ('USER'::message_role, 'ASSISTANT'::message_role)
            ORDER BY created_at DESC
            """, nativeQuery = true)
    List<ChatMessage> findRecentBySessionId(@Param("sessionId") UUID sessionId, Pageable pageable);

    /**
     * Chronological page for the history API endpoint.
     * Native query required for the same reason as findRecentBySessionId.
     */
    @Query(value = """
            SELECT * FROM ai_conversation.conversation_messages
            WHERE session_id = :sessionId
              AND role IN ('USER'::message_role, 'ASSISTANT'::message_role)
            ORDER BY created_at ASC
            """,
           countQuery = """
            SELECT COUNT(*) FROM ai_conversation.conversation_messages
            WHERE session_id = :sessionId
              AND role IN ('USER'::message_role, 'ASSISTANT'::message_role)
            """,
           nativeQuery = true)
    Page<ChatMessage> findBySessionIdOrderByCreatedAtAsc(@Param("sessionId") UUID sessionId, Pageable pageable);

    /**
     * Atomic CTE INSERT of a USER + ASSISTANT message pair.
     *
     * The {@code locked} CTE acquires a row-level lock on the session row so that
     * two concurrent exchanges for the same session cannot both read the same
     * MAX(sequence_number) before either inserts, which would produce duplicate
     * sequence numbers.  The lock is released when the calling transaction commits.
     *
     * Explicit {@code ::message_role} cast is required because PostgreSQL cannot infer
     * the target column type from a UNION ALL SELECT literal.
     */
    @Modifying
    @Query(value = """
            WITH locked AS (
                SELECT id FROM ai_conversation.conversation_sessions
                WHERE id = :sessionId
                FOR UPDATE
            ),
            next_seq AS (
                SELECT COALESCE(MAX(sequence_number), 0) AS last_seq
                FROM ai_conversation.conversation_messages
                WHERE session_id = :sessionId
            )
            INSERT INTO ai_conversation.conversation_messages
                (id, session_id, role, content, sequence_number, created_at)
            SELECT :userMsgId, :sessionId, 'USER'::message_role, :userContent, last_seq + 1, CURRENT_TIMESTAMP FROM next_seq
            UNION ALL
            SELECT :aiMsgId,   :sessionId, 'ASSISTANT'::message_role, :aiContent,  last_seq + 2, CURRENT_TIMESTAMP FROM next_seq
            """, nativeQuery = true)
    void saveExchange(@Param("sessionId")   UUID sessionId,
                      @Param("userMsgId")   UUID userMsgId,
                      @Param("userContent") String userContent,
                      @Param("aiMsgId")     UUID aiMsgId,
                      @Param("aiContent")   String aiContent);
}
