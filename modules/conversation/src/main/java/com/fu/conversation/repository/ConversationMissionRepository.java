package com.fu.conversation.repository;

import com.fu.conversation.entity.ConversationMission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ConversationMissionRepository extends JpaRepository<ConversationMission, UUID> {

    /**
     * Returns all active (non-deleted) missions for a conversation, sorted by order index ascending.
     */
    List<ConversationMission> findByConversationIdAndDeletedAtIsNullOrderByOrderIndexAsc(UUID conversationId);

    /**
     * Counts active (non-deleted) missions for a given conversation.
     */
    long countByConversationIdAndDeletedAtIsNull(UUID conversationId);
}
