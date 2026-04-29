-- 67-vocab-grammar-partial-unique.sql
--
-- Replace full unique constraints on (japanese_text, jlpt_level) and
-- (pattern, jlpt_level) with partial unique indexes that exclude
-- soft-deleted rows. Aligns DB-level uniqueness with the app-level lookup
-- (findBy...AndDeletedAtIsNull), so re-importing content whose previous
-- lesson was rejected/rolled-back no longer collides on insert.

-- Vocabulary
ALTER TABLE learning_content.vocabulary
    DROP CONSTRAINT IF EXISTS vocabulary_japanese_text_jlpt_level_key;

CREATE UNIQUE INDEX IF NOT EXISTS uk_vocabulary_japanese_text_jlpt_level_active
    ON learning_content.vocabulary (japanese_text, jlpt_level)
    WHERE deleted_at IS NULL;

-- Grammar points
ALTER TABLE learning_content.grammar_points
    DROP CONSTRAINT IF EXISTS grammar_points_pattern_jlpt_level_key;

CREATE UNIQUE INDEX IF NOT EXISTS uk_grammar_points_pattern_jlpt_level_active
    ON learning_content.grammar_points (pattern, jlpt_level)
    WHERE deleted_at IS NULL;
