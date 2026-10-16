package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging.OutboxMessage;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging.OutcomeObservation;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging.PostgresOutboxRepository;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging.QuarantineMetrics;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging.RabbitTraceContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AcceptReversalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AcceptanceResult;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ActorContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApproveReconciliationCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AuditEvidenceView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.DemonstrationFailure;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ProposeReconciliationCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ReconciliationCaseView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.RetryReversalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.OutboxProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ReconciliationProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ResilientPaySecurityProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.RuntimeMode;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation.ReconciliationDecision;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalCommandState;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalReason;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ActorId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.CorrelationId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.Money;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Exercises authoritative PostgreSQL workflow invariants through the real orchestration adapter.
 *
 * @author Daniel Jr Nkulu
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresOrchestrationStoreIT {
    private static final TenantId TENANT = new TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final TenantId OTHER_TENANT = new TenantId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    private static final UUID CARD_ORIGINAL = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID TRANSFER_ORIGINAL = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID CASH_ORIGINAL = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID MERCHANT_ORIGINAL = UUID.fromString("10000000-0000-4000-8000-000000000004");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static PostgresOrchestrationStore store;

    @BeforeAll
    static void configureStore() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/devseed")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TransactionTemplate independentAudit = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        independentAudit.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        store = new PostgresOrchestrationStore(
                jdbc,
                Clock.systemUTC(),
                new ReconciliationProperties(Duration.ofSeconds(5), 25, Duration.ofHours(24)),
                new ResilientPaySecurityProperties(
                        "resilientpay-api", "postgres-it-evidence-key", "postgres-it-v1", "urn:resilientpay:acr:mfa"),
                new WorkflowMetrics(meters),
                new QuarantineMetrics(meters),
                new RabbitTraceContext(Tracer.NOOP, Propagator.NOOP),
                independentAudit,
                new RuntimeMode(true));
    }

    @Test
    void acceptanceReplayOutcomeDedupRetryDueTimeAndTenantIsolationAreDurable() {
        ActorContext actor = actor("card-operator");
        AcceptReversalCommand request = acceptance(
                "synth-txn-card-0001",
                OperationType.CARD_PURCHASE,
                "120",
                "idempotency-card-000001",
                UUID.randomUUID());

        AcceptanceResult accepted = transaction(() -> store.accept(actor, request));
        AcceptanceResult replay = transaction(() -> store.accept(actor, request));

        assertFalse(accepted.replayed());
        assertTrue(replay.replayed());
        assertEquals(accepted.command().id(), replay.command().id());
        assertEquals("REVERSAL_RESERVED", originalState(CARD_ORIGINAL));
        assertEquals(3, outboxCount(request.correlationId().value()));
        assertTrue(
                transaction(() -> store.listReversals(OTHER_TENANT, null, 50)).isEmpty());
        assertThrows(
                ApplicationException.class,
                () -> transaction(
                        () -> store.getReversal(OTHER_TENANT, accepted.command().id())));

        UUID attemptId = jdbc.queryForObject(
                "SELECT id FROM execution_attempt WHERE reversal_command_id = ?",
                UUID.class,
                accepted.command().id());
        UUID messageId = UUID.randomUUID();
        OutcomeObservation safeOutcome = outcome(
                accepted,
                attemptId,
                messageId,
                "a".repeat(64),
                "CONFIRMED_NOT_EXECUTED",
                "SWITCH_CONFIRMED_NOT_EXECUTED");
        transaction(() -> {
            store.processOutcome(safeOutcome);
            return null;
        });
        transaction(() -> {
            store.processOutcome(safeOutcome);
            return null;
        });
        OutcomeObservation conflictingReplay = outcome(
                accepted,
                attemptId,
                messageId,
                "b".repeat(64),
                "CONFIRMED_NOT_EXECUTED",
                "SWITCH_CONFIRMED_NOT_EXECUTED");
        transaction(() -> {
            store.processOutcome(conflictingReplay);
            return null;
        });

        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inbox_message WHERE message_id = ?", Integer.class, messageId));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM message_quarantine WHERE error_code = 'MESSAGE_ID_PAYLOAD_CONFLICT'",
                        Integer.class));
        RetryReversalCommand retry = new RetryReversalCommand(
                accepted.command().id(),
                "CONFIRMED_NOT_EXECUTED",
                "Synthetic retry after confirmed non-execution",
                "idempotency-retry-00001",
                new CorrelationId(UUID.randomUUID()));
        assertThrows(ApplicationException.class, () -> transaction(() -> store.retry(actor, retry)));

        jdbc.update(
                "UPDATE reversal_command SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?",
                accepted.command().id());
        assertEquals(2, transaction(() -> store.retry(actor, retry)).attemptCount());
        assertEquals(2, transaction(() -> store.retry(actor, retry)).attemptCount());
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM execution_attempt WHERE reversal_command_id = ?",
                        Integer.class,
                        accepted.command().id()));
    }

    @Test
    @DisplayName("R03: concurrent distinct keys reserve one original and commit one atomic outbox set")
    void concurrentDistinctKeysReserveOneOriginalAndCommitOneAtomicOutboxSet() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = List.of(
                    executor.submit(() -> concurrentAccept(ready, start, "idempotency-transfer-a1")),
                    executor.submit(() -> concurrentAccept(ready, start, "idempotency-transfer-b1")));
            ready.await();
            start.countDown();

            assertEquals(
                    1,
                    results.stream()
                            .filter(PostgresOrchestrationStoreIT::successful)
                            .count());
        }

        assertEquals("REVERSAL_RESERVED", originalState(TRANSFER_ORIGINAL));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM reversal_command WHERE original_transaction_id = ?",
                        Integer.class,
                        TRANSFER_ORIGINAL));
        assertEquals(3, jdbc.queryForObject("""
                        SELECT COUNT(*) FROM outbox_event
                        WHERE aggregate_id = ?
                           OR aggregate_id IN (
                               SELECT id FROM reversal_command WHERE original_transaction_id = ?
                           )
                        """, Integer.class, TRANSFER_ORIGINAL, TRANSFER_ORIGINAL));
    }

    @Test
    @DisplayName("R01 concurrent identical requests with one key create one command and replay")
    void r01ConcurrentIdenticalRequestsCreateOneLogicalOutboxSetAndOneReplay() throws Exception {
        String token = "synth-r01-identical-request-0001";
        UUID originalId = insertSyntheticOriginal(token, OperationType.CARD_PURCHASE, "120");
        ActorContext actor = actor("r01-operator");
        AcceptReversalCommand request =
                acceptance(token, OperationType.CARD_PURCHASE, "120", "idempotency-r01-same-key", UUID.randomUUID());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<AcceptanceResult>> results = List.of(
                    executor.submit(() -> concurrentAcceptResult(ready, start, actor, request)),
                    executor.submit(() -> concurrentAcceptResult(ready, start, actor, request)));
            ready.await();
            start.countDown();
            AcceptanceResult first = results.get(0).get();
            AcceptanceResult second = results.get(1).get();

            assertEquals(first.command().id(), second.command().id());
            assertEquals(
                    1,
                    List.of(first, second).stream()
                            .filter(AcceptanceResult::replayed)
                            .count());
        }

        UUID commandId = jdbc.queryForObject(
                "SELECT id FROM reversal_command WHERE original_transaction_id = ?", UUID.class, originalId);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM reversal_command WHERE original_transaction_id = ?",
                        Integer.class,
                        originalId));
        assertEquals(3, logicalOutboxCount(originalId, commandId));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_event WHERE resource_id = ? AND action = 'REVERSAL_ACCEPT_REPLAYED'",
                        Integer.class,
                        commandId));
    }

    @Test
    @DisplayName("R02 same idempotency key with changed request is denied without new financial rows")
    void r02ChangedRequestWithSameKeyIsDeniedAndDurablyAudited() {
        String token = "synth-r02-key-reuse-request-0001";
        UUID originalId = insertSyntheticOriginal(token, OperationType.CARD_PURCHASE, "120");
        ActorContext actor = actor("r02-operator");
        UUID correlationId = UUID.randomUUID();
        AcceptReversalCommand original =
                acceptance(token, OperationType.CARD_PURCHASE, "120", "idempotency-r02-reused-key", correlationId);
        AcceptanceResult accepted = transaction(() -> store.accept(actor, original));
        AcceptReversalCommand changed = new AcceptReversalCommand(
                token,
                OperationType.CARD_PURCHASE,
                new Money(new BigDecimal("120"), Currency.getInstance("ZAR")),
                new ReversalReason("Changed synthetic request"),
                DemonstrationFailure.NONE,
                original.idempotencyKey(),
                new CorrelationId(UUID.randomUUID()));

        ApplicationException failure =
                assertThrows(ApplicationException.class, () -> transaction(() -> store.accept(actor, changed)));

        assertEquals(ApplicationErrorCode.IDEMPOTENCY_KEY_REUSED, failure.code());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM reversal_command WHERE original_transaction_id = ?",
                        Integer.class,
                        originalId));
        assertEquals(3, logicalOutboxCount(originalId, accepted.command().id()));
        assertEquals(
                1, jdbc.queryForObject("""
                        SELECT COUNT(*) FROM audit_event
                        WHERE resource_id = ? AND action = 'IDEMPOTENCY_KEY_REUSE_DENIED'
                          AND outcome = 'DENIED' AND reason_code = 'IDEMPOTENCY_KEY_REUSED'
                        """, Integer.class, accepted.command().id()));
    }

    @Test
    @DisplayName("R14 same maker with correct ACR cannot approve and denial remains audited")
    void r14SameMakerWithCorrectAcrIsRejectedWithoutChangingCase() {
        String token = "synth-r14-maker-checker-0001";
        insertSyntheticOriginal(token, OperationType.CARD_PURCHASE, "120");
        ActorContext maker = actorWithAcr("r14-maker");
        AcceptanceResult accepted = transaction(() -> store.accept(
                maker,
                acceptance(
                        token, OperationType.CARD_PURCHASE, "120", "idempotency-r14-accept-key", UUID.randomUUID())));
        UUID attemptId = jdbc.queryForObject(
                "SELECT id FROM execution_attempt WHERE reversal_command_id = ?",
                UUID.class,
                accepted.command().id());
        transaction(() -> {
            store.processOutcome(outcome(
                    accepted, attemptId, UUID.randomUUID(), "d".repeat(64), "AMBIGUOUS", "SWITCH_OUTCOME_UNKNOWN"));
            return null;
        });
        ReconciliationCaseView opened = transaction(() -> store.listReconciliationCases(TENANT)).stream()
                .filter(item ->
                        item.reversalCommandId().equals(accepted.command().id()))
                .findFirst()
                .orElseThrow();
        AuditEvidenceView evidence = transaction(
                        () -> store.evidence(TENANT, accepted.command().id()))
                .getFirst();
        ReconciliationCaseView proposed = transaction(() -> store.propose(
                maker,
                new ProposeReconciliationCommand(
                        opened.id(),
                        ReconciliationDecision.QUARANTINE,
                        "Review R14 synthetic ambiguity",
                        evidence.digest(),
                        "idempotency-r14-proposal",
                        new CorrelationId(UUID.randomUUID()))));
        ApproveReconciliationCommand approval = new ApproveReconciliationCommand(
                proposed.id(),
                "Approve R14 synthetic decision",
                "idempotency-r14-approval",
                new CorrelationId(UUID.randomUUID()));

        ApplicationException failure =
                assertThrows(ApplicationException.class, () -> transaction(() -> store.approve(maker, approval)));

        assertEquals(ApplicationErrorCode.RECONCILIATION_APPROVER_CONFLICT, failure.code());
        assertEquals(
                "DECISION_PROPOSED",
                jdbc.queryForObject("SELECT state FROM reconciliation_case WHERE id = ?", String.class, proposed.id()));
        assertNull(jdbc.queryForObject(
                "SELECT approved_by FROM reconciliation_decision WHERE reconciliation_case_id = ?",
                String.class,
                proposed.id()));
        assertEquals(1, jdbc.queryForObject("""
                        SELECT COUNT(*) FROM audit_event
                        WHERE resource_id = ?
                          AND action = 'RECONCILIATION_APPROVAL_MAKER_CHECKER_DENIED'
                          AND outcome = 'DENIED'
                          AND reason_code = 'RECONCILIATION_APPROVER_CONFLICT'
                        """, Integer.class, proposed.id()));
    }

    @Test
    void acceptedCommandRetainsImmutablePolicySnapshotAfterPolicyRowChanges() {
        String token = "synth-policy-snapshot-0001";
        insertSyntheticOriginal(token, OperationType.CARD_PURCHASE, "120");
        transactions.executeWithoutResult(status -> {
            AcceptanceResult accepted = store.accept(
                    actor("snapshot-operator"),
                    acceptance(
                            token, OperationType.CARD_PURCHASE, "120", "idempotency-snapshot-001", UUID.randomUUID()));
            Instant dispatchAt = accepted.command().createdAt().plusSeconds(1);
            Map<String, Object> snapshot =
                    jdbc.queryForMap("""
                    SELECT policy_version_snapshot, max_attempts_snapshot,
                           result_timeout_ms_snapshot, initial_retry_delay_ms_snapshot,
                           maximum_retry_delay_ms_snapshot
                    FROM reversal_command WHERE id = ?
                    """, accepted.command().id());
            long frozenTimeout = ((Number) snapshot.get("result_timeout_ms_snapshot")).longValue();
            jdbc.update("""
                    UPDATE operation_policy
                    SET max_attempts = 1, initial_retry_delay_ms = 200,
                        maximum_retry_delay_ms = 400, result_timeout_ms = 12345,
                        policy_version = policy_version + 100
                    WHERE tenant_id = ? AND id = (
                        SELECT operation_policy_id FROM reversal_command WHERE id = ?
                    )
                    """, TENANT.value(), accepted.command().id());
            assertEquals(snapshot, jdbc.queryForMap("""
                            SELECT policy_version_snapshot, max_attempts_snapshot,
                                   result_timeout_ms_snapshot, initial_retry_delay_ms_snapshot,
                                   maximum_retry_delay_ms_snapshot
                            FROM reversal_command WHERE id = ?
                            """, accepted.command().id()));
            PostgresOutboxRepository repository = new PostgresOutboxRepository(
                    jdbc,
                    Clock.fixed(dispatchAt, java.time.ZoneOffset.UTC),
                    new OutboxProperties(
                            500,
                            Duration.ofSeconds(30),
                            Duration.ofMillis(500),
                            3,
                            Duration.ofSeconds(5),
                            Duration.ofMinutes(5),
                            3),
                    (message, recoveryCount) -> {});
            OutboxMessage execution = repository.claimDue().stream()
                    .filter(message ->
                            message.aggregateId().equals(accepted.command().id()))
                    .filter(message -> "ReversalExecutionRequested.v1".equals(message.eventType()))
                    .findFirst()
                    .orElseThrow();
            repository.prepareForPublish(execution);
            assertEquals(
                    dispatchAt.plusMillis(frozenTimeout).toString(),
                    jdbc.queryForObject(
                            "SELECT payload->>'deadline' FROM outbox_event WHERE id = ?",
                            String.class,
                            execution.id()));
            status.setRollbackOnly();
        });
    }

    @Test
    void reconciliationProposalRequiresCaseOwnedEvidenceAndExposesReviewSafeFields() {
        ActorContext actor = actor("cash-maker");
        AcceptanceResult accepted = transaction(() -> store.accept(
                actor,
                acceptance(
                        "synth-txn-cash-0001",
                        OperationType.CASH_WITHDRAWAL,
                        "500",
                        "idempotency-cash-000001",
                        UUID.randomUUID())));
        UUID attemptId = jdbc.queryForObject(
                "SELECT id FROM execution_attempt WHERE reversal_command_id = ?",
                UUID.class,
                accepted.command().id());
        transaction(() -> {
            store.processOutcome(outcome(
                    accepted, attemptId, UUID.randomUUID(), "c".repeat(64), "AMBIGUOUS", "SWITCH_OUTCOME_UNKNOWN"));
            return null;
        });
        ReconciliationCaseView reconciliationCase =
                transaction(() -> store.listReconciliationCases(TENANT)).getFirst();
        AuditEvidenceView evidence = transaction(
                        () -> store.evidence(TENANT, accepted.command().id()))
                .getFirst();
        String purpose = "Review synthetic ambiguity evidence";
        ProposeReconciliationCommand unowned = new ProposeReconciliationCommand(
                reconciliationCase.id(),
                ReconciliationDecision.QUARANTINE,
                purpose,
                "f".repeat(64),
                "idempotency-proposal-bad1",
                new CorrelationId(UUID.randomUUID()));
        assertThrows(ApplicationException.class, () -> transaction(() -> store.propose(actor, unowned)));

        ProposeReconciliationCommand owned = new ProposeReconciliationCommand(
                reconciliationCase.id(),
                ReconciliationDecision.QUARANTINE,
                purpose,
                evidence.digest(),
                "idempotency-proposal-good",
                new CorrelationId(UUID.randomUUID()));
        ReconciliationCaseView proposed = transaction(() -> store.propose(actor, owned));

        assertEquals(purpose, proposed.proposalPurpose());
        assertEquals(evidence.digest(), proposed.evidenceDigest());
        assertTrue(proposed.makerPseudonym().matches("[a-f0-9]{24}"));
        assertNotEquals(actor.actorId().value(), proposed.makerPseudonym());
    }

    @Test
    @DisplayName("R07: crash after dispatch before result persistence opens reconciliation without blind retry")
    void overdueDispatchOpensExactlyOneReconciliationCase() {
        AcceptanceResult accepted = transaction(() -> store.accept(
                actor("merchant-worker"),
                acceptance(
                        "synth-txn-merchant-01",
                        OperationType.MERCHANT_PAYOUT,
                        "840",
                        "idempotency-merchant-001",
                        UUID.randomUUID())));
        jdbc.update("""
                UPDATE execution_attempt
                SET state = 'DISPATCHED',
                    started_at = CURRENT_TIMESTAMP - INTERVAL '2 seconds',
                    result_deadline = CURRENT_TIMESTAMP - INTERVAL '1 second',
                    updated_at = CURRENT_TIMESTAMP,
                    version = version + 1
                WHERE reversal_command_id = ?
                """, accepted.command().id());
        jdbc.update(
                "UPDATE reversal_command SET state = 'EXECUTING' WHERE id = ?",
                accepted.command().id());

        assertEquals(1, transaction(() -> store.reconcileOverdueAttempts(10)));
        assertEquals(0, transaction(() -> store.reconcileOverdueAttempts(10)));
        assertEquals(
                ReversalCommandState.RECONCILIATION_REQUIRED.name(),
                jdbc.queryForObject(
                        "SELECT state FROM reversal_command WHERE id = ?",
                        String.class,
                        accepted.command().id()));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM reconciliation_case WHERE reversal_command_id = ?",
                        Integer.class,
                        accepted.command().id()));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM execution_attempt WHERE reversal_command_id = ?",
                        Integer.class,
                        accepted.command().id()));
        assertEquals(
                1, jdbc.queryForObject("""
                        SELECT COUNT(*) FROM outbox_event
                        WHERE aggregate_id = ? AND event_type = 'ReversalExecutionRequested.v1'
                        """, Integer.class, accepted.command().id()));
    }

    private static boolean concurrentAccept(CountDownLatch ready, CountDownLatch start, String idempotencyKey)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            transaction(() -> store.accept(
                    actor("transfer-operator"),
                    acceptance(
                            "synth-txn-transfer-001",
                            OperationType.ACCOUNT_TRANSFER,
                            "1250",
                            idempotencyKey,
                            UUID.randomUUID())));
            return true;
        } catch (ApplicationException exception) {
            return false;
        }
    }

    private static AcceptanceResult concurrentAcceptResult(
            CountDownLatch ready, CountDownLatch start, ActorContext actor, AcceptReversalCommand request)
            throws InterruptedException {
        ready.countDown();
        start.await();
        return transaction(() -> store.accept(actor, request));
    }

    private static boolean successful(Future<Boolean> result) {
        try {
            return result.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static AcceptReversalCommand acceptance(
            String token, OperationType operation, String amount, String idempotencyKey, UUID correlationId) {
        return new AcceptReversalCommand(
                token,
                operation,
                new Money(new BigDecimal(amount), Currency.getInstance("ZAR")),
                new ReversalReason("Synthetic customer request"),
                DemonstrationFailure.NONE,
                idempotencyKey,
                new CorrelationId(correlationId));
    }

    private static OutcomeObservation outcome(
            AcceptanceResult accepted,
            UUID attemptId,
            UUID messageId,
            String payloadHash,
            String outcome,
            String reason) {
        return new OutcomeObservation(
                TENANT,
                messageId,
                accepted.command().correlationId(),
                UUID.randomUUID(),
                payloadHash,
                accepted.command().id(),
                attemptId,
                outcome,
                reason,
                Instant.now());
    }

    private static ActorContext actor(String name) {
        return new ActorContext(TENANT, new ActorId(name), Set.of("ROLE_SERVICE"));
    }

    private static ActorContext actorWithAcr(String name) {
        return new ActorContext(TENANT, new ActorId(name), Set.of("ROLE_SERVICE"), "urn:resilientpay:acr:mfa");
    }

    private static UUID insertSyntheticOriginal(String token, OperationType operation, String amount) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO original_transaction (
                    id, tenant_id, external_reference_token, public_reference_token,
                    operation_type, amount, currency, status, occurred_at,
                    created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, 'ZAR', 'POSTED', ?, ?, ?, 0)
                """,
                id,
                TENANT.value(),
                sha256(token),
                token,
                operation.name(),
                new BigDecimal(amount),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now));
        return id;
    }

    private static int logicalOutboxCount(UUID originalId, UUID commandId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id IN (?, ?)", Integer.class, originalId, commandId);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String originalState(UUID originalId) {
        return jdbc.queryForObject("SELECT status FROM original_transaction WHERE id = ?", String.class, originalId);
    }

    private static int outboxCount(UUID correlationId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE correlation_id = ?", Integer.class, correlationId);
    }

    private static <T> T transaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }
}
