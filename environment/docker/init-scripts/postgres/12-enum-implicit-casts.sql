-- ==================== FIX: Implicit CAST for PostgreSQL ENUM types ====================
-- Problem: Hibernate sends ENUM parameters as VARCHAR in WHERE clauses.
--          PostgreSQL cannot compare VARCHAR to custom ENUM types without explicit cast.
--          @ColumnTransformer(write = "CAST(? AS ...)") only applies to INSERT/UPDATE, not SELECT WHERE.
-- Fix:     Create implicit casts so PostgreSQL auto-converts VARCHAR → ENUM in comparisons.
-- ========================================================================================

-- Learning ENUM casts
CREATE CAST (character varying AS jlpt_level) WITH INOUT AS IMPLICIT;
CREATE CAST (character varying AS lesson_type) WITH INOUT AS IMPLICIT;
CREATE CAST (character varying AS content_status) WITH INOUT AS IMPLICIT;
CREATE CAST (character varying AS grammar_category) WITH INOUT AS IMPLICIT;
CREATE CAST (character varying AS processing_status) WITH INOUT AS IMPLICIT;
CREATE CAST (character varying AS course_status) WITH INOUT AS IMPLICIT;

-- User ENUM casts
CREATE CAST (character varying AS user_role) WITH INOUT AS IMPLICIT;
CREATE CAST (character varying AS user_status) WITH INOUT AS IMPLICIT;

-- Assignment ENUM casts
CREATE CAST (character varying AS assignment_type) WITH INOUT AS IMPLICIT;
CREATE CAST (character varying AS assignment_status) WITH INOUT AS IMPLICIT;
CREATE CAST (character varying AS submission_status) WITH INOUT AS IMPLICIT;

-- Other ENUM casts
CREATE CAST (character varying AS feedback_status) WITH INOUT AS IMPLICIT;
CREATE CAST (character varying AS session_type) WITH INOUT AS IMPLICIT;
CREATE CAST (character varying AS session_status) WITH INOUT AS IMPLICIT;
CREATE CAST (character varying AS message_role) WITH INOUT AS IMPLICIT;
CREATE CAST (character varying AS badge_type) WITH INOUT AS IMPLICIT;
CREATE CAST (character varying AS transaction_type) WITH INOUT AS IMPLICIT;
CREATE CAST (character varying AS item_type) WITH INOUT AS IMPLICIT;
CREATE CAST (character varying AS item_rarity) WITH INOUT AS IMPLICIT;
