-- ==========================================================================
-- init-test-db.sql
-- Setup script for satori_test database (integration test environment)
--
-- Run this ONCE after creating the satori_test database:
--   psql -U satori_user -d satori_test -f init-test-db.sql
--
-- Safe to re-run: all statements use IF NOT EXISTS or DO $$ ... $$ guards.
-- DO NOT include table DDL here — Hibernate ddl-auto=create-drop manages tables.
-- ==========================================================================

-- ==================== Extensions ====================
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;
-- Note: vector extension may not be available locally; comment out if not installed
-- CREATE EXTENSION IF NOT EXISTS vector;

-- ==================== Schemas ====================
CREATE SCHEMA IF NOT EXISTS user_management;
CREATE SCHEMA IF NOT EXISTS learning_content;
CREATE SCHEMA IF NOT EXISTS assignment;
CREATE SCHEMA IF NOT EXISTS pronunciation;
CREATE SCHEMA IF NOT EXISTS speaking;
CREATE SCHEMA IF NOT EXISTS progress;
CREATE SCHEMA IF NOT EXISTS gamification;
CREATE SCHEMA IF NOT EXISTS shop;
CREATE SCHEMA IF NOT EXISTS system_admin;
CREATE SCHEMA IF NOT EXISTS ai_conversation;

-- ==================== ENUM Types ====================
-- All guarded with DO $$ ... $$ so they are safe to re-run.

-- User enums
DO $$ BEGIN CREATE TYPE user_role AS ENUM ('LEARNER', 'TEACHER', 'MANAGER', 'ADMIN'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE user_status AS ENUM ('PENDING_ACTIVATION', 'ACTIVE', 'INACTIVE', 'SUSPENDED', 'DELETED'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE auth_provider AS ENUM ('LOCAL', 'GOOGLE'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE gender AS ENUM ('MALE', 'FEMALE', 'OTHER', 'PREFER_NOT_TO_SAY'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Learning enums
DO $$ BEGIN CREATE TYPE jlpt_level AS ENUM ('N5', 'N4', 'N3', 'N2', 'N1'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE lesson_type AS ENUM ('VOCABULARY', 'GRAMMAR', 'KANJI', 'LISTENING', 'READING', 'MIXED'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE content_status AS ENUM ('DRAFT', 'PUBLISHED', 'ARCHIVED'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE processing_status AS ENUM ('QUEUED', 'EXTRACTING', 'CHUNKING', 'AI_PROCESSING', 'EMBEDDING', 'COMPLETED', 'FAILED'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE grammar_category AS ENUM ('VERB_FORM', 'PARTICLE', 'ADJECTIVE', 'CONJUGATION', 'SENTENCE_PATTERN', 'HONORIFIC', 'CONDITIONAL', 'PASSIVE', 'CAUSATIVE', 'OTHER'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE learning_pace AS ENUM ('slow', 'normal', 'fast'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE formality_level AS ENUM ('casual', 'polite', 'formal'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE question_type_enum AS ENUM ('multiple_choice', 'fill_blank', 'true_false', 'matching', 'ordering', 'listening', 'reading'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Assignment enums
DO $$ BEGIN CREATE TYPE assignment_type AS ENUM ('QUIZ', 'VOCABULARY_TEST', 'GRAMMAR_TEST', 'MIXED_TEST'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE assignment_status AS ENUM ('DRAFT', 'PUBLISHED', 'CLOSED'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE submission_status AS ENUM ('NOT_STARTED', 'IN_PROGRESS', 'SUBMITTED', 'GRADED', 'OVERDUE'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Pronunciation enums
DO $$ BEGIN CREATE TYPE pronunciation_topic_category AS ENUM ('HIRAGANA', 'KATAKANA', 'VOWEL', 'CONSONANT', 'PITCH_ACCENT', 'WORD', 'PHRASE', 'SENTENCE', 'MINIMAL_PAIR'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE feedback_status AS ENUM ('PENDING', 'COMPLETED', 'FAILED'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Conversation enums
DO $$ BEGIN CREATE TYPE session_type AS ENUM ('GUIDED_CONVERSATION', 'ROLEPLAY', 'FREE_TALK', 'PRACTICE_ASSIGNMENT'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE session_status AS ENUM ('ACTIVE', 'COMPLETED', 'ABANDONED'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE message_role AS ENUM ('USER', 'ASSISTANT', 'SYSTEM', 'HINT'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE correction_type AS ENUM ('GRAMMAR', 'VOCABULARY', 'KANJI', 'POLITENESS', 'PARTICLE', 'CONJUGATION', 'WORD_ORDER', 'SPELLING', 'NATURAL'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE conversation_category AS ENUM ('daily_life', 'travel', 'business', 'shopping', 'healthcare', 'education', 'entertainment', 'social'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Gamification enums
DO $$ BEGIN CREATE TYPE badge_type AS ENUM ('STREAK', 'ACHIEVEMENT', 'MILESTONE', 'SPECIAL'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE reward_type AS ENUM ('EXP', 'COINS', 'BADGE', 'ITEM'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE transaction_type AS ENUM ('EARN', 'SPEND', 'REFUND', 'BONUS'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE item_type AS ENUM ('AVATAR', 'THEME', 'BOOSTER', 'FEATURE_UNLOCK', 'DECORATION'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE item_rarity AS ENUM ('COMMON', 'UNCOMMON', 'RARE', 'EPIC', 'LEGENDARY'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- ==========================================================================
-- Done. Hibernate ddl-auto=create-drop will create/drop all tables on each
-- test run. No table DDL needed here.
-- ==========================================================================

