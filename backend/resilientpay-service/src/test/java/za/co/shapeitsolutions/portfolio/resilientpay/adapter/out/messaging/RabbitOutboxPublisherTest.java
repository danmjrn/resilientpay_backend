package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence.SpringTransactionBoundary;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OutboxMessage;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OutboxTraceContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OutboxPublicationAudit;
import za.co.shapeitsolutions.portfolio.resilientpay.application.service.OutboxApplicationService;
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
        RecordingTransactionManager manager = new RecordingTransactionManager();
        MessagingProperties messaging = messaging();
        RabbitOutboxPublisher publisher = new RabbitOutboxPublisher(
                new OutboxApplicationService(new SpringTransactionBoundary(manager), repository, audit),
                rabbit,
                new MessageIntegrity(messaging),
                messaging,
                outbox(),
                new SimpleMeterRegistry(),
                new RabbitTraceContext(Tracer.NOOP, Propagator.NOOP));

        publisher.publishDue();

        assertEquals(0, repository.preparationCalls);
        assertEquals(message, repository.published);
        assertEquals(message, audit.published);
        assertEquals(1, rabbit.sendCalls);
        assertEquals(List.of(0, 0, 3, 0), manager.propagations);
        assertEquals(4, manager.commits);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
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
        RecordingTransactionManager manager = new RecordingTransactionManager();
        MessagingProperties messaging = messaging();
        RabbitOutboxPublisher publisher = new RabbitOutboxPublisher(
                new OutboxApplicationService(new SpringTransactionBoundary(manager), repository, audit),
                rabbit,
                new MessageIntegrity(messaging),
                messaging,
                outbox(),
                new SimpleMeterRegistry(),
                new RabbitTraceContext(Tracer.NOOP, Propagator.NOOP));

        publisher.publishDue();

        assertEquals(1, repository.preparationCalls);
        assertEquals(prepared, repository.published);
        assertEquals(prepared, audit.published);
        assertArrayEquals(prepared.payload(), rabbit.sent.getBody());
        assertEquals(List.of(0, 0, 0, 3, 0), manager.propagations);
        assertEquals(5, manager.commits);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void failedIndependentConfirmationAuditPreventsPublishedStateUpdate() {
        RecordingRepository repository = new RecordingRepository(message("ReversalAccepted.v1", "{}"));
        RecordingTransactionManager manager = new RecordingTransactionManager();
        RecordingRabbitTemplate rabbit = new RecordingRabbitTemplate();
        MessagingProperties properties = messaging();
        OutboxPublicationAudit failedAudit = message -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("synthetic audit failure");
        };
        RabbitOutboxPublisher publisher = new RabbitOutboxPublisher(
                new OutboxApplicationService(new SpringTransactionBoundary(manager), repository, failedAudit),
                rabbit,
                new MessageIntegrity(properties),
                properties,
                outbox(),
                new SimpleMeterRegistry(),
                new RabbitTraceContext(Tracer.NOOP, Propagator.NOOP));

        assertThrows(IllegalStateException.class, publisher::publishDue);

        assertEquals(1, rabbit.sendCalls);
        assertNull(repository.published);
        assertEquals(List.of(0, 0, 3), manager.propagations);
        assertEquals(2, manager.commits);
        assertEquals(1, manager.rollbacks);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void suppressedExecutionDoesNotSendAndDoesNotStarveTheRemainingClaimedBatch() {
        OutboxMessage suppressed = message("ReversalExecutionRequested.v1", "{}");
        OutboxMessage lifecycle = message("ReversalAccepted.v1", "{}");
        RecordingRepository repository = new RecordingRepository(suppressed);
        repository.suppressExecution = true;
        repository.additional = List.of(lifecycle);
        RecordingTransactionManager manager = new RecordingTransactionManager();
        RecordingRabbitTemplate rabbit = new RecordingRabbitTemplate();
        RecordingAudit audit = new RecordingAudit();
        MessagingProperties properties = messaging();
        RabbitOutboxPublisher publisher = new RabbitOutboxPublisher(
                new OutboxApplicationService(new SpringTransactionBoundary(manager), repository, audit),
                rabbit,
                new MessageIntegrity(properties),
                properties,
                outbox(),
                new SimpleMeterRegistry(),
                new RabbitTraceContext(Tracer.NOOP, Propagator.NOOP));

        publisher.publishDue();

        assertEquals(1, repository.preparationCalls);
        assertEquals(suppressed, repository.suppressed);
        assertEquals(1, rabbit.sendCalls);
        assertEquals(
                lifecycle.id().toString(), rabbit.sent.getMessageProperties().getMessageId());
        assertEquals(lifecycle, repository.published);
        assertEquals(lifecycle, audit.published);
        assertEquals(1, manager.rollbacks);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void cancelledFinalAttemptFailureDoesNotStarveTheRemainingClaimedBatch() {
        OutboxMessage initial = message("ReversalExecutionRequested.v1", "{}");
        OutboxMessage finalAttempt = new OutboxMessage(
                initial.id(),
                initial.tenantId(),
                initial.aggregateId(),
                initial.eventType(),
                initial.schemaVersion(),
                initial.correlationId(),
                initial.causationId(),
                initial.payload(),
                outbox().maxAttempts(),
                initial.traceContext());
        OutboxMessage lifecycle = message("ReversalAccepted.v1", "{}");
        RecordingRepository repository = new RecordingRepository(finalAttempt);
        repository.cancelledDuringPublication = true;
        repository.additional = List.of(lifecycle);
        RecordingTransactionManager manager = new RecordingTransactionManager();
        RecordingRabbitTemplate rabbit = new RecordingRabbitTemplate();
        rabbit.failExecution = true;
        RecordingAudit audit = new RecordingAudit();
        MessagingProperties properties = messaging();
        RabbitOutboxPublisher publisher = new RabbitOutboxPublisher(
                new OutboxApplicationService(new SpringTransactionBoundary(manager), repository, audit),
                rabbit,
                new MessageIntegrity(properties),
                properties,
                outbox(),
                new SimpleMeterRegistry(),
                new RabbitTraceContext(Tracer.NOOP, Propagator.NOOP));

        publisher.publishDue();

        assertEquals(1, repository.preparationCalls);
        assertEquals(finalAttempt, repository.ignoredCancelledFailure);
        assertEquals(2, rabbit.sendCalls);
        assertEquals(lifecycle, repository.published);
        assertEquals(lifecycle, audit.published);
        assertEquals(
                lifecycle.id().toString(), rabbit.sent.getMessageProperties().getMessageId());
        assertEquals(0, manager.rollbacks);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
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
        private OutboxMessage suppressed;
        private boolean suppressExecution;
        private boolean cancelledDuringPublication;
        private OutboxMessage ignoredCancelledFailure;
        private List<OutboxMessage> additional = List.of();

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
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return 0;
        }

        @Override
        public List<OutboxMessage> claimDue() {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            List<OutboxMessage> batch = new ArrayList<>(List.of(claimed));
            batch.addAll(additional);
            return List.copyOf(batch);
        }

        @Override
        public OutboxMessage prepareForPublish(OutboxMessage message) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            preparationCalls++;
            if (suppressExecution) {
                throw new ExecutionPublicationSuppressedException();
            }
            return prepared;
        }

        @Override
        public void markPublished(OutboxMessage message) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            published = message;
        }

        @Override
        public void markFailed(OutboxMessage message, String errorCode, boolean retryable) {
            if (cancelledDuringPublication && "BROKER_UNAVAILABLE".equals(errorCode) && retryable) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(outbox().maxAttempts(), message.attemptCount());
                ignoredCancelledFailure = message;
                return;
            }
            if (suppressExecution && "EXECUTION_SUPPRESSED".equals(errorCode) && !retryable) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                suppressed = message;
                return;
            }
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
        private boolean failExecution;

        @Override
        public void send(String exchange, String routingKey, Message message, CorrelationData correlationData) {
            assertFalse(
                    TransactionSynchronizationManager.isActualTransactionActive(),
                    "Rabbit send/confirmation must execute outside every database transaction");
            sendCalls++;
            sent = message;
            if (failExecution
                    && "ReversalExecutionRequested.v1"
                            .equals(message.getMessageProperties().getType())) {
                throw new AmqpException("synthetic failure after concurrent cancellation");
            }
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
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            published = message;
        }
    }

    /**
     * Runs Spring's real transaction-scope machinery without a database and records its completion.
     *
     * @author Daniel Jr Nkulu
     */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private static final long serialVersionUID = 1L;
        private final List<Integer> propagations = new ArrayList<>();
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            propagations.add(definition.getPropagationBehavior());
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
