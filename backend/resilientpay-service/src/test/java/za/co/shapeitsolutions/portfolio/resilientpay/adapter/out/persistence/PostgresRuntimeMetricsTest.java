package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;

/**
 * Verifies the aggregate SQL shape and low-cardinality database gauge mapping.
 *
 * @author Daniel Jr Nkulu
 */
class PostgresRuntimeMetricsTest {
    @Test
    void mapsOneAggregateQueryToAllRuntimeGauges() {
        AtomicReference<String> executedSql = new AtomicReference<>();
        Map<String, Object> row = Map.of(
                "due_outbox", 2L,
                "pending_outbox", 5L,
                "oldest_outbox_seconds", new BigDecimal("42.125"),
                "executing_commands", 3L,
                "overdue_commands", 1L,
                "open_reconciliation_cases", 4L,
                "quarantine_depth", 6L,
                "duplicate_financial_actions", 0L);
        JdbcOperations jdbc = (JdbcOperations) Proxy.newProxyInstance(
                JdbcOperations.class.getClassLoader(),
                new Class<?>[] {JdbcOperations.class},
                (proxy, method, arguments) -> {
                    if ("queryForMap".equals(method.getName())) {
                        executedSql.set((String) arguments[0]);
                        return row;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new PostgresRuntimeMetrics(jdbc).bindTo(registry);

        assertTrue(executedSql.get().contains("status = 'DEAD' AND retryable"));
        assertTrue(executedSql.get().contains("attempt.result_deadline <= CURRENT_TIMESTAMP"));
        assertTrue(executedSql.get().contains("HAVING COUNT(*) > 1"));
        assertEquals(
                2.0,
                registry.get("resilientpay.outbox.backlog")
                        .tag("state", "due")
                        .gauge()
                        .value());
        assertEquals(
                5.0,
                registry.get("resilientpay.outbox.backlog")
                        .tag("state", "pending")
                        .gauge()
                        .value());
        assertEquals(
                42.125,
                registry.get("resilientpay.outbox.oldest.age.seconds").gauge().value());
        assertEquals(
                3.0,
                registry.get("resilientpay.reversal.commands")
                        .tag("state", "executing")
                        .gauge()
                        .value());
        assertEquals(
                1.0,
                registry.get("resilientpay.reversal.commands")
                        .tag("state", "overdue")
                        .gauge()
                        .value());
        assertEquals(
                4.0,
                registry.get("resilientpay.reconciliation.cases")
                        .tag("state", "open")
                        .gauge()
                        .value());
        assertEquals(
                6.0,
                registry.get("resilientpay.quarantine.depth")
                        .tag("state", "open")
                        .gauge()
                        .value());
        assertEquals(
                0.0,
                registry.get("resilientpay.financial.duplicate.action.detected")
                        .gauge()
                        .value());
    }
}
