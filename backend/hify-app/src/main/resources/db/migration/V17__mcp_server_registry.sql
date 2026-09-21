CREATE TABLE mcp_servers (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    transport VARCHAR(32) NOT NULL,
    endpoint_url VARCHAR(1000) NOT NULL,
    credential_ref VARCHAR(255),
    enabled BOOLEAN NOT NULL,
    server_revision BIGINT NOT NULL,
    schema_digest VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    last_error VARCHAR(1000),
    archived_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL,
    CONSTRAINT uq_mcp_server_name UNIQUE(name),
    CONSTRAINT ck_mcp_server_transport CHECK(transport IN ('STREAMABLE_HTTP')),
    CONSTRAINT ck_mcp_server_status CHECK(status IN ('NEW','READY','FAILED','ARCHIVED'))
);

CREATE TABLE mcp_tool_snapshots (
    id VARCHAR(64) PRIMARY KEY,
    server_id VARCHAR(64) NOT NULL,
    server_revision BIGINT NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    input_schema_json TEXT NOT NULL,
    risk VARCHAR(32) NOT NULL,
    schema_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_mcp_tool_server FOREIGN KEY(server_id) REFERENCES mcp_servers(id),
    CONSTRAINT uq_mcp_tool_revision_name UNIQUE(server_id,server_revision,tool_name),
    CONSTRAINT ck_mcp_tool_risk CHECK(risk IN ('READ','WRITE','EXTERNAL','EXECUTE'))
);

CREATE TABLE mcp_debug_calls (
    id VARCHAR(64) PRIMARY KEY,
    server_id VARCHAR(64) NOT NULL,
    server_revision BIGINT NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    schema_digest VARCHAR(64) NOT NULL,
    arguments_digest VARCHAR(64) NOT NULL,
    result_json TEXT,
    is_error BOOLEAN NOT NULL,
    elapsed_ms BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_mcp_debug_server FOREIGN KEY(server_id) REFERENCES mcp_servers(id)
);

CREATE INDEX idx_mcp_tool_server_revision ON mcp_tool_snapshots(server_id,server_revision);
CREATE INDEX idx_mcp_debug_server_created ON mcp_debug_calls(server_id,created_at);
