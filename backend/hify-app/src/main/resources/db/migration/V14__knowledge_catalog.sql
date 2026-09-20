CREATE TABLE knowledge_bases (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    chunk_size INTEGER NOT NULL,
    chunk_overlap INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL,
    archived_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL,
    CONSTRAINT uq_knowledge_base_name UNIQUE (name),
    CONSTRAINT ck_knowledge_chunk_size CHECK (chunk_size BETWEEN 64 AND 2048),
    CONSTRAINT ck_knowledge_chunk_overlap CHECK (chunk_overlap >= 0 AND chunk_overlap < chunk_size)
);

CREATE TABLE knowledge_documents (
    id VARCHAR(64) PRIMARY KEY,
    knowledge_base_id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    file_size BIGINT NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    document_version INTEGER NOT NULL,
    canonical_content TEXT NOT NULL,
    indexing_state VARCHAR(32) NOT NULL,
    error_message VARCHAR(1000),
    chunk_count INTEGER NOT NULL,
    archived_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL,
    CONSTRAINT fk_knowledge_document_base FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id),
    CONSTRAINT uq_knowledge_document_version UNIQUE (knowledge_base_id, checksum, document_version),
    CONSTRAINT ck_knowledge_document_state CHECK (indexing_state IN ('PENDING','PROCESSING','DONE','FAILED','ARCHIVED'))
);

CREATE INDEX idx_knowledge_documents_base_created
    ON knowledge_documents(knowledge_base_id, created_at);

CREATE TABLE document_chunks (
    id VARCHAR(64) PRIMARY KEY,
    knowledge_base_id VARCHAR(64) NOT NULL,
    document_id VARCHAR(64) NOT NULL,
    document_version INTEGER NOT NULL,
    ordinal INTEGER NOT NULL,
    content TEXT NOT NULL,
    content_digest VARCHAR(64) NOT NULL,
    token_count INTEGER NOT NULL,
    embedding_text TEXT NOT NULL,
    archived_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_document_chunk_base FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id),
    CONSTRAINT fk_document_chunk_document FOREIGN KEY (document_id) REFERENCES knowledge_documents(id),
    CONSTRAINT uq_document_chunk_ordinal UNIQUE (document_id, document_version, ordinal)
);

CREATE INDEX idx_document_chunks_base_active
    ON document_chunks(knowledge_base_id, archived_at);

CREATE TABLE document_index_tasks (
    id VARCHAR(64) PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL,
    document_version INTEGER NOT NULL,
    state VARCHAR(32) NOT NULL,
    attempt INTEGER NOT NULL,
    lease_owner VARCHAR(128),
    checkpoint_ordinal INTEGER NOT NULL,
    error_message VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_document_index_task_document FOREIGN KEY (document_id) REFERENCES knowledge_documents(id),
    CONSTRAINT uq_document_index_task_version UNIQUE (document_id, document_version),
    CONSTRAINT ck_document_index_task_state CHECK (state IN ('PENDING','RUNNING','SUCCEEDED','FAILED'))
);

CREATE INDEX idx_document_index_tasks_state_created
    ON document_index_tasks(state, created_at);
