package com.fu.conversation.service;

import com.fu.conversation.repository.ChatMessageRepository;
import com.fu.conversation.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Manages conversation history persistence for the CURRICULUM_QA chatbot.
 *
 * History loading is handled by {@link com.fu.conversation.ai.memory.CurriculumChatMemoryRepository}
 * which is called by the Spring AI {@code MessageChatMemoryAdvisor}.
 *
 * Token estimate: CJK ≈ 1 token/char, ASCII ≈ 0.25 token/char.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatHistoryManager {

    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionRepository chatSessionRepository;

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
        for (int cp : (Iterable<Integer>) text.codePoints()::iterator) {
            if ((cp >= 0x3000 && cp <= 0x9FFF)
                    || (cp >= 0xF900 && cp <= 0xFAFF)
                    || (cp >= 0xAC00 && cp <= 0xD7AF)) {
                tokens += 1.0;
            } else {
                tokens += 0.25;
            }
        }
        return (int) Math.ceil(tokens);
    }
}
