package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ConditionalOnRuntimeRole;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.MessagingProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.OutboxProperties;

/**
 * Relays transactional outbox events to RabbitMQ and requires an unrouted-safe publisher confirm.
 *
 * @author Daniel Jr Nkulu
 */
@Component
@Profile("!test")
@ConditionalOnRuntimeRole("outbox-publisher")
public final class RabbitOutboxPublisher {
    private static final String EXECUTION_EVENT = "ReversalExecutionRequested.v1";
    private static final String ACCEPTED_EVENT = "ReversalAccepted.v1";
    private static final String ORIGINAL_STATUS_EVENT = "OriginalTransactionStatusChanged.v1";

    private final PostgresOutboxRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final MessageIntegrity integrity;
    private final OutboxPublicationAudit publicationAudit;
    private final MessagingProperties messaging;
    private final Duration confirmTimeout;
    private final MeterRegistry meterRegistry;
    private final RabbitTraceContext traceContext;

    /**
     * Creates the confirmed outbox relay.
     *
     * @param repository transactional lease repository
     * @param rabbitTemplate confirmed RabbitMQ publisher
     * @param integrity envelope and body integrity boundary
     * @param publicationAudit append-only confirmed publication evidence
     * @param messaging topology and wire limits
     * @param outbox bounded publication settings
     * @param meterRegistry low-cardinality publication metrics
     * @param traceContext W3C message trace propagation
     */
    public RabbitOutboxPublisher(
            PostgresOutboxRepository repository,
            RabbitTemplate rabbitTemplate,
            MessageIntegrity integrity,
            OutboxPublicationAudit publicationAudit,
            MessagingProperties messaging,
            OutboxProperties outbox,
            MeterRegistry meterRegistry,
            RabbitTraceContext traceContext) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate must not be null");
        this.integrity = Objects.requireNonNull(integrity, "integrity must not be null");
        this.publicationAudit = Objects.requireNonNull(publicationAudit, "publicationAudit must not be null");
        this.messaging = Objects.requireNonNull(messaging, "messaging must not be null");
        this.confirmTimeout =
                Objects.requireNonNull(outbox, "outbox must not be null").confirmTimeout();
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.traceContext = Objects.requireNonNull(traceContext, "traceContext must not be null");
        this.rabbitTemplate.setMandatory(true);
    }

    /** Publishes one bounded claimed batch. */
    @Scheduled(fixedDelayString = "${resilientpay.outbox.poll-delay:500ms}")
    public void publishDue() {
        int recovered = repository.recoverDueDead();
        if (recovered > 0) {
            meterRegistry.counter("resilientpay.outbox.recovered").increment(recovered);
        }
        for (OutboxMessage message : repository.claimDue()) {
            publish(message);
        }
    }

    private void publish(OutboxMessage outbox) {
        // Only execution requests carry a dispatch-relative deadline. Avoid opening an otherwise
        // empty database transaction for the four lifecycle events produced by each happy path.
        if (EXECUTION_EVENT.equals(outbox.eventType())) {
            outbox = repository.prepareForPublish(outbox);
        }
        if ((!EXECUTION_EVENT.equals(outbox.eventType())
                        && !ACCEPTED_EVENT.equals(outbox.eventType())
                        && !ORIGINAL_STATUS_EVENT.equals(outbox.eventType()))
                || outbox.schemaVersion() != 1
                || outbox.payload().length > messaging.maximumPayloadBytes()) {
            repository.markFailed(outbox, "UNSUPPORTED_OUTBOX_EVENT", false);
            meterRegistry
                    .counter("resilientpay.outbox.dead", "reason", "invalid-event")
                    .increment();
            return;
        }
        byte[] body = outbox.payload();
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setTimestamp(new Date());
        properties.setMessageId(outbox.id().toString());
        properties.setCorrelationId(outbox.correlationId().toString());
        properties.setType(outbox.eventType());
        properties.setHeader("messageId", outbox.id().toString());
        properties.setHeader("correlationId", outbox.correlationId().toString());
        properties.setHeader("causationId", outbox.causationId().toString());
        properties.setHeader("schemaVersion", Integer.toString(outbox.schemaVersion()));
        properties.setHeader("tenantId", outbox.tenantId().toString());
        properties.setHeader("signatureKeyId", integrity.keyId());
        properties.setHeader(
                "signature",
                integrity.sign(
                        body,
                        outbox.id(),
                        outbox.correlationId(),
                        outbox.causationId(),
                        outbox.tenantId(),
                        Integer.toString(outbox.schemaVersion())));
        CorrelationData correlation = new CorrelationData(outbox.id().toString());
        String routingKey =
                switch (outbox.eventType()) {
                    case ACCEPTED_EVENT -> messaging.acceptedRoutingKey();
                    case ORIGINAL_STATUS_EVENT -> messaging.originalStatusRoutingKey();
                    default -> messaging.executionRoutingKey();
                };
        try (RabbitTraceContext.TraceScope ignored =
                traceContext.startProducer(properties, outbox.eventType(), outbox.traceContext())) {
            rabbitTemplate.send(messaging.exchange(), routingKey, new Message(body, properties), correlation);
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) {
                repository.markFailed(outbox, "BROKER_REJECTED", true);
                meterRegistry
                        .counter("resilientpay.outbox.publish", "outcome", "rejected")
                        .increment();
                return;
            }
            publicationAudit.recordPublished(outbox);
            repository.markPublished(outbox);
            meterRegistry
                    .counter("resilientpay.outbox.publish", "outcome", "confirmed")
                    .increment();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            repository.markFailed(outbox, "PUBLISH_INTERRUPTED", true);
            meterRegistry
                    .counter("resilientpay.outbox.publish", "outcome", "interrupted")
                    .increment();
        } catch (AmqpException | ExecutionException | TimeoutException exception) {
            repository.markFailed(outbox, "BROKER_UNAVAILABLE", true);
            meterRegistry
                    .counter("resilientpay.outbox.publish", "outcome", "unavailable")
                    .increment();
        }
    }
}
