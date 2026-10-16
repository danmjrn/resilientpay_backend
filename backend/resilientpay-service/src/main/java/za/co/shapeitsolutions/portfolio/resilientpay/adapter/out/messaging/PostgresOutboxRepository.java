package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.OutboxProperties;

/**
 * Leases PostgreSQL outbox rows with skip-locked concurrency and advances workflow state only
 * after broker confirmation.
 *
 * @author Daniel Jr Nkulu
 */
@Repository
@Profile("!test")
public class PostgresOutboxRepository {
    private final JdbcOperations jdbc;
    private final Clock clock;
    private final OutboxProperties properties;
    private final OutboxRecoveryAudit recoveryAudit;
    private final String workerId = "outbox-" + UUID.randomUUID();

    /**
     * Creates the transactional outbox repository.
     *
     * @param jdbc PostgreSQL access
     * @param clock UTC time boundary
     * @param properties bounded lease and retry settings
     * @param recoveryAudit append-only evidence for cold recovery
     */
    public PostgresOutboxRepository(
            JdbcOperations jdbc, Clock clock, OutboxProperties properties, OutboxRecoveryAudit recoveryAudit) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        this.recoveryAudit = recoveryAudit;
    }

    /**
     * Claims a bounded due batch without blocking concurrent publishers.
     *
     * @return leased messages owned by this process
     */
    @Transactional
    public List<OutboxMessage> claimDue() {
        Instant now = clock.instant();
        return jdbc.query(
                """
                WITH due AS (
                    SELECT id
                    FROM outbox_event
                    WHERE available_at <= ?
                      AND (status = 'PENDING' OR (status = 'CLAIMED' AND lease_until < ?))
                      AND attempt_count < ?
                    ORDER BY available_at, created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE outbox_event event
                SET status = 'CLAIMED', lease_owner = ?, lease_until = ?,
                    attempt_count = LEAST(event.attempt_count + 1, ?), last_error_code = NULL
                FROM due
                WHERE event.id = due.id
                RETURNING event.id, event.tenant_id, event.aggregate_id, event.event_type,
                          event.schema_version, event.correlation_id,
                          COALESCE(event.causation_id, event.correlation_id) AS causation_id,
                          event.payload::text AS payload, event.attempt_count,
                          event.traceparent, event.tracestate
                """,
                (resultSet, rowNumber) -> new OutboxMessage(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("tenant_id", UUID.class),
                        resultSet.getObject("aggregate_id", UUID.class),
                        resultSet.getString("event_type"),
                        resultSet.getInt("schema_version"),
                        resultSet.getObject("correlation_id", UUID.class),
                        resultSet.getObject("causation_id", UUID.class),
                        resultSet.getString("payload").getBytes(StandardCharsets.UTF_8),
                        resultSet.getInt("attempt_count"),
                        new OutboxTraceContext(resultSet.getString("traceparent"), resultSet.getString("tracestate"))),
                Timestamp.from(now),
                Timestamp.from(now),
                properties.maxAttempts(),
                properties.batchSize(),
                workerId,
                Timestamp.from(now.plus(properties.leaseDuration())),
                properties.maxAttempts());
    }

    /**
     * Rebases an execution deadline immediately before dispatch and returns the exact payload that
     * will later supply the confirmed attempt deadline.
     *
     * @param message leased message
     * @return message with a dispatch-time deadline when it is an execution request
     */
    @Transactional
    public OutboxMessage prepareForPublish(OutboxMessage message) {
        if (!"ReversalExecutionRequested.v1".equals(message.eventType())) {
            return message;
        }
        Long resultTimeoutMillis = jdbc.queryForObject("""
                SELECT result_timeout_ms_snapshot
                FROM reversal_command
                WHERE tenant_id = ? AND id = ?
                """, Long.class, message.tenantId(), message.aggregateId());
        Instant deadline = clock.instant().plusMillis(Objects.requireNonNull(resultTimeoutMillis));
        int changed = update("""
                UPDATE outbox_event
                SET payload = jsonb_set(payload, '{deadline}', to_jsonb(CAST(? AS text)), true)
                WHERE id = ? AND tenant_id = ? AND status = 'CLAIMED' AND lease_owner = ?
                """, deadline.toString(), message.id(), message.tenantId(), workerId);
        if (changed != 1) {
            throw new IllegalStateException("Outbox lease was lost before dispatch preparation");
        }
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE id = ? AND tenant_id = ?",
                String.class,
                message.id(),
                message.tenantId());
        return new OutboxMessage(
                message.id(),
                message.tenantId(),
                message.aggregateId(),
                message.eventType(),
                message.schemaVersion(),
                message.correlationId(),
                message.causationId(),
                Objects.requireNonNull(payload).getBytes(StandardCharsets.UTF_8),
                message.attemptCount(),
                message.traceContext());
    }

    /**
     * Marks a broker-confirmed event published and advances its command and attempt.
     *
     * @param message confirmed message
     */
    @Transactional
    public void markPublished(OutboxMessage message) {
        Instant now = clock.instant();
        int changed = update("""
                UPDATE outbox_event
                SET status = 'PUBLISHED', published_at = ?, lease_owner = NULL, lease_until = NULL
                WHERE id = ? AND tenant_id = ? AND status = 'CLAIMED' AND lease_owner = ?
                """, now, message.id(), message.tenantId(), workerId);
        if (changed != 1) {
            throw new IllegalStateException("Outbox lease was lost before confirmation");
        }
        if (!"ReversalExecutionRequested.v1".equals(message.eventType())) {
            return;
        }
        // Keep the aggregate/attempt lock order identical to processOutcome. The broker can
        // return an outcome immediately after confirmation, so taking the attempt first here
        // would deadlock against an outcome transaction that already owns the command row.
        update("""
                UPDATE reversal_command
                SET state = 'EXECUTING', updated_at = ?, version = version + 1
                WHERE tenant_id = ? AND id = ? AND state = 'EXECUTION_PENDING'
                """, now, message.tenantId(), message.aggregateId());
        update(
                """
                UPDATE execution_attempt
                SET state = 'DISPATCHED',
                    result_deadline = (CAST(? AS jsonb)->>'deadline')::timestamptz,
                    updated_at = ?, version = version + 1
                WHERE tenant_id = ? AND id = (CAST(? AS jsonb)->>'executionAttemptId')::uuid
                  AND state = 'CREATED'
                """,
                new String(message.payload(), StandardCharsets.UTF_8),
                now,
                message.tenantId(),
                new String(message.payload(), StandardCharsets.UTF_8));
    }

    /**
     * Releases a failed lease with bounded backoff or marks a permanent/exhausted event dead.
     *
     * @param message failed message
     * @param errorCode stable redaction-safe error code
     * @param retryable whether another bounded attempt may be made
     */
    @Transactional
    public void markFailed(OutboxMessage message, String errorCode, boolean retryable) {
        if (!retryable) {
            update("""
                    UPDATE outbox_event
                    SET status = 'DEAD', lease_owner = NULL, lease_until = NULL,
                        last_error_code = ?, retryable = FALSE
                    WHERE id = ? AND tenant_id = ? AND status = 'CLAIMED' AND lease_owner = ?
                    """, errorCode, message.id(), message.tenantId(), workerId);
            return;
        }
        if (message.attemptCount() >= properties.maxAttempts()) {
            Integer currentRecoveryCycle =
                    jdbc.queryForObject("""
                    SELECT recovery_cycle_count
                    FROM outbox_event
                    WHERE id = ? AND tenant_id = ? AND status = 'CLAIMED' AND lease_owner = ?
                    FOR UPDATE
                    """, Integer.class, message.id(), message.tenantId(), workerId);
            int recoveryCycle = Objects.requireNonNull(currentRecoveryCycle);
            if (recoveryCycle >= properties.maxRecoveryCycles()) {
                recoveryAudit.recordTerminalFailure(message, recoveryCycle, errorCode);
                update("""
                        UPDATE outbox_event
                        SET status = 'DEAD', lease_owner = NULL, lease_until = NULL,
                            last_error_code = 'OUTBOX_RECOVERY_EXHAUSTED', retryable = FALSE
                        WHERE id = ? AND tenant_id = ? AND status = 'CLAIMED' AND lease_owner = ?
                        """, message.id(), message.tenantId(), workerId);
                return;
            }
            update(
                    """
                    UPDATE outbox_event
                    SET status = 'DEAD', available_at = ?, lease_owner = NULL, lease_until = NULL,
                        last_error_code = ?, retryable = TRUE
                    WHERE id = ? AND tenant_id = ? AND status = 'CLAIMED' AND lease_owner = ?
                    """,
                    clock.instant().plus(properties.coldRetryDelay()),
                    errorCode,
                    message.id(),
                    message.tenantId(),
                    workerId);
            return;
        }
        Instant nextAttempt = clock.instant().plus(backoff(message.attemptCount()));
        update("""
                UPDATE outbox_event
                SET status = 'PENDING', available_at = ?, lease_owner = NULL, lease_until = NULL,
                    last_error_code = ?
                WHERE id = ? AND tenant_id = ? AND status = 'CLAIMED' AND lease_owner = ?
                """, nextAttempt, errorCode, message.id(), message.tenantId(), workerId);
    }

    /**
     * Moves a bounded batch of exhausted retryable events into a fresh bounded hot cycle. The
     * recovery audit joins this transaction, so an audit failure leaves the event cold and dead.
     *
     * @return number of events recovered
     */
    @Transactional
    public int recoverDueDead() {
        Instant now = clock.instant();
        List<OutboxMessage> due = jdbc.query(
                """
                SELECT id, tenant_id, aggregate_id, event_type, schema_version, correlation_id,
                       COALESCE(causation_id, correlation_id) AS causation_id,
                       payload::text AS payload, attempt_count, traceparent, tracestate
                FROM outbox_event
                WHERE status = 'DEAD' AND retryable AND available_at <= ?
                  AND recovery_cycle_count < ?
                ORDER BY available_at, created_at
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """,
                (resultSet, rowNumber) -> new OutboxMessage(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("tenant_id", UUID.class),
                        resultSet.getObject("aggregate_id", UUID.class),
                        resultSet.getString("event_type"),
                        resultSet.getInt("schema_version"),
                        resultSet.getObject("correlation_id", UUID.class),
                        resultSet.getObject("causation_id", UUID.class),
                        resultSet.getString("payload").getBytes(StandardCharsets.UTF_8),
                        resultSet.getInt("attempt_count"),
                        new OutboxTraceContext(resultSet.getString("traceparent"), resultSet.getString("tracestate"))),
                Timestamp.from(now),
                properties.maxRecoveryCycles(),
                properties.batchSize());
        for (OutboxMessage message : due) {
            Integer currentRecoveryCount = jdbc.queryForObject(
                    "SELECT recovery_cycle_count FROM outbox_event WHERE id = ? AND tenant_id = ? FOR UPDATE",
                    Integer.class,
                    message.id(),
                    message.tenantId());
            int recoveryCount = Objects.requireNonNull(currentRecoveryCount) + 1;
            recoveryAudit.recordRecovered(message, recoveryCount);
            int changed = update("""
                    UPDATE outbox_event
                    SET status = 'PENDING', attempt_count = 0, recovery_cycle_count = ?,
                        available_at = ?, lease_owner = NULL, lease_until = NULL,
                        last_error_code = 'COLD_RECOVERY_SCHEDULED'
                    WHERE id = ? AND tenant_id = ? AND status = 'DEAD' AND retryable
                    """, recoveryCount, now, message.id(), message.tenantId());
            if (changed != 1) {
                throw new IllegalStateException("Outbox cold recovery lost its locked event");
            }
        }
        return due.size();
    }

    private Duration backoff(int attempt) {
        long multiplier = 1L << Math.min(attempt - 1, 10);
        Duration candidate = properties.pollDelay().multipliedBy(multiplier);
        return candidate.compareTo(Duration.ofSeconds(30)) > 0 ? Duration.ofSeconds(30) : candidate;
    }

    private int update(String sql, Object... arguments) {
        Object[] values = arguments.clone();
        for (int index = 0; index < values.length; index++) {
            if (values[index] instanceof Instant instant) {
                values[index] = Timestamp.from(instant);
            }
        }
        return jdbc.update(sql, values);
    }
}
