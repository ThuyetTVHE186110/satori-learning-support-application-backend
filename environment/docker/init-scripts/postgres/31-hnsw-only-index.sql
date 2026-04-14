-- ==========================================================================
-- Migration 31: Drop redundant IVFFlat index, keep HNSW only
-- ==========================================================================
-- Problem: content_embeddings has BOTH IVFFlat (lists=100) and HNSW running
-- simultaneously, causing:
--   1. Double write overhead on every INSERT/UPDATE
--   2. IVFFlat with lists=100 + probes=1 (default) scans only 1/100 clusters
--      → ~1% of data → terrible recall for small datasets
--   3. lists=100 is calibrated for ~100K rows; at thousands of rows it hurts
--
-- HNSW is strictly better for this scale because:
--   - No training data needed (works on empty table)
--   - ~99% recall at default ef_search=40, tunable at query time
--   - Self-updating on inserts, no rebuild needed
--   - Memory overhead negligible at thousands of rows
-- ==========================================================================

-- Drop the IVFFlat index (redundant with HNSW)
-- Note: CONCURRENTLY is not used here because init scripts run in a single-connection
-- context where concurrent DDL is not permitted (Docker ON_ERROR_STOP=1).
DROP INDEX IF EXISTS idx_content_embeddings_vector;

-- Also drop the legacy IVFFlat on user_embeddings if it exists
DROP INDEX IF EXISTS idx_user_embeddings_vector;

-- Recreate HNSW on user_embeddings to match (was IVFFlat only)
CREATE INDEX IF NOT EXISTS idx_user_embeddings_hnsw
    ON progress.user_embeddings
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Rebuild HNSW on content_embeddings with better params for 1536-dim vectors:
-- m=32  → more connections per layer → better recall for high-dim vectors
-- ef_construction=128 → higher build quality → better recall at query time
DROP INDEX IF EXISTS idx_content_embeddings_hnsw;

CREATE INDEX IF NOT EXISTS idx_content_embeddings_hnsw
    ON progress.content_embeddings
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 32, ef_construction = 128);

DO $$ BEGIN
    RAISE NOTICE '31. Replaced IVFFlat with HNSW-only index on content_embeddings (m=32, ef_construction=128)';
END $$;
