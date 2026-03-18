package com.fu.conversation.repository;

import com.fu.conversation.entity.UserConversationTopic;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserConversationTopicRepository extends JpaRepository<UserConversationTopic, UUID> {

    Page<UserConversationTopic> findByUserId(UUID userId, Pageable pageable);

    Optional<UserConversationTopic> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByIdAndUserId(UUID id, UUID userId);
}
