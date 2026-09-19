ALTER TABLE agent_definitions ADD COLUMN archived_at TIMESTAMP;
ALTER TABLE agent_definitions ADD CONSTRAINT uq_agent_definition_name UNIQUE (name);

CREATE TABLE agent_tool_bindings (
    agent_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_agent_tool_binding PRIMARY KEY (agent_id, tool_name),
    CONSTRAINT fk_agent_tool_binding_agent
        FOREIGN KEY (agent_id) REFERENCES agent_definitions(id)
);

CREATE TABLE agent_version_tool_bindings (
    agent_version_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_agent_version_tool_binding PRIMARY KEY (agent_version_id, tool_name),
    CONSTRAINT fk_agent_version_tool_binding_version
        FOREIGN KEY (agent_version_id) REFERENCES agent_versions(id)
);

CREATE INDEX idx_agent_tool_bindings_agent ON agent_tool_bindings(agent_id);
CREATE INDEX idx_agent_version_tool_bindings_version
    ON agent_version_tool_bindings(agent_version_id);
