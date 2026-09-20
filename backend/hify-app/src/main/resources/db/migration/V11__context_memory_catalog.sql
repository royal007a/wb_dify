CREATE TABLE history_detail_refs (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    source_revision BIGINT NOT NULL,
    source_message_index INTEGER NOT NULL,
    kind VARCHAR(32) NOT NULL,
    role VARCHAR(32) NOT NULL,
    content_digest VARCHAR(64) NOT NULL,
    content_preview VARCHAR(2000) NOT NULL,
    search_text TEXT NOT NULL,
    keywords_text VARCHAR(4000) NOT NULL,
    entities_text VARCHAR(4000) NOT NULL,
    token_count INTEGER NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_detail_ref_run FOREIGN KEY (run_id) REFERENCES agent_runs(id),
    CONSTRAINT fk_detail_ref_conversation FOREIGN KEY (conversation_id) REFERENCES conversations(id),
    CONSTRAINT uq_detail_ref_source UNIQUE (run_id, source_message_index, content_digest)
);

CREATE INDEX idx_detail_ref_run_index ON history_detail_refs(run_id, source_message_index);
CREATE INDEX idx_detail_ref_conversation_time ON history_detail_refs(conversation_id, occurred_at);
CREATE INDEX idx_detail_ref_kind_time ON history_detail_refs(kind, occurred_at);

CREATE TABLE context_summaries (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    range_start INTEGER NOT NULL,
    range_end INTEGER NOT NULL,
    content_json TEXT NOT NULL,
    source_refs_json TEXT NOT NULL,
    critical_facts_json TEXT NOT NULL,
    constraints_json TEXT NOT NULL,
    decisions_json TEXT NOT NULL,
    open_gaps_json TEXT NOT NULL,
    digest VARCHAR(64) NOT NULL,
    summary_version INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_context_summary_run FOREIGN KEY (run_id) REFERENCES agent_runs(id),
    CONSTRAINT fk_context_summary_conversation FOREIGN KEY (conversation_id) REFERENCES conversations(id),
    CONSTRAINT uq_context_summary_version UNIQUE (run_id, kind, summary_version),
    CONSTRAINT ck_context_summary_range CHECK (range_start >= 0 AND range_end >= range_start)
);

CREATE INDEX idx_context_summary_run_kind ON context_summaries(run_id, kind, summary_version);

CREATE TABLE context_summary_claims (
    id VARCHAR(64) PRIMARY KEY,
    summary_id VARCHAR(64) NOT NULL,
    claim_key VARCHAR(128) NOT NULL,
    claim_type VARCHAR(32) NOT NULL,
    statement VARCHAR(4000) NOT NULL,
    source_refs_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    conflict_group VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_summary_claim_summary FOREIGN KEY (summary_id) REFERENCES context_summaries(id)
);

CREATE INDEX idx_summary_claim_summary ON context_summary_claims(summary_id);
CREATE INDEX idx_summary_claim_key ON context_summary_claims(claim_key, status);
