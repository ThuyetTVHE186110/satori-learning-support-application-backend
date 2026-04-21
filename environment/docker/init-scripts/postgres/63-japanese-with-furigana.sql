-- Task P0.5: Furigana-annotated Japanese content for learners who can't yet read kanji
-- Format: 漢字[かんじ] (e.g., 先生[せんせい]と話[はな]しました。)
-- Rendered by the client alongside the plain japanese field.

ALTER TABLE ai_conversation.conversation_messages
    ADD COLUMN IF NOT EXISTS japanese_with_furigana TEXT;

COMMENT ON COLUMN ai_conversation.conversation_messages.japanese_with_furigana IS
    'Same sentence as japanese_content with every kanji word annotated 漢字[かんじ]. Rendered by the client for learners who cannot yet read kanji.';

ALTER TABLE practice.practice_items
    ADD COLUMN IF NOT EXISTS question_japanese_with_furigana TEXT;

COMMENT ON COLUMN practice.practice_items.question_japanese_with_furigana IS
    'Same sentence as question_japanese with every kanji word annotated 漢字[かんじ]. Rendered by the client for learners who cannot yet read kanji.';
