package com.fu.conversation.ai.advisor;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the semantic {@code IntentRouterAdvisor}.
 *
 * <p>Bound under {@code conversation.intent-router.*} in application.yml.
 *
 * <p>Replaces the old regex-based {@code DomainScopeAdvisor}. The router embeds
 * the incoming user query once and compares cosine similarity against a set of
 * curated exemplars partitioned into off-topic and in-scope categories. When
 * the best off-topic exemplar beats the best in-scope exemplar by more than
 * {@link #margin}, the query is short-circuited with a canned refusal before
 * any RAG / LLM cost is incurred.
 *
 * <p>Calibration baseline from {@link ChatRagProperties}: with
 * {@code text-embedding-3-large} (1536d) cross-lingual VI↔JA meaningful pairs
 * score 0.50–0.65, noise sits at 0.35–0.49. Defaults here are tuned to fit
 * inside that band.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "conversation.intent-router")
public class IntentRouterProperties {

    /** Master switch. When false, router becomes a no-op and every query passes through. */
    private boolean enabled = true;

    /**
     * Minimum cosine similarity the best off-topic exemplar must reach for the
     * router to even consider refusing. Below this, we fail open (pass through)
     * regardless of the margin — too low a score means the query resembles
     * nothing in the exemplar set, and we'd rather let the LLM/refuseOffTopic
     * tool handle it than guess.
     */
    private double minOffTopicScore = 0.50;

    /**
     * Required gap between the top off-topic score and the top in-scope score
     * before the router short-circuits. The bigger the margin, the more
     * confident the classification — lesson-range queries ("bài 1 + 2 có gì")
     * sit very close to math exemplars in embedding space, so a non-trivial
     * margin is needed to separate them from actual arithmetic ("1+1=?").
     */
    private double margin = 0.05;

    /**
     * LRU cache size for classification results. Re-issuing the same query
     * (common in chat) avoids re-embedding. Set to 0 to disable caching.
     */
    private int cacheSize = 256;

    /**
     * Path (classpath resource) to the exemplar YAML file. Kept configurable so
     * ops can swap in a different exemplar set without rebuilding.
     */
    private String exemplarResource = "intent-exemplars.yml";
}
