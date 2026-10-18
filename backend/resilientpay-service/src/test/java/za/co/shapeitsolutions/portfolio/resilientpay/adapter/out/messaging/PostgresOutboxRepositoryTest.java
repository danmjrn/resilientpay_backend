package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OutboxMessage;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OutboxTraceContext;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.OutboxProperties;

/**
 * Locks the broker-confirmation SQL order to the outcome-processing aggregate order.
 *
 * @author Daniel Jr Nkulu
 */
class PostgresOutboxRepositoryTest {
    @Test
    void marksCommandBeforeAttemptToAvoidImmediateOutcomeDeadlock() {
        List<String> updatedTables = new ArrayList<>();
        JdbcOperations jdbc = (JdbcOperations) Proxy.newProxyInstance(
                JdbcOperations.class.getClassLoader(),
                new Class<?>[] {JdbcOperations.class},
                (proxy, method, arguments) -> {
                    if (!"update".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    String sql = (String) arguments[0];
                    if (sql.contains("UPDATE outbox_event")) {
                        updatedTables.add("outbox_event");
                    } else if (sql.contains("UPDATE reversal_command")) {
                        updatedTables.add("reversal_command");
                    } else if (sql.contains("UPDATE execution_attempt")) {
                        updatedTables.add("execution_attempt");
                    }
                    return 1;
                });
        OutboxProperties properties = new OutboxProperties(
                50, Duration.ofSeconds(30), Duration.ofMillis(500), 8, Duration.ofSeconds(5), Duration.ofMinutes(5), 3);
        PostgresOutboxRepository repository = new PostgresOutboxRepository(
                jdbc,
                Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC),
                properties,
                (message, recoveryCount) -> {});
        UUID tenantId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID(),
                tenantId,
                commandId,
                "ReversalExecutionRequested.v1",
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                ("{\"executionAttemptId\":\"" + attemptId + "\",\"deadline\":\"2026-08-30T00:01:00Z\"}")
                        .getBytes(StandardCharsets.UTF_8),
                1,
                OutboxTraceContext.empty());

        repository.markPublished(message);

        assertEquals(List.of("outbox_event", "reversal_command", "execution_attempt"), updatedTables);
    }
}
