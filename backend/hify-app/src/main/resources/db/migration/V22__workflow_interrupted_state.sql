-- Process interruption is not user cancellation; preserve all execution records.
ALTER TABLE workflow_runs DROP CONSTRAINT ck_workflow_run_status;
ALTER TABLE workflow_runs ADD CONSTRAINT ck_workflow_run_status
    CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'INTERRUPTED'));
ALTER TABLE workflow_node_runs DROP CONSTRAINT ck_workflow_node_run_status;
ALTER TABLE workflow_node_runs ADD CONSTRAINT ck_workflow_node_run_status
    CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'INTERRUPTED'));
