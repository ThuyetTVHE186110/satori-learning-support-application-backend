package com.fu.notification.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-memory registry of active SSE emitters keyed by userId.
 *
 * <p>Allows real-time delivery of notifications to browser / desktop clients
 * that are not registered for FCM push. Each open connection registers one
 * {@link SseEmitter}; the registry pushes JSON payloads as {@code notification}
 * SSE events.
 *
 * <p><b>Note:</b> This is a single-node, in-memory solution. For horizontal
 * scaling, replace with Redis Pub/Sub.
 */
@Slf4j
@Component
public class NotificationSseEmitterRegistry {

    // TODO: Replace with Redis Pub/Sub for multi-node deployments
    private final ConcurrentHashMap<UUID, CopyOnWriteArraySet<SseEmitter>> emitters =
            new ConcurrentHashMap<>();

    /** SSE connection timeout: 5 minutes. Client should reconnect automatically. */
    private static final long TIMEOUT_MS = 300_000L;

    /**
     * Opens a new SSE connection for the given user and registers cleanup handlers.
     *
     * @param userId the authenticated user
     * @return a configured {@link SseEmitter} ready to return from the controller
     */
    public SseEmitter register(UUID userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(emitter);

        Runnable cleanup = () -> remove(userId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());

        log.debug("SSE connection opened for user {}", userId);
        return emitter;
    }

    /**
     * Sends a notification payload to all open SSE connections for the given user.
     * Dead emitters (failed send) are removed automatically.
     *
     * @param userId the target user
     * @param data   the JSON-serialisable payload (e.g. {@code NotificationResponse})
     */
    public void send(UUID userId, Object data) {
        Set<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters == null || userEmitters.isEmpty()) {
            return;
        }

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : userEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("notification")
                        .data(data, MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                log.debug("SSE send failed for user {}, removing dead emitter: {}", userId, ex.getMessage());
                dead.add(emitter);
            }
        }
        dead.forEach(e -> remove(userId, e));
    }

    private void remove(UUID userId, SseEmitter emitter) {
        Set<SseEmitter> set = emitters.get(userId);
        if (set != null) {
            set.remove(emitter);
            log.debug("SSE connection closed for user {}", userId);
        }
    }
}
