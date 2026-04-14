-- 58-drop-google-oauth.sql
-- Remove Google OAuth login feature from the database schema.
-- Firebase is still used for FCM push notifications (notification module) — untouched here.

-- Drop columns from users table
ALTER TABLE user_management.users DROP COLUMN IF EXISTS google_id;
ALTER TABLE user_management.users DROP COLUMN IF EXISTS auth_provider;

-- Drop the implicit cast (if it exists)
DROP CAST IF EXISTS (character varying AS auth_provider);

-- Drop the ENUM type
DROP TYPE IF EXISTS auth_provider;
