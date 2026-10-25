package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security.PortfolioRequestSecurity;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging.QuarantineMetrics;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging.RabbitTraceContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AcceptReversalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AcceptanceResult;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ActorContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.DemonstrationFailure;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.PortfolioSession;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.PortfolioSessions;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.TransactionBoundary;
import za.co.shapeitsolutions.portfolio.resilientpay.application.service.AuditApplicationService;
import za.co.shapeitsolutions.portfolio.resilientpay.application.service.PortfolioSessionApplicationService;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ReconciliationProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ResilientPaySecurityProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.RuntimeMode;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalReason;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ActorId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.CorrelationId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.Money;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Proves real PostgreSQL seed atomicity, tenant isolation, revocation, concurrent quotas and grants.
 * All data belongs to this disposable Testcontainers database, never the deployed application.
 *
 * @author Daniel Jr Nkulu
 */
@Testcontainers(disabledWithoutDocker = true)
class PortfolioSessionStoreIT {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionBoundary boundary;
    private static PortfolioSessions sessions;
    private static PostgresOrchestrationStore reversals;
    private static final Clock CLOCK = Clock.systemUTC();

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource source =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(source);
        boundary = new SpringTransactionBoundary(new DataSourceTransactionManager(source));
        sessions = new PortfolioSessionApplicationService(new PostgresPortfolioSessionStore(jdbc), boundary, CLOCK);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        WorkflowMetrics metrics = new WorkflowMetrics(meters);
        PostgresAuditStore audit = new PostgresAuditStore(
                jdbc,
                new ResilientPaySecurityProperties(
                        "resilientpay-api", "synthetic-portfolio-it-evidence", "test", "urn:resilientpay:aal2"),
                metrics);
        reversals = new PostgresOrchestrationStore(
                jdbc,
                CLOCK,
                new ReconciliationProperties(Duration.ofSeconds(5), 25, Duration.ofHours(24)),
                audit,
                metrics,
                new QuarantineMetrics(meters),
                new RabbitTraceContext(Tracer.NOOP, Propagator.NOOP),
                new AuditApplicationService(audit, boundary, CLOCK),
                new RuntimeMode(true));
    }

    @BeforeEach
    void emptyOnlyDisposableTestDatabase() {
        jdbc.execute(
                "TRUNCATE portfolio_session, original_transaction, operation_policy, audit_event, outbox_event, inbox_message, idempotency_record, message_quarantine CASCADE");
    }

    @Test
    void separateVisitorsHaveFreshEntityIdsAndTenantScopedQueriesAndRevocation() {
        PortfolioSession first = create(hash(), hash());
        PortfolioSession second = create(hash(), hash());
        assertNotEquals(first.id(), second.id());
        assertNotEquals(first.tenantId(), second.tenantId());
        assertEquals(8, count("original_transaction"));
        assertEquals(8, count("operation_policy"));
        assertEquals(
                4,
                boundary.readOnly(() -> reversals.listOriginals(new TenantId(first.tenantId())))
                        .size());
        Set<UUID> firstIds = new java.util.HashSet<>(jdbc.queryForList(
                "SELECT id FROM original_transaction WHERE tenant_id = ?", UUID.class, first.tenantId()));
        assertTrue(
                jdbc
                        .queryForList(
                                "SELECT id FROM original_transaction WHERE tenant_id = ?",
                                UUID.class,
                                second.tenantId())
                        .stream()
                        .noneMatch(firstIds::contains));
        AcceptanceResult accepted = boundary.required(
                () -> reversals.accept(actor(first), request("synth-txn-card-0001", 120, "first-acceptance-key")));
        assertEquals(
                ApplicationErrorCode.RESOURCE_NOT_FOUND,
                assertThrows(
                                ApplicationException.class,
                                () -> boundary.readOnly(() -> reversals.getReversal(
                                        new TenantId(second.tenantId()),
                                        accepted.command().id())))
                        .code());
        PortfolioSession replacement = sessions.reset(first.cookieHash(), hash(), first.gateHash());
        assertNotEquals(first.tenantId(), replacement.tenantId());
        assertEquals(3, count("portfolio_session"));
        assertEquals(12, count("original_transaction"));
        assertEquals(1, count("reversal_command"));
        assertTrue(count("audit_event") > 0);
        assertTrue(count("outbox_event") > 0);
        assertEquals(
                ApplicationErrorCode.AUTHENTICATION_REQUIRED,
                assertThrows(ApplicationException.class, () -> sessions.active(first.cookieHash(), first.gateHash()))
                        .code());
        assertEquals(second, sessions.active(second.cookieHash(), second.gateHash()));
        sessions.logout(replacement.cookieHash(), replacement.gateHash());
        assertThrows(
                ApplicationException.class, () -> sessions.active(replacement.cookieHash(), replacement.gateHash()));
        assertEquals(1, count("reversal_command"));
    }

    @Test
    void wrongGateAndExpiredWorkspaceFailClosedWithoutDeletingHistoricalData() {
        PortfolioSession session = create(hash(), hash());
        assertThrows(ApplicationException.class, () -> sessions.active(session.cookieHash(), hash()));
        jdbc.update(
                "UPDATE portfolio_session SET created_at = ?, expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(7201)),
                Timestamp.from(Instant.now().minusSeconds(1)),
                session.id());
        assertThrows(ApplicationException.class, () -> sessions.active(session.cookieHash(), session.gateHash()));
        assertEquals(4, count("original_transaction"));
        assertEquals(4, count("operation_policy"));
    }

    @Test
    void nanosecondClockRoundTripsExactlyAndNeverExtendsTheGateDeadline() {
        Instant nanoseconds = Instant.now()
                .minusSeconds(1)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .plusNanos(123456789);
        Clock fixed = Clock.fixed(nanoseconds, java.time.ZoneOffset.UTC);
        PortfolioSessions precise =
                new PortfolioSessionApplicationService(new PostgresPortfolioSessionStore(jdbc), boundary, fixed);
        PortfolioSession created = precise.create(hash(), hash(), nanoseconds.plusSeconds(28800));
        assertEquals(created, precise.active(created.cookieHash(), created.gateHash()));
        assertEquals(
                nanoseconds.truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusSeconds(7200), created.expiresAt());
        assertEquals(0, created.expiresAt().getNano() % 1000);
        PortfolioSession reset = precise.reset(created.cookieHash(), hash(), created.gateHash());
        assertEquals(reset, precise.active(reset.cookieHash(), reset.gateHash()));
        assertEquals(created.expiresAt(), reset.expiresAt());
        Instant gateDeadline = nanoseconds.plusSeconds(60).plusNanos(100);
        PortfolioSession capped = precise.create(hash(), hash(), gateDeadline);
        assertEquals(capped, precise.active(capped.cookieHash(), capped.gateHash()));
        assertEquals(gateDeadline.truncatedTo(java.time.temporal.ChronoUnit.MICROS), capped.expiresAt());
        assertFalse(capped.expiresAt().isAfter(gateDeadline));
        PortfolioSessions expired = new PortfolioSessionApplicationService(
                new PostgresPortfolioSessionStore(jdbc),
                boundary,
                Clock.fixed(capped.expiresAt(), java.time.ZoneOffset.UTC));
        assertEquals(
                ApplicationErrorCode.AUTHENTICATION_REQUIRED,
                assertThrows(ApplicationException.class, () -> expired.active(capped.cookieHash(), capped.gateHash()))
                        .code());
    }

    @Test
    void concurrentSameVisitorCreationCannotExceedFiveWorkspacesOrPartiallySeed() throws Exception {
        String gate = hash();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < 8; index++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        create(hash(), gate);
                        return true;
                    } catch (ApplicationException failure) {
                        assertEquals(ApplicationErrorCode.RATE_LIMITED, failure.code());
                        return false;
                    }
                }));
            }
            ready.await();
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    accepted++;
                }
            }
            assertEquals(5, accepted);
        }
        assertEquals(5, count("portfolio_session"));
        assertEquals(20, count("original_transaction"));
        assertEquals(20, count("operation_policy"));
    }

    @Test
    void resetQuotaFailureLeavesCurrentWorkspaceActiveAndSeedUnchanged() {
        PortfolioSession current = create(hash(), hash());
        for (int index = 0; index < 4; index++) {
            current = sessions.reset(current.cookieHash(), hash(), current.gateHash());
        }
        PortfolioSession last = current;
        assertEquals(
                ApplicationErrorCode.RATE_LIMITED,
                assertThrows(
                                ApplicationException.class,
                                () -> sessions.reset(last.cookieHash(), hash(), last.gateHash()))
                        .code());
        assertEquals(last, sessions.active(last.cookieHash(), last.gateHash()));
        assertEquals(5, count("portfolio_session"));
        assertEquals(20, count("original_transaction"));
    }

    @Test
    void resetAndOldTenantEntityInsertFollowOneLockOrderWithoutDeadlock() throws Exception {
        PortfolioSession current = create(hash(), hash());
        CountDownLatch revokedBeforeReplacement = new CountDownLatch(1);
        CountDownLatch resumeReset = new CountDownLatch(1);
        JdbcTemplate pausing = new PausingJdbc(
                java.util.Objects.requireNonNull(jdbc.getDataSource()), revokedBeforeReplacement, resumeReset);
        PortfolioSessions racing =
                new PortfolioSessionApplicationService(new PostgresPortfolioSessionStore(pausing), boundary, CLOCK);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<PortfolioSession> reset =
                    pool.submit(() -> racing.reset(current.cookieHash(), hash(), current.gateHash()));
            assertTrue(revokedBeforeReplacement.await(5, TimeUnit.SECONDS));
            Future<String> insert = pool.submit(() -> {
                try {
                    boundary.required(() -> {
                        jdbc.execute("SET LOCAL application_name = 'portfolio-old-entity-insert'");
                        original(current.tenantId(), "synth-reset-racing-insert");
                    });
                    return "ACCEPTED";
                } catch (DataAccessException exception) {
                    if (exception.getMostSpecificCause() instanceof java.sql.SQLException database) {
                        return database.getSQLState() + ":"
                                + (database.getMessage().contains("PORTFOLIO_SESSION_INACTIVE") ? "INACTIVE" : "OTHER");
                    }
                    return "UNKNOWN_FAILURE";
                }
            });
            try {
                org.awaitility.Awaitility.await()
                        .atMost(Duration.ofSeconds(5))
                        .until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                                "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = 'portfolio-old-entity-insert' AND wait_event_type = 'Lock')",
                                Boolean.class)));
            } finally {
                resumeReset.countDown();
            }
            PortfolioSession replacement = reset.get(10, TimeUnit.SECONDS);
            assertEquals("P0001:INACTIVE", insert.get(10, TimeUnit.SECONDS));
            assertEquals(replacement, sessions.active(replacement.cookieHash(), replacement.gateHash()));
            assertEquals(2, count("portfolio_session"));
            assertEquals(8, count("original_transaction"));
        } finally {
            resumeReset.countDown();
        }
    }

    @Test
    void activeLifetimeAndGlobalCreationRateCapsFailBeforePartialSeed() {
        Instant now = Instant.now();
        metadataRows(100, now.minusSeconds(120), now.plusSeconds(7200));
        assertRateLimitedCreation();
        assertEquals(0, count("original_transaction"));
        jdbc.execute("TRUNCATE portfolio_session");
        metadataRows(2000, now.minusSeconds(7201), now.minusSeconds(1));
        assertRateLimitedCreation();
        jdbc.execute("TRUNCATE portfolio_session");
        metadataRows(12, now.minusSeconds(2), now.plusSeconds(7200));
        assertRateLimitedCreation();
        assertEquals(0, count("operation_policy"));
    }

    @Test
    void physicalCommandCapStillAllowsExactIdempotentReplayAndRollsBackExcessAcceptance() {
        PortfolioSession session = create(hash(), hash());
        for (int index = 0; index < 41; index++) {
            original(session.tenantId(), "synth-cap-original-" + index);
        }
        AcceptReversalCommand first = request("synth-cap-original-0", 1, "portfolio-command-cap-0");
        for (int index = 0; index < 40; index++) {
            AcceptReversalCommand command =
                    index == 0 ? first : request("synth-cap-original-" + index, 1, "portfolio-command-cap-" + index);
            boundary.required(() -> reversals.accept(actor(session), command));
        }
        long auditBefore = count("audit_event");
        long outboxBefore = count("outbox_event");
        assertEquals(
                ApplicationErrorCode.RATE_LIMITED,
                assertThrows(
                                ApplicationException.class,
                                () -> boundary.required(() -> reversals.accept(
                                        actor(session),
                                        request("synth-cap-original-40", 1, "portfolio-command-cap-40"))))
                        .code());
        assertEquals(40, count("reversal_command"));
        assertEquals(auditBefore, count("audit_event"));
        assertEquals(outboxBefore, count("outbox_event"));
        assertTrue(
                boundary.required(() -> reversals.accept(actor(session), first)).replayed());
        assertEquals(
                "POSTED",
                jdbc.queryForObject(
                        "SELECT status FROM original_transaction WHERE tenant_id = ? AND public_reference_token = ?",
                        String.class,
                        session.tenantId(),
                        "synth-cap-original-40"));
    }

    @Test
    void originalLocalAndGlobalCapsAreEnforcedByDatabaseAndSeedFailureIsAtomic() {
        PortfolioSession session = create(hash(), hash());
        for (int index = 0; index < 76; index++) {
            original(session.tenantId(), "synth-local-cap-" + index);
        }
        assertThrows(DataAccessException.class, () -> original(session.tenantId(), "synth-local-cap-overflow"));
        assertEquals(80, count("original_transaction"));
        // A disposable administrative fixture reaches the independent global threshold quickly.
        jdbc.execute("ALTER TABLE original_transaction DISABLE TRIGGER portfolio_original_quota");
        try {
            jdbc.update("""
                    INSERT INTO original_transaction (id, tenant_id, external_reference_token, public_reference_token,
                        operation_type, amount, currency, status, occurred_at, created_at, updated_at, version)
                    SELECT gen_random_uuid(), ?, encode(sha256(convert_to('global-' || n, 'UTF8')), 'hex'),
                        'synth-global-cap-' || n, 'CARD_PURCHASE', 1, 'ZAR', 'POSTED', now(), now(), now(), 0
                    FROM generate_series(1, 11920) n
                    """, session.tenantId());
        } finally {
            jdbc.execute("ALTER TABLE original_transaction ENABLE TRIGGER portfolio_original_quota");
        }
        assertEquals(12000, count("original_transaction"));
        assertRateLimitedCreation();
        assertEquals(1, count("portfolio_session"));
        assertEquals(4, count("operation_policy"));
    }

    @Test
    void runtimeRoleCanCreateWorkspacesAndAppendAuditButCannotMigrateOrMutateAudit() {
        String password = UUID.randomUUID().toString();
        jdbc.execute("CREATE ROLE resilientpay_runtime LOGIN PASSWORD '" + password
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        jdbc.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource runtimeSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "resilientpay_runtime", password);
        JdbcTemplate runtime = new JdbcTemplate(runtimeSource);
        TransactionBoundary runtimeBoundary =
                new SpringTransactionBoundary(new DataSourceTransactionManager(runtimeSource));
        PortfolioSessions runtimeSessions = new PortfolioSessionApplicationService(
                new PostgresPortfolioSessionStore(runtime), runtimeBoundary, CLOCK);
        PortfolioSession session =
                runtimeSessions.create(hash(), hash(), Instant.now().plusSeconds(28800));
        assertEquals(
                session.id(),
                runtimeSessions.active(session.cookieHash(), session.gateHash()).id());
        assertFalse(runtime.queryForObject(
                "SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname = current_user",
                Boolean.class));
        assertThrows(DataAccessException.class, () -> runtime.execute("CREATE TABLE forbidden_runtime_ddl (id int)"));
        assertThrows(DataAccessException.class, () -> runtime.update("DELETE FROM flyway_schema_history"));
        assertThrows(DataAccessException.class, () -> runtime.update("DELETE FROM portfolio_session"));
        assertTrue(runtime.queryForObject(
                "SELECT has_table_privilege(current_user, 'audit_event', 'INSERT')", Boolean.class));
        assertFalse(runtime.queryForObject(
                "SELECT has_table_privilege(current_user, 'audit_event', 'UPDATE') OR has_table_privilege(current_user, 'audit_event', 'DELETE')",
                Boolean.class));
        runtimeSessions.logout(session.cookieHash(), session.gateHash());
    }

    private static PortfolioSession create(String cookieHash, String gateHash) {
        return sessions.create(cookieHash, gateHash, Instant.now().plusSeconds(28800));
    }

    private static void assertRateLimitedCreation() {
        assertEquals(
                ApplicationErrorCode.RATE_LIMITED,
                assertThrows(ApplicationException.class, () -> create(hash(), hash()))
                        .code());
    }

    private static long count(String table) {
        if (!Set.of(
                        "portfolio_session",
                        "original_transaction",
                        "operation_policy",
                        "reversal_command",
                        "audit_event",
                        "outbox_event")
                .contains(table)) {
            throw new IllegalArgumentException("Unknown synthetic fixture relation");
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static String hash() {
        return PortfolioRequestSecurity.digest(UUID.randomUUID().toString());
    }

    private static void metadataRows(int count, Instant created, Instant expires) {
        jdbc.update("""
                INSERT INTO portfolio_session (id, tenant_id, cookie_hash, gate_hash, created_at, expires_at)
                SELECT gen_random_uuid(), gen_random_uuid(), encode(sha256(convert_to('cookie-' || n, 'UTF8')), 'hex'),
                    encode(sha256(convert_to('gate-' || n, 'UTF8')), 'hex'), ?, ?
                FROM generate_series(1, ?) n
                """, Timestamp.from(created), Timestamp.from(expires), count);
    }

    private static void original(UUID tenantId, String token) {
        jdbc.update("""
                INSERT INTO original_transaction (id, tenant_id, external_reference_token, public_reference_token,
                    operation_type, amount, currency, status, occurred_at, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'CARD_PURCHASE', 1, 'ZAR', 'POSTED', now(), now(), now(), 0)
                """, UUID.randomUUID(), tenantId, PortfolioRequestSecurity.digest(token), token);
    }

    private static ActorContext actor(PortfolioSession session) {
        return new ActorContext(
                new TenantId(session.tenantId()),
                new ActorId("synthetic-portfolio-it-operator"),
                Set.of("SCOPE_reversals.write"));
    }

    private static AcceptReversalCommand request(String token, int amount, String key) {
        return new AcceptReversalCommand(
                token,
                OperationType.CARD_PURCHASE,
                new Money(BigDecimal.valueOf(amount), Currency.getInstance("ZAR")),
                new ReversalReason("Synthetic portfolio verification"),
                DemonstrationFailure.NONE,
                key,
                new CorrelationId(UUID.randomUUID()));
    }

    /**
     * Pauses only a replacement insert after reset has revoked the old row, exposing lock ordering.
     *
     * @author Daniel Jr Nkulu
     */
    private static final class PausingJdbc extends JdbcTemplate {
        private final CountDownLatch paused;
        private final CountDownLatch resume;

        private PausingJdbc(javax.sql.DataSource source, CountDownLatch paused, CountDownLatch resume) {
            super(source);
            this.paused = paused;
            this.resume = resume;
        }

        @Override
        public int update(String sql, Object... arguments) {
            if (sql.contains("INSERT INTO portfolio_session")) {
                paused.countDown();
                try {
                    if (!resume.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Synthetic reset probe timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Synthetic reset probe interrupted", exception);
                }
            }
            return super.update(sql, arguments);
        }
    }
}
