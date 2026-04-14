package com.fu.roleplay.repository;

import com.fu.roleplay.entity.LearnerErrorPattern;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LearnerErrorPatternRepository extends JpaRepository<LearnerErrorPattern, UUID> {

    List<LearnerErrorPattern> findBySessionId(UUID sessionId);

    /**
     * Returns error type counts aggregated over the N most recent completed sessions.
     * Each row: [errorType (String), totalCount (Long), sessionCount (Long)].
     */
    @Query(value = """
            SELECT ep.error_type, SUM(ep.occurrence_count) AS total_count, COUNT(DISTINCT ep.session_id) AS session_count
            FROM ai_conversation.learner_error_patterns ep
            WHERE ep.user_id = :userId
              AND ep.session_id IN (
                  SELECT s.id FROM ai_conversation.conversation_sessions s
                  WHERE s.user_id = :userId AND s.status = 'COMPLETED'
                  ORDER BY s.completed_at DESC
                  LIMIT :sessionLimit
              )
            GROUP BY ep.error_type
            ORDER BY total_count DESC
            """, nativeQuery = true)
    List<Object[]> findTopErrorTypesByRecentSessions(
            @Param("userId") UUID userId,
            @Param("sessionLimit") int sessionLimit);
}
