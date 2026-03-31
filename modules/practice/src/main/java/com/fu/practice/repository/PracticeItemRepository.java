package com.fu.practice.repository;

import com.fu.practice.entity.PracticeItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PracticeItemRepository extends JpaRepository<PracticeItem, UUID> {

    /**
     * Lấy tất cả item của session, sắp xếp theo thứ tự.
     */
    List<PracticeItem> findBySessionIdOrderByItemIndex(UUID sessionId);

    /**
     * Lấy câu hỏi tại vị trí cụ thể trong session — dùng bởi submitAnswer để
     * lấy câu tiếp theo đã được sinh sẵn từ startSession.
     */
    java.util.Optional<PracticeItem> findBySessionIdAndItemIndex(UUID sessionId, int itemIndex);

    /**
     * Lấy các item chưa được trả lời trong session.
     */
    @Query("SELECT pi FROM PracticeItem pi WHERE pi.sessionId = :sessionId AND pi.userAnswer IS NULL ORDER BY pi.itemIndex ASC")
    List<PracticeItem> findBySessionIdAndUserAnswerIsNull(@Param("sessionId") UUID sessionId);

    /**
     * Đếm số item đúng trong session (để tính lại nếu cần).
     */
    @Query("SELECT COUNT(pi) FROM PracticeItem pi WHERE pi.sessionId = :sessionId AND pi.isCorrect = true")
    long countCorrectBySessionId(@Param("sessionId") UUID sessionId);

    /**
     * Lấy các item sai trong session (cho feedback).
     */
    @Query("SELECT pi FROM PracticeItem pi WHERE pi.sessionId = :sessionId AND pi.isCorrect = false ORDER BY pi.itemIndex ASC")
    List<PracticeItem> findIncorrectBySessionId(@Param("sessionId") UUID sessionId);
}
