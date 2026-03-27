package com.fu.conversation.service;

import com.fu.conversation.dto.response.ChatHistoryItemResponse;
import com.fu.conversation.dto.response.ChatMessageResponse;
import com.fu.conversation.dto.response.ChatSessionResponse;

import java.util.List;
import java.util.UUID;

/**
 * Business operations for the CURRICULUM_QA RAG chatbot.
 */
public interface CurriculumChatService {

    /** Create a new chatbot session for an enrolled learner. */
    ChatSessionResponse createSession(UUID userId, UUID courseId,
                                      UUID lessonId, String jlptLevel);

    /** Send a question; triggers RAG retrieval + AI response + message persistence. */
    ChatMessageResponse chat(UUID sessionId, UUID userId, String question);

    /** Paginated message history for a session (caller must own the session). */
    List<ChatHistoryItemResponse> getHistory(UUID sessionId, UUID userId, int page, int size);

    /** Mark the session as COMPLETED. */
    void closeSession(UUID sessionId, UUID userId);
}
