-- The primary tenant timeline is ordered without a state predicate.  The V1 search index
-- leads with state and therefore cannot provide this ordering across all command states.
-- Keep the query bounded by allowing PostgreSQL to stop after the newest tenant rows.
CREATE INDEX ix_reversal_command_tenant_timeline
    ON reversal_command (tenant_id, accepted_at DESC, id DESC);
