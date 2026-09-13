ALTER TABLE agent_definitions ADD COLUMN max_tokens INTEGER NOT NULL DEFAULT 2048;
ALTER TABLE agent_definitions ADD COLUMN max_context_turns INTEGER NOT NULL DEFAULT 10;
ALTER TABLE agent_definitions ADD COLUMN draft_revision INTEGER NOT NULL DEFAULT 1;
ALTER TABLE agent_definitions ADD COLUMN published_version_id VARCHAR(64);
ALTER TABLE agent_definitions ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE agent_definitions ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE agent_versions (
    id VARCHAR(64) PRIMARY KEY,
    agent_id VARCHAR(64) NOT NULL,
    version_no INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(2000),
    instructions VARCHAR(8000) NOT NULL,
    provider_id VARCHAR(64) NOT NULL,
    model VARCHAR(255) NOT NULL,
    temperature DOUBLE PRECISION NOT NULL,
    max_tokens INTEGER NOT NULL,
    max_turns INTEGER NOT NULL,
    max_context_turns INTEGER NOT NULL,
    enabled_tools VARCHAR(2000),
    enabled BOOLEAN NOT NULL,
    snapshot_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_agent_version_agent FOREIGN KEY (agent_id) REFERENCES agent_definitions(id),
    CONSTRAINT fk_agent_version_provider FOREIGN KEY (provider_id) REFERENCES providers(public_id),
    CONSTRAINT uq_agent_version_no UNIQUE (agent_id, version_no)
);

INSERT INTO agent_versions (
    id, agent_id, version_no, name, description, instructions, provider_id, model,
    temperature, max_tokens, max_turns, max_context_turns, enabled_tools, enabled,
    snapshot_digest, created_at
)
SELECT id || '-v1', id, 1, name, description, instructions, provider_id,
       COALESCE(model, ''), temperature, max_tokens, max_turns, max_context_turns,
       enabled_tools, enabled, 'legacy-' || id, CURRENT_TIMESTAMP
FROM agent_definitions;

UPDATE agent_definitions SET published_version_id = id || '-v1';

ALTER TABLE agent_definitions ADD CONSTRAINT fk_agent_published_version
    FOREIGN KEY (published_version_id) REFERENCES agent_versions(id);

ALTER TABLE conversations ADD COLUMN agent_version_id VARCHAR(64);
UPDATE conversations c SET agent_version_id = (
    SELECT a.published_version_id FROM agent_definitions a WHERE a.id = c.agent_id
);
ALTER TABLE conversations ADD CONSTRAINT fk_conversation_agent_version
    FOREIGN KEY (agent_version_id) REFERENCES agent_versions(id);

ALTER TABLE agent_runs ADD COLUMN agent_version_id VARCHAR(64);
ALTER TABLE agent_runs ADD COLUMN agent_snapshot_digest VARCHAR(64);
ALTER TABLE agent_runs ADD CONSTRAINT fk_run_agent_version
    FOREIGN KEY (agent_version_id) REFERENCES agent_versions(id);

CREATE INDEX idx_agent_versions_agent ON agent_versions(agent_id, version_no);
