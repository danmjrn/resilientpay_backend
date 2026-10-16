ALTER TABLE reversal_command
    ADD COLUMN demonstration_failure VARCHAR(24) NOT NULL DEFAULT 'NONE',
    ADD COLUMN action_fingerprint CHAR(64);

ALTER TABLE original_transaction
    ADD COLUMN public_reference_token VARCHAR(80),
    ADD CONSTRAINT ck_original_public_reference CHECK (
        public_reference_token IS NULL
        OR public_reference_token ~ '^synth-[a-z0-9-]{8,64}$'
    );

CREATE UNIQUE INDEX uq_original_public_reference
    ON original_transaction (tenant_id, public_reference_token)
    WHERE public_reference_token IS NOT NULL;

ALTER TABLE reversal_command
    ALTER COLUMN reason_code TYPE VARCHAR(120);

ALTER TABLE reconciliation_decision
    ALTER COLUMN purpose_code TYPE VARCHAR(160),
    ADD COLUMN domain_purpose_code VARCHAR(64) NOT NULL DEFAULT 'OPERATOR_DECISION';

ALTER TABLE audit_event
    ALTER COLUMN purpose_code TYPE VARCHAR(160),
    DROP CONSTRAINT ck_audit_outcome,
    ADD CONSTRAINT ck_audit_outcome CHECK (
        outcome IN ('ACCEPTED', 'SUCCEEDED', 'REJECTED', 'FAILED', 'DENIED')
    );

ALTER TABLE operation_policy
    DROP CONSTRAINT ck_operation_policy_attempts,
    ADD CONSTRAINT ck_operation_policy_attempts CHECK (max_attempts BETWEEN 1 AND 5);

ALTER TABLE execution_attempt
    DROP CONSTRAINT ck_execution_attempt_number,
    ADD CONSTRAINT ck_execution_attempt_number CHECK (attempt_number BETWEEN 1 AND 5);

UPDATE reversal_command
SET action_fingerprint = ENCODE(
    SHA256(CONVERT_TO(
        tenant_id::text || ':' || original_transaction_id::text || ':FULL_REVERSAL',
        'UTF8'
    )),
    'hex'
)
WHERE action_fingerprint IS NULL;

ALTER TABLE reversal_command
    ALTER COLUMN action_fingerprint SET NOT NULL,
    ADD CONSTRAINT ck_reversal_demonstration_failure CHECK (
        demonstration_failure IN ('NONE', 'INTERRUPT_ONCE', 'AMBIGUOUS', 'REJECT')
    ),
    ADD CONSTRAINT uq_reversal_command_action UNIQUE (tenant_id, action_fingerprint);

ALTER TABLE reconciliation_decision
    DROP CONSTRAINT ck_reconciliation_decision_outcome,
    ADD CONSTRAINT ck_reconciliation_decision_outcome CHECK (
        proposed_outcome IN (
            'CONFIRM_EXECUTED', 'CONFIRM_NOT_EXECUTED', 'REJECT_REVERSAL', 'QUARANTINE'
        )
    ),
    ADD CONSTRAINT uq_reconciliation_decision_case UNIQUE (
        tenant_id, reconciliation_case_id
    );
