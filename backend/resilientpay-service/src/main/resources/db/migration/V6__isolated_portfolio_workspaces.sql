-- Portfolio workspaces are additive. Existing synthetic-demo tenants remain unchanged.
CREATE TABLE portfolio_session (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL UNIQUE,
    cookie_hash CHAR(64) NOT NULL UNIQUE CHECK (cookie_hash ~ '^[a-f0-9]{64}$'),
    gate_hash CHAR(64) NOT NULL CHECK (gate_hash ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    revoked_at TIMESTAMPTZ,
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);
CREATE INDEX ix_portfolio_session_gate ON portfolio_session (gate_hash, created_at);
CREATE INDEX ix_portfolio_session_active ON portfolio_session (expires_at) WHERE revoked_at IS NULL;

-- Serialize physical row admission, so concurrent distinct idempotency keys cannot exceed caps.
-- Existing non-portfolio tenants and already accepted background work are deliberately unaffected.
CREATE FUNCTION enforce_portfolio_entity_quota()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    workspace portfolio_session%ROWTYPE;
    local_count BIGINT;
    global_count BIGINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM portfolio_session WHERE tenant_id = NEW.tenant_id) THEN
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(2026102511);
    SELECT * INTO STRICT workspace FROM portfolio_session WHERE tenant_id = NEW.tenant_id FOR UPDATE;
    IF workspace.revoked_at IS NOT NULL OR workspace.expires_at <= CURRENT_TIMESTAMP THEN
        RAISE EXCEPTION 'PORTFOLIO_SESSION_INACTIVE';
    END IF;
    IF TG_TABLE_NAME = 'original_transaction' THEN
        SELECT COUNT(*) INTO local_count FROM original_transaction WHERE tenant_id = NEW.tenant_id;
        SELECT COUNT(*) INTO global_count FROM original_transaction entity
            JOIN portfolio_session session ON session.tenant_id = entity.tenant_id;
        IF local_count >= 80 OR global_count >= 12000 THEN
            RAISE EXCEPTION 'PORTFOLIO_QUOTA_EXCEEDED';
        END IF;
    ELSIF TG_TABLE_NAME = 'reversal_command' THEN
        SELECT COUNT(*) INTO local_count FROM reversal_command WHERE tenant_id = NEW.tenant_id;
        SELECT COUNT(*) INTO global_count FROM reversal_command entity
            JOIN portfolio_session session ON session.tenant_id = entity.tenant_id;
        IF local_count >= 40 OR global_count >= 20000 THEN
            RAISE EXCEPTION 'PORTFOLIO_QUOTA_EXCEEDED';
        END IF;
    ELSE
        RAISE EXCEPTION 'Unsupported portfolio quota relation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER portfolio_original_quota BEFORE INSERT ON original_transaction
    FOR EACH ROW EXECUTE FUNCTION enforce_portfolio_entity_quota();
CREATE TRIGGER portfolio_command_quota BEFORE INSERT ON reversal_command
    FOR EACH ROW EXECUTE FUNCTION enforce_portfolio_entity_quota();
