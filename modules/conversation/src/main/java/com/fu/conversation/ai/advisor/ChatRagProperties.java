package com.fu.conversation.ai.advisor;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for the Agentic RAG advisor chain (Self-RAG + CRAG).
 *
 * Properties under {@code conversation.rag.*} in application.yml.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "conversation.rag")
public class ChatRagProperties {

    /** Enable the Self-RAG relevance check advisor. */
    private boolean relevanceCheckEnabled = true;

    /** Minimum confidence for retrieved context to be considered relevant (0.0–1.0). */
    private double relevanceThreshold = 0.5;

    /** Enable the CRAG grounding check advisor. */
    private boolean groundingCheckEnabled = true;

    /** Minimum confidence for response to be considered grounded (0.0–1.0). */
    private double groundingThreshold = 0.6;

    /**
     * Minimum cosine similarity for the *best* retrieved item to be considered
     * meaningful context. If no item clears this bar, RetrievalAdvisor treats
     * retrieval as empty even when items above similarityThreshold exist.
     *
     * Rationale: items at 0.30–0.44 are technically above the retrieval floor
     * but are semantically too weak to ground the LLM — injecting them causes
     * hallucination rather than preventing it.
     */
    // text-embedding-3-large (1536d): cross-lingual VI↔JA meaningful pairs score 0.50–0.65,
    // noise sits at 0.35–0.49.  With text-embedding-3-small use 0.45 instead.
    private double minMeaningfulSimilarity = 0.50;

    /**
     * Per-intent cosine similarity thresholds that override {@link #minMeaningfulSimilarity}.
     *
     * Rationale for asymmetric values:
     *   LOOKUP  — single term lookup against stored vocabulary entries → high bar (0.55) to
     *             avoid returning unrelated words that happen to be near the embedding.
     *   EXPLAIN — grammar explanation text is verbose; a 0.42 cosine still captures
     *             structurally similar patterns across cross-lingual VI↔JA embeddings.
     *   EXAMPLE — example/dialog chunks have different surface forms; lower bar (0.35) is
     *             needed to retrieve relevant dialogue when the query uses meta-language
     *             ("ví dụ", "hội thoại") instead of the actual Japanese content.
     *
     * Configurable via {@code conversation.rag.intent-thresholds.*} in application.yml.
     * Falls back to {@link #minMeaningfulSimilarity} for intents not present in the map.
     */
    private Map<String, Double> intentThresholds = new HashMap<>(Map.of(
            "LOOKUP",  0.55,
            "EXPLAIN", 0.42,
            "EXAMPLE", 0.35
    ));

    /**
     * Returns the similarity threshold for the given intent, falling back to
     * {@link #minMeaningfulSimilarity} when no per-intent override is configured.
     */
    public double getThresholdForIntent(String intent) {
        if (intent == null) return minMeaningfulSimilarity;
        return intentThresholds.getOrDefault(intent, minMeaningfulSimilarity);
    }

    /** Model for lightweight advisor LLM calls (relevance + grounding checks). */
    private String checkModel = "gpt-4o-mini";

    /** Max tokens for advisor check calls. */
    private int checkMaxTokens = 512;

    /**
     * Enable combined query analysis (intent classification + bilingual translation) before retrieval.
     *
     * One GPT-4o-mini call returns:
     *   intent         — COUNT | EXPLAIN | LOOKUP | EXAMPLE | GENERAL
     *   translatedQuery — question translated to the other language (VI↔JA)
     *   countTarget    — GRAMMAR | VOCABULARY | ALL (for COUNT intent)
     *   preferredTypes — content_type hints to narrow the vector search
     *
     * Routing:
     *   COUNT   → skip vector search → SQL COUNT → inject exact numbers
     *   EXPLAIN → RAG with contentTypes = [GRAMMAR_EXPLANATION, GRAMMAR]
     *   LOOKUP  → RAG with contentTypes = [VOCABULARY]
     *   EXAMPLE → RAG with contentTypes = [EXAMPLE, DIALOG]
     *   GENERAL → RAG with all content types
     *
     * In all non-COUNT cases translatedQuery also triggers bilingual retrieval (two vector
     * searches merged by content_id max similarity).
     */
    private boolean queryAnalysisEnabled = true;

    /**
     * Fallback: enable bilingual query expansion when queryAnalysisEnabled=false.
     * Ignored when queryAnalysisEnabled=true (translation is part of the analysis call).
     */
    private boolean bilingualQueryEnabled = true;

    /**
     * Enable hybrid retrieval (dense + sparse BM25-like via pg_trgm) with RRF fusion.
     *
     * When enabled, retrieval runs two channels:
     *   1. Dense HNSW cosine similarity (semantic meaning)
     *   2. Sparse pg_trgm word_similarity (lexical / exact-term match)
     *
     * Results are fused via Reciprocal Rank Fusion (RRF, k=60).
     * Items that appear in BOTH channels are strongly promoted.
     *
     * When combined with HyDE, the dense channel uses the hypothetical document
     * embedding while the sparse channel uses the original clean search keyword —
     * giving the best of both worlds (semantic reach + lexical precision).
     *
     * Requires migration 49-bm25-hybrid-index.sql (pg_trgm + GIN index).
     */
    private boolean hybridEnabled = true;

    /**
     * Enable HyDE (Hypothetical Document Embeddings) for LOOKUP and EXPLAIN intents.
     *
     * Instead of embedding the raw search keyword (a question/term), the advisor first asks
     * the LLM to write a short hypothetical curriculum entry that would answer the question,
     * then embeds THAT text. Hypothetical answers live in the same semantic space as stored
     * curriculum documents, yielding +5–15% cosine similarity vs. embedding raw questions.
     *
     * Cost: one extra gpt-4o-mini call per LOOKUP/EXPLAIN query (~$0.0001).
     * Only applies when queryAnalysisEnabled=true and searchKeyword is non-null.
     */
    private boolean hydeEnabled = true;
}
