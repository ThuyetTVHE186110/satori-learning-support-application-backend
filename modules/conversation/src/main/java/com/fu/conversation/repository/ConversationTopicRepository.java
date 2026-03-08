package com.fu.conversation.repository;

import com.fu.conversation.entity.ConversationTopic;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ConversationTopicRepository extends JpaRepository<ConversationTopic, UUID> {

    Page<ConversationTopic> findByThemeIdAndDeletedAtIsNull(UUID themeId, Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversation_topics WHERE theme_id = :themeId AND status = CAST(:status AS content_status) AND deleted_at IS NULL ORDER BY order_index",
           countQuery = "SELECT COUNT(*) FROM speaking.conversation_topics WHERE theme_id = :themeId AND status = CAST(:status AS content_status) AND deleted_at IS NULL",
           nativeQuery = true)
    Page<ConversationTopic> findByThemeIdAndStatusAndDeletedAtIsNull(
            @Param("themeId") UUID themeId, @Param("status") String status, Pageable pageable);

    @Query(value = "SELECT * FROM speaking.conversation_topics WHERE theme_id = :themeId AND status = CAST(:status AS content_status) AND deleted_at IS NULL ORDER BY order_index ASC",
           nativeQuery = true)
    List<ConversationTopic> findByThemeIdAndStatusAndDeletedAtIsNullOrderByOrderIndexAsc(
            @Param("themeId") UUID themeId, @Param("status") String status);

    /** Used for cascade soft-delete when a theme is deleted. */
    List<ConversationTopic> findByThemeIdAndDeletedAtIsNull(UUID themeId);

    Page<ConversationTopic> findByDeletedAtIsNull(Pageable pageable);

    long countByThemeIdAndDeletedAtIsNull(UUID themeId);
}
