package com.fu.conversation.repository;

import com.fu.conversation.entity.UserConversationProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface UserConversationProgressRepository extends JpaRepository<UserConversationProgress, UUID> {

    /**
     * UPSERT on session start: create row or increment total_sessions + update last_practiced_at.
     */
    @Modifying
    @Query(value = """
            INSERT INTO speaking.user_conversation_progress
                (id, user_id, conversation_id, total_sessions,
                 last_practiced_at, is_completed,
                 created_at, updated_at)
            VALUES
                (uuid_generate_v4(), :userId, :conversationId, 1,
                 :practicedAt, false,
                 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (user_id, conversation_id) DO UPDATE SET
                total_sessions    = speaking.user_conversation_progress.total_sessions + 1,
                last_practiced_at = :practicedAt,
                updated_at        = CURRENT_TIMESTAMP
            """, nativeQuery = true)
    void upsertOnSessionStart(
            @Param("userId") UUID userId,
            @Param("conversationId") UUID conversationId,
            @Param("practicedAt") LocalDateTime practicedAt);

    /**
     * Mark topic as completed.
     * completed_at is set only on the first completion (COALESCE keeps the original).
     */
    @Modifying
    @Query(value = """
            UPDATE speaking.user_conversation_progress SET
                is_completed = true,
                completed_at = COALESCE(completed_at, :completedAt),
                updated_at   = CURRENT_TIMESTAMP
            WHERE user_id = :userId AND conversation_id = :conversationId
            """, nativeQuery = true)
    void markCompleted(
            @Param("userId") UUID userId,
            @Param("conversationId") UUID conversationId,
            @Param("completedAt") LocalDateTime completedAt);
}
