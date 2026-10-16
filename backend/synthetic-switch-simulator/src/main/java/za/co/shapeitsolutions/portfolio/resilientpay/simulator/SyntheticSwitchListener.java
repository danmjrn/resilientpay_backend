package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Validator;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

/**
 * Verifies signed execution requests, publishes exact signed outcomes, and quarantines safely.
 *
 * @author Daniel Jr Nkulu
 */
@Component
public class SyntheticSwitchListener {
    private static final String MESSAGE_ID = "messageId";
    private static final String CORRELATION_ID = "correlationId";
    private static final String CAUSATION_ID = "causationId";
    private static final String SCHEMA_VERSION = "schemaVersion";
    private static final String TENANT_ID = "tenantId";
    private static final String SIGNATURE_KEY_ID = "signatureKeyId";
    private static final String SIGNATURE = "signature";

    private final Validator validator;
    private final SyntheticSwitchProcessor processor;
    private final ConfirmedRabbitPublisher publisher;
    private final MessageIntegrity integrity;
    private final SimulatorProperties properties;
    private final Clock clock;

    /** Creates the authenticated RabbitMQ inbound adapter. */
    public SyntheticSwitchListener(
            Validator validator,
            SyntheticSwitchProcessor processor,
            ConfirmedRabbitPublisher publisher,
            MessageIntegrity integrity,
            SimulatorProperties properties,
            Clock clock) {
        this.validator = validator;
        this.processor = processor;
        this.publisher = publisher;
        this.integrity = integrity;
        this.properties = properties;
        this.clock = clock;
    }

    /** Consumes one at-least-once execution request and emits a replay-safe outcome. */
    @Observed(name = "SWITCH_EXECUTION", contextualName = "SWITCH_EXECUTION")
    @RabbitListener(queues = RabbitTopology.EXECUTION_QUEUE)
    public void consume(Message message) {
        byte[] body = message.getBody();
        try {
            Envelope envelope = validatedEnvelope(message.getMessageProperties(), body);
            ExecutionRequestedPayload request = readRequest(body);
            ProcessedExecution processed = processor.processForPublication(request);
            OutcomePayload outcome = processed.outcome();
            byte[] outcomeBody = SimulatorJsonCodec.write(outcome);
            publish(
                    RabbitTopology.LIFECYCLE_EXCHANGE,
                    RabbitTopology.OUTCOME_ROUTING_KEY,
                    outcomeBody,
                    processed.outcomeMessageId(),
                    envelope.correlationId(),
                    envelope.messageId(),
                    envelope.tenantId(),
                    envelope.traceContext());
        } catch (InvalidInboundMessageException exception) {
            publishQuarantine(message, exception.safeReasonCode());
        } catch (JacksonException exception) {
            publishQuarantine(message, "INVALID_JSON_PAYLOAD");
        }
    }

    private ExecutionRequestedPayload readRequest(byte[] body) throws JacksonException {
        ExecutionRequestedPayload request = SimulatorJsonCodec.readRequest(body);
        if (!validator.validate(request).isEmpty()) {
            throw new InvalidInboundMessageException("SCHEMA_VALIDATION_FAILED");
        }
        return request;
    }

    private Envelope validatedEnvelope(MessageProperties messageProperties, byte[] body) {
        Map<String, Object> headers = messageProperties.getHeaders();
        UUID messageId = uuid(headers.get(MESSAGE_ID), "INVALID_MESSAGE_ID");
        UUID correlationId = uuid(headers.get(CORRELATION_ID), "INVALID_CORRELATION_ID");
        UUID causationId = uuid(headers.get(CAUSATION_ID), "INVALID_CAUSATION_ID");
        String schemaVersion = text(headers.get(SCHEMA_VERSION));
        String tenantId = text(headers.get(TENANT_ID));
        String keyId = text(headers.get(SIGNATURE_KEY_ID));
        String signature = text(headers.get(SIGNATURE));
        if (!"1".equals(schemaVersion)) {
            throw new InvalidInboundMessageException("UNSUPPORTED_SCHEMA_VERSION");
        }
        if (!isCanonicalTenantId(tenantId)) {
            throw new InvalidInboundMessageException("INVALID_TENANT_SCOPE");
        }
        if (!properties.signatureKeyId().equals(keyId)
                || !integrity.verifies(
                        body,
                        messageId.toString(),
                        correlationId.toString(),
                        causationId.toString(),
                        schemaVersion,
                        tenantId,
                        keyId,
                        signature)) {
            throw new InvalidInboundMessageException("INTEGRITY_CHECK_FAILED");
        }
        return new Envelope(messageId, correlationId, tenantId, W3cTraceContext.from(headers));
    }

