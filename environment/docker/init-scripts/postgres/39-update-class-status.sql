-- 38-update-class-status.sql
-- Refactor classes table to use status enum instead of is_active boolean

-- 1. Create the enum type if it does not exist
DO $$ 
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'class_status') THEN
        CREATE TYPE user_management.class_status AS ENUM ('NOT_STARTED', 'ACTIVE', 'CLOSED', 'DELETED');
    END IF;
END $$;

-- 2. Add the status column if it does not exist
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_schema = 'user_management' 
                   AND table_name = 'classes' 
                   AND column_name = 'status') THEN
        ALTER TABLE user_management.classes ADD COLUMN status user_management.class_status DEFAULT 'NOT_STARTED';
    END IF;
END $$;

-- 3. Migrate data from is_active to status
-- If is_active is true, set status to ACTIVE (if currently NOT_STARTED)
UPDATE user_management.classes 
SET status = 'ACTIVE' 
WHERE is_active = true AND status = 'NOT_STARTED';

-- If is_active is false, set status to CLOSED
UPDATE user_management.classes 
SET status = 'CLOSED' 
WHERE is_active = false;

-- 4. Handle dependent views
-- v_active_classes depends on is_active, so we must recreate it
DROP VIEW IF EXISTS user_management.v_active_classes;

-- 5. Drop the is_active column and its associated index
DROP INDEX IF EXISTS user_management.idx_classes_is_active;
ALTER TABLE user_management.classes DROP COLUMN IF EXISTS is_active;

-- 6. Recreate the view using the new status field
CREATE OR REPLACE VIEW user_management.v_active_classes AS
SELECT * FROM user_management.classes 
WHERE deleted_at IS NULL AND status = 'ACTIVE';
