-- ==================== UAT Seed Data ====================
-- Satori Learning System — Minimal seed for UAT environment
--
-- Admin credentials: admin@uat.satori.com / Password123!
-- BCrypt hash: $2a$12$wXFj.DR15CKPFTyhwFL.XuV8JvVqvnTPSIK3E8w0WTNC5omj9Lzp2
--
-- NOTE: This file runs ONCE on first DB initialization.
-- Re-running docker-compose down -v && up will re-apply it.
-- =========================================================

INSERT INTO user_management.users (
    id,
    email,
    password_hash,
    first_name,
    last_name,
    display_name,
    role,
    status,
    auth_provider,
    email_verified_at,
    created_at,
    updated_at
) VALUES (
    'd0000000-0000-0000-0000-000000000001',
    'admin@uat.satori.com',
    '$2a$12$wXFj.DR15CKPFTyhwFL.XuV8JvVqvnTPSIK3E8w0WTNC5omj9Lzp2',
    'Admin',
    'UAT',
    'UAT Admin',
    'ADMIN',
    'ACTIVE',
    'LOCAL',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (email) DO NOTHING;
