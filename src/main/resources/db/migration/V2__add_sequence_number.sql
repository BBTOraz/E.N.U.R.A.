-- Add sequence_number column to spring_ai_chat_memory table
-- Run this migration manually before starting the application

ALTER TABLE spring_ai_chat_memory ADD COLUMN IF NOT EXISTS sequence_number BIGINT;

-- Populate sequence_number for existing records based on timestamp order
WITH numbered AS (
    SELECT 
        conversation_id,
        timestamp,
        ROW_NUMBER() OVER (PARTITION BY conversation_id ORDER BY timestamp ASC) as seq
    FROM spring_ai_chat_memory
)
UPDATE spring_ai_chat_memory m
SET sequence_number = n.seq
FROM numbered n
WHERE m.conversation_id = n.conversation_id 
  AND m.timestamp = n.timestamp
  AND m.sequence_number IS NULL;

-- Create index for better query performance
CREATE INDEX IF NOT EXISTS idx_chat_memory_seq ON spring_ai_chat_memory(conversation_id, sequence_number);
