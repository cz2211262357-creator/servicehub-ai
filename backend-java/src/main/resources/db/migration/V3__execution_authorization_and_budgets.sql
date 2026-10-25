-- session_version is the existing authoritative user authorization/revocation version.
ALTER TABLE users MODIFY COLUMN session_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE conversations MODIFY COLUMN generation_epoch BIGINT NOT NULL DEFAULT 0,
  MODIFY COLUMN version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE ai_runs MODIFY COLUMN generation_epoch BIGINT NOT NULL,
  MODIFY COLUMN provider VARCHAR(80) NOT NULL DEFAULT 'bailian',
  ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 1,
  ADD COLUMN business_scope JSON NOT NULL DEFAULT (JSON_OBJECT()),
  ADD COLUMN model_requests_reserved INT NOT NULL DEFAULT 0,
  ADD COLUMN tool_executions_reserved INT NOT NULL DEFAULT 0,
  ADD COLUMN budget_revision BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN terminal_reason VARCHAR(64) NULL,
  ADD COLUMN input_expires_at DATETIME(6) NULL;

ALTER TABLE jobs MODIFY COLUMN execution_version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN execution_class VARCHAR(16) NOT NULL DEFAULT 'BACKGROUND',
  ADD COLUMN conversation_id BIGINT NULL,
  ADD COLUMN accepted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  ADD COLUMN started_at DATETIME(6) NULL,
  ADD COLUMN deadline_at DATETIME(6) NULL,
  ADD COLUMN budget_revision BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN lifetime_attempts INT NOT NULL DEFAULT 0,
  ADD UNIQUE KEY uq_jobs_conversation_id(tenant_id,conversation_id,id),
  ADD KEY ix_jobs_conversation(tenant_id,conversation_id,execution_class,state),
  ADD CONSTRAINT ck_jobs_execution_class CHECK(execution_class IN ('INTERACTIVE','BACKGROUND'));
UPDATE jobs SET execution_class='INTERACTIVE',conversation_id=CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.conversation_id')) AS UNSIGNED)
  WHERE kind='ai_reply';
ALTER TABLE jobs ADD CONSTRAINT fk_jobs_conversation FOREIGN KEY(tenant_id,conversation_id) REFERENCES conversations(tenant_id,id);
ALTER TABLE ai_runs ADD CONSTRAINT fk_runs_job_scope FOREIGN KEY(tenant_id,conversation_id,job_id) REFERENCES jobs(tenant_id,conversation_id,id);
ALTER TABLE messages ADD CONSTRAINT fk_messages_trigger_job FOREIGN KEY(tenant_id,conversation_id,trigger_job_id) REFERENCES jobs(tenant_id,conversation_id,id);

ALTER TABLE outbox_events MODIFY COLUMN lease_version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN dispatch_key VARCHAR(100) NULL,
  ADD UNIQUE KEY uq_outbox_dispatch(tenant_id,job_id,dispatch_key);

ALTER TABLE ticket_drafts
  ADD COLUMN origin_topic_id BIGINT NULL,
  ADD COLUMN origin_run_id BIGINT NULL,
  ADD COLUMN origin_epoch BIGINT NULL,
  ADD COLUMN origin_budget_revision BIGINT NULL,
  ADD COLUMN action_hash VARCHAR(64) NULL,
  ADD UNIQUE KEY uq_draft_agent_action(tenant_id,origin_run_id,origin_budget_revision,action_hash),
  ADD CONSTRAINT fk_draft_origin_topic FOREIGN KEY(tenant_id,origin_topic_id) REFERENCES conversation_topics(tenant_id,id),
  ADD CONSTRAINT fk_draft_origin_run FOREIGN KEY(tenant_id,origin_run_id) REFERENCES ai_runs(tenant_id,id);
