package com.fu.conversation.ai.advisor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.openai.OpenAiEmbeddingModel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IntentClassifier}.
 *
 * <p>Uses 2-dimensional orthogonal exemplars so cosine similarity is trivially
 * predictable: {1,0} vs {1,0} = 1.0, {1,0} vs {0,1} = 0.0. This lets us
 * exercise the margin-comparison logic without involving a real embedding
 * model or the YAML loader.
 */
@DisplayName("IntentClassifier — fail-open semantics + margin-based decision")
class IntentClassifierTest {

    private static final float[] V_MATH = {1f, 0f};
    private static final float[] V_GRAMMAR = {0f, 1f};
    // 45° between math and grammar → cosine ≈ 0.707 with either
    private static final float[] V_AMBIGUOUS = {0.7071f, 0.7071f};

    private OpenAiEmbeddingModel embeddingModel;
    private IntentRouterProperties props;
    private IntentClassifier classifier;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(OpenAiEmbeddingModel.class);
        props = new IntentRouterProperties();
        props.setEnabled(true);
        props.setMinOffTopicScore(0.50);
        props.setMargin(0.05);
        props.setCacheSize(16);
        classifier = new IntentClassifier(embeddingModel, props);
    }

    // ── fail-open paths ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Not-ready classifier → PASS (fail-open)")
    void notReady_passThrough() {
        // seedForTest NOT called — classifier stays ready=false
        assertThat(classifier.isReady()).isFalse();
        assertThat(classifier.classify("1+1 bằng mấy")).isEqualTo(IntentClassifier.Decision.PASS);
        verify(embeddingModel, never()).embed(ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Disabled via props → PASS (even when exemplars are loaded)")
    void disabled_passThrough() {
        props.setEnabled(false);
        classifier.seedForTest(Map.of("math", List.of(V_MATH)), Map.of("grammar", List.of(V_GRAMMAR)));
        assertThat(classifier.classify("1+1 bằng mấy")).isEqualTo(IntentClassifier.Decision.PASS);
        verify(embeddingModel, never()).embed(ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Null / blank query → PASS")
    void blank_passThrough() {
        classifier.seedForTest(Map.of("math", List.of(V_MATH)), Map.of("grammar", List.of(V_GRAMMAR)));
        assertThat(classifier.classify(null)).isEqualTo(IntentClassifier.Decision.PASS);
        assertThat(classifier.classify("")).isEqualTo(IntentClassifier.Decision.PASS);
        assertThat(classifier.classify("   ")).isEqualTo(IntentClassifier.Decision.PASS);
        verify(embeddingModel, never()).embed(ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Embedding model throws → PASS (fail-open, never refuse on internal error)")
    void embeddingFails_passThrough() {
        classifier.seedForTest(Map.of("math", List.of(V_MATH)), Map.of("grammar", List.of(V_GRAMMAR)));
        when(embeddingModel.embed(ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("openai outage"));

        IntentClassifier.Decision decision = classifier.classify("some query");
        assertThat(decision).isEqualTo(IntentClassifier.Decision.PASS);
    }

    @Test
    @DisplayName("Embedding returns empty vector → PASS")
    void emptyVector_passThrough() {
        classifier.seedForTest(Map.of("math", List.of(V_MATH)), Map.of("grammar", List.of(V_GRAMMAR)));
        when(embeddingModel.embed(ArgumentMatchers.anyString())).thenReturn(new float[0]);

        assertThat(classifier.classify("some query")).isEqualTo(IntentClassifier.Decision.PASS);
    }

    // ── margin-based decision ──────────────────────────────────────────────

    @Test
    @DisplayName("Clear off-topic match (cos=1.0 vs cos=0.0) → offTopic=true")
    void clearOffTopic_classified() {
        classifier.seedForTest(Map.of("math", List.of(V_MATH)), Map.of("grammar", List.of(V_GRAMMAR)));
        when(embeddingModel.embed(ArgumentMatchers.anyString())).thenReturn(V_MATH);

        IntentClassifier.Decision decision = classifier.classify("what is 1+1");
        assertThat(decision.offTopic()).isTrue();
        assertThat(decision.topOffTopicCategory()).isEqualTo("math");
        assertThat(decision.topOffTopicScore()).isEqualTo(1.0);
        assertThat(decision.topInScopeScore()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Clear in-scope match → offTopic=false")
    void clearInScope_passThrough() {
        classifier.seedForTest(Map.of("math", List.of(V_MATH)), Map.of("grammar", List.of(V_GRAMMAR)));
        when(embeddingModel.embed(ArgumentMatchers.anyString())).thenReturn(V_GRAMMAR);

        IntentClassifier.Decision decision = classifier.classify("giải thích ngữ pháp te-form");
        assertThat(decision.offTopic()).isFalse();
    }

    @Test
    @DisplayName("Ambiguous query equally close to both → offTopic=false (margin not cleared)")
    void ambiguous_passThrough() {
        classifier.seedForTest(Map.of("math", List.of(V_MATH)), Map.of("grammar", List.of(V_GRAMMAR)));
        when(embeddingModel.embed(ArgumentMatchers.anyString())).thenReturn(V_AMBIGUOUS);

        IntentClassifier.Decision decision = classifier.classify("bài 1 + 2");
        // both cos ≈ 0.707, margin=0 < 0.05 → pass through
        assertThat(decision.offTopic()).isFalse();
    }

    @Test
    @DisplayName("Off-topic score below min threshold → offTopic=false even if in-scope is 0")
    void belowMinThreshold_passThrough() {
        // raise min-off-topic to 0.9 so the 0.707 score doesn't qualify
        props.setMinOffTopicScore(0.90);
        classifier.seedForTest(Map.of("math", List.of(V_MATH)), Map.of());
        when(embeddingModel.embed(ArgumentMatchers.anyString())).thenReturn(V_AMBIGUOUS);

        IntentClassifier.Decision decision = classifier.classify("something math-adjacent");
        assertThat(decision.offTopic()).isFalse();
    }

    @Test
    @DisplayName("Gap clearly above margin → offTopic=true")
    void gapAboveMargin_refuses() {
        // Query = {1, 0} → cosine(query, x) is simply x[0] for any unit vector x.
        // Off score ≈ 0.80, in score ≈ 0.60, gap ≈ 0.20 >> margin 0.05.
        props.setMinOffTopicScore(0.50);
        props.setMargin(0.05);
        classifier.seedForTest(
                Map.of("math", List.of(scaled(0.80))),
                Map.of("grammar", List.of(scaled(0.60))));
        when(embeddingModel.embed(ArgumentMatchers.anyString())).thenReturn(new float[]{1f, 0f});

        IntentClassifier.Decision decision = classifier.classify("q");
        assertThat(decision.offTopic()).isTrue();
        assertThat(decision.topOffTopicScore()).isCloseTo(0.80, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(decision.topInScopeScore()).isCloseTo(0.60, org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    @DisplayName("Margin not cleared (gap < margin) → passes through")
    void marginNotCleared_passThrough() {
        // off=0.70, in=0.68, gap=0.02, margin=0.05 → should NOT refuse
        props.setMinOffTopicScore(0.50);
        props.setMargin(0.05);
        classifier.seedForTest(
                Map.of("math", List.of(scaled(0.70))),
                Map.of("grammar", List.of(scaled(0.68))));
        when(embeddingModel.embed(ArgumentMatchers.anyString())).thenReturn(new float[]{1f, 0f});

        IntentClassifier.Decision decision = classifier.classify("q");
        assertThat(decision.offTopic()).isFalse();
    }

    // ── cache behavior ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Repeated identical query hits the LRU cache (no re-embed)")
    void cache_dedupesRepeatedQuery() {
        classifier.seedForTest(Map.of("math", List.of(V_MATH)), Map.of("grammar", List.of(V_GRAMMAR)));
        when(embeddingModel.embed(ArgumentMatchers.anyString())).thenReturn(V_MATH);

        classifier.classify("1+1 bằng mấy");
        classifier.classify("1+1 bằng mấy");
        classifier.classify("1+1 bằng mấy");

        verify(embeddingModel, times(1)).embed(ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Cache keys are normalized (trim + lowercase)")
    void cache_normalizesKeys() {
        classifier.seedForTest(Map.of("math", List.of(V_MATH)), Map.of("grammar", List.of(V_GRAMMAR)));
        when(embeddingModel.embed(ArgumentMatchers.anyString())).thenReturn(V_MATH);

        classifier.classify("1+1 bằng mấy");
        classifier.classify("  1+1 BẰNG MẤY  ");

        verify(embeddingModel, times(1)).embed(ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Cache disabled when cacheSize=0 (re-embeds every call)")
    void cacheDisabled_reembedsEveryCall() {
        props.setCacheSize(0);
        // rebuild classifier to pick up the new size (cache is constructed in ctor)
        classifier = new IntentClassifier(embeddingModel, props);
        classifier.seedForTest(Map.of("math", List.of(V_MATH)), Map.of("grammar", List.of(V_GRAMMAR)));
        when(embeddingModel.embed(ArgumentMatchers.anyString())).thenReturn(V_MATH);

        classifier.classify("q");
        classifier.classify("q");

        verify(embeddingModel, times(2)).embed(ArgumentMatchers.anyString());
    }

    // ── cosine helper sanity ───────────────────────────────────────────────

    @Test
    @DisplayName("cosine() handles zero-norm vectors without NaN")
    void cosine_zeroVector_returnsZero() {
        assertThat(IntentClassifier.cosine(new float[]{0f, 0f}, V_MATH)).isEqualTo(0.0);
        assertThat(IntentClassifier.cosine(V_MATH, new float[]{0f, 0f})).isEqualTo(0.0);
        assertThat(IntentClassifier.cosine(null, V_MATH)).isEqualTo(0.0);
        assertThat(IntentClassifier.cosine(V_MATH, null)).isEqualTo(0.0);
        assertThat(IntentClassifier.cosine(new float[]{1f, 0f}, new float[]{1f, 0f, 0f})).isEqualTo(0.0);
    }

    // ── utilities ──────────────────────────────────────────────────────────

    /** Returns a 2D unit vector whose cosine with {1, 0} equals {@code score}. */
    private static float[] scaled(double score) {
        double y = Math.sqrt(Math.max(0, 1 - score * score));
        return new float[]{(float) score, (float) y};
    }
}
