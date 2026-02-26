-- =====================================================================
-- Migration: 05 — Self-Reflection & Grounding Verification Support
-- Description: Adds columns for LLM self-reflection scores, source
--              grounding verification, and hallucination tracking.
--
-- References:
--   - Self-RAG (Asai et al., 2023) — Reflection tokens
--   - CRAG (Yan et al., 2024) — Knowledge Refinement / Grounding
--
-- Date: 2026-02-11
-- =====================================================================

-- =============================================================
-- 1. Add Self-Reflection & Grounding columns to import_chunks
-- =============================================================

-- Self-reflection score from LLM critique (0.0-1.0)
ALTER TABLE system_admin.import_chunks
    ADD COLUMN IF NOT EXISTS reflection_score NUMERIC(3,2);

-- Source grounding score (0.0-1.0)
ALTER TABLE system_admin.import_chunks
    ADD COLUMN IF NOT EXISTS grounding_score NUMERIC(3,2);

-- Number of items flagged as hallucinated in this chunk
ALTER TABLE system_admin.import_chunks
    ADD COLUMN IF NOT EXISTS hallucinated_count INTEGER DEFAULT 0;

-- Add index for chunks with low grounding (easy hallucination audit)
CREATE INDEX IF NOT EXISTS idx_import_chunks_grounding
    ON system_admin.import_chunks (grounding_score)
    WHERE grounding_score IS NOT NULL AND grounding_score < 0.5;

-- Add index for chunks with hallucinations
CREATE INDEX IF NOT EXISTS idx_import_chunks_hallucinated
    ON system_admin.import_chunks (hallucinated_count)
    WHERE hallucinated_count > 0;

-- =============================================================
-- 2. Add hallucination tracking to curriculum_imports
-- =============================================================

ALTER TABLE system_admin.curriculum_imports
    ADD COLUMN IF NOT EXISTS hallucinated_items INTEGER DEFAULT 0;

-- =============================================================
-- 3. Add comment documentation
-- =============================================================

COMMENT ON COLUMN system_admin.import_chunks.reflection_score IS
    'Self-RAG reflection score (0.0-1.0). LLM self-critique of extraction quality using [IsRelevant], [IsSupported], [IsUseful] tokens.';

COMMENT ON COLUMN system_admin.import_chunks.grounding_score IS
    'Source grounding score (0.0-1.0). Cross-reference verification that extracted content exists in the original source text.';

COMMENT ON COLUMN system_admin.import_chunks.hallucinated_count IS
    'Number of extracted items flagged as potentially hallucinated (not grounded in source text).';

COMMENT ON COLUMN system_admin.curriculum_imports.hallucinated_items IS
    'Total number of items across all chunks flagged as hallucinated during source grounding verification.';

-- =============================================================
-- Done
-- =============================================================
SELECT 'Migration 05 complete: Self-Reflection & Grounding columns added' AS status;
