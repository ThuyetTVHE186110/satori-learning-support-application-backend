package com.fu.conversation.service;

import com.fu.conversation.entity.ChatMessage;
import com.fu.conversation.repository.ChatMessageRepository;
import com.fu.conversation.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Manages conversation history for the CURRICULUM_QA chatbot.
 *
 * Sliding-window strategy (token-aware):
 * - Loads up to {@value MAX_DB_MESSAGES} messages (newest first) from DB
 * - Walks newest → oldest, accumulating token estimates
 * - Stops when the token budget is exhausted, then reverses to chronological order
 *
 * Token estimate: CJK ≈ 1 token/char, ASCII ≈ 0.25 token/char
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatHistoryManager {

    /** Token budget reserved for history (leaves ~2500 tokens for system + RAG + new question). */
    private static final int MAX_HISTORY_TOKENS = 2000;

    /** Max messages fetched from DB per request (avoids large queries). */
    private static final int MAX_DB_MESSAGES = 40;

    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionRepository chatSessionRepository;

    /**
     * Load sliding-window history for a session as Spring AI {@link Message} objects.
     *
     * @param sessionId UUID of the chat session
     * @return messages in chronological order (oldest → newest), within token budget
     */
    @Transactional(readOnly = true)
    public List<Message> loadHistory(UUID sessionId) {
        if (sessionId == null) return List.of();

        try {
            List<ChatMessage> rows = chatMessageRepository.findRecentBySessionId(
                    sessionId, PageRequest.of(0, MAX_DB_MESSAGES));

            if (rows.isEmpty()) return List.of();

            // Walk newest → oldest, accumulate token budget
            List<Message> window = new ArrayList<>();
            int tokenCount = 0;

            for (ChatMessage row : rows) {
                int tokens = estimateTokens(row.getContent());
                if (tokenCount + tokens > MAX_HISTORY_TOKENS) {
                    log.debug("History window: stopped at {} messages ({} tokens)",
                            window.size(), tokenCount);
                    break;
                }
                window.add("USER".equals(row.getRole())
                        ? new UserMessage(row.getContent())
                        : new AssistantMessage(row.getContent()));
                tokenCount += tokens;
            }

            Collections.reverse(window); // chronological order
            return window;

        } catch (Exception e) {
            log.warn("Cannot load chat history for session {}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Persist a USER/ASSISTANT exchange and update the session message counters.
     *
     * @param sessionId   UUID of the chat session
     * @param userMessage learner's question
     * @param aiResponse  AI's answer
     * @return UUID of the persisted AI message (used as the API response's messageId)
     */
    @Transactional
    public UUID saveExchange(UUID sessionId, String userMessage, String aiResponse) {
        UUID userMsgId = UUID.randomUUID();
        UUID aiMsgId   = UUID.randomUUID();

        chatMessageRepository.saveExchange(sessionId, userMsgId, userMessage, aiMsgId, aiResponse);
        chatSessionRepository.incrementMessageCount(sessionId);

        return aiMsgId;
    }

    /**
     * Estimate the number of tokens in a text string.
     * CJK characters (Japanese/Korean/Chinese) ≈ 1 token each;
     * ASCII/Latin characters ≈ 0.25 tokens each.
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        double tokens = 0;
        for (char c : text.toCharArray()) {
            if ((c >= 0x3000 && c <= 0x9FFF) || (c >= 0xF900 && c <= 0xFAFF)
                    || (c >= 0xAC00 && c <= 0xD7AF)) {
                tokens += 1.0;
            } else {
                tokens += 0.25;
            }
        }
        return (int) Math.ceil(tokens);
    }
}
