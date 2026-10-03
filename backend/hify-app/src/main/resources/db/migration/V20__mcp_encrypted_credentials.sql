-- Append-only credential versions. Server/revision rows contain references only.
CREATE TABLE mcp_credentials (
    id VARCHAR(64) PRIMARY KEY,
    server_id VARCHAR(64) NOT NULL,
    encrypted_value TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_mcp_credential_server FOREIGN KEY(server_id) REFERENCES mcp_servers(id)
);
CREATE INDEX idx_mcp_credential_server ON mcp_credentials(server_id);
