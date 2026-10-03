-- Widen allowed terminal states; preserve every existing Run/Node record and immutable version.
ALTER TABLE workflow_runs DROP CONSTRAINT ck_workflow_run_status;
ALTER TABLE workflow_runs ADD CONSTRAINT ck_workflow_run_status
    CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'));

ALTER TABLE workflow_node_runs DROP CONSTRAINT ck_workflow_node_run_status;
ALTER TABLE workflow_node_runs ADD CONSTRAINT ck_workflow_node_run_status
    CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'));
