package com.fu.conversation.repository;

import com.fu.conversation.entity.ConversationTheme;
import com.fu.course.entity.Course;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ConversationThemeRepository extends JpaRepository<ConversationTheme, UUID> {

    Page<ConversationTheme> findByDeletedAtIsNull(Pageable pageable);

    Page<ConversationTheme> findByStatusAndDeletedAtIsNull(Course.ContentStatus status, Pageable pageable);

    Page<ConversationTheme> findByJlptLevelAndStatusAndDeletedAtIsNull(
            Course.JlptLevel jlptLevel, Course.ContentStatus status, Pageable pageable);

    @Query("SELECT t FROM ConversationTheme t WHERE t.deletedAt IS NULL AND (" +
           "LOWER(t.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(t.titleJapanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(t.category) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<ConversationTheme> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT t FROM ConversationTheme t WHERE t.deletedAt IS NULL " +
           "AND t.status = 'PUBLISHED' AND (" +
           "LOWER(t.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(t.titleJapanese) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(t.category) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<ConversationTheme> searchPublishedByKeyword(@Param("keyword") String keyword, Pageable pageable);
}
