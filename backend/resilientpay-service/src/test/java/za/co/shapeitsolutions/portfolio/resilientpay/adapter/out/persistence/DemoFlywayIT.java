package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging.LifecycleProjectionStore;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging.OutboxMessage;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging.OutboxRecoveryAudit;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging.PostgresLifecycleProjectionStore;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging.PostgresOutboxRepository;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.OutboxProperties;

/**
 * Verifies the canonical PostgreSQL 18 schema, repeatable demo seed, and outbox lease SQL.
 *
 * @author Daniel Jr Nkulu
 */
@Testcontainers(disabledWithoutDocker = true)
class DemoFlywayIT {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/devseed")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void cleanDemoMigrationHasExactlyFourOriginalsAndFourActivePolicies() {
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM original_transaction", Integer.class));
        assertEquals(
                4, jdbc.queryForObject("SELECT COUNT(*) FROM operation_policy WHERE status = 'ACTIVE'", Integer.class));
        assertEquals(
                List.of(
                        "synth-txn-card-0001",
                        "synth-txn-cash-0001",
                        "synth-txn-merchant-01",
                        "synth-txn-transfer-001"),
                jdbc.queryForList(
                        "SELECT public_reference_token FROM original_transaction ORDER BY public_reference_token",
                        String.class));
    }

    @Test
    void boundedTenantTimelineHasAnOrderCompatibleIndex() {
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_indexes
                    WHERE schemaname = current_schema()
                      AND tablename = 'reversal_command'
                      AND indexname = 'ix_reversal_command_tenant_timeline'
                      AND indexdef LIKE '%(tenant_id, accepted_at DESC, id DESC)%'
                )
                """, Boolean.class)));
    }

    @Test
    void outboxClaimsAndPermanentlyFailsOneBoundedLease() {
        UUID id = UUID.randomUUID();
        UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID aggregate = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        jdbc.update(
                """
                INSERT INTO outbox_event (
                    id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type,
                    schema_version, correlation_id, causation_id, payload, status, attempt_count,
                    available_at, created_at
                ) VALUES (?, ?, 'REVERSAL_COMMAND', ?, 1, 'ReversalAccepted.v1', 1, ?, ?,
                          ?::jsonb, 'PENDING', 0, ?, ?)
                """,
                id,
                tenant,
                aggregate,
                correlation,
                correlation,
                "{\"reversalCommandId\":\"" + aggregate + "\"}",
                Timestamp.from(now),
                Timestamp.from(now));
        AtomicInteger recoveries = new AtomicInteger();
        PostgresOutboxRepository repository = new PostgresOutboxRepository(
                jdbc,
                Clock.fixed(now.plusSeconds(1), java.time.ZoneOffset.UTC),
                new OutboxProperties(
                        10,
                        Duration.ofSeconds(30),
                        Duration.ofMillis(500),
                        3,
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(5),
                        3),
                (message, recoveryCount) -> recoveries.incrementAndGet());

        List<OutboxMessage> claimed = repository.claimDue();
        repository.markFailed(claimed.getFirst(), "PERMANENT_TEST_FAILURE", false);

        assertEquals(1, claimed.size());
        assertEquals(id, claimed.getFirst().id());
        assertEquals("DEAD", jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, id));
        assertEquals(0, recoveries.get());
    }

    @Test
    void exhaustedRetryableOutboxCoolsDownThenRecoversTheSameLogicalEvent() {
        UUID id = UUID.randomUUID();
        UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID aggregate = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        Instant acceptedAt = Instant.parse("2026-08-25T13:00:00Z");
        jdbc.update(
                """
                INSERT INTO outbox_event (
                    id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type,
                    schema_version, correlation_id, causation_id, payload, status, attempt_count,
                    available_at, created_at, traceparent, tracestate
                ) VALUES (?, ?, 'REVERSAL_COMMAND', ?, 1, 'ReversalAccepted.v1', 1, ?, ?,
                          ?::jsonb, 'PENDING', 0, ?, ?,
                          '00-11111111111111111111111111111111-2222222222222222-01', 'vendor=demo')
                """,
                id,
                tenant,
                aggregate,
                correlation,
                correlation,
                "{\"reversalCommandId\":\"" + aggregate + "\"}",
                Timestamp.from(acceptedAt),
                Timestamp.from(acceptedAt));
        AtomicInteger recoveries = new AtomicInteger();

        PostgresOutboxRepository first = repositoryAt(acceptedAt.plusSeconds(1), recoveries);
        OutboxMessage firstAttempt = first.claimDue().getFirst();
        first.markFailed(firstAttempt, "BROKER_UNAVAILABLE", true);

        PostgresOutboxRepository second = repositoryAt(acceptedAt.plusSeconds(2), recoveries);
        OutboxMessage secondAttempt = second.claimDue().getFirst();
        second.markFailed(secondAttempt, "BROKER_UNAVAILABLE", true);

        PostgresOutboxRepository third = repositoryAt(acceptedAt.plusSeconds(4), recoveries);
        OutboxMessage thirdAttempt = third.claimDue().getFirst();
        third.markFailed(thirdAttempt, "BROKER_UNAVAILABLE", true);

        assertEquals("DEAD", jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, id));
        assertEquals(3, jdbc.queryForObject("SELECT attempt_count FROM outbox_event WHERE id = ?", Integer.class, id));
        assertEquals(0, repositoryAt(acceptedAt.plusSeconds(303), recoveries).recoverDueDead());

        PostgresOutboxRepository recovered = repositoryAt(acceptedAt.plusSeconds(305), recoveries);
        assertEquals(1, recovered.recoverDueDead());
        List<OutboxMessage> recoveredClaim = recovered.claimDue();
        recovered.markPublished(recoveredClaim.getFirst());

        assertEquals(1, recoveries.get());
        assertEquals(1, recoveredClaim.size());
        assertEquals(id, recoveredClaim.getFirst().id());
        assertEquals(
                "00-11111111111111111111111111111111-2222222222222222-01",
                recoveredClaim.getFirst().traceContext().traceparent());
        assertEquals("vendor=demo", recoveredClaim.getFirst().traceContext().tracestate());
        assertEquals(
                "PUBLISHED", jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, id));
        assertEquals(
                1,
                jdbc.queryForObject("SELECT recovery_cycle_count FROM outbox_event WHERE id = ?", Integer.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE id = ?", Integer.class, id));
    }

    @Test
    void finiteColdRecoveryBudgetEndsInTerminalOperatorVisibleCallback() {
        UUID id = UUID.randomUUID();
        UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID aggregate = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        Instant acceptedAt = Instant.parse("2026-08-25T14:00:00Z");
        jdbc.update(
                """
                INSERT INTO outbox_event (
                    id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type,
                    schema_version, correlation_id, causation_id, payload, status, attempt_count,
                    available_at, created_at
                ) VALUES (?, ?, 'REVERSAL_COMMAND', ?, 1, 'ReversalAccepted.v1', 1, ?, ?,
                          ?::jsonb, 'PENDING', 0, ?, ?)
                """,
                id,
                tenant,
                aggregate,
                correlation,
                correlation,
                "{\"reversalCommandId\":\"" + aggregate + "\"}",
                Timestamp.from(acceptedAt),
                Timestamp.from(acceptedAt));
        AtomicInteger recoveries = new AtomicInteger();
        AtomicInteger terminalFailures = new AtomicInteger();
        OutboxRecoveryAudit audit = new OutboxRecoveryAudit() {
            @Override
            public void recordRecovered(OutboxMessage message, int recoveryCount) {
                recoveries.incrementAndGet();
            }

            @Override
            public void recordTerminalFailure(OutboxMessage message, int recoveryCount, String errorCode) {
                terminalFailures.incrementAndGet();
            }
        };

        PostgresOutboxRepository initial = repositoryAt(acceptedAt.plusSeconds(1), audit, 1, 1);
        initial.markFailed(initial.claimDue().getFirst(), "BROKER_UNAVAILABLE", true);
        PostgresOutboxRepository recovered = repositoryAt(acceptedAt.plusSeconds(301), audit, 1, 1);
        assertEquals(1, recovered.recoverDueDead());
        recovered.markFailed(recovered.claimDue().getFirst(), "BROKER_UNAVAILABLE", true);

        assertEquals(1, recoveries.get());
        assertEquals(1, terminalFailures.get());
        assertEquals("DEAD", jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, id));
        assertEquals(false, jdbc.queryForObject("SELECT retryable FROM outbox_event WHERE id = ?", Boolean.class, id));
        assertEquals(
                "OUTBOX_RECOVERY_EXHAUSTED",
                jdbc.queryForObject("SELECT last_error_code FROM outbox_event WHERE id = ?", String.class, id));
        assertEquals(0, repositoryAt(acceptedAt.plusSeconds(1000), audit, 1, 1).recoverDueDead());
    }

    @Test
    void lifecycleProjectionInboxDeduplicatesAndDetectsConflictingPayloads() {
        PostgresLifecycleProjectionStore projection = new PostgresLifecycleProjectionStore(jdbc);
        UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID messageId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant observedAt = Instant.parse("2026-08-26T00:00:00Z");

        assertEquals(
                LifecycleProjectionStore.RECORDED,
                projection.record(tenant, messageId, "1".repeat(64), correlationId, "ReversalAccepted.v1", observedAt));
        assertEquals(
                LifecycleProjectionStore.DUPLICATE,
                projection.record(
                        tenant,
                        messageId,
                        "1".repeat(64),
                        correlationId,
                        "ReversalAccepted.v1",
                        observedAt.plusSeconds(1)));
        assertEquals(
                LifecycleProjectionStore.CONFLICT,
                projection.record(
                        tenant,
                        messageId,
                        "2".repeat(64),
                        correlationId,
                        "ReversalAccepted.v1",
                        observedAt.plusSeconds(2)));
        assertEquals(1, jdbc.queryForObject("""
                        SELECT COUNT(*) FROM inbox_message
                        WHERE tenant_id = ? AND consumer_name = 'resilientpay-lifecycle-projection-v1'
                          AND message_id = ?
                        """, Integer.class, tenant, messageId));
    }

    private static PostgresOutboxRepository repositoryAt(Instant now, AtomicInteger recoveries) {
        return new PostgresOutboxRepository(
                jdbc,
                Clock.fixed(now, java.time.ZoneOffset.UTC),
                new OutboxProperties(
                        10,
                        Duration.ofSeconds(30),
                        Duration.ofMillis(500),
                        3,
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(5),
                        3),
                (message, recoveryCount) -> recoveries.incrementAndGet());
    }

    private static PostgresOutboxRepository repositoryAt(
            Instant now, OutboxRecoveryAudit audit, int maxAttempts, int maxRecoveryCycles) {
        return new PostgresOutboxRepository(
                jdbc,
                Clock.fixed(now, java.time.ZoneOffset.UTC),
                new OutboxProperties(
                        10,
                        Duration.ofSeconds(30),
                        Duration.ofMillis(500),
                        maxAttempts,
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(5),
                        maxRecoveryCycles),
                audit);
    }
}
