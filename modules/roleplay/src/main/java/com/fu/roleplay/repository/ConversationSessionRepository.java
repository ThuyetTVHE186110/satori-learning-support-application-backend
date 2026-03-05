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

    Page<ConversationSession> findByUserIdAndSessionTypeOrderByCreatedAtDesc(
            UUID userId, ConversationSession.SessionType sessionType, Pageable pageable);

    Page<ConversationSession> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<ConversationSession> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Acquires a pessimistic write lock on the session row, preventing concurrent
     * sendMessage calls from producing duplicate sequence numbers.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ConversationSession s WHERE s.id = :id AND s.userId = :userId")
    Optional<ConversationSession> findByIdAndUserIdForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

    long countByUserIdAndStatus(UUID userId, ConversationSession.SessionStatus status);

    Page<ConversationSession> findByUserIdAndStatusOrderByCreatedAtDesc(
            UUID userId, ConversationSession.SessionStatus status, Pageable pageable);
}
