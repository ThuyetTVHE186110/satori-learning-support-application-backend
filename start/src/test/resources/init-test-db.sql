-- ==========================================================================
-- init-test-db.sql
-- Setup script for integration test database
-- Safe to run multiple times
-- ==========================================================================


-- ==================== EXTENSIONS ====================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;



-- ==================== SCHEMAS ====================

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



-- ==================== ENUM TYPES ====================

-- USER

DO $$ BEGIN
    CREATE TYPE user_role AS ENUM ('LEARNER','TEACHER','MANAGER','ADMIN');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE user_status AS ENUM ('PENDING_ACTIVATION','ACTIVE','INACTIVE','SUSPENDED','DELETED');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE auth_provider AS ENUM ('LOCAL','GOOGLE');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE gender AS ENUM ('MALE','FEMALE','OTHER','PREFER_NOT_TO_SAY');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;



-- LEARNING

DO $$ BEGIN
    CREATE TYPE jlpt_level AS ENUM ('N5','N4','N3','N2','N1');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE lesson_type AS ENUM ('VOCABULARY','GRAMMAR','KANJI','LISTENING','READING','MIXED');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE content_status AS ENUM ('DRAFT','PUBLISHED','ARCHIVED');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE course_status AS ENUM ('ACTIVE','INACTIVE','DELETED');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE processing_status AS ENUM (
        'QUEUED','EXTRACTING','CHUNKING','CLASSIFYING','AI_PROCESSING',
        'EMBEDDING','VALIDATING','PERSISTING','PENDING_APPROVAL',
        'COMPLETED','ROLLED_BACK','FAILED','CANCELLED'
        );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE grammar_category AS ENUM (
        'VERB_FORM','PARTICLE','ADJECTIVE','CONJUGATION','SENTENCE_PATTERN',
        'HONORIFIC','CONDITIONAL','PASSIVE','CAUSATIVE','OTHER'
        );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;



-- ASSIGNMENT

DO $$ BEGIN
    CREATE TYPE assignment_type AS ENUM ('QUIZ','WRITING','TRANSLATION','SPEAKING');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE assignment_status AS ENUM ('DRAFT','PUBLISHED','CLOSED');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE submission_status AS ENUM (
        'NOT_STARTED','IN_PROGRESS','SUBMITTED','GRADED','OVERDUE'
        );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;



-- PRONUNCIATION

DO $$ BEGIN
    CREATE TYPE feedback_status AS ENUM ('PENDING','COMPLETED','FAILED');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;



-- CONVERSATION

DO $$ BEGIN
    CREATE TYPE session_type AS ENUM (
        'GUIDED_CONVERSATION','ROLEPLAY','FREE_TALK','PRACTICE_ASSIGNMENT'
        );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE session_status AS ENUM ('ACTIVE','COMPLETED','ABANDONED');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE message_role AS ENUM ('USER','ASSISTANT','SYSTEM','HINT');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;



-- GAMIFICATION

DO $$ BEGIN
    CREATE TYPE badge_type AS ENUM ('STREAK','ACHIEVEMENT','MILESTONE','SPECIAL');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE transaction_type AS ENUM ('EARN','SPEND','REFUND','BONUS');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE item_type AS ENUM (
        'AVATAR','THEME','BOOSTER','FEATURE_UNLOCK','DECORATION'
        );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE item_rarity AS ENUM (
        'COMMON','UNCOMMON','RARE','EPIC','LEGENDARY'
        );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;



-- ==========================================================================
-- IMPLICIT CASTS: VARCHAR → ENUM (mirrors 12-enum-implicit-casts.sql)
-- Required so Hibernate native queries can compare VARCHAR params to ENUM columns
-- ==========================================================================
DO $$ BEGIN CREATE CAST (character varying AS jlpt_level)       WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (character varying AS lesson_type)      WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (character varying AS content_status)   WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (character varying AS grammar_category) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (character varying AS processing_status)WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (character varying AS course_status)    WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (character varying AS user_role)        WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (character varying AS user_status)      WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (character varying AS assignment_type)  WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (character varying AS assignment_status)WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (character varying AS submission_status)WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (character varying AS feedback_status)  WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (character varying AS session_type)     WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (character varying AS session_status)   WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (character varying AS message_role)     WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (character varying AS badge_type)       WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (character varying AS transaction_type) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (character varying AS item_type)        WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (character varying AS item_rarity)      WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- ==========================================================================
-- REVERSE IMPLICIT CASTS: ENUM → VARCHAR
-- When Hibernate ddl-auto=create-drop generates enum columns as varchar,
-- native queries like:  status = CAST(? AS submission_status)
-- produce:  character varying = submission_status
-- PostgreSQL cannot resolve this without a cast from enum → varchar.
-- With IMPLICIT cast, PostgreSQL auto-promotes the enum side to varchar
-- and uses the built-in varchar = varchar operator.
-- ==========================================================================
DO $$ BEGIN CREATE CAST (submission_status  AS character varying) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (assignment_status  AS character varying) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (assignment_type    AS character varying) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (user_role          AS character varying) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (user_status        AS character varying) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (content_status     AS character varying) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (course_status      AS character varying) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (jlpt_level         AS character varying) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (lesson_type        AS character varying) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (grammar_category   AS character varying) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (processing_status  AS character varying) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (feedback_status    AS character varying) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (session_type       AS character varying) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (session_status     AS character varying) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (message_role       AS character varying) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (badge_type         AS character varying) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (transaction_type   AS character varying) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (item_type          AS character varying) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE CAST (item_rarity        AS character varying) WITH INOUT AS IMPLICIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$;


-- ==========================================================================
-- DONE
-- Hibernate will create/drop tables automatically
-- ==========================================================================
