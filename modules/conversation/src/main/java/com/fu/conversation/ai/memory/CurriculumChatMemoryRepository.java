package com.fu.conversation.ai.memory;

import com.fu.conversation.config.ChatProperties;
import com.fu.conversation.entity.ChatMessage;
import com.fu.conversation.repository.ChatMessageRepository;
import com.fu.conversation.service.ChatHistoryManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Spring AI {@link ChatMemoryRepository} backed by the chat_messages table.
 *
 * <p>Implements a token-aware sliding window: fetches up to {@value MAX_DB_MESSAGES}
 * messages newest-first, cuts when the token budget from {@code ChatProperties}
 * is exhausted, then returns in chronological order for the advisor to inject
 * into the prompt.
 *
 * <p>{@link #saveAll} is intentionally a no-op. Persistence is handled explicitly
 * by {@link ChatHistoryManager#saveExchange} after the ChatClient call completes,
 * using an atomic CTE insert that increments the session message counter in one
 * round-trip. Allowing the advisor to also call saveAll would cause double-writes.
 */
@Component
@Slf4j
public class CurriculumChatMemoryRepository implements ChatMemoryRepository {

    private static final int MAX_DB_MESSAGES = 40;

    private final ChatMessageRepository chatMessageRepository;
    private final ChatProperties chatProperties;

    public CurriculumChatMemoryRepository(ChatMessageRepository chatMessageRepository,
                                           ChatProperties chatProperties) {
        this.chatMessageRepository = chatMessageRepository;
        this.chatProperties = chatProperties;
    }

    @Override
    public List<String> findConversationIds() {
        return List.of(); // Not needed for per-session chatbot use case
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return List.of();
        try {
            UUID sessionId = UUID.fromString(conversationId);
            List<ChatMessage> rows = chatMessageRepository.findRecentBySessionId(
                    sessionId, PageRequest.of(0, MAX_DB_MESSAGES));

            if (rows.isEmpty()) return List.of();

            // Walk newest → oldest, accumulate token budget
            List<Message> window = new ArrayList<>();
            int tokenCount = 0;
            for (ChatMessage row : rows) {
                int tokens = ChatHistoryManager.estimateTokens(row.getContent());
                if (tokenCount + tokens > chatProperties.getMaxHistoryTokens()) {
                    log.debug("Memory window: stopped at {} messages ({} tokens) for session {}",
                            window.size(), tokenCount, conversationId);
                    break;
                }
                window.add("USER".equals(row.getRole())
                        ? new UserMessage(row.getContent())
                        : new AssistantMessage(row.getContent()));
                tokenCount += tokens;
            }

            Collections.reverse(window); // chronological order for prompt injection
            return window;

        } catch (IllegalArgumentException e) {
            log.warn("findByConversationId: invalid UUID '{}': {}", conversationId, e.getMessage());
            return List.of();
        } catch (Exception e) {
            log.warn("findByConversationId failed for {}: {}", conversationId, e.getMessage());
            return List.of();
        }
    }

    /**
     * No-op — persistence is handled by {@link ChatHistoryManager#saveExchange}.
     * {@link org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor} calls
     * this in its {@code after()} phase; we deliberately ignore it to avoid double-writes.
     */
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        // intentional no-op
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        // Not needed: sessions are closed by status update, not hard-deleted
    }
}
