ALTER TABLE ai_runs ADD COLUMN classification_attempted BOOLEAN NOT NULL DEFAULT FALSE, ADD COLUMN classification_result JSON NULL;
ALTER TABLE tool_calls MODIFY COLUMN execution_version BIGINT NOT NULL;
