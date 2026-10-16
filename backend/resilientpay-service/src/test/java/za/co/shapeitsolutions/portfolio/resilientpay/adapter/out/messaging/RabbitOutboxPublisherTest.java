package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.MessagingProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.OutboxProperties;

/**
 * Verifies that confirmed lifecycle publication avoids execution-only database preparation work.
 *
 * @author Daniel Jr Nkulu
 */
class RabbitOutboxPublisherTest {

    @ParameterizedTest
    @ValueSource(strings = {"ReversalAccepted.v1", "OriginalTransactionStatusChanged.v1"})
    void nonExecutionEventDoesNotOpenDispatchPreparationTransaction(String eventType) {
        OutboxMessage message = message(eventType, "{}");
        RecordingRepository repository = new RecordingRepository(message);
        RecordingRabbitTemplate rabbit = new RecordingRabbitTemplate();
        RecordingAudit audit = new RecordingAudit();
        MessagingProperties messaging = messaging();
        RabbitOutboxPublisher publisher = new RabbitOutboxPublisher(
                repository,
                rabbit,
                new MessageIntegrity(messaging),
                audit,
                messaging,
                outbox(),
                new SimpleMeterRegistry(),
                new RabbitTraceContext(Tracer.NOOP, Propagator.NOOP));

        publisher.publishDue();

        assertEquals(0, repository.preparationCalls);
        assertEquals(message, repository.published);
        assertEquals(message, audit.published);
        assertEquals(1, rabbit.sendCalls);
    }

    @Test
    void executionEventPublishesAndRecordsThePreparedMessage() {
        OutboxMessage claimed = message("ReversalExecutionRequested.v1", "{\"deadline\":\"old\"}");
        OutboxMessage prepared = new OutboxMessage(
                claimed.id(),
                claimed.tenantId(),
                claimed.aggregateId(),
                claimed.eventType(),
                claimed.schemaVersion(),
                claimed.correlationId(),
                claimed.causationId(),
                "{\"deadline\":\"rebased\"}".getBytes(StandardCharsets.UTF_8),
                claimed.attemptCount(),
                claimed.traceContext());
        RecordingRepository repository = new RecordingRepository(claimed, prepared);
        RecordingRabbitTemplate rabbit = new RecordingRabbitTemplate();
        RecordingAudit audit = new RecordingAudit();
        MessagingProperties messaging = messaging();
        RabbitOutboxPublisher publisher = new RabbitOutboxPublisher(
                repository,
                rabbit,
                new MessageIntegrity(messaging),
                audit,
                messaging,
                outbox(),
                new SimpleMeterRegistry(),
                new RabbitTraceContext(Tracer.NOOP, Propagator.NOOP));

        publisher.publishDue();

        assertEquals(1, repository.preparationCalls);
        assertEquals(prepared, repository.published);
        assertEquals(prepared, audit.published);
        assertArrayEquals(prepared.payload(), rabbit.sent.getBody());
    }

    private static OutboxMessage message(String eventType, String payload) {
        UUID messageId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        return new OutboxMessage(
                messageId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                eventType,
                1,
                correlationId,
                correlationId,
                payload.getBytes(StandardCharsets.UTF_8),
                1,
                OutboxTraceContext.empty());
    }

    private static OutboxProperties outbox() {
        return new OutboxProperties(
                50, Duration.ofSeconds(30), Duration.ofMillis(500), 8, Duration.ofSeconds(5), Duration.ofMinutes(5), 3);
    }

    private static MessagingProperties messaging() {
        return new MessagingProperties(
                "resilientpay.reversal.lifecycle.v1",
                "accepted.queue",
                "original.queue",
                "execution.queue",
                "outcome.queue",
                "quarantine.exchange",
                "quarantine.queue",
                "accepted.v1",
                "original.v1",
                "execution.v1",
                "outcome.v1",
                "quarantine.v1",
                "execution.quarantine.v1",
                "consumer-v1",
                "test-message-integrity-key",
                "test-key-v1",
                16_384,
                Duration.ofMillis(250),
                Duration.ofSeconds(2),
                3);
    }

    /**
     * Records repository calls without opening a database connection.
     *
     * @author Daniel Jr Nkulu
     */
    private static final class RecordingRepository extends PostgresOutboxRepository {
        private final OutboxMessage claimed;
        private final OutboxMessage prepared;
        private int preparationCalls;
        private OutboxMessage published;

        private RecordingRepository(OutboxMessage claimed) {
            this(claimed, claimed);
        }

        private RecordingRepository(OutboxMessage claimed, OutboxMessage prepared) {
            super(null, null, null, null);
            this.claimed = claimed;
            this.prepared = prepared;
        }

        @Override
        public int recoverDueDead() {
            return 0;
        }

        @Override
        public List<OutboxMessage> claimDue() {
            return List.of(claimed);
        }

        @Override
        public OutboxMessage prepareForPublish(OutboxMessage message) {
            preparationCalls++;
            return prepared;
        }

        @Override
        public void markPublished(OutboxMessage message) {
            published = message;
        }

        @Override
        public void markFailed(OutboxMessage message, String errorCode, boolean retryable) {
            throw new AssertionError("Unexpected publication failure: " + errorCode);
        }
    }

    /**
     * Completes each synthetic publisher confirmation synchronously and counts sends.
     *
     * @author Daniel Jr Nkulu
     */
    private static final class RecordingRabbitTemplate extends RabbitTemplate {
        private int sendCalls;
        private Message sent;

        @Override
        public void send(String exchange, String routingKey, Message message, CorrelationData correlationData) {
            sendCalls++;
            sent = message;
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
        }
    }

    /**
     * Captures the exact event whose confirmed publication was audited.
     *
     * @author Daniel Jr Nkulu
     */
    private static final class RecordingAudit implements OutboxPublicationAudit {
        private OutboxMessage published;

        @Override
        public void recordPublished(OutboxMessage message) {
            published = message;
        }
    }
}
