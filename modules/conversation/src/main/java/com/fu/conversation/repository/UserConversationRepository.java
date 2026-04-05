package com.fu.conversation.repository;

import com.fu.conversation.entity.UserConversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserConversationRepository extends JpaRepository<UserConversation, UUID> {

    Page<UserConversation> findByUserId(UUID userId, Pageable pageable);

    Optional<UserConversation> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByIdAndUserId(UUID id, UUID userId);
}
