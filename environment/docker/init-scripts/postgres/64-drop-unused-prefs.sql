-- Task P2.9: Drop unused learning preference columns.
-- learning_pace and conversation_style were never consumed by any AI prompt or feature logic;
-- UI surfaces were confusing learners who expected them to change AI behavior.

ALTER TABLE user_management.user_learning_preferences
    DROP COLUMN IF EXISTS learning_pace,
    DROP COLUMN IF EXISTS conversation_style;
