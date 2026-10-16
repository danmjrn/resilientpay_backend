CREATE TABLE original_transaction (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    external_reference_token CHAR(64) NOT NULL,
    operation_type VARCHAR(40) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_original_transaction_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_original_transaction_reference UNIQUE (tenant_id, external_reference_token),
    CONSTRAINT ck_original_transaction_amount CHECK (amount > 0),
    CONSTRAINT ck_original_transaction_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_original_transaction_status CHECK (status IN ('POSTED', 'REVERSAL_RESERVED', 'REVERSED')),
    CONSTRAINT ck_original_transaction_operation CHECK (
        operation_type IN ('CARD_PURCHASE', 'ACCOUNT_TRANSFER', 'CASH_WITHDRAWAL', 'MERCHANT_PAYOUT')
    )
);

CREATE TABLE operation_policy (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    operation_type VARCHAR(40) NOT NULL,
    policy_version INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    max_attempts SMALLINT NOT NULL,
    initial_retry_delay_ms BIGINT NOT NULL,
    maximum_retry_delay_ms BIGINT NOT NULL,
    result_timeout_ms BIGINT NOT NULL,
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    created_by VARCHAR(160) NOT NULL,
    submitted_by VARCHAR(160),
    approved_by VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_operation_policy_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_operation_policy_version UNIQUE (tenant_id, operation_type, policy_version),
    CONSTRAINT ck_operation_policy_attempts CHECK (max_attempts BETWEEN 1 AND 5),
    CONSTRAINT ck_operation_policy_delays CHECK (
        initial_retry_delay_ms >= 100 AND maximum_retry_delay_ms >= initial_retry_delay_ms
    ),
    CONSTRAINT ck_operation_policy_timeout CHECK (result_timeout_ms BETWEEN 100 AND 300000),
    CONSTRAINT ck_operation_policy_status CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_operation_policy_checker CHECK (approved_by IS NULL OR approved_by <> submitted_by),
    CONSTRAINT ck_operation_policy_effective_range CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE UNIQUE INDEX uq_operation_policy_active
    ON operation_policy (tenant_id, operation_type)
    WHERE status = 'ACTIVE';

CREATE TABLE reversal_command (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    original_transaction_id UUID NOT NULL,
    operation_policy_id UUID NOT NULL,
    idempotency_key_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    correlation_id UUID NOT NULL,
    operation_type VARCHAR(40) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    state VARCHAR(40) NOT NULL,
    failure_code VARCHAR(80),
    accepted_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_reversal_command_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_reversal_command_idempotency UNIQUE (tenant_id, idempotency_key_hash),
    CONSTRAINT fk_reversal_original FOREIGN KEY (tenant_id, original_transaction_id)
        REFERENCES original_transaction (tenant_id, id),
    CONSTRAINT fk_reversal_policy FOREIGN KEY (tenant_id, operation_policy_id)
        REFERENCES operation_policy (tenant_id, id),
    CONSTRAINT ck_reversal_amount CHECK (amount > 0),
    CONSTRAINT ck_reversal_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_reversal_state CHECK (state IN (
        'ACCEPTED', 'ROUTED', 'EXECUTION_PENDING', 'EXECUTING', 'RETRY_SCHEDULED',
        'RECONCILIATION_REQUIRED', 'COMPLETED', 'REJECTED', 'QUARANTINED'
    ))
);

CREATE INDEX ix_reversal_command_search
    ON reversal_command (tenant_id, state, accepted_at DESC);
CREATE INDEX ix_reversal_command_original
    ON reversal_command (tenant_id, original_transaction_id, accepted_at DESC);

CREATE TABLE reversal_reservation (
    tenant_id UUID NOT NULL,
    original_transaction_id UUID NOT NULL,
    reversal_command_id UUID NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, original_transaction_id),
    CONSTRAINT uq_reversal_reservation_command UNIQUE (tenant_id, reversal_command_id),
    CONSTRAINT fk_reservation_original FOREIGN KEY (tenant_id, original_transaction_id)
        REFERENCES original_transaction (tenant_id, id),
    CONSTRAINT fk_reservation_command FOREIGN KEY (tenant_id, reversal_command_id)
        REFERENCES reversal_command (tenant_id, id)
);

CREATE TABLE execution_attempt (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    reversal_command_id UUID NOT NULL,
    attempt_number SMALLINT NOT NULL,
    dispatch_id UUID NOT NULL,
    state VARCHAR(40) NOT NULL,
    result_deadline TIMESTAMPTZ NOT NULL,
    downstream_reference_token CHAR(64),
    result_code VARCHAR(80),
    error_code VARCHAR(80),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_execution_attempt_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_execution_attempt_number UNIQUE (tenant_id, reversal_command_id, attempt_number),
    CONSTRAINT uq_execution_attempt_dispatch UNIQUE (tenant_id, dispatch_id),
    CONSTRAINT fk_attempt_reversal FOREIGN KEY (tenant_id, reversal_command_id)
        REFERENCES reversal_command (tenant_id, id),
    CONSTRAINT ck_execution_attempt_number CHECK (attempt_number BETWEEN 1 AND 5),
    CONSTRAINT ck_execution_attempt_state CHECK (state IN (
        'CREATED', 'DISPATCHED', 'KNOWN_NOT_SENT', 'SUCCEEDED', 'REJECTED', 'OUTCOME_UNKNOWN'
    ))
);

CREATE INDEX ix_execution_attempt_deadline
    ON execution_attempt (state, result_deadline)
    WHERE state = 'DISPATCHED';

CREATE TABLE reconciliation_case (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    reversal_command_id UUID NOT NULL,
    state VARCHAR(40) NOT NULL,
    ambiguity_reason VARCHAR(80) NOT NULL,
    evidence_deadline TIMESTAMPTZ NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_reconciliation_case_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_reconciliation_case_command UNIQUE (tenant_id, reversal_command_id),
    CONSTRAINT fk_reconciliation_reversal FOREIGN KEY (tenant_id, reversal_command_id)
        REFERENCES reversal_command (tenant_id, id),
    CONSTRAINT ck_reconciliation_state CHECK (state IN (
        'OPEN', 'EVIDENCE_PENDING', 'DECISION_PROPOSED', 'RESOLVED', 'QUARANTINED'
    ))
);

CREATE INDEX ix_reconciliation_case_work
    ON reconciliation_case (state, evidence_deadline)
    WHERE state IN ('OPEN', 'EVIDENCE_PENDING');

CREATE TABLE reconciliation_decision (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    reconciliation_case_id UUID NOT NULL,
    proposed_outcome VARCHAR(40) NOT NULL,
    proposed_by VARCHAR(160) NOT NULL,
    approved_by VARCHAR(160),
    purpose_code VARCHAR(80) NOT NULL,
    evidence_digest CHAR(64) NOT NULL,
    proposed_at TIMESTAMPTZ NOT NULL,
    approved_at TIMESTAMPTZ,
    CONSTRAINT uq_reconciliation_decision_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_decision_case FOREIGN KEY (tenant_id, reconciliation_case_id)
        REFERENCES reconciliation_case (tenant_id, id),
    CONSTRAINT ck_reconciliation_decision_checker CHECK (approved_by IS NULL OR approved_by <> proposed_by),
    CONSTRAINT ck_reconciliation_decision_outcome CHECK (
        proposed_outcome IN ('CONFIRMED_EXECUTED', 'CONFIRMED_NOT_EXECUTED', 'REMAIN_UNRESOLVED')
    )
);
