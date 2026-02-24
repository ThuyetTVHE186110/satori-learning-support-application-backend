package com.fu.conversation.repository;

import com.fu.conversation.entity.ConversationTopic;
import com.fu.course.entity.Course;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ConversationTopicRepository extends JpaRepository<ConversationTopic, UUID> {

    Page<ConversationTopic> findByThemeIdAndDeletedAtIsNull(UUID themeId, Pageable pageable);

    Page<ConversationTopic> findByThemeIdAndStatusAndDeletedAtIsNull(
            UUID themeId, Course.ContentStatus status, Pageable pageable);

    List<ConversationTopic> findByThemeIdAndStatusAndDeletedAtIsNullOrderByOrderIndexAsc(
            UUID themeId, Course.ContentStatus status);

    /** Used for cascade soft-delete when a theme is deleted. */
    List<ConversationTopic> findByThemeIdAndDeletedAtIsNull(UUID themeId);

    Page<ConversationTopic> findByDeletedAtIsNull(Pageable pageable);
}
