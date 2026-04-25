-- ============================================================================
-- Migration 65 — cleanup orphan junctions in lesson_vocabulary / lesson_grammar
-- ----------------------------------------------------------------------------
-- Production data accumulated three classes of inconsistencies because the
-- previous code paths didn't keep the M-N junction tables aligned:
--
--   1. Vocabulary / GrammarPoint soft-delete didn't remove junction rows
--      → list-view counts (countByLessonId) returned more rows than the
--        detail view (which filters v.deletedAt IS NULL).
--   2. Single-lesson soft-delete (LessonServiceImpl.deleteLesson) didn't wipe
--      its junction rows (whereas import rollback did).
--   3. Import rollback wiped junctions but left vocabulary/grammar entities
--      created exclusively by that import as orphans (no remaining link, but
--      still surfaced by /manager/vocabulary listings).
--
-- This script is idempotent — safe to re-run.
-- It does NOT touch shared vocabulary/grammar that still belongs to at least
-- one active lesson.
-- ============================================================================

BEGIN;

-- Fail fast (5s) instead of blocking writes if another transaction is holding
-- a conflicting lock on the junction tables. Re-run the migration after the
-- contending transaction finishes.
SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------
-- 1. Drop junctions pointing to a soft-deleted vocabulary
-- ---------------------------------------------------------------------------
WITH dropped AS (
    DELETE FROM learning_content.lesson_vocabulary lv
    USING learning_content.vocabulary v
    WHERE lv.vocabulary_id = v.id
      AND v.deleted_at IS NOT NULL
    RETURNING lv.id
)
SELECT 'dropped lesson_vocabulary (vocab tombstone): ' || COUNT(*) AS report FROM dropped;

-- ---------------------------------------------------------------------------
-- 2. Drop junctions pointing to a soft-deleted grammar point
-- ---------------------------------------------------------------------------
WITH dropped AS (
    DELETE FROM learning_content.lesson_grammar lg
    USING learning_content.grammar_points g
    WHERE lg.grammar_id = g.id
      AND g.deleted_at IS NOT NULL
    RETURNING lg.id
)
SELECT 'dropped lesson_grammar (grammar tombstone): ' || COUNT(*) AS report FROM dropped;

-- ---------------------------------------------------------------------------
-- 3. Drop junctions whose lesson has been soft-deleted
--    (legacy DELETE /manager/lessons/{id} didn't clean these up)
-- ---------------------------------------------------------------------------
WITH dropped AS (
    DELETE FROM learning_content.lesson_vocabulary lv
    USING learning_content.lessons l
    WHERE lv.lesson_id = l.id
      AND l.deleted_at IS NOT NULL
    RETURNING lv.id
)
SELECT 'dropped lesson_vocabulary (lesson tombstone): ' || COUNT(*) AS report FROM dropped;

WITH dropped AS (
    DELETE FROM learning_content.lesson_grammar lg
    USING learning_content.lessons l
    WHERE lg.lesson_id = l.id
      AND l.deleted_at IS NOT NULL
    RETURNING lg.id
)
SELECT 'dropped lesson_grammar (lesson tombstone): ' || COUNT(*) AS report FROM dropped;

-- ---------------------------------------------------------------------------
-- 4. Re-pack order_index so it is contiguous [0..n-1] per lesson after the
--    deletions above. Keeps UI ordering stable and avoids gaps that confuse
--    drag-and-drop reordering on the manager side.
-- ---------------------------------------------------------------------------
WITH ordered AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY lesson_id
                              ORDER BY order_index NULLS LAST, id) - 1 AS new_idx
    FROM learning_content.lesson_vocabulary
)
UPDATE learning_content.lesson_vocabulary lv
SET order_index = o.new_idx
FROM ordered o
WHERE lv.id = o.id
  AND COALESCE(lv.order_index, -1) <> o.new_idx;

WITH ordered AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY lesson_id
                              ORDER BY order_index NULLS LAST, id) - 1 AS new_idx
    FROM learning_content.lesson_grammar
)
UPDATE learning_content.lesson_grammar lg
SET order_index = o.new_idx
FROM ordered o
WHERE lg.id = o.id
  AND COALESCE(lg.order_index, -1) <> o.new_idx;

-- ---------------------------------------------------------------------------
-- 5. Indexes to support the new lookups (deleteByVocabularyId / countByVocabularyId
--    and the grammar twins). Without these, a vocab used in many lessons would
--    trigger a full junction-table scan on delete.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_lesson_vocabulary_vocabulary
    ON learning_content.lesson_vocabulary(vocabulary_id);

CREATE INDEX IF NOT EXISTS idx_lesson_grammar_grammar
    ON learning_content.lesson_grammar(grammar_id);

COMMIT;

-- ---------------------------------------------------------------------------
-- 6. Orphan vocabulary / grammar preview (READ-ONLY).
--    Items that are not soft-deleted but are no longer referenced by any
--    non-deleted lesson. These are likely leftovers from older import rollbacks
--    that ran BEFORE the orphan-cleanup logic was added.
--
--    Run the SELECTs first, eyeball the results, THEN execute the UPDATEs
--    manually. The 7-day grace window prevents wiping items a CONTENT_MANAGER
--    just created without linking yet.
-- ---------------------------------------------------------------------------
-- SELECT v.id, v.japanese_text, v.jlpt_level, v.created_at, v.created_by
-- FROM learning_content.vocabulary v
-- WHERE v.deleted_at IS NULL
--   AND v.created_at < NOW() - INTERVAL '7 days'
--   AND NOT EXISTS (
--       SELECT 1 FROM learning_content.lesson_vocabulary lv
--       JOIN learning_content.lessons l ON l.id = lv.lesson_id
--       WHERE lv.vocabulary_id = v.id AND l.deleted_at IS NULL
--   );
--
-- UPDATE learning_content.vocabulary
-- SET deleted_at = NOW(), deleted_by = '00000000-0000-0000-0000-000000000000'
-- WHERE id IN ( /* paste IDs from the SELECT above */ );
--
-- SELECT g.id, g.pattern, g.jlpt_level, g.created_at, g.created_by
-- FROM learning_content.grammar_points g
-- WHERE g.deleted_at IS NULL
--   AND g.created_at < NOW() - INTERVAL '7 days'
--   AND NOT EXISTS (
--       SELECT 1 FROM learning_content.lesson_grammar lg
--       JOIN learning_content.lessons l ON l.id = lg.lesson_id
--       WHERE lg.grammar_id = g.id AND l.deleted_at IS NULL
--   );
