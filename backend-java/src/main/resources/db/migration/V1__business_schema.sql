-- Business baseline adapted from the existing Python SQLAlchemy schema.
-- UTC defaults support Java inserts; all network calls remain outside transactions.

CREATE TABLE tenants (
	name VARCHAR(120) NOT NULL, 
	id BIGINT NOT NULL AUTO_INCREMENT, 
	created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	CONSTRAINT pk_tenants PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE jobs (
	kind VARCHAR(32) NOT NULL, 
	business_key VARCHAR(160) NOT NULL, 
	payload JSON NOT NULL, 
	state VARCHAR(20) NOT NULL DEFAULT 'queued', 
	execution_version INTEGER NOT NULL DEFAULT 0, 
	attempts INTEGER NOT NULL DEFAULT 0, 
	manual_retries INTEGER NOT NULL DEFAULT 0, 
	owner VARCHAR(80), 
	lease_expires_at DATETIME(6), 
	available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	finished_at DATETIME(6), 
	error_code VARCHAR(64), 
	error_message TEXT, 
	result JSON, 
	tenant_id BIGINT NOT NULL, 
	id BIGINT NOT NULL AUTO_INCREMENT, 
	created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	CONSTRAINT pk_jobs PRIMARY KEY (id), 
	CONSTRAINT uq_jobs_tenant_id_kind_business_key UNIQUE (tenant_id, kind, business_key), 
	CONSTRAINT uq_jobs_tenant_id_id UNIQUE (tenant_id, id), 
	CONSTRAINT fk_jobs_tenant_id_tenants FOREIGN KEY(tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_jobs_schedule ON jobs (state, available_at, lease_expires_at, id);

CREATE TABLE users (
	email VARCHAR(254) NOT NULL, 
	display_name VARCHAR(80) NOT NULL, 
	password_hash VARCHAR(255) NOT NULL, 
	`role` VARCHAR(20) NOT NULL, 
	is_active BOOL NOT NULL DEFAULT 1, 
	session_version INTEGER NOT NULL DEFAULT 1, 
	tenant_id BIGINT NOT NULL, 
	id BIGINT NOT NULL AUTO_INCREMENT, 
	created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	CONSTRAINT pk_users PRIMARY KEY (id), 
	CONSTRAINT uq_users_tenant_id_email UNIQUE (tenant_id, email), 
	CONSTRAINT ck_users_role CHECK (role IN ('admin','agent','customer')), 
	CONSTRAINT uq_users_tenant_id_id UNIQUE (tenant_id, id), 
	CONSTRAINT fk_users_tenant_id_tenants FOREIGN KEY(tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE audit_logs (
	actor_id BIGINT, 
	action VARCHAR(80) NOT NULL, 
	target_type VARCHAR(40) NOT NULL, 
	target_id VARCHAR(100) NOT NULL, 
	outcome VARCHAR(24) NOT NULL, 
	request_id VARCHAR(96) NOT NULL, 
	details JSON NOT NULL DEFAULT (JSON_OBJECT()), 
	tenant_id BIGINT NOT NULL, 
	id BIGINT NOT NULL AUTO_INCREMENT, 
	created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	CONSTRAINT pk_audit_logs PRIMARY KEY (id), 
	CONSTRAINT fk_audit_logs_tenant_id_actor_id_users FOREIGN KEY(tenant_id, actor_id) REFERENCES users (tenant_id, id), 
	CONSTRAINT uq_audit_logs_tenant_id_id UNIQUE (tenant_id, id), 
	CONSTRAINT fk_audit_logs_tenant_id_tenants FOREIGN KEY(tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_audit_tenant_created ON audit_logs (tenant_id, created_at, id);

CREATE TABLE conversations (
	customer_id BIGINT NOT NULL, 
	assigned_agent_id BIGINT, 
	title VARCHAR(120) NOT NULL DEFAULT '售后咨询', 
	status VARCHAR(24) NOT NULL DEFAULT 'AI_ACTIVE', 
	version INTEGER NOT NULL DEFAULT 1, 
	generation_epoch INTEGER NOT NULL DEFAULT 0, 
	last_event_seq BIGINT NOT NULL DEFAULT 0, 
	updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	claimed_at DATETIME(6), 
	closed_at DATETIME(6), 
	tenant_id BIGINT NOT NULL, 
	id BIGINT NOT NULL AUTO_INCREMENT, 
	created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	CONSTRAINT pk_conversations PRIMARY KEY (id), 
	CONSTRAINT ck_conversations_status CHECK (status IN ('AI_ACTIVE','WAITING_HUMAN','HUMAN_ACTIVE','CLOSED')), 
	CONSTRAINT fk_conversations_tenant_id_customer_id_users FOREIGN KEY(tenant_id, customer_id) REFERENCES users (tenant_id, id), 
	CONSTRAINT fk_conversations_tenant_id_assigned_agent_id_users FOREIGN KEY(tenant_id, assigned_agent_id) REFERENCES users (tenant_id, id), 
	CONSTRAINT uq_conversations_tenant_id_id UNIQUE (tenant_id, id), 
	CONSTRAINT fk_conversations_tenant_id_tenants FOREIGN KEY(tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_conversations_customer ON conversations (tenant_id, customer_id, updated_at, id);

CREATE INDEX ix_conversations_queue ON conversations (tenant_id, status, updated_at, id);

CREATE TABLE knowledge_documents (
	title VARCHAR(200) NOT NULL, 
	uploaded_by BIGINT NOT NULL, 
	enabled BOOL NOT NULL DEFAULT 1, 
	deleted BOOL NOT NULL DEFAULT 0, 
	version INTEGER NOT NULL DEFAULT 1, 
	tenant_id BIGINT NOT NULL, 
	id BIGINT NOT NULL AUTO_INCREMENT, 
	created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	CONSTRAINT pk_knowledge_documents PRIMARY KEY (id), 
	CONSTRAINT fk_knowledge_documents_tenant_id_uploaded_by_users FOREIGN KEY(tenant_id, uploaded_by) REFERENCES users (tenant_id, id), 
	CONSTRAINT uq_knowledge_documents_tenant_id_id UNIQUE (tenant_id, id), 
	CONSTRAINT fk_knowledge_documents_tenant_id_tenants FOREIGN KEY(tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_knowledge_listing ON knowledge_documents (tenant_id, deleted, created_at, id);

CREATE TABLE orders (
	customer_id BIGINT NOT NULL, 
	order_number VARCHAR(40) NOT NULL, 
	status VARCHAR(24) NOT NULL DEFAULT 'DELIVERED', 
	total_amount NUMERIC(12, 2) NOT NULL, 
	currency VARCHAR(3) NOT NULL DEFAULT 'CNY', 
	tracking_number VARCHAR(80), 
	carrier VARCHAR(80), 
	delivery_summary VARCHAR(255), 
	tenant_id BIGINT NOT NULL, 
	id BIGINT NOT NULL AUTO_INCREMENT, 
	created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	CONSTRAINT pk_orders PRIMARY KEY (id), 
	CONSTRAINT uq_orders_tenant_id_order_number UNIQUE (tenant_id, order_number), 
	CONSTRAINT fk_orders_tenant_id_customer_id_users FOREIGN KEY(tenant_id, customer_id) REFERENCES users (tenant_id, id), 
	CONSTRAINT uq_orders_tenant_id_id UNIQUE (tenant_id, id), 
	CONSTRAINT fk_orders_tenant_id_tenants FOREIGN KEY(tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_orders_customer_created ON orders (tenant_id, customer_id, created_at, id);

CREATE TABLE outbox_events (
	job_id BIGINT, 
	kind VARCHAR(32) NOT NULL, 
	payload JSON NOT NULL DEFAULT (JSON_OBJECT()), 
	available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	dispatched_at DATETIME(6), 
	owner VARCHAR(80), 
	lease_version INTEGER NOT NULL DEFAULT 0, 
	lease_expires_at DATETIME(6), 
	attempts INTEGER NOT NULL DEFAULT 0, 
	tenant_id BIGINT NOT NULL, 
	id BIGINT NOT NULL AUTO_INCREMENT, 
	created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	CONSTRAINT pk_outbox_events PRIMARY KEY (id), 
	CONSTRAINT fk_outbox_events_tenant_id_job_id_jobs FOREIGN KEY(tenant_id, job_id) REFERENCES jobs (tenant_id, id), 
	CONSTRAINT uq_outbox_events_tenant_id_id UNIQUE (tenant_id, id), 
	CONSTRAINT fk_outbox_events_tenant_id_tenants FOREIGN KEY(tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_outbox_schedule ON outbox_events (dispatched_at, available_at, lease_expires_at, id);

CREATE TABLE conversation_events (
	conversation_id BIGINT NOT NULL, 
	seq BIGINT NOT NULL, 
	kind VARCHAR(40) NOT NULL, 
	generation_epoch INTEGER NOT NULL DEFAULT 0, 
	payload JSON NOT NULL DEFAULT (JSON_OBJECT()), 
	tenant_id BIGINT NOT NULL, 
	id BIGINT NOT NULL AUTO_INCREMENT, 
	created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	CONSTRAINT pk_conversation_events PRIMARY KEY (id), 
	CONSTRAINT uq_conversation_events_tenant_id_conversation_id_seq UNIQUE (tenant_id, conversation_id, seq), 
	CONSTRAINT fk_conversation_events_tenant_id_conversation_id_conversations FOREIGN KEY(tenant_id, conversation_id) REFERENCES conversations (tenant_id, id), 
	CONSTRAINT uq_conversation_events_tenant_id_id UNIQUE (tenant_id, id), 
	CONSTRAINT fk_conversation_events_tenant_id_tenants FOREIGN KEY(tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE document_versions (
	document_id BIGINT NOT NULL, 
	number INTEGER NOT NULL, 
	storage_key VARCHAR(100) NOT NULL, 
	filename VARCHAR(255) NOT NULL, 
	media_type VARCHAR(80) NOT NULL, 
	size_bytes BIGINT NOT NULL, 
	sha256 VARCHAR(64) NOT NULL, 
	status VARCHAR(24) NOT NULL DEFAULT 'queued', 
	error_code VARCHAR(64), 
	job_id BIGINT, 
	embedding_model VARCHAR(160), 
	embedding_dimensions INTEGER, 
	tenant_id BIGINT NOT NULL, 
	id BIGINT NOT NULL AUTO_INCREMENT, 
	created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	CONSTRAINT pk_document_versions PRIMARY KEY (id), 
	CONSTRAINT uq_document_versions_tenant_id_document_id_number UNIQUE (tenant_id, document_id, number), 
	CONSTRAINT uq_document_versions_tenant_id_document_id_id UNIQUE (tenant_id, document_id, id), 
	CONSTRAINT fk_document_versions_tenant_id_document_id_knowledge_documents FOREIGN KEY(tenant_id, document_id) REFERENCES knowledge_documents (tenant_id, id), 
	CONSTRAINT fk_document_versions_tenant_id_job_id_jobs FOREIGN KEY(tenant_id, job_id) REFERENCES jobs (tenant_id, id), 
	CONSTRAINT uq_document_versions_tenant_id_id UNIQUE (tenant_id, id), 
	CONSTRAINT fk_document_versions_tenant_id_tenants FOREIGN KEY(tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE order_items (
	order_id BIGINT NOT NULL, 
	product_name VARCHAR(200) NOT NULL, 
	sku VARCHAR(80) NOT NULL, 
	quantity INTEGER NOT NULL, 
	unit_price NUMERIC(12, 2) NOT NULL, 
	tenant_id BIGINT NOT NULL, 
	id BIGINT NOT NULL AUTO_INCREMENT, 
	created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	CONSTRAINT pk_order_items PRIMARY KEY (id), 
	CONSTRAINT fk_order_items_tenant_id_order_id_orders FOREIGN KEY(tenant_id, order_id) REFERENCES orders (tenant_id, id), 
	CONSTRAINT uq_order_items_tenant_id_id UNIQUE (tenant_id, id), 
	CONSTRAINT fk_order_items_tenant_id_tenants FOREIGN KEY(tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ticket_drafts (
	customer_id BIGINT NOT NULL, 
	order_id BIGINT NOT NULL, 
	kind VARCHAR(24) NOT NULL, 
	reason TEXT NOT NULL, 
	version INTEGER NOT NULL DEFAULT 1, 
	expires_at DATETIME(6) NOT NULL, 
	consumed BOOL NOT NULL DEFAULT 0, 
	tenant_id BIGINT NOT NULL, 
	id BIGINT NOT NULL AUTO_INCREMENT, 
	created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	CONSTRAINT pk_ticket_drafts PRIMARY KEY (id), 
	CONSTRAINT fk_ticket_drafts_tenant_id_customer_id_users FOREIGN KEY(tenant_id, customer_id) REFERENCES users (tenant_id, id), 
	CONSTRAINT fk_ticket_drafts_tenant_id_order_id_orders FOREIGN KEY(tenant_id, order_id) REFERENCES orders (tenant_id, id), 
	CONSTRAINT uq_ticket_drafts_tenant_id_id UNIQUE (tenant_id, id), 
	CONSTRAINT fk_ticket_drafts_tenant_id_tenants FOREIGN KEY(tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE after_sale_tickets (
	draft_id BIGINT NOT NULL, 
	customer_id BIGINT NOT NULL, 
	order_id BIGINT NOT NULL, 
	kind VARCHAR(24) NOT NULL, 
	reason TEXT NOT NULL, 
	status VARCHAR(24) NOT NULL DEFAULT 'SUBMITTED', 
	version INTEGER NOT NULL DEFAULT 1, 
	idempotency_key VARCHAR(80) NOT NULL, 
	request_hash VARCHAR(64) NOT NULL, 
	tenant_id BIGINT NOT NULL, 
	id BIGINT NOT NULL AUTO_INCREMENT, 
	created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	CONSTRAINT pk_after_sale_tickets PRIMARY KEY (id), 
	CONSTRAINT uq_after_sale_tickets_tenant_id_draft_id UNIQUE (tenant_id, draft_id), 
	CONSTRAINT uq_after_sale_tickets_tenant_id_idempotency_key UNIQUE (tenant_id, idempotency_key), 
	CONSTRAINT fk_after_sale_tickets_tenant_id_draft_id_ticket_drafts FOREIGN KEY(tenant_id, draft_id) REFERENCES ticket_drafts (tenant_id, id), 
	CONSTRAINT fk_after_sale_tickets_tenant_id_customer_id_users FOREIGN KEY(tenant_id, customer_id) REFERENCES users (tenant_id, id), 
	CONSTRAINT fk_after_sale_tickets_tenant_id_order_id_orders FOREIGN KEY(tenant_id, order_id) REFERENCES orders (tenant_id, id), 
	CONSTRAINT uq_after_sale_tickets_tenant_id_id UNIQUE (tenant_id, id), 
	CONSTRAINT fk_after_sale_tickets_tenant_id_tenants FOREIGN KEY(tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_tickets_status_created ON after_sale_tickets (tenant_id, status, created_at, id);

CREATE TABLE ai_runs (
	conversation_id BIGINT NOT NULL, 
	trigger_event_seq BIGINT NOT NULL, 
	generation_epoch INTEGER NOT NULL, 
	job_id BIGINT NOT NULL, 
	state VARCHAR(24) NOT NULL DEFAULT 'queued', 
	provider VARCHAR(80) NOT NULL DEFAULT 'demo', 
	model VARCHAR(160), 
	`usage` JSON, 
	started_at DATETIME(6), 
	finished_at DATETIME(6), 
	latency_ms INTEGER, 
	error_code VARCHAR(64), 
	tenant_id BIGINT NOT NULL, 
	id BIGINT NOT NULL AUTO_INCREMENT, 
	created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	CONSTRAINT pk_ai_runs PRIMARY KEY (id), 
	CONSTRAINT uq_ai_runs_tenant_id_conversation_id_generation_epoch UNIQUE (tenant_id, conversation_id, generation_epoch), 
	CONSTRAINT fk_ai_runs_tenant_id_conversation_id_trigger_event_seq_c_b527 FOREIGN KEY(tenant_id, conversation_id, trigger_event_seq) REFERENCES conversation_events (tenant_id, conversation_id, seq), 
	CONSTRAINT fk_ai_runs_tenant_id_job_id_jobs FOREIGN KEY(tenant_id, job_id) REFERENCES jobs (tenant_id, id), 
	CONSTRAINT uq_ai_runs_tenant_id_id UNIQUE (tenant_id, id), 
	CONSTRAINT fk_ai_runs_tenant_id_tenants FOREIGN KEY(tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE knowledge_active_versions (
	document_id BIGINT NOT NULL, 
	version_id BIGINT NOT NULL, 
	tenant_id BIGINT NOT NULL, 
	id BIGINT NOT NULL AUTO_INCREMENT, 
	created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	CONSTRAINT pk_knowledge_active_versions PRIMARY KEY (id), 
	CONSTRAINT uq_knowledge_active_versions_tenant_id_document_id UNIQUE (tenant_id, document_id), 
	CONSTRAINT fk_knowledge_active_versions_tenant_id_document_id_knowl_74da FOREIGN KEY(tenant_id, document_id) REFERENCES knowledge_documents (tenant_id, id), 
	CONSTRAINT fk_knowledge_active_versions_tenant_id_document_id_versi_b88e FOREIGN KEY(tenant_id, document_id, version_id) REFERENCES document_versions (tenant_id, document_id, id), 
	CONSTRAINT uq_knowledge_active_versions_tenant_id_id UNIQUE (tenant_id, id), 
	CONSTRAINT fk_knowledge_active_versions_tenant_id_tenants FOREIGN KEY(tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE knowledge_chunks (
	document_id BIGINT NOT NULL, 
	version_id BIGINT NOT NULL, 
	ordinal INTEGER NOT NULL, 
	page INTEGER NOT NULL, 
	text TEXT NOT NULL, 
	tenant_id BIGINT NOT NULL, 
	id BIGINT NOT NULL AUTO_INCREMENT, 
	created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	CONSTRAINT pk_knowledge_chunks PRIMARY KEY (id), 
	CONSTRAINT uq_knowledge_chunks_tenant_id_version_id_ordinal UNIQUE (tenant_id, version_id, ordinal), 
	CONSTRAINT fk_knowledge_chunks_tenant_id_document_id_version_id_doc_2cb2 FOREIGN KEY(tenant_id, document_id, version_id) REFERENCES document_versions (tenant_id, document_id, id), 
	CONSTRAINT uq_knowledge_chunks_tenant_id_id UNIQUE (tenant_id, id), 
	CONSTRAINT fk_knowledge_chunks_tenant_id_tenants FOREIGN KEY(tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE FULLTEXT INDEX ft_knowledge_text ON knowledge_chunks (text) WITH PARSER ngram;

CREATE INDEX ix_chunks_version ON knowledge_chunks (tenant_id, version_id);

CREATE TABLE messages (
	conversation_id BIGINT NOT NULL, 
	sender_id BIGINT, 
	author_role VARCHAR(20) NOT NULL, 
	client_message_id VARCHAR(80), 
	ai_run_id BIGINT, 
	body TEXT NOT NULL, 
	citations JSON NOT NULL DEFAULT (JSON_ARRAY()), 
	draft_id BIGINT, 
	tenant_id BIGINT NOT NULL, 
	id BIGINT NOT NULL AUTO_INCREMENT, 
	created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	CONSTRAINT pk_messages PRIMARY KEY (id), 
	CONSTRAINT uq_messages_tenant_id_conversation_id_client_message_id UNIQUE (tenant_id, conversation_id, client_message_id), 
	CONSTRAINT uq_messages_tenant_id_ai_run_id UNIQUE (tenant_id, ai_run_id), 
	CONSTRAINT fk_messages_tenant_id_conversation_id_conversations FOREIGN KEY(tenant_id, conversation_id) REFERENCES conversations (tenant_id, id), 
	CONSTRAINT fk_messages_tenant_id_sender_id_users FOREIGN KEY(tenant_id, sender_id) REFERENCES users (tenant_id, id), 
	CONSTRAINT fk_messages_tenant_id_ai_run_id_ai_runs FOREIGN KEY(tenant_id, ai_run_id) REFERENCES ai_runs (tenant_id, id), 
	CONSTRAINT uq_messages_tenant_id_id UNIQUE (tenant_id, id), 
	CONSTRAINT fk_messages_tenant_id_tenants FOREIGN KEY(tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_messages_timeline ON messages (tenant_id, conversation_id, created_at, id);

CREATE TABLE ticket_events (
	ticket_id BIGINT NOT NULL, 
	actor_id BIGINT NOT NULL, 
	from_status VARCHAR(24), 
	to_status VARCHAR(24) NOT NULL, 
	note VARCHAR(500) NOT NULL DEFAULT '', 
	tenant_id BIGINT NOT NULL, 
	id BIGINT NOT NULL AUTO_INCREMENT, 
	created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	CONSTRAINT pk_ticket_events PRIMARY KEY (id), 
	CONSTRAINT fk_ticket_events_tenant_id_ticket_id_after_sale_tickets FOREIGN KEY(tenant_id, ticket_id) REFERENCES after_sale_tickets (tenant_id, id), 
	CONSTRAINT fk_ticket_events_tenant_id_actor_id_users FOREIGN KEY(tenant_id, actor_id) REFERENCES users (tenant_id, id), 
	CONSTRAINT uq_ticket_events_tenant_id_id UNIQUE (tenant_id, id), 
	CONSTRAINT fk_ticket_events_tenant_id_tenants FOREIGN KEY(tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tool_calls (
	ai_run_id BIGINT NOT NULL, 
	execution_version INTEGER NOT NULL, 
	step INTEGER NOT NULL, 
	tool_name VARCHAR(80) NOT NULL, 
	arguments_summary JSON NOT NULL DEFAULT (JSON_OBJECT()), 
	outcome VARCHAR(24) NOT NULL, 
	latency_ms INTEGER NOT NULL DEFAULT 0, 
	error_code VARCHAR(64), 
	tenant_id BIGINT NOT NULL, 
	id BIGINT NOT NULL AUTO_INCREMENT, 
	created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), 
	CONSTRAINT pk_tool_calls PRIMARY KEY (id), 
	CONSTRAINT fk_tool_calls_tenant_id_ai_run_id_ai_runs FOREIGN KEY(tenant_id, ai_run_id) REFERENCES ai_runs (tenant_id, id), 
	CONSTRAINT uq_tool_calls_tenant_id_ai_run_id_execution_version_step UNIQUE (tenant_id, ai_run_id, execution_version, step), 
	CONSTRAINT uq_tool_calls_tenant_id_id UNIQUE (tenant_id, id), 
	CONSTRAINT fk_tool_calls_tenant_id_tenants FOREIGN KEY(tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
