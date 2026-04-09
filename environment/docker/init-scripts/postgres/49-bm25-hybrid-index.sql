-- ==========================================================================
-- Migration 49: BM25-like sparse retrieval via pg_trgm for hybrid RAG
-- ==========================================================================
-- Problem: vector search alone ("retrieval monoculture") fails on exact-term
-- queries. When a user asks about "食べる" (a specific word), the cosine
-- similarity of the question embedding vs. stored documents may be low (0.40)
-- even though the answer is right there in the DB.
--
-- Solution: add a sparse retrieval channel using pg_trgm word_similarity.
-- Trigrams are language-agnostic 3-character substrings that work for both
-- Vietnamese (space-separated) and Japanese (no spaces between words).
--
-- Architecture:
--   Dense channel:  HNSW cosine similarity  (semantic meaning)
--   Sparse channel: pg_trgm word_similarity  (lexical / exact-term match)
--   Fusion:         Reciprocal Rank Fusion (RRF)
--                   score = 1/(60+rank_dense) + 1/(60+rank_sparse)
--                   k=60 is the standard constant from Cormack et al. 2009
--
-- Expected improvement: +10-20% recall on exact-term queries (e.g. "食べる nghĩa là gì")
-- with minimal overhead on conceptual queries (RRF just promotes already-top items).
-- ==========================================================================

-- Enable pg_trgm (ships with standard PostgreSQL, no extra install needed).
-- Safe to run on an existing cluster — IF NOT EXISTS ensures idempotency.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- GIN trigram index on text_content for fast ILIKE and word_similarity() queries.
--
-- GIN vs GiST choice:
--   GIN  → better read performance, larger size, slower updates (good for batch imports)
--   GiST → faster updates, weaker read performance
-- Curriculum imports are batch operations, reads are chatbot queries → GIN is optimal.
--
-- This index accelerates:
--   WHERE text_content ILIKE '%<keyword>%'         (substring match)
--   ORDER BY word_similarity('<keyword>', text_content) DESC  (ranked similarity)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_content_embeddings_text_trgm
    ON progress.content_embeddings
    USING GIN (text_content gin_trgm_ops);

DO $$ BEGIN
    RAISE NOTICE '49. Added pg_trgm extension and GIN trigram index on progress.content_embeddings(text_content) for hybrid BM25+dense RAG retrieval';
END $$;
