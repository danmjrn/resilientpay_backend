-- Production onboarding creates these fixed app-specific roles. Legacy/demo databases omit them.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'resilientpay_runtime') THEN
        GRANT USAGE ON SCHEMA public TO resilientpay_runtime;
        REVOKE CREATE ON SCHEMA public FROM resilientpay_runtime;
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO resilientpay_runtime;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO resilientpay_runtime;
        REVOKE UPDATE, DELETE, TRUNCATE ON audit_event FROM resilientpay_runtime;
        REVOKE ALL ON flyway_schema_history FROM resilientpay_runtime;
        GRANT SELECT ON flyway_schema_history TO resilientpay_runtime;
        REVOKE DELETE, TRUNCATE ON portfolio_session FROM resilientpay_runtime;
    END IF;
END;
$$;
