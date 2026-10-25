ALTER TABLE document_versions ADD COLUMN index_execution_version BIGINT NULL;
ALTER TABLE knowledge_chunks ADD COLUMN execution_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE knowledge_index_generations (
    tenant_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    execution_version BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_cleanup_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id,version_id,execution_version),
    CONSTRAINT fk_index_generation_version FOREIGN KEY (tenant_id,version_id) REFERENCES document_versions(tenant_id,id),
    CONSTRAINT fk_index_generation_job FOREIGN KEY (tenant_id,job_id) REFERENCES jobs(tenant_id,id),
    INDEX ix_index_generation_cleanup(last_cleanup_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
