-- =====================================================================
-- 69-pronunciation-word-details.sql
--
-- Mục đích: Persist Azure word-level + phoneme-level pronunciation
-- assessment vào MessagePronunciation để aggregator có thể đọc lại.
-- Trước đây chỉ lưu 4 average scores + raw_response JSONB; word_details
-- bị parse rồi vứt → không thể sinh feedback chỉ rõ từ nào sai phát âm.
--
-- Theo PRONUNCIATION_FEEDBACK_PLAN.md (Levis intelligibility principle +
-- Derwing & Munro 3-construct + Saito explicit-feedback efficacy).
-- Scope: roleplay + free-talk only.
--
-- Thay đổi:
--   - word_details JSONB: structured array of {word, accuracyScore,
--     errorType, phonemes[]} — output trực tiếp từ AzureSpeechProvider.
--   - weak_phonemes JSONB: pre-aggregated per-message {ipa: {count, avg}}
--     cho phoneme có score < 70, tránh re-scan word_details ở session level.
--
-- Idempotent (safe to re-run).
-- =====================================================================

ALTER TABLE ai_conversation.message_pronunciation
    ADD COLUMN IF NOT EXISTS word_details   JSONB,
    ADD COLUMN IF NOT EXISTS weak_phonemes  JSONB;

COMMENT ON COLUMN ai_conversation.message_pronunciation.word_details IS
    'Azure word-level assessment array: [{word, accuracyScore, errorType, phonemes:[{phoneme, accuracyScore}]}]. NULL when provider returned no detail.';

COMMENT ON COLUMN ai_conversation.message_pronunciation.weak_phonemes IS
    'Pre-aggregated phonemes with score < 70 for this message: {ipa: {count, avgScore}}. Speeds session-level rollup.';
