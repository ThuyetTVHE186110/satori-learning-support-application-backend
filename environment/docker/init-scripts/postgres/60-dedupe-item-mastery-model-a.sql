-- =====================================================================
-- 60-dedupe-item-mastery-model-a.sql
--
-- Mục đích: Hợp nhất ItemMastery về Model A — một UUID chỉ có đúng một
-- row mastery per user, bất kể entity_type. LLM có thể hallucinate sai
-- entity_type cho refContentId, sinh row (user, entity_id, WRONG_TYPE)
-- bên cạnh row đúng → buildMasteryMap đổ IllegalStateException do
-- duplicate key khi Collectors.toMap lên entity_id.
--
-- Thay đổi:
--   1. Audit snapshot các row trùng (user_id, entity_id) trước khi xóa.
--   2. Xóa row có entity_type không khớp bảng nguồn (vocabulary /
--      grammar_points) — đây là row sai do LLM hallucinate.
--   3. Nếu vẫn còn trùng (UUID thực trùng cross-table, xác suất ~0),
--      giữ row có attempt_count cao hơn (tie-break updated_at DESC).
--   4. Đổi unique constraint từ (user_id, entity_id, entity_type)
--      sang (user_id, entity_id) — enforce Model A ở DB level.
-- =====================================================================

BEGIN;

-- 1) Snapshot audit (giữ lại để có thể khôi phục thủ công nếu cần)
DROP TABLE IF EXISTS practice.item_mastery_dedupe_audit;
CREATE TABLE practice.item_mastery_dedupe_audit AS
SELECT im.*, now() AS audited_at
FROM practice.item_mastery im
WHERE (im.user_id, im.entity_id) IN (
    SELECT user_id, entity_id
    FROM practice.item_mastery
    GROUP BY user_id, entity_id
    HAVING COUNT(*) > 1
);

-- 2) Xóa row có entity_type không khớp bảng nguồn
DELETE FROM practice.item_mastery im
WHERE (im.user_id, im.entity_id) IN (
    SELECT user_id, entity_id
    FROM practice.item_mastery
    GROUP BY user_id, entity_id
    HAVING COUNT(*) > 1
)
AND (
    (im.entity_type = 'VOCABULARY'
        AND NOT EXISTS (
            SELECT 1 FROM learning_content.vocabulary v
            WHERE v.id = im.entity_id
        ))
    OR
    (im.entity_type = 'GRAMMAR'
        AND NOT EXISTS (
            SELECT 1 FROM learning_content.grammar_points g
            WHERE g.id = im.entity_id
        ))
);

-- 3) Fallback: nếu sau bước 2 vẫn còn trùng (cả 2 row đều khớp bảng
--    nguồn — UUID thực trùng cross-table) → giữ row attempt_count cao
--    nhất, tie-break updated_at DESC.
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY user_id, entity_id
               ORDER BY attempt_count DESC, updated_at DESC
           ) AS rn
    FROM practice.item_mastery
)
DELETE FROM practice.item_mastery im
USING ranked r
WHERE im.id = r.id AND r.rn > 1;

-- 4) Thay unique constraint sang Model A
ALTER TABLE practice.item_mastery
    DROP CONSTRAINT IF EXISTS uq_item_mastery;

ALTER TABLE practice.item_mastery
    ADD CONSTRAINT uq_item_mastery_user_entity
    UNIQUE (user_id, entity_id);

COMMENT ON CONSTRAINT uq_item_mastery_user_entity ON practice.item_mastery
    IS 'Model A: một UUID chỉ đại diện một thực thể (vocab/grammar), nên tối đa một mastery row per (user, entity_id). entity_type là nhãn denormalize.';

COMMIT;
