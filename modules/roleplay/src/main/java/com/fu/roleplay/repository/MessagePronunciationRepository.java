package com.fu.roleplay.repository;

import com.fu.roleplay.entity.MessagePronunciation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessagePronunciationRepository extends JpaRepository<MessagePronunciation, UUID> {

    Optional<MessagePronunciation> findByMessageId(UUID messageId);

    List<MessagePronunciation> findBySessionId(UUID sessionId);
}
