package com.fu.roleplay.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fu.roleplay.entity.ConversationSession;
import com.fu.roleplay.entity.SessionMission;
import com.fu.roleplay.repository.ConversationSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent "world state" memory for roleplay sessions.
 *
 * <p>Solves the "context lost after mission completion" problem: when sliding-window
 * truncation drops the early establishing turns, the AI forgets facts the learner already
 * established (orders placed, names given, decisions made). Instead of relying on raw
 * dialogue history alone, we maintain a structured fact store that the AI updates via
 * JSON-Patch-like ops on every turn and that the server re-injects into every prompt.
 *
 * <p>Storage: {@code conversation_sessions.context.worldState} JSONB sub-tree.
 *
 * <pre>{@code
 * worldState: {
 *   "schemaVersion": 1,
 *   "facts": { ... AI-controlled, free-form key/value tree ... },
 *   "completedMissions": [
 *     {"id": "...", "title": "...", "completedAtTurn": N, "keyFact": "..."}
 *   ],
 *   "lastUpdatedAtTurn": N
 * }
 * }</pre>
 *
 * <p>Op safety:
 * <ul>
 *   <li>AI ops are applied only to {@code facts.*}. The {@code completedMissions} list is
 *       server-controlled (append-only via {@link #recordCompletedMission}).</li>
 *   <li>Path traversal characters are rejected; dot-segmented paths only.</li>
 *   <li>Size guards prevent unbounded growth of {@code facts} or {@code completedMissions}.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldStateService {

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_FACTS_BYTES = 4096;
    private static final int MAX_COMPLETED_MISSIONS = 50;
    private static final int MAX_PATH_DEPTH = 6;
    private static final int MAX_KEY_FACT_CHARS = 200;

    private final ObjectMapper objectMapper;
    private final ConversationSessionRepository sessionRepository;

    /** Returns the worldState sub-tree for the session, creating an empty one if missing. */
    public ObjectNode loadState(ConversationSession session) {
        try {
            String ctxJson = session.getContext();
            JsonNode ctx = (ctxJson == null || ctxJson.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(ctxJson);
            JsonNode ws = ctx.path("worldState");
            if (ws.isMissingNode() || !ws.isObject()) {
                return emptyState();
            }
            return (ObjectNode) ws;
        } catch (Exception e) {
            log.warn("Failed to load worldState for session {}: {}", session.getId(), e.getMessage());
            return emptyState();
        }
    }

    /**
     * Render an established-facts block to inject into the AI prompt. Returns empty
     * string when there is nothing to share (the prompt should not include a confusing
     * empty block).
     */
    public String renderForPrompt(ConversationSession session) {
        ObjectNode ws = loadState(session);
        JsonNode facts = ws.path("facts");
        JsonNode completed = ws.path("completedMissions");
        boolean hasFacts = facts.isObject() && !facts.isEmpty();
        boolean hasCompleted = completed.isArray() && !completed.isEmpty();
        if (!hasFacts && !hasCompleted) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<world_state>\n");
        sb.append("# ESTABLISHED FACTS — must remain consistent across the entire session.\n");
        sb.append("# Even if earlier dialogue is no longer in the context window, the following\n");
        sb.append("# facts have already been established and you MUST honor them. Do not contradict,\n");
        sb.append("# do not re-ask, do not reset.\n\n");

        if (hasFacts) {
            sb.append("## Facts:\n");
            renderJsonAsTree(facts, sb, 0);
            sb.append("\n");
        }
        if (hasCompleted) {
            sb.append("## Missions already completed:\n");
            int cap = Math.min(completed.size(), 10);
            int skipped = completed.size() - cap;
            if (skipped > 0) {
                sb.append("(... ").append(skipped).append(" earlier completed missions omitted ...)\n");
            }
            for (int i = completed.size() - cap; i < completed.size(); i++) {
                JsonNode entry = completed.get(i);
                sb.append("- [turn ").append(entry.path("completedAtTurn").asInt(-1)).append("] ");
                sb.append(entry.path("title").asText("")).append(" — ");
                sb.append(entry.path("keyFact").asText("")).append("\n");
            }
        }
        sb.append("</world_state>\n");
        return sb.toString();
    }

    /**
     * Apply an array of JSON-Patch-like ops to {@code facts}. Each op is an object with
     * {@code op}, {@code path} (relative to facts root, dot-segmented), and {@code value}.
     * Supported ops: {@code set}, {@code append}, {@code remove}, {@code inc}.
     * Anything outside {@code facts.*} is silently rejected — only the AI controls facts.
     */
    public void applyDelta(ConversationSession session, JsonNode opsArray, int turn) {
        if (opsArray == null || !opsArray.isArray() || opsArray.isEmpty()) {
            return;
        }
        try {
            ObjectNode ws = loadState(session);
            ObjectNode facts = ws.has("facts") && ws.get("facts").isObject()
                    ? (ObjectNode) ws.get("facts")
                    : ws.putObject("facts");

            int applied = 0;
            for (JsonNode op : opsArray) {
                if (!op.isObject()) continue;
                String opName = op.path("op").asText("");
                String path = op.path("path").asText("");
                JsonNode value = op.get("value");
                if (!isSafePath(path)) {
                    log.debug("Skip unsafe worldState op path='{}'", path);
                    continue;
                }
                String[] segs = path.split("\\.");
                if (segs.length > MAX_PATH_DEPTH) {
                    log.debug("Skip too-deep worldState op path='{}'", path);
                    continue;
                }
                switch (opName) {
                    case "set" -> { if (value != null) { setAtPath(facts, segs, value); applied++; } }
                    case "append" -> { if (value != null) { appendAtPath(facts, segs, value); applied++; } }
                    case "remove" -> { removeAtPath(facts, segs); applied++; }
                    case "inc" -> { incAtPath(facts, segs, value != null ? value.asInt(1) : 1); applied++; }
                    default -> log.debug("Unknown worldState op '{}' — skipped", opName);
                }
            }
            if (applied == 0) return;

            // Size guard — drop the whole delta if it pushes facts past the cap.
            byte[] factsBytes = objectMapper.writeValueAsBytes(facts);
            if (factsBytes.length > MAX_FACTS_BYTES) {
                log.warn("worldState.facts exceeded {} bytes after delta in session {}; reverting delta",
                        MAX_FACTS_BYTES, session.getId());
                return; // reload-on-next-call discards the in-memory mutation
            }

            ws.put("schemaVersion", SCHEMA_VERSION);
            ws.put("lastUpdatedAtTurn", turn);
            persistState(session, ws);
            log.debug("Applied {} worldState ops to session {} at turn {}", applied, session.getId(), turn);
        } catch (Exception e) {
            log.warn("Failed to apply worldState delta for session {}: {}", session.getId(), e.getMessage());
        }
    }

    /** Append a completed-mission entry. Server-controlled, never from AI ops. */
    public void recordCompletedMission(ConversationSession session, SessionMission mission,
                                       String reasoning, int turn) {
        try {
            ObjectNode ws = loadState(session);
            ArrayNode completed = ws.has("completedMissions") && ws.get("completedMissions").isArray()
                    ? (ArrayNode) ws.get("completedMissions")
                    : ws.putArray("completedMissions");

            // De-dupe: skip if this mission id is already recorded.
            String missionId = mission.getId().toString();
            for (JsonNode entry : completed) {
                if (missionId.equals(entry.path("id").asText())) {
                    return;
                }
            }
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("id", missionId);
            entry.put("title", safeTrim(mission.getTitle(), 120));
            entry.put("completedAtTurn", turn);
            entry.put("keyFact", safeTrim(reasoning, MAX_KEY_FACT_CHARS));
            completed.add(entry);

            // Cap log size — drop oldest beyond the limit.
            while (completed.size() > MAX_COMPLETED_MISSIONS) {
                completed.remove(0);
            }
            ws.put("schemaVersion", SCHEMA_VERSION);
            ws.put("lastUpdatedAtTurn", turn);
            persistState(session, ws);
        } catch (Exception e) {
            log.warn("Failed to record completed mission for session {}: {}", session.getId(), e.getMessage());
        }
    }

    // ─── private helpers ──────────────────────────────────────────────────────────

    private ObjectNode emptyState() {
        ObjectNode ws = objectMapper.createObjectNode();
        ws.put("schemaVersion", SCHEMA_VERSION);
        ws.putObject("facts");
        ws.putArray("completedMissions");
        ws.put("lastUpdatedAtTurn", 0);
        return ws;
    }

    private void persistState(ConversationSession session, ObjectNode ws) throws Exception {
        String ctxJson = session.getContext();
        ObjectNode ctx = (ctxJson == null || ctxJson.isBlank())
                ? objectMapper.createObjectNode()
                : (ObjectNode) objectMapper.readTree(ctxJson);
        ctx.set("worldState", ws);
        session.setContext(objectMapper.writeValueAsString(ctx));
        sessionRepository.save(session);
    }

    private static boolean isSafePath(String path) {
        if (path == null || path.isBlank()) return false;
        // Allow letters, digits, underscore, dot, hyphen. Reject brackets/quotes/spaces.
        return path.matches("[A-Za-z0-9_\\-]+(\\.[A-Za-z0-9_\\-]+)*");
    }

    private static void setAtPath(ObjectNode root, String[] segs, JsonNode value) {
        ObjectNode cur = root;
        for (int i = 0; i < segs.length - 1; i++) {
            JsonNode next = cur.get(segs[i]);
            if (next == null || !next.isObject()) {
                next = cur.putObject(segs[i]);
            }
            cur = (ObjectNode) next;
        }
        cur.set(segs[segs.length - 1], value);
    }

    private static void appendAtPath(ObjectNode root, String[] segs, JsonNode value) {
        ObjectNode cur = root;
        for (int i = 0; i < segs.length - 1; i++) {
            JsonNode next = cur.get(segs[i]);
            if (next == null || !next.isObject()) {
                next = cur.putObject(segs[i]);
            }
            cur = (ObjectNode) next;
        }
        String last = segs[segs.length - 1];
        JsonNode existing = cur.get(last);
        ArrayNode arr;
        if (existing != null && existing.isArray()) {
            arr = (ArrayNode) existing;
        } else {
            arr = cur.putArray(last);
        }
        arr.add(value);
    }

    private static void removeAtPath(ObjectNode root, String[] segs) {
        ObjectNode cur = root;
        for (int i = 0; i < segs.length - 1; i++) {
            JsonNode next = cur.get(segs[i]);
            if (next == null || !next.isObject()) return;
            cur = (ObjectNode) next;
        }
        cur.remove(segs[segs.length - 1]);
    }

    private static void incAtPath(ObjectNode root, String[] segs, int delta) {
        ObjectNode cur = root;
        for (int i = 0; i < segs.length - 1; i++) {
            JsonNode next = cur.get(segs[i]);
            if (next == null || !next.isObject()) {
                next = cur.putObject(segs[i]);
            }
            cur = (ObjectNode) next;
        }
        String last = segs[segs.length - 1];
        int current = cur.path(last).asInt(0);
        cur.put(last, current + delta);
    }

    private static String safeTrim(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** Render JSON tree as indented bullet list for prompt readability. */
    private static void renderJsonAsTree(JsonNode node, StringBuilder sb, int depth) {
        String indent = "  ".repeat(depth);
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                if (v.isValueNode()) {
                    sb.append(indent).append("- ").append(e.getKey()).append(": ")
                      .append(v.asText("")).append("\n");
                } else {
                    sb.append(indent).append("- ").append(e.getKey()).append(":\n");
                    renderJsonAsTree(v, sb, depth + 1);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isValueNode()) {
                    sb.append(indent).append("- ").append(item.asText("")).append("\n");
                } else {
                    sb.append(indent).append("-\n");
                    renderJsonAsTree(item, sb, depth + 1);
                }
            }
        }
    }

    /** Convenience: parse and apply a {@code WORLD_STATE_DELTA} segment from an AI response array. */
    public void extractAndApplyDelta(ConversationSession session, JsonNode aiResponseArray, int turn) {
        if (aiResponseArray == null || !aiResponseArray.isArray()) return;
        for (JsonNode seg : aiResponseArray) {
            if ("WORLD_STATE_DELTA".equals(seg.path("type").asText())) {
                applyDelta(session, seg.path("ops"), turn);
            }
        }
    }

    /** Convenience overload accepting a list of segments already parsed. */
    public void extractAndApplyDelta(ConversationSession session, List<? extends JsonNode> segments, int turn) {
        if (segments == null) return;
        for (JsonNode seg : segments) {
            if ("WORLD_STATE_DELTA".equals(seg.path("type").asText())) {
                applyDelta(session, seg.path("ops"), turn);
            }
        }
    }

    /** Make {@link LinkedHashMap} import non-empty for tooling — also used by tests in helper paths. */
    @SuppressWarnings("unused")
    private static Map<String, Object> noopMap() { return new LinkedHashMap<>(); }
}
