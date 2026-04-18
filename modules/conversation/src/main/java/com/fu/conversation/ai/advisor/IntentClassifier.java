package com.fu.conversation.ai.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Semantic intent classifier — replaces the regex-based {@code DomainScopeAdvisor}.
 *
 * <p>On {@link ApplicationReadyEvent} the classifier loads a curated exemplar YAML
 * (path configurable via {@link IntentRouterProperties#getExemplarResource()}),
 * batch-embeds every exemplar via OpenAI, and keeps the vectors in memory. For
 * each incoming user query, {@link #classify(String)} embeds the query once and
 * takes the per-category top-1 cosine similarity. If the best off-topic category
 * beats the best in-scope category by more than the configured margin, the
 * classifier reports {@code offTopic = true}.
 *
 * <p><b>Design choices (acknowledged guard-rails):</b>
 * <ul>
 *   <li><b>Fail-open</b> — any YAML / embedding / runtime error leaves
 *       {@link #ready} at {@code false}; callers get {@link Decision#PASS}.
 *       We never refuse a query because of an internal failure.</li>
 *   <li><b>Non-blocking startup</b> — embedding happens on
 *       {@link ApplicationReadyEvent}, after the context is fully initialized.
 *       Chat traffic before the classifier is warm simply bypasses routing.</li>
 *   <li><b>No catch-all off-topic category</b> — only concrete intents
 *       (math / coding / cooking / personal). A "miscellaneous" bucket
 *       inevitably drifts toward matching anything.</li>
 *   <li><b>Top-1 per category, not mean</b> — means dilute: one matching
 *       exemplar is enough signal, and averaging buries it under noise from
 *       the tail of the list.</li>
 *   <li><b>LRU cache</b> — same query repeated in a session is common; caching
 *       the decision avoids a second embedding call.</li>
 * </ul>
 */
@Service
@Slf4j
public class IntentClassifier {

    /** YAML top-level key for off-topic category lists. */
    static final String OFF_TOPIC_KEY = "off-topic";
    /** YAML top-level key for in-scope category lists. */
    static final String IN_SCOPE_KEY = "in-scope";

    private final OpenAiEmbeddingModel embeddingModel;
    private final IntentRouterProperties props;

    /** {@code true} once exemplars are loaded + embedded. Until then, classify() returns PASS. */
    private volatile boolean ready = false;

    /** category-name → list of exemplar embeddings (one float[] per exemplar). */
    private Map<String, List<float[]>> offTopicExemplars = Map.of();
    private Map<String, List<float[]>> inScopeExemplars = Map.of();

    /**
     * LRU cache of query → decision. Access-order {@link LinkedHashMap} wrapped
     * with {@link Collections#synchronizedMap} for thread safety. Small (256)
     * by default — the goal is to dedupe repeats within a single chat session,
     * not to cache across the entire application lifetime.
     */
    private final Map<String, Decision> cache;

    public IntentClassifier(OpenAiEmbeddingModel embeddingModel, IntentRouterProperties props) {
        this.embeddingModel = embeddingModel;
        this.props = props;
        this.cache = props.getCacheSize() > 0
                ? Collections.synchronizedMap(new LinkedHashMap<>(props.getCacheSize(), 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Decision> eldest) {
                        return size() > props.getCacheSize();
                    }
                })
                : null;
    }

    /**
     * Load exemplars from classpath YAML and embed them in batch. Runs after the
     * application context is ready so the OpenAI bean + config are available.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        if (!props.isEnabled()) {
            log.info("IntentClassifier disabled via conversation.intent-router.enabled=false");
            return;
        }
        try {
            Map<String, Map<String, List<String>>> parsed = loadExemplarsYaml();
            this.offTopicExemplars = embedGroup(parsed.getOrDefault(OFF_TOPIC_KEY, Map.of()));
            this.inScopeExemplars = embedGroup(parsed.getOrDefault(IN_SCOPE_KEY, Map.of()));
            this.ready = true;
            log.info("IntentClassifier ready: offTopic categories={} inScope categories={}",
                    offTopicExemplars.keySet(), inScopeExemplars.keySet());
        } catch (Exception e) {
            // Fail-open: stay not-ready, all queries pass through.
            log.error("IntentClassifier initialization failed — router will pass-through: {}",
                    e.getMessage(), e);
        }
    }

    /**
     * Classify the query. Returns {@link Decision#PASS} when the classifier is
     * not ready, disabled, or the query is blank. On any embedding failure the
     * method also returns PASS — we never refuse on uncertainty.
     */
    public Decision classify(String query) {
        if (!ready || !props.isEnabled() || query == null || query.isBlank()) {
            return Decision.PASS;
        }

        String key = query.trim().toLowerCase();
        if (cache != null) {
            Decision cached = cache.get(key);
            if (cached != null) return cached;
        }

        Decision decision;
        try {
            float[] q = embeddingModel.embed(key);
            if (q == null || q.length == 0) return Decision.PASS;

            CategoryScore bestOff = topScore(q, offTopicExemplars);
            CategoryScore bestIn = topScore(q, inScopeExemplars);

            boolean offTopic = bestOff.score >= props.getMinOffTopicScore()
                    && (bestOff.score - bestIn.score) >= props.getMargin();
            decision = new Decision(offTopic, bestOff.category, bestOff.score,
                    bestIn.category, bestIn.score);
        } catch (Exception e) {
            log.warn("IntentClassifier.classify failed — passing through: {}", e.getMessage());
            return Decision.PASS;
        }

        if (cache != null) cache.put(key, decision);
        return decision;
    }

    /** Visible for tests — whether the exemplar set has finished embedding. */
    public boolean isReady() {
        return ready;
    }

    /**
     * Visible for tests only — seeds pre-computed exemplar vectors, bypassing
     * YAML loading and live embedding calls. Test code constructs orthogonal
     * vectors so {@link #cosine} returns deterministic, easy-to-reason-about
     * scores (1.0 for identical, 0.0 for orthogonal).
     */
    void seedForTest(Map<String, List<float[]>> offTopic, Map<String, List<float[]>> inScope) {
        this.offTopicExemplars = offTopic == null ? Map.of() : offTopic;
        this.inScopeExemplars = inScope == null ? Map.of() : inScope;
        this.ready = true;
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * Reads and parses the exemplar YAML. The expected structure is:
     *
     * <pre>
     * off-topic:
     *   math:    [ "1+1 bằng mấy", ... ]
     *   coding:  [ ... ]
     * in-scope:
     *   vocabulary: [ ... ]
     *   grammar:    [ ... ]
     * </pre>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, List<String>>> loadExemplarsYaml() throws Exception {
        ClassPathResource resource = new ClassPathResource(props.getExemplarResource());
        try (InputStream in = resource.getInputStream()) {
            Yaml yaml = new Yaml();
            Object root = yaml.load(in);
            if (!(root instanceof Map<?, ?> rootMap)) {
                throw new IllegalStateException("Exemplar YAML root is not a map");
            }
            return (Map<String, Map<String, List<String>>>) rootMap;
        }
    }

    /**
     * Embeds every exemplar string in every category using a single batch call
     * per category. One round-trip per category is a reasonable balance: it
     * keeps individual requests under OpenAI's per-request token cap while
     * avoiding a separate API call per exemplar.
     */
    private Map<String, List<float[]>> embedGroup(Map<String, List<String>> categories) {
        Map<String, List<float[]>> out = new HashMap<>();
        if (categories == null) return out;
        for (Map.Entry<String, List<String>> entry : categories.entrySet()) {
            List<String> strings = entry.getValue();
            if (strings == null || strings.isEmpty()) continue;
            try {
                List<float[]> vectors = embeddingModel.embed(strings);
                out.put(entry.getKey(), new ArrayList<>(vectors));
            } catch (Exception e) {
                log.warn("Failed to embed exemplars for category '{}' — skipping: {}",
                        entry.getKey(), e.getMessage());
            }
        }
        return out;
    }

    /**
     * Per-category top-1 cosine similarity against the query vector.
     * Returns a sentinel score of 0.0 when a category has no embeddings.
     */
    private CategoryScore topScore(float[] query, Map<String, List<float[]>> groups) {
        String bestCategory = "none";
        double bestScore = 0.0;
        for (Map.Entry<String, List<float[]>> entry : groups.entrySet()) {
            double top = 0.0;
            for (float[] vec : entry.getValue()) {
                double sim = cosine(query, vec);
                if (sim > top) top = sim;
            }
            if (top > bestScore) {
                bestScore = top;
                bestCategory = entry.getKey();
            }
        }
        return new CategoryScore(bestCategory, bestScore);
    }

    /**
     * Cosine similarity. Handles zero-norm vectors by returning 0.0 rather
     * than NaN so the downstream comparison is always well-defined.
     */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0.0;
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private record CategoryScore(String category, double score) {}

    /**
     * Classifier output. {@code PASS} is the fail-open sentinel used whenever
     * the classifier isn't confident enough to refuse — not-ready, disabled,
     * embedding failure, or margin not cleared.
     */
    public record Decision(
            boolean offTopic,
            String topOffTopicCategory,
            double topOffTopicScore,
            String topInScopeCategory,
            double topInScopeScore
    ) {
        public static final Decision PASS =
                new Decision(false, "none", 0.0, "none", 0.0);
    }
}
