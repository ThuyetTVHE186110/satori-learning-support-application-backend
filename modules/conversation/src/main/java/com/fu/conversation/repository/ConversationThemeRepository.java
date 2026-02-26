package com.fu.conversation.repository;

import com.fu.conversation.entity.ConversationTheme;
import com.fu.course.entity.Course;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ConversationThemeRepository extends JpaRepository<ConversationTheme, UUID> {

    Page<ConversationTheme> findByDeletedAtIsNull(Pageable pageable);

    Page<ConversationTheme> findByStatusAndDeletedAtIsNull(Course.ContentStatus status, Pageable pageable);

    Page<ConversationTheme> findByJlptLevelAndStatusAndDeletedAtIsNull(
            Course.JlptLevel jlptLevel, Course.ContentStatus status, Pageable pageable);
}
