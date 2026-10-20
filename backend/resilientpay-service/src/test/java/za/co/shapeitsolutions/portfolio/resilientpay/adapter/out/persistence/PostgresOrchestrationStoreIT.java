package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging.ExecutionPublicationSuppressedException;
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
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OutboxMessage;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OutcomeObservation;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ProposeReconciliationCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ReconciliationCaseView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.RetryReversalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.AuditStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.IndependentAuditPort;
import za.co.shapeitsolutions.portfolio.resilientpay.application.service.AuditApplicationService;
import za.co.shapeitsolutions.portfolio.resilientpay.application.service.OutboxApplicationService;
import za.co.shapeitsolutions.portfolio.resilientpay.application.service.ReversalApplicationService;
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
    private static DataSourceTransactionManager transactionManager;
    private static SpringTransactionBoundary boundary;
    private static PostgresAuditStore auditStore;
    private static AuditApplicationService securityAudit;
    private static WorkflowMetrics metrics;
    private static QuarantineMetrics quarantineMetrics;

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
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        boundary = new SpringTransactionBoundary(transactionManager);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        metrics = new WorkflowMetrics(meters);
        quarantineMetrics = new QuarantineMetrics(meters);
        auditStore = new PostgresAuditStore(
                jdbc,
                new ResilientPaySecurityProperties(
                        "resilientpay-api", "postgres-it-evidence-key", "postgres-it-v1", "urn:resilientpay:acr:mfa"),
                metrics);
        securityAudit = new AuditApplicationService(auditStore, boundary, Clock.systemUTC());
        store = createStore(auditStore, securityAudit);
    }

    private static PostgresOrchestrationStore createStore(AuditStore writer, IndependentAuditPort independentAudit) {
        return new PostgresOrchestrationStore(
                jdbc,
                Clock.systemUTC(),
                new ReconciliationProperties(Duration.ofSeconds(5), 25, Duration.ofHours(24)),
                writer,
                metrics,
                quarantineMetrics,
                new RabbitTraceContext(Tracer.NOOP, Propagator.NOOP),
                independentAudit,
                new RuntimeMode(true));
    }

    @Test
    void applicationEntryRollsBackEveryAcceptanceWriteWhenLateAuditFails() {
        String token = "synth-application-rollback-0001";
        UUID originalId = insertSyntheticOriginal(token, OperationType.CARD_PURCHASE, "120");
        UUID correlationId = UUID.randomUUID();
        String key = "idempotency-application-rollback-0001";
        AtomicReference<UUID> attemptedCommand = new AtomicReference<>();
        AuditStore failingLateWriter = entry -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            auditStore.append(entry);
            if ("REVERSAL_ROUTED".equals(entry.action())) {
                attemptedCommand.set(entry.resourceId());
                assertEquals(3, outboxCount(correlationId));
                assertEquals(
                        2,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE correlation_id = ?",
                                Integer.class,
                                correlationId));
                throw new IllegalStateException("synthetic failure after all acceptance writes");
            }
        };
        ReversalApplicationService application =
                application(createStore(failingLateWriter, securityAudit), securityAudit);

        assertThrows(
                IllegalStateException.class,
                () -> application.accept(
                        applicationActor(), acceptance(token, OperationType.CARD_PURCHASE, "120", key, correlationId)));

        UUID commandId = attemptedCommand.get();
        assertTrue(commandId != null, "Failure injection must happen after the real acceptance writes");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("POSTED", originalState(originalId));
        assertEquals(
                0L,
                jdbc.queryForObject("SELECT version FROM original_transaction WHERE id = ?", Long.class, originalId));
        assertEquals(
                0, jdbc.queryForObject("SELECT COUNT(*) FROM reversal_command WHERE id = ?", Integer.class, commandId));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM reversal_reservation WHERE original_transaction_id = ?",
                        Integer.class,
                        originalId));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM execution_attempt WHERE reversal_command_id = ?",
                        Integer.class,
                        commandId));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM idempotency_record WHERE key_hash = ?", Integer.class, sha256(key)));
        assertEquals(0, outboxCount(correlationId));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_event WHERE correlation_id = ?", Integer.class, correlationId));
    }

    @Test
    void applicationDenialCommitsIndependentlyAndSurvivesEnclosingRollback() {
        String token = "synth-application-denial-0001";
        UUID originalId = insertSyntheticOriginal(token, OperationType.CARD_PURCHASE, "120");
        AtomicReference<Long> denialTransaction = new AtomicReference<>();
        AuditApplicationService observedAudit = new AuditApplicationService(
                entry -> {
                    if ("IDEMPOTENCY_KEY_REUSE_DENIED".equals(entry.action())) {
                        denialTransaction.set(jdbc.queryForObject("SELECT txid_current()", Long.class));
                    }
                    auditStore.append(entry);
                },
                boundary,
                Clock.systemUTC());
        ReversalApplicationService application = application(createStore(auditStore, observedAudit), observedAudit);
        AcceptReversalCommand request = acceptance(
                token, OperationType.CARD_PURCHASE, "120", "idempotency-application-denial-0001", UUID.randomUUID());
        AcceptanceResult accepted = application.accept(applicationActor(), request);
        AcceptReversalCommand changed = new AcceptReversalCommand(
                token,
                request.operationType(),
                request.money(),
                new ReversalReason("Changed synthetic request"),
                request.demonstrationFailure(),
                request.idempotencyKey(),
                new CorrelationId(UUID.randomUUID()));
        Long originalVersion =
                jdbc.queryForObject("SELECT version FROM original_transaction WHERE id = ?", Long.class, originalId);
        AtomicReference<Long> enclosingTransaction = new AtomicReference<>();

        ApplicationException failure = assertThrows(
                ApplicationException.class,
                () -> boundary.required(() -> {
                    enclosingTransaction.set(jdbc.queryForObject("SELECT txid_current()", Long.class));
                    jdbc.update("UPDATE original_transaction SET version = version + 1 WHERE id = ?", originalId);
                    application.accept(applicationActor(), changed);
                }));

        assertEquals(ApplicationErrorCode.IDEMPOTENCY_KEY_REUSED, failure.code());
        assertTrue(denialTransaction.get() != null);
        assertNotEquals(enclosingTransaction.get(), denialTransaction.get());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(
                originalVersion,
                jdbc.queryForObject("SELECT version FROM original_transaction WHERE id = ?", Long.class, originalId));
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
    void mandatoryPersistenceGuardsRejectCallsWithoutAnApplicationTransaction() {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory storeFactory = new ProxyFactory(store);
        storeFactory.setProxyTargetClass(true);
        storeFactory.addAdvice(interceptor);
        PostgresOrchestrationStore guardedStore = (PostgresOrchestrationStore) storeFactory.getProxy();
        assertThrows(IllegalTransactionStateException.class, () -> guardedStore.listPolicies(TENANT));
        ProxyFactory auditFactory = new ProxyFactory(auditStore);
        auditFactory.setProxyTargetClass(true);
        auditFactory.addAdvice(interceptor);
        AuditStore guardedAudit = (AuditStore) auditFactory.getProxy();
        assertThrows(IllegalTransactionStateException.class, () -> guardedAudit.append(null));
        assertFalse(application(guardedStore, securityAudit)
                .listPolicies(applicationActor())
                .isEmpty());
    }

    private static ReversalApplicationService application(
            PostgresOrchestrationStore applicationStore, AuditApplicationService audit) {
        return new ReversalApplicationService(
                applicationStore,
                (tenant, action) -> {
                    // The primary entry test has no enclosing unit; the suspension test deliberately does.
                    assertEquals(TENANT, tenant);
                },
                audit,
                boundary,
                "urn:resilientpay:acr:mfa");
    }

    private static ActorContext applicationActor() {
        return new ActorContext(
                TENANT,
                new ActorId("application-boundary-operator"),
                Set.of("SCOPE_reversals.write", "SCOPE_policies.read"));
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

    @ParameterizedTest
    @EnumSource(
            value = ReconciliationDecision.class,
            names = {"CONFIRM_EXECUTED", "CONFIRM_NOT_EXECUTED", "QUARANTINE"})
    void approvedReconciliationDecisionsCommitOnceWithoutDuplicatingDispatch(ReconciliationDecision decision) {
        String suffix = decision.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        String token = "synth-approved-" + suffix;
        UUID originalId = insertSyntheticOriginal(token, OperationType.CARD_PURCHASE, "120");
        ActorContext maker = actorWithAcr("maker-" + suffix);
        ActorContext checker = actorWithAcr("checker-" + suffix);
        AcceptanceResult accepted = transaction(() -> store.accept(
                maker, acceptance(token, OperationType.CARD_PURCHASE, "120", "accept-" + suffix, UUID.randomUUID())));
        UUID commandId = accepted.command().id();
        UUID attemptId = latestAttemptId(commandId);
        process(outcome(accepted, attemptId, UUID.randomUUID(), "d".repeat(64), "AMBIGUOUS", "SWITCH_OUTCOME_UNKNOWN"));
        ReconciliationCaseView opened = transaction(() -> store.listReconciliationCases(TENANT)).stream()
                .filter(item -> item.reversalCommandId().equals(commandId))
                .findFirst()
                .orElseThrow();
        AuditEvidenceView evidence =
                transaction(() -> store.evidence(TENANT, commandId)).getFirst();
        ReconciliationCaseView proposed = transaction(() -> store.propose(
                maker,
                new ProposeReconciliationCommand(
                        opened.id(),
                        decision,
                        "Review synthetic approved decision",
                        evidence.digest(),
                        "propose-" + suffix,
                        new CorrelationId(UUID.randomUUID()))));
        Map<String, Object> ambiguousAttempt = attemptRow(attemptId);
        Map<String, Object> reservation = reservationRow(commandId);
        ApproveReconciliationCommand approval = new ApproveReconciliationCommand(
                proposed.id(),
                "Approve synthetic reviewed decision",
                "approve-" + suffix,
                new CorrelationId(UUID.randomUUID()));

        ReconciliationCaseView approved = transaction(() -> store.approve(checker, approval));

        String expectedCommandState =
                switch (decision) {
                    case CONFIRM_EXECUTED -> "COMPLETED";
                    case CONFIRM_NOT_EXECUTED -> "RETRY_SCHEDULED";
                    case QUARANTINE -> "QUARANTINED";
                    default -> throw new AssertionError("Unexpected decision");
                };
        assertEquals(expectedCommandState, commandRow(commandId).get("state"));
        assertEquals(
                decision == ReconciliationDecision.QUARANTINE ? "QUARANTINED" : "RESOLVED",
                approved.status().name());
        assertNotNull(approved.resolvedAt());
        assertEquals(decision.name(), approved.proposedDecision());
        assertEquals(
                decision == ReconciliationDecision.CONFIRM_EXECUTED ? "REVERSED" : "REVERSAL_RESERVED",
                originalState(originalId));
        assertEquals(ambiguousAttempt, attemptRow(attemptId), "Approval must preserve the original ambiguous evidence");
        assertEquals("OUTCOME_UNKNOWN", ambiguousAttempt.get("state"));
        assertEquals("AMBIGUOUS", ambiguousAttempt.get("result_code"));
        assertEquals(reservation, reservationRow(commandId));
        assertNull(reservation.get("released_at"));
        assertEquals(1, attemptCount(commandId));
        assertEquals(1, executionRequestCount(commandId));
        assertEquals(
                decision == ReconciliationDecision.CONFIRM_EXECUTED ? 4 : 3, logicalOutboxCount(originalId, commandId));
        Map<String, Object> durableDecision = jdbc.queryForMap(
                "SELECT * FROM reconciliation_decision WHERE reconciliation_case_id = ?", proposed.id());
        assertEquals(maker.actorId().value(), durableDecision.get("proposed_by"));
        assertEquals(checker.actorId().value(), durableDecision.get("approved_by"));
        assertEquals(decision.name(), durableDecision.get("proposed_outcome"));
        assertEquals(evidence.digest(), durableDecision.get("evidence_digest"));
        assertNotNull(durableDecision.get("approved_at"));
        assertEquals(1, auditActionCount(commandId, "SWITCH_OUTCOME_AMBIGUOUS"));
        assertEquals(1, auditActionCount(commandId, "RECONCILIATION_PROPOSED"));
        assertEquals(
                1,
                auditActionCount(
                        commandId,
                        decision == ReconciliationDecision.QUARANTINE
                                ? "RECONCILIATION_QUARANTINED"
                                : "RECONCILIATION_RESOLVED"));
        if (decision == ReconciliationDecision.CONFIRM_NOT_EXECUTED) {
            assertEquals(
                    "RECONCILIATION_CONFIRMED_NOT_EXECUTED",
                    commandRow(commandId).get("failure_code"));
            assertNotNull(commandRow(commandId).get("next_attempt_at"));
        } else {
            assertNull(commandRow(commandId).get("next_attempt_at"));
        }
        if (decision == ReconciliationDecision.CONFIRM_EXECUTED) {
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM outbox_event
                    WHERE aggregate_id = ? AND event_type = 'OriginalTransactionStatusChanged.v1'
                      AND payload->>'status' = 'REVERSED'
                    """, Integer.class, originalId));
        }
        Map<String, Object> commandAfterApproval = commandRow(commandId);
        Map<String, Object> caseAfterApproval =
                jdbc.queryForMap("SELECT * FROM reconciliation_case WHERE id = ?", proposed.id());
        List<Map<String, Object>> outboxAfterApproval = logicalOutboxRows(originalId, commandId);

        assertEquals(approved, transaction(() -> store.approve(checker, approval)));

        assertEquals(commandAfterApproval, commandRow(commandId));
        assertEquals(
                caseAfterApproval, jdbc.queryForMap("SELECT * FROM reconciliation_case WHERE id = ?", proposed.id()));
        assertEquals(
                durableDecision,
                jdbc.queryForMap(
                        "SELECT * FROM reconciliation_decision WHERE reconciliation_case_id = ?", proposed.id()));
        assertEquals(outboxAfterApproval, logicalOutboxRows(originalId, commandId));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM idempotency_record WHERE key_hash = ?",
                        Integer.class,
                        sha256(approval.idempotencyKey())));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_event
                WHERE resource_id = ? AND action = 'RECONCILIATION_APPROVAL_REPLAYED'
                """, Integer.class, proposed.id()));
        if (decision == ReconciliationDecision.CONFIRM_NOT_EXECUTED) {
            makeRetryDue(commandId);
            RetryReversalCommand retry = retryRequest(commandId, "retry-" + suffix);
            assertEquals(2, transaction(() -> store.retry(maker, retry)).attemptCount());
            assertEquals(2, transaction(() -> store.retry(maker, retry)).attemptCount());
            assertEquals(2, attemptCount(commandId));
            assertEquals(2, executionRequestCount(commandId));
            OutcomeObservation executed = outcome(
                    accepted,
                    latestAttemptId(commandId),
                    UUID.randomUUID(),
                    "e".repeat(64),
                    "EXECUTED",
                    "SWITCH_EXECUTED");
            process(executed);
            process(executed);
            assertEquals("COMPLETED", commandRow(commandId).get("state"));
            assertEquals("REVERSED", originalState(originalId));
            assertEquals(2, attemptCount(commandId));
            assertEquals(2, executionRequestCount(commandId));
            assertEquals(1, auditActionCount(commandId, "SWITCH_EXECUTED"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void distinctMessageContradictionsPreserveEvidenceAndPreventUnsafeRetry(boolean executedFirst) {
        String suffix = executedFirst ? "executed-first" : "not-executed-first";
        String token = "synth-contradiction-" + suffix;
        UUID originalId = insertSyntheticOriginal(token, OperationType.CARD_PURCHASE, "120");
        AcceptanceResult accepted = transaction(() -> store.accept(
                actor("contradiction-operator"),
                acceptance(token, OperationType.CARD_PURCHASE, "120", "accept-" + suffix, UUID.randomUUID())));
        UUID commandId = accepted.command().id();
        UUID attemptId = latestAttemptId(commandId);
        OutcomeObservation first = outcome(
                accepted,
                attemptId,
                UUID.randomUUID(),
                "a".repeat(64),
                executedFirst ? "EXECUTED" : "CONFIRMED_NOT_EXECUTED",
                "SWITCH_FIRST_RESULT");
        OutcomeObservation conflicting = outcome(
                accepted,
                attemptId,
                UUID.randomUUID(),
                "b".repeat(64),
                executedFirst ? "CONFIRMED_NOT_EXECUTED" : "EXECUTED",
                "SWITCH_CONFLICTING_RESULT");
        markLatestExecutionPublished(commandId, attemptId);
        process(first);
        Map<String, Object> firstCommand = commandRow(commandId);
        Map<String, Object> firstAttempt = attemptRow(attemptId);
        Map<String, Object> firstOriginal =
                jdbc.queryForMap("SELECT * FROM original_transaction WHERE id = ?", originalId);
        Map<String, Object> firstReservation = reservationRow(commandId);
        List<Map<String, Object>> firstOutbox = logicalOutboxRows(originalId, commandId);

        process(conflicting);
        process(conflicting);
        process(first);

        assertNotEquals(first.messageId(), conflicting.messageId());
        assertEquals(
                executedFirst ? "COMPLETED" : "RECONCILIATION_REQUIRED",
                commandRow(commandId).get("state"));
        assertEquals(
                executedFirst ? "SUCCEEDED" : "KNOWN_NOT_SENT",
                attemptRow(attemptId).get("state"));
        assertEquals(first.outcome(), attemptRow(attemptId).get("result_code"));
        if (executedFirst) {
            assertEquals(firstCommand, commandRow(commandId));
        } else {
            assertEquals("CONTRADICTORY_OUTCOME", commandRow(commandId).get("failure_code"));
            assertNull(commandRow(commandId).get("next_attempt_at"));
            assertEquals(
                    "OPEN",
                    jdbc.queryForObject(
                            "SELECT state FROM reconciliation_case WHERE reversal_command_id = ?",
                            String.class,
                            commandId));
        }
        assertEquals(firstAttempt, attemptRow(attemptId));
        assertEquals(firstOriginal, jdbc.queryForMap("SELECT * FROM original_transaction WHERE id = ?", originalId));
        assertEquals(firstReservation, reservationRow(commandId));
        assertEquals(firstOutbox, logicalOutboxRows(originalId, commandId));
        assertEquals(1, attemptCount(commandId));
        assertEquals(1, executionRequestCount(commandId));
        assertEquals(
                executedFirst ? 0 : 1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM reconciliation_case WHERE reversal_command_id = ?",
                        Integer.class,
                        commandId));
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM inbox_message
                WHERE message_id IN (?, ?) AND processing_outcome = 'PROCESSED'
                """, Integer.class, first.messageId(), conflicting.messageId()));
        Map<String, Object> quarantine = jdbc.queryForMap(
                "SELECT * FROM message_quarantine WHERE message_id = ?",
                conflicting.messageId().toString());
        assertEquals("CONTRADICTORY_OUTCOME", quarantine.get("error_code"));
        assertEquals("OPEN", quarantine.get("state"));
        assertEquals(conflicting.payloadHash(), quarantine.get("payload_digest"));
        assertEquals(1, ((Number) quarantine.get("occurrence_count")).intValue());
        assertEquals(1, auditActionCount(commandId, "CONTRADICTORY_SWITCH_OUTCOME"));
        assertEquals(
                1, auditActionCount(commandId, executedFirst ? "SWITCH_EXECUTED" : "SWITCH_CONFIRMED_NOT_EXECUTED"));
        if (!executedFirst) {
            assertFalse(transaction(() -> store.getReversal(TENANT, commandId))
                    .allowedActions()
                    .contains("REQUEST_SAFE_RETRY"));
            assertTrue(transaction(() -> store.getReversal(TENANT, commandId))
                    .allowedActions()
                    .contains("CHECK_OUTCOME"));
            makeRetryDue(commandId);
            ApplicationException retryFailure = assertThrows(
                    ApplicationException.class,
                    () -> transaction(() -> store.retry(
                            actor("contradiction-operator"), retryRequest(commandId, "retry-after-contradiction"))));
            assertEquals(ApplicationErrorCode.STATE_TRANSITION_NOT_ALLOWED, retryFailure.code());
            assertEquals(1, executionRequestCount(commandId));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exhaustedNonExecutionCommitsTerminalRejectionWithoutAnotherDispatch(boolean confirmedByReconciliation) {
        String suffix = confirmedByReconciliation ? "reconciled" : "direct";
        String token = "synth-exhausted-retry-" + suffix;
        UUID originalId = insertSyntheticOriginal(token, OperationType.CARD_PURCHASE, "120");
        ActorContext actor = actor("exhausted-retry-operator");
        AcceptanceResult accepted = transaction(() -> store.accept(
                actor,
                acceptance(
                        token, OperationType.CARD_PURCHASE, "120", "accept-exhausted-" + suffix, UUID.randomUUID())));
        UUID commandId = accepted.command().id();
        assertEquals(3, ((Number) commandRow(commandId).get("max_attempts_snapshot")).intValue());
        for (int completedAttempt = 1; completedAttempt < 3; completedAttempt++) {
            process(outcome(
                    accepted,
                    latestAttemptId(commandId),
                    UUID.randomUUID(),
                    "c".repeat(64),
                    "CONFIRMED_NOT_EXECUTED",
                    "SWITCH_NOT_RECEIVED"));
            makeRetryDue(commandId);
            RetryReversalCommand retry = retryRequest(commandId, "exhausted-" + suffix + "-" + completedAttempt);
            assertEquals(
                    completedAttempt + 1,
                    transaction(() -> store.retry(actor, retry)).attemptCount());
        }
        UUID finalAttemptId = latestAttemptId(commandId);
        markLatestExecutionPublished(commandId, finalAttemptId);
        assertEquals("EXECUTING", commandRow(commandId).get("state"));
        assertEquals("DISPATCHED", attemptRow(finalAttemptId).get("state"));
        int outboxBefore = logicalOutboxCount(originalId, commandId);
        OutcomeObservation exhausted = outcome(
                accepted,
                finalAttemptId,
                UUID.randomUUID(),
                "f".repeat(64),
                confirmedByReconciliation ? "AMBIGUOUS" : "CONFIRMED_NOT_EXECUTED",
                confirmedByReconciliation ? "SWITCH_OUTCOME_UNKNOWN" : "SWITCH_NOT_RECEIVED");

        process(exhausted);
        process(exhausted);
        if (confirmedByReconciliation) {
            ReconciliationCaseView opened = transaction(() -> store.listReconciliationCases(TENANT)).stream()
                    .filter(item -> item.reversalCommandId().equals(commandId))
                    .findFirst()
                    .orElseThrow();
            AuditEvidenceView evidence =
                    transaction(() -> store.evidence(TENANT, commandId)).getFirst();
            transaction(() -> store.propose(
                    actor,
                    new ProposeReconciliationCommand(
                            opened.id(),
                            ReconciliationDecision.CONFIRM_NOT_EXECUTED,
                            "Review exhausted attempt non-execution",
                            evidence.digest(),
                            "propose-exhausted-reconciled",
                            new CorrelationId(UUID.randomUUID()))));
            ApproveReconciliationCommand approval = new ApproveReconciliationCommand(
                    opened.id(),
                    "Approve exhausted attempt non-execution",
                    "approve-exhausted-reconciled",
                    new CorrelationId(UUID.randomUUID()));
            ReconciliationCaseView approved =
                    transaction(() -> store.approve(actorWithAcr("exhaustion-checker"), approval));
            assertEquals("RESOLVED", approved.status().name());
            assertEquals(approved, transaction(() -> store.approve(actorWithAcr("exhaustion-checker"), approval)));
        }

        assertEquals("REJECTED", commandRow(commandId).get("state"));
        assertEquals("ATTEMPT_LIMIT_EXCEEDED", commandRow(commandId).get("failure_code"));
        assertEquals(
                confirmedByReconciliation ? "OUTCOME_UNKNOWN" : "KNOWN_NOT_SENT",
                attemptRow(finalAttemptId).get("state"));
        assertEquals(
                confirmedByReconciliation ? "AMBIGUOUS" : "CONFIRMED_NOT_EXECUTED",
                attemptRow(finalAttemptId).get("result_code"));
        assertEquals(outboxBefore + 1, logicalOutboxCount(originalId, commandId));
        assertEquals("POSTED", originalState(originalId));
        assertNotNull(reservationRow(commandId).get("released_at"));
        assertEquals(3, attemptCount(commandId));
        assertEquals(3, executionRequestCount(commandId));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inbox_message WHERE message_id = ?",
                        Integer.class,
                        exhausted.messageId()));
        assertEquals(2, auditActionCount(commandId, "SWITCH_CONFIRMED_NOT_EXECUTED"));
        assertEquals(
                1,
                auditActionCount(
                        commandId, confirmedByReconciliation ? "RECONCILIATION_RESOLVED" : "SWITCH_RETRY_EXHAUSTED"));
        ApplicationException retryFailure = assertThrows(
                ApplicationException.class,
                () -> transaction(() -> store.retry(actor, retryRequest(commandId, "retry-beyond-ceiling"))));
        assertEquals(ApplicationErrorCode.STATE_TRANSITION_NOT_ALLOWED, retryFailure.code());
        assertEquals(3, executionRequestCount(commandId));
    }

    @Test
    void contradictionAfterApprovedNonExecutionQuarantinesWithoutRewritingTheResolvedDecision() {
        String token = "synth-approved-contradiction";
        UUID originalId = insertSyntheticOriginal(token, OperationType.CARD_PURCHASE, "120");
        ActorContext maker = actorWithAcr("approved-conflict-maker");
        AcceptanceResult accepted = transaction(() -> store.accept(
                maker,
                acceptance(token, OperationType.CARD_PURCHASE, "120", "accept-approved-conflict", UUID.randomUUID())));
        UUID commandId = accepted.command().id();
        UUID attemptId = latestAttemptId(commandId);
        markLatestExecutionPublished(commandId, attemptId);
        process(outcome(accepted, attemptId, UUID.randomUUID(), "a".repeat(64), "AMBIGUOUS", "SWITCH_OUTCOME_UNKNOWN"));
        ReconciliationCaseView opened = transaction(() -> store.listReconciliationCases(TENANT)).stream()
                .filter(item -> item.reversalCommandId().equals(commandId))
                .findFirst()
                .orElseThrow();
        AuditEvidenceView evidence =
                transaction(() -> store.evidence(TENANT, commandId)).getFirst();
        transaction(() -> store.propose(
                maker,
                new ProposeReconciliationCommand(
                        opened.id(),
                        ReconciliationDecision.CONFIRM_NOT_EXECUTED,
                        "Review synthetic non-execution",
                        evidence.digest(),
                        "propose-approved-conflict",
                        new CorrelationId(UUID.randomUUID()))));
        transaction(() -> store.approve(
                actorWithAcr("approved-conflict-checker"),
                new ApproveReconciliationCommand(
                        opened.id(),
                        "Approve synthetic non-execution",
                        "approve-approved-conflict",
                        new CorrelationId(UUID.randomUUID()))));
        assertEquals("RETRY_SCHEDULED", commandRow(commandId).get("state"));
        Map<String, Object> priorCase = jdbc.queryForMap("SELECT * FROM reconciliation_case WHERE id = ?", opened.id());
        Map<String, Object> priorDecision =
                jdbc.queryForMap("SELECT * FROM reconciliation_decision WHERE reconciliation_case_id = ?", opened.id());
        Map<String, Object> priorAttempt = attemptRow(attemptId);
        Map<String, Object> priorReservation = reservationRow(commandId);
        List<Map<String, Object>> priorOutbox = logicalOutboxRows(originalId, commandId);
        OutcomeObservation conflicting = outcome(
                accepted, attemptId, UUID.randomUUID(), "b".repeat(64), "EXECUTED", "SWITCH_CONFLICTING_RESULT");

        process(conflicting);
        process(conflicting);

        assertEquals("QUARANTINED", commandRow(commandId).get("state"));
        assertEquals("CONTRADICTORY_OUTCOME", commandRow(commandId).get("failure_code"));
        assertNull(commandRow(commandId).get("next_attempt_at"));
        assertEquals(priorCase, jdbc.queryForMap("SELECT * FROM reconciliation_case WHERE id = ?", opened.id()));
        assertEquals("RESOLVED", priorCase.get("state"));
        assertEquals(
                priorDecision,
                jdbc.queryForMap(
                        "SELECT * FROM reconciliation_decision WHERE reconciliation_case_id = ?", opened.id()));
        assertEquals(priorAttempt, attemptRow(attemptId));
        assertEquals(priorReservation, reservationRow(commandId));
        assertEquals("REVERSAL_RESERVED", originalState(originalId));
        assertEquals(priorOutbox, logicalOutboxRows(originalId, commandId));
        assertEquals(1, executionRequestCount(commandId));
        assertEquals(1, auditActionCount(commandId, "CONTRADICTORY_SWITCH_OUTCOME"));
        assertEquals(
                1,
                jdbc.queryForObject("""
                SELECT COUNT(*) FROM message_quarantine
                WHERE message_id = ? AND error_code = 'CONTRADICTORY_OUTCOME' AND state = 'OPEN'
                """, Integer.class, conflicting.messageId().toString()));
        assertThrows(
                ApplicationException.class,
                () -> transaction(() -> store.retry(maker, retryRequest(commandId, "retry-approved-conflict"))));
        assertEquals(1, executionRequestCount(commandId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "CLAIMED", "PREPARED", "DISPATCHED"})
    void contradictionCancelsQueuedRetryButPreservesAlreadyInFlightDispatchEvidence(String publicationStage) {
        String suffix = publicationStage.toLowerCase(java.util.Locale.ROOT);
        String token = "synth-queued-conflict-" + suffix;
        UUID originalId = insertSyntheticOriginal(token, OperationType.CARD_PURCHASE, "120");
        ActorContext actor = actor("queued-conflict-operator");
        AcceptanceResult accepted = transaction(() -> store.accept(
                actor,
                acceptance(token, OperationType.CARD_PURCHASE, "120", "accept-queued-" + suffix, UUID.randomUUID())));
        UUID commandId = accepted.command().id();
        UUID firstAttemptId = latestAttemptId(commandId);
        markLatestExecutionPublished(commandId, firstAttemptId);
        process(outcome(
                accepted,
                firstAttemptId,
                UUID.randomUUID(),
                "a".repeat(64),
                "CONFIRMED_NOT_EXECUTED",
                "SWITCH_NOT_RECEIVED"));
        Map<String, Object> firstAttempt = attemptRow(firstAttemptId);
        Map<String, Object> reservation = reservationRow(commandId);
        makeRetryDue(commandId);
        transaction(() -> store.retry(actor, retryRequest(commandId, "retry-queued-" + suffix)));
        UUID secondAttemptId = latestAttemptId(commandId);
        UUID secondEventId = executionEventId(commandId, secondAttemptId);
        PostgresOutboxRepository repository = outboxRepository();
        if ("PREPARED".equals(publicationStage)) {
            jdbc.update("UPDATE outbox_event SET attempt_count = 2 WHERE id = ?", secondEventId);
        }
        OutboxMessage leased =
                "PENDING".equals(publicationStage) ? null : claimExecution(repository, commandId, secondAttemptId);
        OutboxMessage prepared = ("PREPARED".equals(publicationStage) || "DISPATCHED".equals(publicationStage))
                ? transaction(() -> repository.prepareForPublish(leased))
                : null;
        if ("DISPATCHED".equals(publicationStage)) {
            transaction(() -> {
                repository.markPublished(prepared);
                return null;
            });
        }
        OutcomeObservation conflict = outcome(
                accepted, firstAttemptId, UUID.randomUUID(), "b".repeat(64), "EXECUTED", "SWITCH_CONFLICTING_RESULT");

        process(conflict);
        process(conflict);

        assertEquals("RECONCILIATION_REQUIRED", commandRow(commandId).get("state"));
        assertEquals("CONTRADICTORY_OUTCOME", commandRow(commandId).get("failure_code"));
        assertNull(commandRow(commandId).get("next_attempt_at"));
        assertEquals(firstAttempt, attemptRow(firstAttemptId));
        assertEquals(reservation, reservationRow(commandId));
        assertEquals("REVERSAL_RESERVED", originalState(originalId));
        assertEquals(2, attemptCount(commandId));
        assertEquals(2, executionRequestCount(commandId));
        Map<String, Object> cancelled = jdbc.queryForMap("SELECT * FROM outbox_event WHERE id = ?", secondEventId);
        assertEquals("DISPATCHED".equals(publicationStage) ? "PUBLISHED" : "DEAD", cancelled.get("status"));
        if (!"DISPATCHED".equals(publicationStage)) {
            assertEquals(false, cancelled.get("retryable"));
            assertEquals("CONTRADICTORY_OUTCOME", cancelled.get("last_error_code"));
            assertNull(cancelled.get("lease_owner"));
            assertNull(cancelled.get("lease_until"));
            assertEquals(0, transaction(repository::recoverDueDead));
            List<OutboxMessage> laterClaims = transactions.execute(status -> {
                List<OutboxMessage> claims = repository.claimDue();
                status.setRollbackOnly();
                return claims;
            });
            assertFalse(laterClaims.stream().anyMatch(message -> message.id().equals(secondEventId)));
        }
        if (leased != null) {
            assertThrows(
                    ExecutionPublicationSuppressedException.class,
                    () -> transaction(() -> repository.prepareForPublish(leased)));
        }
        assertThrows(
                ApplicationException.class,
                () -> transaction(() -> store.retry(actor, retryRequest(commandId, "retry-after-queued-" + suffix))));
        assertEquals(1, auditActionCount(commandId, "CONTRADICTORY_SWITCH_OUTCOME"));
        if ("PREPARED".equals(publicationStage)) {
            assertEquals(3, prepared.attemptCount());
            transaction(() -> {
                repository.markFailed(prepared, "BROKER_UNAVAILABLE", true);
                return null;
            });
            assertEquals(cancelled, jdbc.queryForMap("SELECT * FROM outbox_event WHERE id = ?", secondEventId));
            // Preparation committed before conflict; a later broker confirmation is send
            // evidence, not proof that cancellation recalled the already prepared message.
            OutboxApplicationService operations = new OutboxApplicationService(boundary, repository, store);
            operations.confirmPublished(prepared);
            assertEquals(
                    "PUBLISHED",
                    jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, secondEventId));
            assertEquals("DISPATCHED", attemptRow(secondAttemptId).get("state"));
            assertEquals("RECONCILIATION_REQUIRED", commandRow(commandId).get("state"));
            assertEquals(reservation, reservationRow(commandId));
            assertEquals(1, auditActionCount(commandId, "OUTBOX_PUBLISH_CONFIRMED"));
            Map<String, Object> confirmed = jdbc.queryForMap("SELECT * FROM outbox_event WHERE id = ?", secondEventId);
            transaction(() -> {
                repository.markFailed(prepared, "BROKER_UNAVAILABLE", true);
                return null;
            });
            assertEquals(confirmed, jdbc.queryForMap("SELECT * FROM outbox_event WHERE id = ?", secondEventId));
        }
        assertEquals(2, executionRequestCount(commandId));
    }

    @Test
    void finalHotAttemptFailureStillRejectsALeaseOwnedByAnotherPublisher() {
        String token = "synth-foreign-publication-lease";
        insertSyntheticOriginal(token, OperationType.CARD_PURCHASE, "120");
        AcceptanceResult accepted = transaction(() -> store.accept(
                actor("foreign-lease-operator"),
                acceptance(token, OperationType.CARD_PURCHASE, "120", "accept-foreign-lease", UUID.randomUUID())));
        UUID commandId = accepted.command().id();
        UUID attemptId = latestAttemptId(commandId);
        UUID eventId = executionEventId(commandId, attemptId);
        jdbc.update("UPDATE outbox_event SET attempt_count = 2 WHERE id = ?", eventId);
        PostgresOutboxRepository repository = outboxRepository();
        OutboxMessage leased = claimExecution(repository, commandId, attemptId);
        OutboxMessage prepared = transaction(() -> repository.prepareForPublish(leased));
        assertEquals(3, prepared.attemptCount());
        jdbc.update("UPDATE outbox_event SET lease_owner = 'different-publisher' WHERE id = ?", eventId);
        Map<String, Object> foreignLease = jdbc.queryForMap("SELECT * FROM outbox_event WHERE id = ?", eventId);

        assertThrows(
                EmptyResultDataAccessException.class,
                () -> transaction(() -> {
                    repository.markFailed(prepared, "BROKER_UNAVAILABLE", true);
                    return null;
                }));

        assertEquals(foreignLease, jdbc.queryForMap("SELECT * FROM outbox_event WHERE id = ?", eventId));
        assertEquals("CLAIMED", foreignLease.get("status"));
        assertEquals("different-publisher", foreignLease.get("lease_owner"));
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

    private static List<Map<String, Object>> logicalOutboxRows(UUID originalId, UUID commandId) {
        return jdbc.queryForList(
                "SELECT * FROM outbox_event WHERE aggregate_id IN (?, ?) ORDER BY id", originalId, commandId);
    }

    private static Map<String, Object> commandRow(UUID commandId) {
        return jdbc.queryForMap("SELECT * FROM reversal_command WHERE id = ?", commandId);
    }

    private static Map<String, Object> attemptRow(UUID attemptId) {
        return jdbc.queryForMap("SELECT * FROM execution_attempt WHERE id = ?", attemptId);
    }

    private static Map<String, Object> reservationRow(UUID commandId) {
        return jdbc.queryForMap("SELECT * FROM reversal_reservation WHERE reversal_command_id = ?", commandId);
    }

    private static UUID latestAttemptId(UUID commandId) {
        return jdbc.queryForObject("""
                SELECT id FROM execution_attempt
                WHERE reversal_command_id = ? ORDER BY attempt_number DESC LIMIT 1
                """, UUID.class, commandId);
    }

    private static int attemptCount(UUID commandId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_attempt WHERE reversal_command_id = ?", Integer.class, commandId);
    }

    private static int executionRequestCount(UUID commandId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_event
                WHERE aggregate_id = ? AND event_type = 'ReversalExecutionRequested.v1'
                """, Integer.class, commandId);
    }

    private static int auditActionCount(UUID commandId, String action) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id = ? AND action = ?",
                Integer.class,
                commandId,
                action);
    }

    private static void makeRetryDue(UUID commandId) {
        jdbc.update(
                "UPDATE reversal_command SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?",
                commandId);
    }

    private static void markLatestExecutionPublished(UUID commandId, UUID attemptId) {
        PostgresOutboxRepository repository = outboxRepository();
        OutboxMessage leased = claimExecution(repository, commandId, attemptId);
        transaction(() -> {
            repository.markPublished(repository.prepareForPublish(leased));
            return null;
        });
    }

    private static UUID executionEventId(UUID commandId, UUID attemptId) {
        return jdbc.queryForObject("""
                SELECT id FROM outbox_event
                WHERE aggregate_id = ? AND event_type = 'ReversalExecutionRequested.v1'
                  AND payload->>'executionAttemptId' = ?
                """, UUID.class, commandId, attemptId.toString());
    }

    private static OutboxMessage claimExecution(PostgresOutboxRepository repository, UUID commandId, UUID attemptId) {
        UUID eventId = executionEventId(commandId, attemptId);
        jdbc.update("UPDATE outbox_event SET available_at = TIMESTAMPTZ '2000-01-01 00:00:00Z' WHERE id = ?", eventId);
        return transaction(() -> {
            OutboxMessage leased = repository.claimDue().getFirst();
            assertEquals(eventId, leased.id());
            return leased;
        });
    }

    private static PostgresOutboxRepository outboxRepository() {
        return new PostgresOutboxRepository(
                jdbc,
                Clock.systemUTC(),
                new OutboxProperties(
                        1,
                        Duration.ofSeconds(30),
                        Duration.ofMillis(500),
                        3,
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(5),
                        3),
                (message, recoveryCount) -> {});
    }

    private static RetryReversalCommand retryRequest(UUID commandId, String key) {
        return new RetryReversalCommand(
                commandId,
                "CONFIRMED_NOT_EXECUTED",
                "Retry synthetic proven non-execution",
                key,
                new CorrelationId(UUID.randomUUID()));
    }

    private static void process(OutcomeObservation observation) {
        transaction(() -> {
            store.processOutcome(observation);
            return null;
        });
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
