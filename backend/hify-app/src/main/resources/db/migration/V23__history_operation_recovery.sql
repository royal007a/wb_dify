-- Additive, legacy canonical history remains intact. NULL means no typed tool recovery available.
ALTER TABLE run_history_commits ADD COLUMN recovery_json TEXT;
ALTER TABLE run_history_commits ADD COLUMN recovery_digest VARCHAR(64);
ALTER TABLE run_history_commits ADD CONSTRAINT ck_run_history_recovery_pair CHECK (
    (recovery_json IS NULL AND recovery_digest IS NULL)
    OR (recovery_json IS NOT NULL AND recovery_digest IS NOT NULL)
);
