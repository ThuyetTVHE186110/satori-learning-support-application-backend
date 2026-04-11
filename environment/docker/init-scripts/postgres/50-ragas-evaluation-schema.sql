-- ==========================================================================
-- Migration 50: RAGAS evaluation schema for async RAG quality measurement
-- ==========================================================================
-- Purpose: store automated quality assessments of chatbot responses using
-- RAGAS-inspired metrics (faithfulness, context relevance, answer relevance).
--
-- These evaluations run asynchronously after the user receives their response,
-- so they have zero impact on response latency.
--
-- Metrics:
--   faithfulness       — does the answer contain only information from the retrieved context?
--                        Range: 0.0–1.0, computed by LLM self-critique
--   context_relevance  — is the retrieved context relevant to the user's question?
--                        Range: 0.0–1.0, based on cosine similarity and content type match
--   answer_relevance   — does the answer actually address what the user asked?
--                        Range: 0.0–1.0, computed by LLM critique
--
-- Composite score: weighted average of the three metrics.
-- Use for:
--   - Monitoring RAG pipeline health over time
--   - Detecting regression after prompt/threshold changes
--   - Identifying sessions with consistently low quality (QA flagging)
-- ==========================================================================

CREATE TABLE IF NOT EXISTS ai_conversation.rag_evaluations (
    id                  UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),

    -- Links to the chat exchange being evaluated
    session_id          UUID        NOT NULL,
    user_message_id     UUID,           -- optional cross-reference to user message
    ai_message_id       UUID        NOT NULL,

    -- Input snapshot (captured at evaluation time for reproducibility)
    user_question       TEXT        NOT NULL,
    retrieved_context   TEXT,           -- concatenated text of retrieved documents
    ai_response         TEXT        NOT NULL,

    -- RAGAS-inspired metric scores (0.0 – 1.0, NULL = not evaluated)
    faithfulness        NUMERIC(4,3),   -- answer grounded in retrieved context?
    context_relevance   NUMERIC(4,3),   -- retrieved docs relevant to question?
    answer_relevance    NUMERIC(4,3),   -- answer addresses the question?
    composite_score     NUMERIC(4,3),   -- weighted average of the three metrics

    -- Metadata
    intent              VARCHAR(20),    -- COUNT|LOOKUP|EXPLAIN|EXAMPLE|GENERAL
    retrieval_strategy  VARCHAR(30),    -- HYBRID|HYDE|BILINGUAL|BROWSE|COUNT|NONE
    context_item_count  INT,            -- number of retrieved documents
    max_similarity      NUMERIC(5,4),   -- best cosine similarity from retrieval

    -- Evaluation status
    evaluated_at        TIMESTAMPTZ,
    evaluation_model    VARCHAR(50),    -- e.g. gpt-4o-mini
    error_message       TEXT,           -- if evaluation failed

    -- Audit
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index for analytics queries (e.g. "show me low-quality sessions this week")
CREATE INDEX IF NOT EXISTS idx_rag_evaluations_session
    ON ai_conversation.rag_evaluations (session_id, created_at DESC);

-- Index for quality monitoring (find low-scoring evaluations across all sessions)
CREATE INDEX IF NOT EXISTS idx_rag_evaluations_composite
    ON ai_conversation.rag_evaluations (composite_score, created_at DESC)
    WHERE composite_score IS NOT NULL;

-- Index for intent-based analysis (e.g. "which intents perform worst?")
CREATE INDEX IF NOT EXISTS idx_rag_evaluations_intent
    ON ai_conversation.rag_evaluations (intent, composite_score)
    WHERE intent IS NOT NULL;

DO $$ BEGIN
    RAISE NOTICE '50. Created ai_conversation.rag_evaluations table for async RAGAS quality monitoring';
END $$;
