-- Remove thumbnailUrl from Course module
-- Related to user request Step 600

-- Important: The view v_active_courses uses SELECT * from this table
-- We must drop and recreate it to avoid dependency errors.

DROP VIEW IF EXISTS learning_content.v_active_courses;

ALTER TABLE learning_content.courses 
DROP COLUMN IF EXISTS thumbnail_url;

-- Recreate the view as defined in 02-schema-fixes.sql
CREATE OR REPLACE VIEW learning_content.v_active_courses AS
SELECT * FROM learning_content.courses 
WHERE deleted_at IS NULL AND status = 'ACTIVE';
