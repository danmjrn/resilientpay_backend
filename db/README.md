# Database and migration guide

PostgreSQL 18 is authoritative for workflow, idempotency, reservations, outbox/inbox, reconciliation and audit evidence. Redis is never authoritative.

The executable Flyway inventory is mirrored here byte for byte:

1. `migration/V1__core_reversal_schema.sql`
2. `migration/V2__reliable_messaging_and_audit.sql`
3. `migration/V3__runtime_adapter_integrity.sql`
4. `migration/V4__bounded_outbox_cold_recovery.sql`
5. `migration/V5__bounded_reversal_timeline_query.sql`
6. `devseed/R__synthetic_demo_seed.sql` — repeatable, opt-in demo seed containing only neutral synthetic tokens

The application resources under `backend/resilientpay-service/src/main/resources/db` are the files Flyway executes. This directory is the review-facing mirror. Repository validation rejects a missing, extra, renamed or byte-different SQL file on either side; matching descriptions or normalized SQL are insufficient.

Important database controls include tenant-composite foreign keys, unique idempotency and action fingerprints, one reversal reservation per original transaction, bounded attempts, explicit lifecycle checks, maker-checker constraints, due-work indexes and an append-only audit trigger.

The provisional `MigrationCompatibilityIT` report records six passing cases: an empty database and each historical V1, V2, V3 and V4 state migrated to V5, plus a V3-shaped writer failing closed against V5 without changing the row count. `DemoFlywayIT` provisionally records six additional migration/runtime cases, including the exact four-original/four-policy clean demo seed and the V5 bounded timeline index. Runtime-resource mirror validation remains part of the final release gate.

V4 introduced the mandatory acceptance-time policy snapshot, so a pre-V4 binary is not a write-compatible rollback target on the current V5 schema. The additive V5 index does not relax that boundary. No older released application exists for v0.1.0; cross-version application compatibility is therefore `NOT_APPLICABLE_FIRST_RELEASE`, not a passing rollback claim.

Backup/restore instructions live in `docs/operations/backup-restore-runbook.md`. The 2026-09-01 provisional disposable rehearsal passed with byte-equivalent logical snapshots, eight passing invariant probes, observed RPO 0 seconds, measured restore 0.564 seconds and measured RTO 20.283 seconds. Those local measurements require final source-bound reproduction and are not production recovery commitments.
