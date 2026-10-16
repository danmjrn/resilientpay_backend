ALTER TABLE outbox_event
    ADD COLUMN retryable BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN recovery_cycle_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN traceparent VARCHAR(55),
    ADD COLUMN tracestate VARCHAR(512),
    ADD CONSTRAINT ck_outbox_trace_context_pair CHECK (
        traceparent IS NOT NULL OR tracestate IS NULL
    ),
    ADD CONSTRAINT ck_outbox_traceparent CHECK (
        traceparent IS NULL OR traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'
    ),
    ADD CONSTRAINT ck_outbox_recovery_cycle_count CHECK (recovery_cycle_count BETWEEN 0 AND 10);

CREATE INDEX ix_outbox_cold_recovery
    ON outbox_event (available_at, recovery_cycle_count, created_at)
    WHERE status = 'DEAD' AND retryable;

ALTER TABLE reversal_command
    ADD COLUMN policy_version_snapshot INTEGER,
    ADD COLUMN max_attempts_snapshot SMALLINT,
    ADD COLUMN result_timeout_ms_snapshot BIGINT,
    ADD COLUMN initial_retry_delay_ms_snapshot BIGINT,
    ADD COLUMN maximum_retry_delay_ms_snapshot BIGINT;

UPDATE reversal_command command
SET policy_version_snapshot = policy.policy_version,
    max_attempts_snapshot = policy.max_attempts,
    result_timeout_ms_snapshot = policy.result_timeout_ms,
    initial_retry_delay_ms_snapshot = policy.initial_retry_delay_ms,
    maximum_retry_delay_ms_snapshot = policy.maximum_retry_delay_ms
FROM operation_policy policy
WHERE policy.tenant_id = command.tenant_id
  AND policy.id = command.operation_policy_id;

ALTER TABLE reversal_command
    ALTER COLUMN policy_version_snapshot SET NOT NULL,
    ALTER COLUMN max_attempts_snapshot SET NOT NULL,
    ALTER COLUMN result_timeout_ms_snapshot SET NOT NULL,
    ALTER COLUMN initial_retry_delay_ms_snapshot SET NOT NULL,
    ALTER COLUMN maximum_retry_delay_ms_snapshot SET NOT NULL,
    ADD CONSTRAINT ck_reversal_policy_version_snapshot CHECK (policy_version_snapshot > 0),
    ADD CONSTRAINT ck_reversal_max_attempts_snapshot CHECK (max_attempts_snapshot BETWEEN 1 AND 5),
    ADD CONSTRAINT ck_reversal_retry_delay_snapshot CHECK (
        initial_retry_delay_ms_snapshot >= 100
        AND maximum_retry_delay_ms_snapshot >= initial_retry_delay_ms_snapshot
    ),
    ADD CONSTRAINT ck_reversal_result_timeout_snapshot CHECK (
        result_timeout_ms_snapshot BETWEEN 100 AND 300000
    );
