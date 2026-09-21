CREATE TABLE workflows (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    schema_version INTEGER NOT NULL,
    draft_revision BIGINT NOT NULL,
    published_version_id VARCHAR(64),
    archived_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL,
    CONSTRAINT uq_workflow_name UNIQUE(name)
);

CREATE TABLE workflow_nodes (
    id VARCHAR(64) PRIMARY KEY,
    workflow_id VARCHAR(64) NOT NULL,
    node_key VARCHAR(128) NOT NULL,
    node_type VARCHAR(32) NOT NULL,
    name VARCHAR(160) NOT NULL,
    config_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_workflow_node_workflow FOREIGN KEY(workflow_id) REFERENCES workflows(id),
    CONSTRAINT uq_workflow_node_key UNIQUE(workflow_id,node_key)
);

CREATE TABLE workflow_edges (
    id VARCHAR(64) PRIMARY KEY,
    workflow_id VARCHAR(64) NOT NULL,
    edge_key VARCHAR(128) NOT NULL,
    source_node_key VARCHAR(128) NOT NULL,
    target_node_key VARCHAR(128) NOT NULL,
    condition_value VARCHAR(255),
    default_branch BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_workflow_edge_workflow FOREIGN KEY(workflow_id) REFERENCES workflows(id),
    CONSTRAINT uq_workflow_edge_key UNIQUE(workflow_id,edge_key)
);

CREATE TABLE workflow_versions (
    id VARCHAR(64) PRIMARY KEY,
    workflow_id VARCHAR(64) NOT NULL,
    version_no INTEGER NOT NULL,
    schema_version INTEGER NOT NULL,
    dsl_json TEXT NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_workflow_version_workflow FOREIGN KEY(workflow_id) REFERENCES workflows(id),
    CONSTRAINT uq_workflow_version_no UNIQUE(workflow_id,version_no)
);

ALTER TABLE workflows ADD CONSTRAINT fk_workflow_published_version
    FOREIGN KEY(published_version_id) REFERENCES workflow_versions(id);

CREATE TABLE workflow_runs (
    id VARCHAR(64) PRIMARY KEY,
    workflow_version_id VARCHAR(64) NOT NULL,
    workflow_digest VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_text TEXT NOT NULL,
    output_text TEXT,
    context_json TEXT NOT NULL,
    error_message VARCHAR(1000),
    elapsed_ms BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    row_version BIGINT NOT NULL,
    CONSTRAINT fk_workflow_run_version FOREIGN KEY(workflow_version_id) REFERENCES workflow_versions(id),
    CONSTRAINT ck_workflow_run_status CHECK(status IN ('RUNNING','SUCCEEDED','FAILED'))
);

CREATE TABLE workflow_node_runs (
    id VARCHAR(64) PRIMARY KEY,
    workflow_run_id VARCHAR(64) NOT NULL,
    sequence_no INTEGER NOT NULL,
    node_key VARCHAR(128) NOT NULL,
    node_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    output_json TEXT,
    error_message VARCHAR(1000),
    elapsed_ms BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_workflow_node_run_run FOREIGN KEY(workflow_run_id) REFERENCES workflow_runs(id),
    CONSTRAINT uq_workflow_node_run_sequence UNIQUE(workflow_run_id,sequence_no),
    CONSTRAINT ck_workflow_node_run_status CHECK(status IN ('RUNNING','SUCCEEDED','FAILED'))
);

CREATE INDEX idx_workflow_runs_version_created ON workflow_runs(workflow_version_id,created_at);
