package com.fu.conversation.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Unified SSE event payload for the streaming chat endpoint.
 *
 * <p>Discriminated by {@code type}:
 * <ul>
 *   <li>{@code "token"}  — AI is still generating; {@code content} holds the latest text chunk.</li>
 *   <li>{@code "done"}   — Stream complete; {@code messageId}, {@code sources}, and
 *                          {@code noContextFound} are populated.</li>
 *   <li>{@code "error"}  — Unrecoverable error; {@code message} contains a Vietnamese description.</li>
 * </ul>
 *
 * Frontend should accumulate {@code content} from all {@code token} events, then use
 * the {@code done} event to finalise the message (save ID, show sources, etc.).
 */
@Data
@Builder
public class ChatStreamEvent {

    /** Discriminator: "token" | "done" | "error" */
    private String type;

    // ── token event ────────────────────────────────────────────────────────────
    /** Incremental text chunk from the LLM (present for {@code type="token"} only). */
    private String content;

    // ── done event ─────────────────────────────────────────────────────────────
    /** Persisted message ID (present for {@code type="done"} only). */
    private UUID messageId;

    /** Curriculum sources used to ground the answer (present for {@code type="done"} only). */
    private List<ChatSourceReference> sources;

    /** True when no curriculum context was found (present for {@code type="done"} only). */
    private boolean noContextFound;

    /** Server-side timestamp of the completed exchange (present for {@code type="done"} only). */
    private LocalDateTime timestamp;

    // ── error event ────────────────────────────────────────────────────────────
    /** Vietnamese error description (present for {@code type="error"} only). */
    private String message;

    // ── convenience factories ──────────────────────────────────────────────────

    public static ChatStreamEvent token(String chunk) {
        return ChatStreamEvent.builder().type("token").content(chunk).build();
    }

    public static ChatStreamEvent done(UUID messageId, List<ChatSourceReference> sources,
                                       boolean noContextFound) {
        return ChatStreamEvent.builder()
                .type("done")
                .messageId(messageId)
                .sources(sources)
                .noContextFound(noContextFound)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static ChatStreamEvent error(String message) {
        return ChatStreamEvent.builder().type("error").message(message).build();
    }
}
