CREATE TABLE knowledge_corpus_versions (
    id VARCHAR(64) PRIMARY KEY,
    knowledge_base_id VARCHAR(64) NOT NULL,
    revision_no INTEGER NOT NULL,
    manifest_digest VARCHAR(64) NOT NULL,
    chunk_count INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_corpus_version_base FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id),
    CONSTRAINT uq_corpus_version_revision UNIQUE (knowledge_base_id, revision_no),
    CONSTRAINT uq_corpus_version_manifest UNIQUE (knowledge_base_id, manifest_digest)
);

CREATE TABLE knowledge_corpus_version_chunks (
    corpus_version_id VARCHAR(64) NOT NULL,
    chunk_id VARCHAR(64) NOT NULL,
    content_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_corpus_version_chunk PRIMARY KEY (corpus_version_id, chunk_id),
    CONSTRAINT fk_corpus_version_chunk_version FOREIGN KEY (corpus_version_id) REFERENCES knowledge_corpus_versions(id),
    CONSTRAINT fk_corpus_version_chunk_chunk FOREIGN KEY (chunk_id) REFERENCES document_chunks(id)
);

CREATE INDEX idx_corpus_version_chunks_version ON knowledge_corpus_version_chunks(corpus_version_id);

CREATE TABLE agent_knowledge_bindings (
    agent_id VARCHAR(64) NOT NULL,
    knowledge_base_id VARCHAR(64) NOT NULL,
    top_k INTEGER NOT NULL,
    priority INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_agent_knowledge_binding PRIMARY KEY (agent_id, knowledge_base_id),
    CONSTRAINT fk_agent_knowledge_binding_agent FOREIGN KEY (agent_id) REFERENCES agent_definitions(id),
    CONSTRAINT fk_agent_knowledge_binding_base FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id),
    CONSTRAINT ck_agent_knowledge_top_k CHECK (top_k BETWEEN 1 AND 20)
);

CREATE TABLE agent_version_knowledge_bindings (
    agent_version_id VARCHAR(64) NOT NULL,
    knowledge_base_id VARCHAR(64) NOT NULL,
    corpus_version_id VARCHAR(64) NOT NULL,
    manifest_digest VARCHAR(64) NOT NULL,
    top_k INTEGER NOT NULL,
    priority INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_agent_version_knowledge_binding PRIMARY KEY (agent_version_id, knowledge_base_id),
    CONSTRAINT fk_agent_version_knowledge_version FOREIGN KEY (agent_version_id) REFERENCES agent_versions(id),
    CONSTRAINT fk_agent_version_knowledge_base FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id),
    CONSTRAINT fk_agent_version_knowledge_corpus FOREIGN KEY (corpus_version_id) REFERENCES knowledge_corpus_versions(id),
    CONSTRAINT ck_agent_version_knowledge_top_k CHECK (top_k BETWEEN 1 AND 20)
);

CREATE INDEX idx_agent_version_knowledge_version ON agent_version_knowledge_bindings(agent_version_id);
