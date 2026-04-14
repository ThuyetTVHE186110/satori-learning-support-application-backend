package com.fu.conversation.ai.memory;

import com.fu.conversation.config.ChatProperties;
import com.fu.conversation.entity.ChatMessage;
import com.fu.conversation.repository.ChatMessageRepository;
import com.fu.conversation.repository.ChatSessionRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spring AI {@link ChatMemoryRepository} backed by the chat_messages table.
 *
 * <p>Implements a token-aware sliding window: fetches up to {@code maxDbMessages}
 * messages newest-first, cuts when the token budget from {@code ChatProperties}
 * is exhausted, then returns in chronological order for the advisor to inject
 * into the prompt.
 *
 * <p><b>Scope anchor</b>: when the sliding window drops old messages (truncation),
 * a synthetic scope-reminder is prepended to the window so the AI never loses
 * track of which course/lesson is being discussed. This prevents confusing
 * responses when early session context falls outside the token budget.
 * The anchor is a lightweight {@link AssistantMessage} (not a system message)
 * prepended only when ≥ {@value TRUNCATION_THRESHOLD} messages were dropped.
 *
 * <p>{@link #saveAll} is intentionally a no-op. Persistence is handled explicitly
 * by {@link ChatHistoryManager#saveExchange} after the ChatClient call completes,
 * using an atomic CTE insert that increments the session message counter in one
 * round-trip. Allowing the advisor to also call saveAll would cause double-writes.
 */
@Component
@Slf4j
public class CurriculumChatMemoryRepository implements ChatMemoryRepository {


    /**
     * Minimum number of messages dropped before injecting a scope anchor.
     * Below this threshold, the AI can infer context from the remaining window;
     * anchoring on every minor truncation would add noise to short conversations.
     */
    private static final int TRUNCATION_THRESHOLD = 4;

    private final ChatMessageRepository chatMessageRepository;
    private final ChatProperties chatProperties;
    private final ChatSessionRepository chatSessionRepository;

    /** Bounded LRU cache of session titles. Uses a sentinel for null titles to avoid repeated DB queries. */
    private static final String NULL_SENTINEL = "__NO_TITLE__";
    private static final int MAX_CACHE_SIZE = 200;
    private final Map<UUID, String> sessionTitleCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, String> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            });

    public CurriculumChatMemoryRepository(ChatMessageRepository chatMessageRepository,
                                           ChatProperties chatProperties,
                                           ChatSessionRepository chatSessionRepository) {
        this.chatMessageRepository = chatMessageRepository;
        this.chatProperties = chatProperties;
        this.chatSessionRepository = chatSessionRepository;
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
                    sessionId, PageRequest.of(0, chatProperties.getMaxDbMessages()));

            if (rows.isEmpty()) return List.of();

            // Walk newest → oldest, accumulate token budget
            List<Message> window = new ArrayList<>();
            int tokenCount = 0;
            int droppedCount = 0;
            for (ChatMessage row : rows) {
                int tokens = ChatHistoryManager.estimateTokens(row.getContent());
                if (tokenCount + tokens > chatProperties.getMaxHistoryTokens()) {
                    droppedCount = rows.size() - window.size();
                    log.debug("Memory window: stopped at {} messages ({} tokens) for session {} ({} dropped)",
                            window.size(), tokenCount, conversationId, droppedCount);
                    break;
                }
                window.add("USER".equals(row.getRole())
                        ? new UserMessage(row.getContent())
                        : new AssistantMessage(row.getContent()));
                tokenCount += tokens;
            }

            Collections.reverse(window); // chronological order for prompt injection

            // ── Scope anchor: re-inject session context when truncation drops old messages ──
            // Without this, a long conversation loses the topic established in early turns
            // (e.g. "bài 1 có những từ gì?" → later "những từ đó" has no referent).
            // Note: course/lesson is already in the system prompt — the anchor only helps
            // the AI remember topic-anchoring USER statements that were cut from the window.
            if (droppedCount >= TRUNCATION_THRESHOLD) {
                String anchor = buildScopeAnchor(sessionId);
                if (anchor != null) {
                    window.add(0, new AssistantMessage(anchor));
                    log.debug("Scope anchor injected for session {} ({} messages dropped): '{}'",
                            conversationId, droppedCount, anchor);
                }
            }

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
     * Build a concise scope-anchor text from the session's title.
     * Returns {@code null} when the title is unavailable (session not found, title not set).
     *
     * The anchor is phrased as an ASSISTANT message so it reads naturally in the
     * conversation history — "I'm helping you with..." rather than a raw system directive.
     */
    private String buildScopeAnchor(UUID sessionId) {
        try {
            String cached = sessionTitleCache.get(sessionId);
            if (cached == null) {
                // Not in cache — query DB once, store result (or sentinel for no-title)
                String title = chatSessionRepository.findById(sessionId)
                        .map(s -> s.getTitle())
                        .orElse(null);
                cached = (title != null && !title.isBlank()) ? title : NULL_SENTINEL;
                sessionTitleCache.put(sessionId, cached);
            }
            if (NULL_SENTINEL.equals(cached)) return null;
            return "[Nhắc nhở ngữ cảnh] Phiên học này đang thảo luận về: " + cached;
        } catch (Exception e) {
            log.debug("buildScopeAnchor failed for session {}: {}", sessionId, e.getMessage());
            return null;
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
