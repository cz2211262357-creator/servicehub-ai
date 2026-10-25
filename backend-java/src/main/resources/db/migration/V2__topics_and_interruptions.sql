CREATE TABLE conversation_topics (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  conversation_id BIGINT NOT NULL,
  title VARCHAR(120) NOT NULL,
  summary TEXT NULL,
  facts JSON NOT NULL DEFAULT (JSON_OBJECT()),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_activity_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(id),
  UNIQUE KEY uq_topics_tenant_id(tenant_id,id),
  UNIQUE KEY uq_topics_conversation_id(tenant_id,conversation_id,id),
  KEY ix_topics_recent(tenant_id,conversation_id,last_activity_at,id),
  CONSTRAINT fk_topics_conversation FOREIGN KEY(tenant_id,conversation_id) REFERENCES conversations(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE conversations ADD COLUMN active_topic_id BIGINT NULL;
INSERT INTO conversation_topics(tenant_id,conversation_id,title,created_at,last_activity_at)
  SELECT tenant_id,id,'默认话题',created_at,updated_at FROM conversations;
UPDATE conversations c JOIN conversation_topics t ON t.tenant_id=c.tenant_id AND t.conversation_id=c.id
  SET c.active_topic_id=t.id;
ALTER TABLE conversations ADD CONSTRAINT fk_conversations_active_topic
  FOREIGN KEY(tenant_id,id,active_topic_id) REFERENCES conversation_topics(tenant_id,conversation_id,id);

ALTER TABLE messages
  ADD COLUMN topic_id BIGINT NULL,
  ADD COLUMN routing_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  ADD COLUMN context_status VARCHAR(16) NOT NULL DEFAULT 'ELIGIBLE',
  ADD COLUMN accepted_generation_epoch BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN trigger_run_id BIGINT NULL,
  ADD COLUMN trigger_job_id BIGINT NULL,
  ADD CONSTRAINT fk_messages_topic FOREIGN KEY(tenant_id,conversation_id,topic_id) REFERENCES conversation_topics(tenant_id,conversation_id,id),
  ADD CONSTRAINT ck_messages_routing CHECK(routing_state IN ('PENDING','RESOLVED')),
  ADD CONSTRAINT ck_messages_context CHECK(context_status IN ('ELIGIBLE','SUPERSEDED','EXCLUDED')),
  ADD KEY ix_messages_context(tenant_id,conversation_id,topic_id,routing_state,context_status,id);
UPDATE messages m JOIN conversations c ON c.tenant_id=m.tenant_id AND c.id=m.conversation_id
  SET m.topic_id=c.active_topic_id,m.routing_state='RESOLVED';
ALTER TABLE messages ADD CONSTRAINT ck_messages_resolved_topic CHECK(routing_state='PENDING' OR topic_id IS NOT NULL);

ALTER TABLE ai_runs ADD COLUMN topic_id BIGINT NULL,
  ADD UNIQUE KEY uq_runs_conversation_id(tenant_id,conversation_id,id),
  ADD CONSTRAINT fk_runs_topic FOREIGN KEY(tenant_id,conversation_id,topic_id) REFERENCES conversation_topics(tenant_id,conversation_id,id);
UPDATE ai_runs r JOIN conversations c ON c.tenant_id=r.tenant_id AND c.id=r.conversation_id SET r.topic_id=c.active_topic_id;
ALTER TABLE messages
  ADD CONSTRAINT fk_messages_run_scope FOREIGN KEY(tenant_id,conversation_id,ai_run_id) REFERENCES ai_runs(tenant_id,conversation_id,id),
  ADD CONSTRAINT fk_messages_trigger_run FOREIGN KEY(tenant_id,conversation_id,trigger_run_id) REFERENCES ai_runs(tenant_id,conversation_id,id);

CREATE TABLE conversation_interruptions (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  conversation_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  target_epoch BIGINT NOT NULL,
  result_epoch BIGINT NOT NULL,
  result_snapshot JSON NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(id),
  UNIQUE KEY uq_interrupt_actor_epoch(tenant_id,conversation_id,actor_id,target_epoch),
  CONSTRAINT fk_interrupt_conversation FOREIGN KEY(tenant_id,conversation_id) REFERENCES conversations(tenant_id,id),
  CONSTRAINT fk_interrupt_actor FOREIGN KEY(tenant_id,actor_id) REFERENCES users(tenant_id,id),
  CONSTRAINT ck_interrupt_epoch CHECK(target_epoch>=0 AND result_epoch>=target_epoch)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
