ALTER TABLE messages
  ADD COLUMN context_run_id BIGINT NULL,
  ADD CONSTRAINT fk_messages_context_run FOREIGN KEY(tenant_id,conversation_id,context_run_id) REFERENCES ai_runs(tenant_id,conversation_id,id);
UPDATE messages SET context_run_id=COALESCE(trigger_run_id,ai_run_id);
