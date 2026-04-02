package com.fu.conversation.repository;

import com.fu.conversation.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    /**
     * Find a chatbot session by id and status.
     * Native query required: Hibernate binds String parameters as character varying,
     * but PostgreSQL cannot compare session_status = character varying without an explicit cast.
     * Follows the same pattern as ConversationSessionRepository in the roleplay module.
     */
    @Query(value = "SELECT * FROM ai_conversation.conversation_sessions WHERE id = :id AND status = CAST(:status AS session_status)",
           nativeQuery = true)
    Optional<ChatSession> findByIdAndStatus(@Param("id") UUID id, @Param("status") String status);

    /** Ownership check — true when the session belongs to the given user. */
    boolean existsByIdAndUserId(UUID id, UUID userId);

    // ── Write operations ────────────────────────────────────────────────────

    @Modifying
    @Query("UPDATE ChatSession s SET s.status = :status WHERE s.id = :id")
    void updateStatus(@Param("id") UUID id, @Param("status") String status);

    @Modifying
    @Query("UPDATE ChatSession s SET s.title = :title WHERE s.id = :id")
    void updateTitle(@Param("id") UUID id, @Param("title") String title);

    /**
     * Increment message counters and stamp updatedAt so that
     * {@code toSessionResponse()} can expose it as {@code lastMessageAt}
     * without an extra query.
     */
    @Modifying
    @Query("""
            UPDATE ChatSession s
            SET s.messageCount = s.messageCount + 2,
                s.userMessageCount = s.userMessageCount + 1,
                s.updatedAt = CURRENT_TIMESTAMP
            WHERE s.id = :id
            """)
    void incrementMessageCount(@Param("id") UUID id);

    // ── Cross-schema native queries ─────────────────────────────────────────

    /**
     * True when the user is enrolled in at least one active class of the given course.
     * Queries user_management schema (no JPA entity in this module).
     */
    @Query(value = """
            SELECT COUNT(*) > 0
            FROM user_management.class_members cm
            JOIN user_management.classes c ON c.id = cm.class_id
            WHERE cm.user_id = :userId
              AND c.course_id = :courseId
              AND cm.removed_at IS NULL
              AND c.deleted_at IS NULL
              AND c.is_active = true
            """, nativeQuery = true)
    boolean isEnrolledInCourse(@Param("userId") UUID userId, @Param("courseId") UUID courseId);

    /**
     * Resolve the single active course for a learner.
     * Business rule: 1 learner → at most 1 active class → 1 course at any point in time.
     * Returns one row of (course_id, course_name, jlpt_level, lesson_count), or empty list.
     * Used by createSession() to auto-resolve courseId when frontend does not supply it,
     * and by GET /active-course to tell the frontend which course the learner is studying.
     */
    @Query(value = """
            SELECT
                co.id           AS course_id,
                co.name         AS course_name,
                co.jlpt_level   AS jlpt_level,
                COUNT(l.id)     AS lesson_count
            FROM user_management.class_members cm
            JOIN user_management.classes c   ON c.id  = cm.class_id
            JOIN learning_content.courses co ON co.id = c.course_id
            LEFT JOIN learning_content.lessons l
                ON l.course_id = co.id AND l.deleted_at IS NULL
            WHERE cm.user_id    = :userId
              AND cm.removed_at IS NULL
              AND c.deleted_at  IS NULL
              AND c.is_active   = true
              AND co.deleted_at IS NULL
            GROUP BY co.id, co.name, co.jlpt_level
            LIMIT 1
            """, nativeQuery = true)
    List<Object[]> findActiveCourse(@Param("userId") UUID userId);

    /** Course name for system-prompt injection. Returns null on miss (handled in service). */
    @Query(value = "SELECT name FROM learning_content.courses WHERE id = :courseId",
           nativeQuery = true)
    String findCourseNameById(@Param("courseId") UUID courseId);

    /** Lesson title for system-prompt injection. Returns null on miss (handled in service). */
    @Query(value = "SELECT title FROM learning_content.lessons WHERE id = :lessonId",
           nativeQuery = true)
    String findLessonTitleById(@Param("lessonId") UUID lessonId);

    // ── Session listing & reopen ─────────────────────────────────────────────

    /**
     * Paginated list of sessions for a user filtered by session type.
     * Native query required for PostgreSQL enum cast on session_type.
     */
    /**
     * Sessions with at least one message, newest first.
     * Sessions with message_count = 0 are excluded — they are phantom sessions
     * created but never used (e.g. user opened the chat screen then navigated away).
     */
    @Query(value = """
            SELECT * FROM ai_conversation.conversation_sessions
            WHERE user_id = :userId
              AND session_type = CAST(:sessionType AS session_type)
              AND message_count > 0
            ORDER BY updated_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM ai_conversation.conversation_sessions
            WHERE user_id = :userId
              AND session_type = CAST(:sessionType AS session_type)
              AND message_count > 0
            """,
            nativeQuery = true)
    Page<ChatSession> findByUserIdAndSessionType(@Param("userId") UUID userId,
                                                  @Param("sessionType") String sessionType,
                                                  Pageable pageable);

    /** Find session by ID regardless of status (for reopen). */
    @Query(value = "SELECT * FROM ai_conversation.conversation_sessions WHERE id = :id",
           nativeQuery = true)
    Optional<ChatSession> findByIdNotDeleted(@Param("id") UUID id);

    /**
     * Mark all ACTIVE sessions created before {@code cutoff} as ABANDONED.
     * Called nightly by {@link com.fu.conversation.scheduler.ChatSessionCleanupScheduler}.
     *
     * @param cutoff sessions older than this timestamp are abandoned
     * @return number of rows updated
     */
    @Modifying
    @Query(value = """
            UPDATE ai_conversation.conversation_sessions
               SET status = CAST('ABANDONED' AS session_status)
             WHERE status = CAST('ACTIVE' AS session_status)
               AND created_at < :cutoff
            """, nativeQuery = true)
    int markAbandonedBefore(@Param("cutoff") LocalDateTime cutoff);
}
