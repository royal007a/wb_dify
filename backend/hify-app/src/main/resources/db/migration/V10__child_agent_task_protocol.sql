CREATE TABLE child_agent_tasks (
    id VARCHAR(64) PRIMARY KEY,
    parent_run_id VARCHAR(64) NOT NULL,
    child_run_id VARCHAR(64),
    task_digest VARCHAR(128) NOT NULL,
    retry_safe BOOLEAN NOT NULL DEFAULT FALSE,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    executor_id VARCHAR(128),
    state VARCHAR(32) NOT NULL,
    output_ref VARCHAR(1000),
    output_digest VARCHAR(128),
    output_state VARCHAR(32) NOT NULL,
    claim_token VARCHAR(64),
    recovery_action VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(1000),
    delivered_at TIMESTAMP,
    claimed_at TIMESTAMP,
    consumed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_child_task_parent_run FOREIGN KEY (parent_run_id) REFERENCES agent_runs(id),
    CONSTRAINT ck_child_output_reference CHECK (
        (output_state = 'NONE' AND output_ref IS NULL AND output_digest IS NULL)
        OR (output_state IN ('DELIVERED', 'CLAIMED', 'CONSUMED') AND output_ref IS NOT NULL AND output_digest IS NOT NULL)
    ),
    CONSTRAINT ck_child_claim_token CHECK (
        output_state NOT IN ('CLAIMED', 'CONSUMED') OR claim_token IS NOT NULL
    ),
    CONSTRAINT ck_child_state_output CHECK (
        (state = 'SUCCEEDED' AND output_state IN ('DELIVERED', 'CLAIMED', 'CONSUMED'))
        OR (state <> 'SUCCEEDED' AND output_state = 'NONE')
    )
);

CREATE INDEX idx_child_task_parent ON child_agent_tasks(parent_run_id);
CREATE INDEX idx_child_task_state_executor ON child_agent_tasks(state, executor_id);
CREATE INDEX idx_child_task_parent_output ON child_agent_tasks(parent_run_id, output_state);
