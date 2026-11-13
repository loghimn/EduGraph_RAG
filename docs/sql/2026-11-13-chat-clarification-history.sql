-- PostgreSQL; run before starting the updated core-service when schema changes
-- are managed manually. Existing messages remain compatible (NULL = no choices).
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS clarification_options TEXT;
CREATE INDEX IF NOT EXISTS idx_chat_messages_session_cursor
    ON chat_messages (session_id, message_id);
