CREATE TABLE agent_workflow_bindings (
    agent_id VARCHAR(64) PRIMARY KEY,
    workflow_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_agent_workflow_agent FOREIGN KEY (agent_id) REFERENCES agent_definitions(id),
    CONSTRAINT fk_agent_workflow_workflow FOREIGN KEY (workflow_id) REFERENCES workflows(id)
);

CREATE TABLE agent_version_workflow_bindings (
    agent_version_id VARCHAR(64) PRIMARY KEY,
    workflow_id VARCHAR(64) NOT NULL,
    workflow_version_id VARCHAR(64) NOT NULL,
    workflow_checksum VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_agent_version_workflow_agent FOREIGN KEY (agent_version_id) REFERENCES agent_versions(id),
    CONSTRAINT fk_agent_version_workflow_version FOREIGN KEY (workflow_version_id) REFERENCES workflow_versions(id)
);

CREATE TABLE mcp_server_revisions (
    server_id VARCHAR(64) NOT NULL,
    server_revision BIGINT NOT NULL,
    endpoint_url VARCHAR(2048) NOT NULL,
    credential_ref VARCHAR(512),
    schema_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (server_id, server_revision),
    CONSTRAINT fk_mcp_revision_server FOREIGN KEY (server_id) REFERENCES mcp_servers(id)
);

CREATE TABLE agent_mcp_tool_bindings (
    agent_id VARCHAR(64) NOT NULL,
    server_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (agent_id, server_id, tool_name),
    CONSTRAINT fk_agent_mcp_agent FOREIGN KEY (agent_id) REFERENCES agent_definitions(id),
    CONSTRAINT fk_agent_mcp_server FOREIGN KEY (server_id) REFERENCES mcp_servers(id)
);

CREATE TABLE agent_version_mcp_tool_bindings (
    agent_version_id VARCHAR(64) NOT NULL,
    server_id VARCHAR(64) NOT NULL,
    server_revision BIGINT NOT NULL,
    server_schema_digest VARCHAR(64) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    runtime_tool_name VARCHAR(512) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    input_schema_json TEXT NOT NULL,
    tool_schema_digest VARCHAR(64) NOT NULL,
    risk VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (agent_version_id, server_id, tool_name),
    CONSTRAINT uq_agent_version_runtime_tool UNIQUE (agent_version_id, runtime_tool_name),
    CONSTRAINT fk_agent_version_mcp_agent FOREIGN KEY (agent_version_id) REFERENCES agent_versions(id),
    CONSTRAINT fk_agent_version_mcp_revision FOREIGN KEY (server_id, server_revision) REFERENCES mcp_server_revisions(server_id, server_revision),
    CONSTRAINT ck_agent_version_mcp_read CHECK (risk = 'READ')
);

