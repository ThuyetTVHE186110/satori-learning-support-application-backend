package com.fu.conversation.repository;

import com.fu.conversation.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
    @Query("""
            UPDATE ChatSession s
            SET s.messageCount = s.messageCount + 2,
                s.userMessageCount = s.userMessageCount + 1
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

    /** Course name for system-prompt injection. Returns null on miss (handled in service). */
    @Query(value = "SELECT name FROM learning_content.courses WHERE id = :courseId",
           nativeQuery = true)
    String findCourseNameById(@Param("courseId") UUID courseId);

    /** Lesson title for system-prompt injection. Returns null on miss (handled in service). */
    @Query(value = "SELECT title FROM learning_content.lessons WHERE id = :lessonId",
           nativeQuery = true)
    String findLessonTitleById(@Param("lessonId") UUID lessonId);
}
