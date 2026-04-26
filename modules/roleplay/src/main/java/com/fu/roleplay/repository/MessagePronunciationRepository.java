package com.fu.roleplay.repository;

import com.fu.roleplay.entity.MessagePronunciation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessagePronunciationRepository extends JpaRepository<MessagePronunciation, UUID> {

    Optional<MessagePronunciation> findByMessageId(UUID messageId);

    List<MessagePronunciation> findBySessionId(UUID sessionId);

    /**
     * Per-utterance Azure pronunciation rows for the dashboard timeline.
     * Joins to ConversationSession to scope by user. Each utterance contributes
     * equally to the weekly average — see Azure-metrics analysis in
     * {@code memory/learner_progress_dashboard_plan.md}.
     */
    @Query("""
            SELECT mp FROM MessagePronunciation mp, ConversationSession cs
             WHERE cs.id = mp.sessionId
               AND cs.userId = :userId
               AND mp.createdAt BETWEEN :start AND :end
            """)
    List<MessagePronunciation> findByUserIdAndCreatedAtBetween(
            @Param("userId") UUID userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
