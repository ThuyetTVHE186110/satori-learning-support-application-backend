-- ==========================================================================
-- Migration 29: Implicit casts for Practice module enum types
-- These casts allow PostgreSQL to automatically convert varchar → custom enum
-- in prepared statement parameters (Hibernate / JdbcTemplate usage).
-- Pattern mirrors migration 12 (enum-implicit-casts.sql).
-- ==========================================================================

-- Practice enums defined in migration 27
CREATE CAST (character varying AS practice_session_type) WITH INOUT AS IMPLICIT;
CREATE CAST (character varying AS practice_item_type)    WITH INOUT AS IMPLICIT;
CREATE CAST (character varying AS practice_status)       WITH INOUT AS IMPLICIT;
