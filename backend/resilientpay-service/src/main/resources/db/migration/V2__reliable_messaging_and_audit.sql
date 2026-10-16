CREATE TABLE idempotency_record (
    tenant_id UUID NOT NULL,
    action VARCHAR(80) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    resource_id UUID,
    response_status INTEGER,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, action, key_hash),
    CONSTRAINT ck_idempotency_response_status CHECK (
        response_status IS NULL OR response_status BETWEEN 200 AND 599
    ),
    CONSTRAINT ck_idempotency_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_idempotency_expiry ON idempotency_record (expires_at);

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    schema_version INTEGER NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count SMALLINT NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL,
    lease_owner VARCHAR(120),
    lease_until TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    CONSTRAINT uq_outbox_aggregate_event UNIQUE (
        tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type
    ),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'CLAIMED', 'PUBLISHED', 'DEAD')),
    CONSTRAINT ck_outbox_attempts CHECK (attempt_count BETWEEN 0 AND 30),
    CONSTRAINT ck_outbox_schema_version CHECK (schema_version > 0)
);

CREATE INDEX ix_outbox_due
    ON outbox_event (available_at, created_at)
    WHERE status IN ('PENDING', 'CLAIMED');

CREATE TABLE inbox_message (
    tenant_id UUID NOT NULL,
    consumer_name VARCHAR(120) NOT NULL,
    message_id UUID NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    correlation_id UUID NOT NULL,
    processing_outcome VARCHAR(40),
    first_seen_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, consumer_name, message_id)
);

CREATE TABLE message_quarantine (
    id UUID PRIMARY KEY,
    tenant_id UUID,
    consumer_name VARCHAR(120) NOT NULL,
    message_id VARCHAR(160),
    correlation_id UUID,
    payload_digest CHAR(64) NOT NULL,
    error_code VARCHAR(80) NOT NULL,
    state VARCHAR(24) NOT NULL,
    occurrence_count INTEGER NOT NULL DEFAULT 1,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT ck_quarantine_state CHECK (state IN ('OPEN', 'REPLAY_APPROVED', 'RESOLVED')),
    CONSTRAINT ck_quarantine_occurrence CHECK (occurrence_count > 0)
);

CREATE INDEX ix_message_quarantine_open
    ON message_quarantine (state, first_seen_at)
    WHERE state = 'OPEN';

CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_subject_token VARCHAR(160) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    authorities JSONB NOT NULL,
    action VARCHAR(120) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id UUID NOT NULL,
    purpose_code VARCHAR(80) NOT NULL,
    outcome VARCHAR(40) NOT NULL,
    reason_code VARCHAR(80),
    correlation_id UUID NOT NULL,
    trace_id VARCHAR(64),
    policy_version INTEGER,
    safe_details JSONB NOT NULL,
    key_id VARCHAR(64) NOT NULL,
    evidence_digest CHAR(64) NOT NULL,
    CONSTRAINT uq_audit_evidence_digest UNIQUE (tenant_id, evidence_digest),
    CONSTRAINT ck_audit_actor_type CHECK (actor_type IN ('HUMAN', 'SERVICE', 'SYSTEM')),
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('ACCEPTED', 'SUCCEEDED', 'REJECTED', 'FAILED'))
);

CREATE INDEX ix_audit_resource_timeline
    ON audit_event (tenant_id, resource_type, resource_id, occurred_at);
CREATE INDEX ix_audit_correlation
    ON audit_event (tenant_id, correlation_id, occurred_at);

CREATE FUNCTION prevent_audit_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only';
END;
$$;

CREATE TRIGGER audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_event_mutation();
