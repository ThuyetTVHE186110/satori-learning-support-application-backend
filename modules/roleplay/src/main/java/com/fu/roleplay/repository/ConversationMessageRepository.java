package com.fu.roleplay.repository;

import com.fu.roleplay.dto.entity.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, UUID> {

    List<ConversationMessage> findBySessionIdOrderBySequenceNumberAsc(UUID sessionId);

    long countBySessionId(UUID sessionId);

    @Query(value = "SELECT COUNT(*) FROM ai_conversation.conversation_messages WHERE session_id = :sessionId AND role = CAST(:role AS message_role)",
           nativeQuery = true)
    long countBySessionIdAndRole(@Param("sessionId") UUID sessionId, @Param("role") String role);

    @Query("SELECT MAX(m.sequenceNumber) FROM ConversationMessage m WHERE m.sessionId = :sessionId")
    Optional<Integer> findMaxSequenceNumberBySessionId(@Param("sessionId") UUID sessionId);
}
