-- ==================== System Test Seed Data ====================
-- Satori Learning System — Minimal seed for system/integration tests
--
-- Admin credentials: admin@systest.satori.com / Password123!
-- BCrypt hash: $2a$12$wXFj.DR15CKPFTyhwFL.XuV8JvVqvnTPSIK3E8w0WTNC5omj9Lzp2
-- ================================================================

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
    'c0000000-0000-0000-0000-000000000001',
    'admin@systest.satori.com',
    '$2a$12$wXFj.DR15CKPFTyhwFL.XuV8JvVqvnTPSIK3E8w0WTNC5omj9Lzp2',
    'Admin',
    'Systest',
    'Systest Admin',
    'ADMIN',
    'ACTIVE',
    'LOCAL',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (email) DO NOTHING;
