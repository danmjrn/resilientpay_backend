package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Exposes low-cardinality PostgreSQL backlog and workflow gauges without identifiers or payloads.
 *
 * @author Daniel Jr Nkulu
 */
@Component
@Profile("!test")
public final class PostgresRuntimeMetrics implements MeterBinder {
    static final String SNAPSHOT_SQL = """
            SELECT
                (SELECT COUNT(*) FROM outbox_event
                 WHERE (status = 'PENDING' AND available_at <= CURRENT_TIMESTAMP)
                    OR (status = 'CLAIMED' AND lease_until < CURRENT_TIMESTAMP)) AS due_outbox,
                (SELECT COUNT(*) FROM outbox_event
                 WHERE status IN ('PENDING', 'CLAIMED')
                    OR (status = 'DEAD' AND retryable)) AS pending_outbox,
                (SELECT COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN(created_at))), 0)
                 FROM outbox_event
                 WHERE status IN ('PENDING', 'CLAIMED')
                    OR (status = 'DEAD' AND retryable)) AS oldest_outbox_seconds,
                (SELECT COUNT(*) FROM reversal_command
                 WHERE state = 'EXECUTING') AS executing_commands,
                (SELECT COUNT(*)
                 FROM execution_attempt attempt
                 JOIN reversal_command command
                   ON command.tenant_id = attempt.tenant_id
                  AND command.id = attempt.reversal_command_id
                 WHERE attempt.state = 'DISPATCHED'
                   AND attempt.result_deadline <= CURRENT_TIMESTAMP
                   AND command.state = 'EXECUTING') AS overdue_commands,
                (SELECT COUNT(*) FROM reconciliation_case
                 WHERE state IN ('OPEN', 'DECISION_PROPOSED')) AS open_reconciliation_cases,
                (SELECT COUNT(*) FROM message_quarantine
                 WHERE state = 'OPEN') AS quarantine_depth,
                (SELECT COUNT(*)
                 FROM (
                     SELECT tenant_id, reversal_command_id
                     FROM execution_attempt
                     WHERE state = 'SUCCEEDED'
                     GROUP BY tenant_id, reversal_command_id
                     HAVING COUNT(*) > 1
                 ) duplicate_successes) AS duplicate_financial_actions
            """;

    private final JdbcOperations jdbc;
    private final AtomicLong dueOutbox = new AtomicLong();
    private final AtomicLong pendingOutbox = new AtomicLong();
    private final AtomicLong oldestOutboxMillis = new AtomicLong();
    private final AtomicLong executingCommands = new AtomicLong();
    private final AtomicLong overdueCommands = new AtomicLong();
    private final AtomicLong openReconciliationCases = new AtomicLong();
    private final AtomicLong quarantineDepth = new AtomicLong();
    private final AtomicLong duplicateFinancialActions = new AtomicLong();
    private final AtomicLong refreshFailures = new AtomicLong();

    /**
     * Creates the aggregate-only PostgreSQL metric source.
     *
     * @param jdbc authoritative database access
     */
    public PostgresRuntimeMetrics(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("resilientpay.outbox.backlog", dueOutbox, AtomicLong::doubleValue)
                .tag("state", "due")
                .description("Due transactional outbox events")
                .register(registry);
        Gauge.builder("resilientpay.outbox.backlog", pendingOutbox, AtomicLong::doubleValue)
                .tag("state", "pending")
                .description("Durable unpublished transactional outbox events")
                .register(registry);
        Gauge.builder(
                        "resilientpay.outbox.oldest.age.seconds",
                        oldestOutboxMillis,
                        value -> value.doubleValue() / 1000.0)
                .description("Age in seconds of the oldest durable unpublished outbox event")
                .register(registry);
        Gauge.builder("resilientpay.reversal.commands", executingCommands, AtomicLong::doubleValue)
                .tag("state", "executing")
                .register(registry);
        Gauge.builder("resilientpay.reversal.commands", overdueCommands, AtomicLong::doubleValue)
                .tag("state", "overdue")
                .register(registry);
        Gauge.builder("resilientpay.reconciliation.cases", openReconciliationCases, AtomicLong::doubleValue)
                .tag("state", "open")
                .register(registry);
        Gauge.builder("resilientpay.quarantine.depth", quarantineDepth, AtomicLong::doubleValue)
                .tag("state", "open")
                .register(registry);
        Gauge.builder(
                        "resilientpay.financial.duplicate.action.detected",
                        duplicateFinancialActions,
                        AtomicLong::doubleValue)
                .description("Reversal commands with more than one successful execution attempt")
                .register(registry);
        Gauge.builder("resilientpay.runtime.metrics.refresh.failures", refreshFailures, AtomicLong::doubleValue)
                .register(registry);
        refresh();
    }

    /** Refreshes all database-backed gauges with one bounded aggregate query. */
    @Scheduled(fixedDelayString = "${resilientpay.metrics.database-refresh-delay:15s}")
    public void refresh() {
        try {
            Map<String, Object> snapshot = jdbc.queryForMap(SNAPSHOT_SQL);
            dueOutbox.set(longValue(snapshot, "due_outbox"));
            pendingOutbox.set(longValue(snapshot, "pending_outbox"));
            oldestOutboxMillis.set(Math.round(doubleValue(snapshot, "oldest_outbox_seconds") * 1000.0));
            executingCommands.set(longValue(snapshot, "executing_commands"));
            overdueCommands.set(longValue(snapshot, "overdue_commands"));
            openReconciliationCases.set(longValue(snapshot, "open_reconciliation_cases"));
            quarantineDepth.set(longValue(snapshot, "quarantine_depth"));
            duplicateFinancialActions.set(longValue(snapshot, "duplicate_financial_actions"));
        } catch (DataAccessException exception) {
            refreshFailures.incrementAndGet();
        }
    }

    private static long longValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static double doubleValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }
}
