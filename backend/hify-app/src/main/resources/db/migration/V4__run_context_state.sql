ALTER TABLE run_checkpoints ADD COLUMN evidence_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE run_checkpoints ADD COLUMN gap_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE run_checkpoints ADD COLUMN context_json TEXT NOT NULL DEFAULT '{"evidenceVersion":0,"gapVersion":0,"claims":[],"evidence":[],"gaps":[],"lastSemanticFingerprint":null,"repeatedStateCount":0}';

ALTER TABLE agent_runs ADD COLUMN resumed_from_run_id VARCHAR(64);
ALTER TABLE agent_runs ADD COLUMN resolved_gap_ids VARCHAR(4000);
ALTER TABLE agent_runs ADD CONSTRAINT fk_run_resumed_from
    FOREIGN KEY (resumed_from_run_id) REFERENCES agent_runs(id);
CREATE INDEX idx_agent_runs_resumed_from ON agent_runs(resumed_from_run_id);
