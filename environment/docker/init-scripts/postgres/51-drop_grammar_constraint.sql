-- Drop constraint: at least one meaning (vi or en) must be provided
ALTER TABLE learning_content.grammar_points
DROP CONSTRAINT chk_grammar_meaning;
