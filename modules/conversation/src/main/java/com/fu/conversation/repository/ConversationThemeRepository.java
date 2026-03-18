package com.fu.conversation.repository;

import com.fu.conversation.entity.ConversationTheme;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ConversationThemeRepository extends JpaRepository<ConversationTheme, UUID> {

    Page<ConversationTheme> findByDeletedAtIsNull(Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversation_themes WHERE status = CAST(:status AS content_status) AND deleted_at IS NULL ORDER BY order_index",
           countQuery = "SELECT COUNT(*) FROM speaking.conversation_themes WHERE status = CAST(:status AS content_status) AND deleted_at IS NULL",
           nativeQuery = true)
    Page<ConversationTheme> findByStatusAndDeletedAtIsNull(@Param("status") String status, Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversation_themes WHERE jlpt_level = CAST(:jlptLevel AS jlpt_level) AND status = CAST(:status AS content_status) AND deleted_at IS NULL ORDER BY order_index",
           countQuery = "SELECT COUNT(*) FROM speaking.conversation_themes WHERE jlpt_level = CAST(:jlptLevel AS jlpt_level) AND status = CAST(:status AS content_status) AND deleted_at IS NULL",
           nativeQuery = true)
    Page<ConversationTheme> findByJlptLevelAndStatusAndDeletedAtIsNull(
            @Param("jlptLevel") String jlptLevel, @Param("status") String status, Pageable pageable);

    @Query("SELECT t FROM ConversationTheme t WHERE t.deletedAt IS NULL AND (" +
           "LOWER(t.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(t.titleJapanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(t.category) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<ConversationTheme> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT t FROM ConversationTheme t WHERE t.deletedAt IS NULL " +
           "AND t.status = 'PUBLISHED' AND (" +
           "LOWER(t.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(t.titleJapanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(t.category) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<ConversationTheme> searchPublishedByKeyword(@Param("keyword") String keyword, Pageable pageable);
}
