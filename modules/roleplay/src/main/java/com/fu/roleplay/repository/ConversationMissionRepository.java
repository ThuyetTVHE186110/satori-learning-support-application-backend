package com.fu.roleplay.repository;

import com.fu.roleplay.entity.ConversationMission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ConversationMissionRepository extends JpaRepository<ConversationMission, UUID> {

    List<ConversationMission> findByTopicIdAndDeletedAtIsNullOrderByOrderIndexAsc(UUID topicId);

    List<ConversationMission> findByTopicIdAndDeletedAtIsNull(UUID topicId);
}
