-- Migration: Add PENDING_APPROVAL and ROLLED_BACK statuses to processing_status enum
-- UC 2.4.2: Manager approval/rejection/rollback workflow

ALTER TYPE processing_status ADD VALUE IF NOT EXISTS 'PENDING_APPROVAL' AFTER 'EMBEDDING';
ALTER TYPE processing_status ADD VALUE IF NOT EXISTS 'ROLLED_BACK' AFTER 'COMPLETED';
