-- Task P0.1: Azure STT as transcript source
-- Preserve on-device STT transcript separately from the authoritative Azure-recognized transcript.
-- conversation_messages.content now stores the Azure STT result; device_transcript holds the
-- client's locally recognized text for debugging and divergence flagging.

ALTER TABLE ai_conversation.conversation_messages
    ADD COLUMN IF NOT EXISTS device_transcript TEXT;

COMMENT ON COLUMN ai_conversation.conversation_messages.device_transcript IS
    'Raw transcript from on-device STT. Kept for debugging when it diverges from the Azure-recognized transcript stored in content.';
