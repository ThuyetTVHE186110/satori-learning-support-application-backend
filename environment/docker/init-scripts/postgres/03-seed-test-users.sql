-- ==================== Test Users Seed Data ====================
-- Satori Learning System - Test Users for Frontend Integration
-- 
-- ALL PASSWORDS: Password123!
-- BCrypt hash generated with strength 10
--
-- Usage: Run this script after database initialization
-- ==================== ==================== ====================

-- Clear existing test data (optional - uncomment if needed)
-- DELETE FROM user_management.user_sessions WHERE user_id IN (
--     SELECT id FROM user_management.users WHERE email LIKE '%@test.satori.com'
-- );
-- DELETE FROM user_management.users WHERE email LIKE '%@test.satori.com';

-- ==================== ADMIN USER ====================
INSERT INTO user_management.users (
    id, email, password_hash, 
    first_name, last_name, display_name,
    role, status, auth_provider,
    email_verified_at, created_at, updated_at
) VALUES (
    'a0000000-0000-0000-0000-000000000001',
    'admin@test.satori.com',
    '$2a$10$N9qo8uLOickgx2ZMRZoMy.MqrqBuBNO7SxaU8JGzPM2AOuLkSzKWe', -- Password123!
    'Admin',
    'System',
    'System Admin',
    'ADMIN',
    'ACTIVE',
    'LOCAL',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (email) DO NOTHING;

-- ==================== MANAGER USER ====================
INSERT INTO user_management.users (
    id, email, password_hash,
    first_name, last_name, display_name,
    role, status, auth_provider,
    email_verified_at, created_at, updated_at
) VALUES (
    'a0000000-0000-0000-0000-000000000002',
    'manager@test.satori.com',
    '$2a$10$N9qo8uLOickgx2ZMRZoMy.MqrqBuBNO7SxaU8JGzPM2AOuLkSzKWe', -- Password123!
    'Manager',
    'Test',
    'Test Manager',
    'MANAGER',
    'ACTIVE',
    'LOCAL',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (email) DO NOTHING;

-- ==================== TEACHER USER ====================
INSERT INTO user_management.users (
    id, email, password_hash,
    first_name, last_name, display_name,
    phone_number,
    role, status, auth_provider,
    email_verified_at, created_at, updated_at
) VALUES (
    'a0000000-0000-0000-0000-000000000003',
    'teacher@test.satori.com',
    '$2a$10$N9qo8uLOickgx2ZMRZoMy.MqrqBuBNO7SxaU8JGzPM2AOuLkSzKWe', -- Password123!
    'Tanaka',
    'Sensei',
    'Tanaka Sensei',
    '0901234567',
    'TEACHER',
    'ACTIVE',
    'LOCAL',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (email) DO NOTHING;

-- ==================== LEARNER USERS ====================

-- Active Learner 1
INSERT INTO user_management.users (
    id, email, password_hash,
    first_name, last_name, display_name,
    phone_number, date_of_birth, gender,
    role, status, auth_provider,
    email_verified_at, created_at, updated_at
) VALUES (
    'a0000000-0000-0000-0000-000000000004',
    'learner1@test.satori.com',
    '$2a$10$N9qo8uLOickgx2ZMRZoMy.MqrqBuBNO7SxaU8JGzPM2AOuLkSzKWe', -- Password123!
    'Nguyen',
    'Van A',
    'Nguyen Van A',
    '0912345678',
    '2000-05-15',
    'MALE',
    'LEARNER',
    'ACTIVE',
    'LOCAL',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (email) DO NOTHING;

-- Active Learner 2
INSERT INTO user_management.users (
    id, email, password_hash,
    first_name, last_name, display_name,
    phone_number, date_of_birth, gender,
    role, status, auth_provider,
    email_verified_at, created_at, updated_at
) VALUES (
    'a0000000-0000-0000-0000-000000000005',
    'learner2@test.satori.com',
    '$2a$10$N9qo8uLOickgx2ZMRZoMy.MqrqBuBNO7SxaU8JGzPM2AOuLkSzKWe', -- Password123!
    'Tran',
    'Thi B',
    'Tran Thi B',
    '0923456789',
    '2001-08-20',
    'FEMALE',
    'LEARNER',
    'ACTIVE',
    'LOCAL',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (email) DO NOTHING;

-- ==================== SPECIAL STATUS USERS (For Testing) ====================

-- Suspended User
INSERT INTO user_management.users (
    id, email, password_hash,
    first_name, last_name, display_name,
    role, status, auth_provider,
    email_verified_at, created_at, updated_at
) VALUES (
    'a0000000-0000-0000-0000-000000000006',
    'suspended@test.satori.com',
    '$2a$10$N9qo8uLOickgx2ZMRZoMy.MqrqBuBNO7SxaU8JGzPM2AOuLkSzKWe', -- Password123!
    'Suspended',
    'User',
    'Suspended User',
    'LEARNER',
    'SUSPENDED',
    'LOCAL',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (email) DO NOTHING;

-- Inactive User
INSERT INTO user_management.users (
    id, email, password_hash,
    first_name, last_name, display_name,
    role, status, auth_provider,
    email_verified_at, created_at, updated_at
) VALUES (
    'a0000000-0000-0000-0000-000000000007',
    'inactive@test.satori.com',
    '$2a$10$N9qo8uLOickgx2ZMRZoMy.MqrqBuBNO7SxaU8JGzPM2AOuLkSzKWe', -- Password123!
    'Inactive',
    'User',
    'Inactive User',
    'LEARNER',
    'INACTIVE',
    'LOCAL',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (email) DO NOTHING;

-- Pending Activation User
INSERT INTO user_management.users (
    id, email, password_hash,
    first_name, last_name, display_name,
    role, status, auth_provider,
    created_at, updated_at
) VALUES (
    'a0000000-0000-0000-0000-000000000008',
    'pending@test.satori.com',
    '$2a$10$N9qo8uLOickgx2ZMRZoMy.MqrqBuBNO7SxaU8JGzPM2AOuLkSzKWe', -- Password123!
    'Pending',
    'User',
    'Pending User',
    'LEARNER',
    'PENDING_ACTIVATION',
    'LOCAL',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (email) DO NOTHING;

-- Locked User (failed login attempts)
INSERT INTO user_management.users (
    id, email, password_hash,
    first_name, last_name, display_name,
    role, status, auth_provider,
    failed_login_attempts, locked_until,
    email_verified_at, created_at, updated_at
) VALUES (
    'a0000000-0000-0000-0000-000000000009',
    'locked@test.satori.com',
    '$2a$10$N9qo8uLOickgx2ZMRZoMy.MqrqBuBNO7SxaU8JGzPM2AOuLkSzKWe', -- Password123!
    'Locked',
    'User',
    'Locked User',
    'LEARNER',
    'ACTIVE',
    'LOCAL',
    5,
    CURRENT_TIMESTAMP + INTERVAL '15 minutes', -- Locked for 15 more minutes
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (email) DO NOTHING;

-- ==================== Verification Query ====================
-- Run this to verify the test users were created:
-- SELECT id, email, display_name, role, status FROM user_management.users WHERE email LIKE '%@test.satori.com' ORDER BY role;
