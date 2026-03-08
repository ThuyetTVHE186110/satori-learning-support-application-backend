-- ==================== Agentic RAG Schema Updates ====================
-- Satori Japanese Learning System
-- Version: 4.0.0
-- Last Updated: 2026-02-11
-- Purpose: Support Agentic RAG multi-agent pipeline, Corrective RAG,
--          and vector embedding integration for curriculum import

-- ==========================================================================
-- 1. Extend processing_status ENUM with new pipeline steps
-- ==========================================================================
-- Current: QUEUED, EXTRACTING, CHUNKING, AI_PROCESSING, EMBEDDING, COMPLETED, FAILED
-- Need to add: CLASSIFYING, VALIDATING, PERSISTING, CANCELLED

DO $$
BEGIN
    -- Add CLASSIFYING (between CHUNKING and AI_PROCESSING)
    IF NOT EXISTS (SELECT 1 FROM pg_enum WHERE enumlabel = 'CLASSIFYING' 
                   AND enumtypid = 'processing_status'::regtype) THEN
        ALTER TYPE processing_status ADD VALUE 'CLASSIFYING' AFTER 'CHUNKING';
        RAISE NOTICE 'Added CLASSIFYING to processing_status';
    END IF;

    -- Add VALIDATING (after EMBEDDING)
    IF NOT EXISTS (SELECT 1 FROM pg_enum WHERE enumlabel = 'VALIDATING' 
                   AND enumtypid = 'processing_status'::regtype) THEN
        ALTER TYPE processing_status ADD VALUE 'VALIDATING' AFTER 'EMBEDDING';
        RAISE NOTICE 'Added VALIDATING to processing_status';
    END IF;

    -- Add PERSISTING (after VALIDATING)
    IF NOT EXISTS (SELECT 1 FROM pg_enum WHERE enumlabel = 'PERSISTING' 
                   AND enumtypid = 'processing_status'::regtype) THEN
        ALTER TYPE processing_status ADD VALUE 'PERSISTING' AFTER 'VALIDATING';
        RAISE NOTICE 'Added PERSISTING to processing_status';
    END IF;

    -- Add CANCELLED (after FAILED)
    IF NOT EXISTS (SELECT 1 FROM pg_enum WHERE enumlabel = 'CANCELLED' 
                   AND enumtypid = 'processing_status'::regtype) THEN
        ALTER TYPE processing_status ADD VALUE 'CANCELLED' AFTER 'FAILED';
        RAISE NOTICE 'Added CANCELLED to processing_status';
    END IF;
END $$;

-- ==========================================================================
-- 2. Add agent-related columns to import_chunks table
-- ==========================================================================

-- Content classification result from classifier agent
ALTER TABLE system_admin.import_chunks
ADD COLUMN IF NOT EXISTS content_type VARCHAR(30) DEFAULT 'MIXED';
COMMENT ON COLUMN system_admin.import_chunks.content_type IS 'Content type detected by classifier agent: VOCABULARY, GRAMMAR, MIXED, CONVERSATION, OTHER';

-- Validation result from validation agent 
ALTER TABLE system_admin.import_chunks
ADD COLUMN IF NOT EXISTS validation_status VARCHAR(20) DEFAULT 'PENDING';
COMMENT ON COLUMN system_admin.import_chunks.validation_status IS 'Validation agent result: PENDING, VALID, INVALID, NEEDS_REVIEW';

-- Validation details (JSON with specific issues found)
ALTER TABLE system_admin.import_chunks
ADD COLUMN IF NOT EXISTS validation_details JSONB;
COMMENT ON COLUMN system_admin.import_chunks.validation_details IS 'Detailed validation results: issues found, suggestions, quality scores';

-- Re-extraction count (for Corrective RAG)
ALTER TABLE system_admin.import_chunks
ADD COLUMN IF NOT EXISTS re_extraction_count INT DEFAULT 0;
COMMENT ON COLUMN system_admin.import_chunks.re_extraction_count IS 'Number of times this chunk was re-extracted due to validation failure';

-- updated_at (required by BaseEntity / Hibernate)
ALTER TABLE system_admin.import_chunks
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;

-- ==========================================================================
-- 3. Add embedding tracking columns to curriculum_imports
-- ==========================================================================

-- Track embeddings at import level
ALTER TABLE system_admin.curriculum_imports
ADD COLUMN IF NOT EXISTS validation_passed INT DEFAULT 0;
COMMENT ON COLUMN system_admin.curriculum_imports.validation_passed IS 'Number of chunks that passed validation';

ALTER TABLE system_admin.curriculum_imports
ADD COLUMN IF NOT EXISTS validation_failed INT DEFAULT 0;
COMMENT ON COLUMN system_admin.curriculum_imports.validation_failed IS 'Number of chunks that failed validation';

ALTER TABLE system_admin.curriculum_imports
ADD COLUMN IF NOT EXISTS re_extractions INT DEFAULT 0;
COMMENT ON COLUMN system_admin.curriculum_imports.re_extractions IS 'Total re-extraction attempts across all chunks';

-- ==========================================================================
-- 4. Ensure content_embeddings table has proper FK to import_chunks
-- ==========================================================================

-- Add FK index for import_id if not exists
CREATE INDEX IF NOT EXISTS idx_content_embeddings_import 
ON progress.content_embeddings(import_id);

-- Add FK index for content lookup
CREATE INDEX IF NOT EXISTS idx_content_embeddings_content_id 
ON progress.content_embeddings(content_id);

-- ==========================================================================
-- 5. Summary
-- ==========================================================================

DO $$
BEGIN
    RAISE NOTICE '==========================================';
    RAISE NOTICE 'Agentic RAG schema updates applied!';
    RAISE NOTICE '==========================================';
    RAISE NOTICE '1. Extended processing_status ENUM: +CLASSIFYING, +VALIDATING, +PERSISTING, +CANCELLED';
    RAISE NOTICE '2. Added agent columns to import_chunks: content_type, validation_status, validation_details, re_extraction_count, updated_at';
    RAISE NOTICE '3. Added tracking to curriculum_imports: validation_passed, validation_failed, re_extractions';
    RAISE NOTICE '4. Added indexes for content_embeddings';
    RAISE NOTICE '==========================================';
END $$;
