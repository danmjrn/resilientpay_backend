package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging.PostgresLifecycleProjectionStore;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging.PostgresOutboxRepository;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ActorContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AuditEntry;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OutboxMessage;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.OutboxOperations;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.LifecycleProjectionStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OutboxRecoveryAudit;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.TransactionBoundary;
import za.co.shapeitsolutions.portfolio.resilientpay.application.service.OutboxApplicationService;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.OutboxProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ResilientPaySecurityProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ActorId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

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
    private static TransactionBoundary transactions;

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
        transactions = new SpringTransactionBoundary(new JdbcTransactionManager(dataSource));
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
        OutboxOperations repository = repositoryAt(now.plusSeconds(1), recoveries);

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

        OutboxOperations first = repositoryAt(acceptedAt.plusSeconds(1), recoveries);
        OutboxMessage firstAttempt = first.claimDue().getFirst();
        first.markFailed(firstAttempt, "BROKER_UNAVAILABLE", true);

        OutboxOperations second = repositoryAt(acceptedAt.plusSeconds(2), recoveries);
        OutboxMessage secondAttempt = second.claimDue().getFirst();
        second.markFailed(secondAttempt, "BROKER_UNAVAILABLE", true);

        OutboxOperations third = repositoryAt(acceptedAt.plusSeconds(4), recoveries);
        OutboxMessage thirdAttempt = third.claimDue().getFirst();
        third.markFailed(thirdAttempt, "BROKER_UNAVAILABLE", true);

        assertEquals("DEAD", jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, id));
        assertEquals(3, jdbc.queryForObject("SELECT attempt_count FROM outbox_event WHERE id = ?", Integer.class, id));
        assertEquals(0, repositoryAt(acceptedAt.plusSeconds(303), recoveries).recoverDueDead());

        OutboxOperations recovered = repositoryAt(acceptedAt.plusSeconds(305), recoveries);
        assertEquals(1, recovered.recoverDueDead());
        List<OutboxMessage> recoveredClaim = recovered.claimDue();
        recovered.confirmPublished(recoveredClaim.getFirst());

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
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                recoveries.incrementAndGet();
            }

            @Override
            public void recordTerminalFailure(OutboxMessage message, int recoveryCount, String errorCode) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                terminalFailures.incrementAndGet();
            }
        };

        OutboxOperations initial = repositoryAt(acceptedAt.plusSeconds(1), audit, 1, 1);
        initial.markFailed(initial.claimDue().getFirst(), "BROKER_UNAVAILABLE", true);
        OutboxOperations recovered = repositoryAt(acceptedAt.plusSeconds(301), audit, 1, 1);
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
        PostgresLifecycleProjectionStore rawProjection = new PostgresLifecycleProjectionStore(jdbc);
        LifecycleProjectionStore projection = (tenant, message, hash, correlation, event, observed) ->
                transactions.required(() -> rawProjection.record(tenant, message, hash, correlation, event, observed));
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

    @Test
    void confirmedAuditRemainsCommittedWhenPublishedStateTransactionRollsBack() {
        Instant now = Instant.parse("2026-08-24T12:00:00Z");
        UUID eventId = insertTransactionProbeEvent(now, "PENDING");
        PostgresOutboxRepository raw =
                new PostgresOutboxRepository(
                        jdbc,
                        Clock.fixed(now.plusSeconds(1), java.time.ZoneOffset.UTC),
                        transactionProbeProperties(),
                        (message, recoveryCount) -> {}) {
                    @Override
                    public void markPublished(OutboxMessage message) {
                        super.markPublished(message);
                        throw new IllegalStateException("synthetic failure after published-state write");
                    }
                };
        OutboxOperations operations = new OutboxApplicationService(
                transactions, raw, message -> appendTransactionProbeAudit(message, "PUBLICATION_CONFIRMED"));
        try {
            OutboxMessage claimed = operations.claimDue().getFirst();
            assertEquals(eventId, claimed.id());

            assertThrows(IllegalStateException.class, () -> operations.confirmPublished(claimed));

            assertEquals(
                    "CLAIMED",
                    jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, eventId));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM audit_event WHERE resource_id = ? AND action = 'PUBLICATION_CONFIRMED'",
                            Integer.class,
                            eventId));
        } finally {
            jdbc.update("DELETE FROM outbox_event WHERE id = ?", eventId);
        }
    }

    @Test
    void laterRecoveryAuditFailureRollsBackEarlierRecoveryAndAuditWritesInTheBatch() {
        Instant now = Instant.parse("2026-08-24T13:00:00Z");
        UUID first = insertTransactionProbeEvent(now, "DEAD");
        UUID second = insertTransactionProbeEvent(now.plusMillis(1), "DEAD");
        List<UUID> audited = new ArrayList<>();
        OutboxRecoveryAudit audit = (message, recoveryCount) -> {
            appendTransactionProbeAudit(message, "RECOVERY_PROBE");
            audited.add(message.id());
            if (message.id().equals(second)) {
                assertEquals(
                        "PENDING",
                        jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, first));
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT recovery_cycle_count FROM outbox_event WHERE id = ?", Integer.class, first));
                throw new IllegalStateException("synthetic second recovery audit failure");
            }
        };
        OutboxOperations operations = repositoryAt(now.plusSeconds(1), audit, 3, 3);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, operations::recoverDueDead);
            assertEquals("synthetic second recovery audit failure", failure.getMessage());
            assertEquals(List.of(first, second), audited);

            for (UUID eventId : List.of(first, second)) {
                assertEquals(
                        "DEAD",
                        jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, eventId));
                assertEquals(
                        0,
                        jdbc.queryForObject(
                                "SELECT recovery_cycle_count FROM outbox_event WHERE id = ?", Integer.class, eventId));
                assertEquals(
                        3,
                        jdbc.queryForObject(
                                "SELECT attempt_count FROM outbox_event WHERE id = ?", Integer.class, eventId));
                assertEquals(
                        0,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE resource_id = ?", Integer.class, eventId));
            }
        } finally {
            jdbc.update("DELETE FROM outbox_event WHERE id IN (?, ?)", first, second);
        }
    }

    private static UUID insertTransactionProbeEvent(Instant now, String status) {
        UUID id = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        // Cold recovery only sees events that exhausted a claimed publication cycle.
        int attemptCount = "DEAD".equals(status) ? 3 : 0;
        jdbc.update(
                """
                INSERT INTO outbox_event (
                    id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type,
                    schema_version, correlation_id, causation_id, payload, status, attempt_count,
                    available_at, created_at, retryable
                ) VALUES (?, '00000000-0000-0000-0000-000000000001', 'REVERSAL_COMMAND', ?, 1,
                          'ReversalAccepted.v1', 1, ?, ?, '{}'::jsonb, ?, ?, ?, ?, TRUE)
                """,
                id,
                UUID.randomUUID(),
                correlation,
                correlation,
                status,
                attemptCount,
                Timestamp.from(now),
                Timestamp.from(now));
        return id;
    }

    private static void appendTransactionProbeAudit(OutboxMessage message, String action) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        PostgresAuditStore audit = new PostgresAuditStore(
                jdbc,
                new ResilientPaySecurityProperties(
                        "resilientpay-api",
                        "transaction-probe-evidence-key",
                        "transaction-probe-v1",
                        "urn:resilientpay:aal2"),
                new WorkflowMetrics(new SimpleMeterRegistry()));
        audit.append(new AuditEntry(
                new ActorContext(
                        new TenantId(message.tenantId()),
                        new ActorId("transaction-probe"),
                        Set.of("ROLE_SERVICE"),
                        null),
                action,
                "OUTBOX_EVENT",
                message.id(),
                "TRANSACTION_PROBE",
                "SUCCEEDED",
                null,
                message.correlationId(),
                null,
                Map.of(),
                Instant.parse("2026-08-24T14:00:00Z")));
    }

    private static OutboxProperties transactionProbeProperties() {
        return new OutboxProperties(
                10, Duration.ofSeconds(30), Duration.ofMillis(500), 3, Duration.ofSeconds(5), Duration.ofMinutes(5), 3);
    }

    private static OutboxOperations repositoryAt(Instant now, AtomicInteger recoveries) {
        return repositoryAt(
                now,
                (message, recoveryCount) -> {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    recoveries.incrementAndGet();
                },
                3,
                3);
    }

    private static OutboxOperations repositoryAt(
            Instant now, OutboxRecoveryAudit audit, int maxAttempts, int maxRecoveryCycles) {
        PostgresOutboxRepository repository = new PostgresOutboxRepository(
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
        return new OutboxApplicationService(transactions, repository, message -> {});
    }
}