    private void publishQuarantine(Message original, String reasonCode) {
        Map<String, Object> headers = original.getMessageProperties().getHeaders();
        UUID originalMessageId = optionalUuid(headers.get(MESSAGE_ID), original.getBody());
        UUID correlationId = optionalUuid(headers.get(CORRELATION_ID), original.getBody());
        String tenantId = text(headers.get(TENANT_ID));
        if (!isCanonicalTenantId(tenantId)) {
            tenantId = "00000000-0000-0000-0000-000000000000";
        }
        QuarantinePayload quarantine = new QuarantinePayload(
                originalMessageId, reasonCode, integrity.digest(original.getBody()), clock.instant());
        try {
            publish(
                    RabbitTopology.QUARANTINE_EXCHANGE,
                    RabbitTopology.QUARANTINE_ROUTING_KEY,
                    SimulatorJsonCodec.write(quarantine),
                    UUID.randomUUID(),
                    correlationId,
                    originalMessageId,
                    tenantId,
                    new W3cTraceContext(null, null));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Safe quarantine serialization failed", exception);
        }
    }

    private void publish(
            String exchange,
            String routingKey,
            byte[] body,
            UUID messageId,
            UUID correlationId,
            UUID causationId,
            String tenantId,
            W3cTraceContext traceContext) {
        MessageProperties messageProperties = MessagePropertiesBuilder.newInstance()
                .setContentType(MediaType.APPLICATION_JSON_VALUE)
                .setMessageId(messageId.toString())
                .setHeader(MESSAGE_ID, messageId.toString())
                .setHeader(CORRELATION_ID, correlationId.toString())
                .setHeader(CAUSATION_ID, causationId.toString())
                .setHeader(SCHEMA_VERSION, "1")
                .setHeader(TENANT_ID, tenantId)
                .setHeader(SIGNATURE_KEY_ID, properties.signatureKeyId())
                .setHeader(
                        SIGNATURE,
                        integrity.sign(
                                body,
                                messageId.toString(),
                                correlationId.toString(),
                                causationId.toString(),
                                "1",
                                tenantId,
                                properties.signatureKeyId()))
                .build();
        traceContext.inject(messageProperties);
        publisher.send(
                exchange,
                routingKey,
                MessageBuilder.withBody(body).andProperties(messageProperties).build(),
                messageId);
    }

    private static UUID uuid(Object value, String errorCode) {
        try {
            String textValue = String.valueOf(value);
            UUID parsed = UUID.fromString(textValue);
            if (!parsed.toString().equals(textValue)) {
                throw new InvalidInboundMessageException(errorCode);
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw new InvalidInboundMessageException(errorCode);
        }
    }

    private static UUID optionalUuid(Object value, byte[] fallbackSeed) {
        if (value == null) {
            return UUID.nameUUIDFromBytes(fallbackSeed);
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return UUID.nameUUIDFromBytes(fallbackSeed);
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean isCanonicalTenantId(String tenantId) {
        try {
            return tenantId != null && UUID.fromString(tenantId).toString().equals(tenantId);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * Carries only validated inbound envelope values needed by the outcome publisher.
     *
     * @param messageId authenticated inbound message identity
     * @param correlationId authenticated end-to-end correlation identity
     * @param tenantId authenticated canonical tenant UUID
     * @param traceContext optional validated W3C trace correlation
     * @author Daniel Jr Nkulu
     */
    private record Envelope(UUID messageId, UUID correlationId, String tenantId, W3cTraceContext traceContext) {}
}
