-- ==========================================================================
-- Migration 66: Enforce case-insensitive unique title on conversation_topics
--
-- Background:
--   speaking.conversation_topics (top-level themes after migration 36 rename)
--   had no unique constraint on title — managers could create duplicates.
--   Topic doesn't link to a course (conversations do), so uniqueness is global.
--
--   Case-insensitive: "Du Lịch" and "du lịch" are treated as the same topic.
--   Partial index (WHERE deleted_at IS NULL): a soft-deleted title can be
--   reused by creating a new active topic with the same name.
-- ==========================================================================

-- Resolve any pre-existing case-insensitive duplicates by suffixing with id.
-- Without this the unique index creation fails on a populated database.
UPDATE speaking.conversation_topics t
   SET title = t.title || ' (' || SUBSTRING(t.id::text FROM 1 FOR 8) || ')'
 WHERE t.deleted_at IS NULL
   AND EXISTS (
       SELECT 1 FROM speaking.conversation_topics t2
        WHERE t2.deleted_at IS NULL
          AND t2.id <> t.id
          AND LOWER(t2.title) = LOWER(t.title)
          AND t2.created_at < t.created_at
   );

CREATE UNIQUE INDEX IF NOT EXISTS uq_conversation_topics_title_active
    ON speaking.conversation_topics (LOWER(title))
    WHERE deleted_at IS NULL;

COMMENT ON INDEX speaking.uq_conversation_topics_title_active IS
    'Case-insensitive unique title for active (non-soft-deleted) conversation topics';
