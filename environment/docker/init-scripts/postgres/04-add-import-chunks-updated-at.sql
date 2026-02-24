-- Migration: Add missing updated_at column to import_chunks table
-- Required because ImportChunk extends BaseEntity which includes updatedAt
-- Date: 2026-02-10

ALTER TABLE system_admin.import_chunks
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;
