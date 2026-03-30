package com.fu.conversation.ai.advisor;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
}
