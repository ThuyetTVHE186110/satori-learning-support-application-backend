package com.fu.roleplay.repository;

import com.fu.roleplay.entity.ConversationSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    @Query(value = "SELECT COUNT(*) FROM ai_conversation.conversation_sessions WHERE user_id = :userId AND status = CAST(:status AS session_status)",
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
}
