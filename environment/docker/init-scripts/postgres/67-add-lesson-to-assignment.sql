-- Add lesson_id column to assignments table
-- This allows assignments to be linked to specific lessons from the course module

ALTER TABLE assignment.assignments
    ADD COLUMN lesson_id UUID;

-- Add index for efficient filtering by lesson_id
CREATE INDEX idx_assignments_lesson_id ON assignment.assignments (lesson_id);

-- Add foreign key constraint to learning_content.lessons table
ALTER TABLE assignment.assignments
    ADD CONSTRAINT fk_assignments_lesson_id
        FOREIGN KEY (lesson_id)
            REFERENCES learning_content.lessons (id)
            ON DELETE SET NULL;
