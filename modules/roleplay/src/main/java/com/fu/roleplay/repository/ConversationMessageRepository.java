package com.fu.roleplay.repository;

import com.fu.roleplay.entity.ConversationMessage;
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

    long countBySessionIdAndRole(UUID sessionId, ConversationMessage.MessageRole role);

    @Query("SELECT MAX(m.sequenceNumber) FROM ConversationMessage m WHERE m.sessionId = :sessionId")
    Optional<Integer> findMaxSequenceNumberBySessionId(@Param("sessionId") UUID sessionId);
}
