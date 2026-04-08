package com.fu.conversation.repository;

import com.fu.conversation.entity.ConversationTopic;
import com.fu.course.entity.Course;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ConversationTopicRepository extends JpaRepository<ConversationTopic, UUID> {

    Page<ConversationTopic> findByDeletedAtIsNull(Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversation_topics WHERE status = CAST(:status AS content_status) AND deleted_at IS NULL ORDER BY order_index",
           countQuery = "SELECT COUNT(*) FROM speaking.conversation_topics WHERE status = CAST(:status AS content_status) AND deleted_at IS NULL",
           nativeQuery = true)
    Page<ConversationTopic> findByStatusAndDeletedAtIsNull(@Param("status") String status, Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversation_topics WHERE jlpt_level = CAST(:jlptLevel AS jlpt_level) AND status = CAST(:status AS content_status) AND deleted_at IS NULL ORDER BY order_index",
           countQuery = "SELECT COUNT(*) FROM speaking.conversation_topics WHERE jlpt_level = CAST(:jlptLevel AS jlpt_level) AND status = CAST(:status AS content_status) AND deleted_at IS NULL",
           nativeQuery = true)
    Page<ConversationTopic> findByJlptLevelAndStatusAndDeletedAtIsNull(
            @Param("jlptLevel") String jlptLevel, @Param("status") String status, Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversation_topics WHERE deleted_at IS NULL AND (" +
                   "LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                   "LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                   "LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            countQuery = "SELECT COUNT(*) FROM speaking.conversation_topics WHERE deleted_at IS NULL AND (" +
                         "LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                         "LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                         "LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            nativeQuery = true)
    Page<ConversationTopic> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversation_topics WHERE deleted_at IS NULL AND status = CAST(:status AS content_status) AND (" +
                   "LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                   "LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                   "LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            countQuery = "SELECT COUNT(*) FROM speaking.conversation_topics WHERE deleted_at IS NULL AND status = CAST(:status AS content_status) AND (" +
                         "LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                         "LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                         "LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            nativeQuery = true)
    Page<ConversationTopic> searchByKeywordAndStatus(@Param("keyword") String keyword, @Param("status") String status, Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversation_topics " +
                   "WHERE deleted_at IS NULL AND status = CAST('PUBLISHED' AS content_status) AND (" +
                   "LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                   "LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                   "LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
           countQuery = "SELECT COUNT(*) FROM speaking.conversation_topics " +
                        "WHERE deleted_at IS NULL AND status = CAST('PUBLISHED' AS content_status) AND (" +
                        "LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                        "LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                        "LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
           nativeQuery = true)
    Page<ConversationTopic> searchPublishedByKeyword(@Param("keyword") String keyword, Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversation_topics " +
                   "WHERE deleted_at IS NULL AND status = CAST('PUBLISHED' AS content_status) " +
                   "AND jlpt_level = CAST(:jlptLevel AS jlpt_level) AND (" +
                   "LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                   "LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                   "LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            countQuery = "SELECT COUNT(*) FROM speaking.conversation_topics " +
                         "WHERE deleted_at IS NULL AND status = CAST('PUBLISHED' AS content_status) " +
                         "AND jlpt_level = CAST(:jlptLevel AS jlpt_level) AND (" +
                         "LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                         "LOWER(title_japanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
                         "LOWER(category) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            nativeQuery = true)
    Page<ConversationTopic> searchPublishedByKeywordAndJlptLevel(
            @Param("keyword") String keyword,
            @Param("jlptLevel") String jlptLevel,
            Pageable pageable);
}
