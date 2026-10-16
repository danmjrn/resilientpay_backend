package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence.PostgresOrchestrationStore;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ConditionalOnRuntimeRole;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.MessagingProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Validates signed outcome envelopes and applies them through the transactional inbox boundary.
 * Permanent wire failures are acknowledged only after digest-only PostgreSQL quarantine succeeds.
 *
 * @author Daniel Jr Nkulu
 */
@Component
@Profile("!test")
@ConditionalOnRuntimeRole("worker")
public final class ReversalOutcomeListener {
    private static final Set<String> OUTCOMES = Set.of("EXECUTED", "CONFIRMED_NOT_EXECUTED", "REJECTED", "AMBIGUOUS");

    private final ObjectMapper objectMapper;
    private final MessageIntegrity integrity;
    private final MessagingProperties properties;
    private final PostgresOrchestrationStore store;
    private final PostgresMessageQuarantineRepository quarantine;
    private final MeterRegistry meterRegistry;
    private final RabbitTraceContext traceContext;

    /**
     * Creates the strict outcome consumer.
     *
     * @param objectMapper strict JSON deserializer
     * @param integrity envelope and body integrity boundary
     * @param properties wire limits and active key
     * @param store transactional inbox and workflow adapter
     * @param quarantine digest-only invalid-message sink
     * @param meterRegistry low-cardinality outcome metrics
     * @param traceContext bounded W3C message trace propagation
     */
    public ReversalOutcomeListener(
            ObjectMapper objectMapper,
            MessageIntegrity integrity,
            MessagingProperties properties,
            PostgresOrchestrationStore store,
            PostgresMessageQuarantineRepository quarantine,
            MeterRegistry meterRegistry,
            RabbitTraceContext traceContext) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.integrity = Objects.requireNonNull(integrity, "integrity must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.traceContext = Objects.requireNonNull(traceContext, "traceContext must not be null");
    }

    /**
     * Consumes one at-least-once signed outcome.
     *
     * @param message raw RabbitMQ delivery
     */
    @RabbitListener(queues = "${resilientpay.messaging.outcome-queue}")
    public void consume(Message message) {
        byte[] body = message.getBody();
        try {
            if (!traceContext.isValid(message.getMessageProperties())) {
                throw invalid("INVALID_TRACE_CONTEXT");
            }
            try (RabbitTraceContext.TraceScope ignored =
                    traceContext.startConsumer(message.getMessageProperties(), "ReversalOutcomeObserved.v1")) {
                Envelope envelope = validateEnvelope(message, body);
                ReversalOutcomePayload payload = readPayload(body);
                traceContext.recordValidatedCorrelation(envelope.correlationId());
                store.processOutcome(new OutcomeObservation(
                        new TenantId(envelope.tenantId()),
                        envelope.messageId(),
                        envelope.correlationId(),
                        envelope.causationId(),
                        integrity.digest(body),
                        payload.reversalCommandId(),
                        payload.executionAttemptId(),
                        payload.outcome(),
                        payload.reasonCode(),
                        payload.observedAt()));
                meterRegistry
                        .counter("resilientpay.outcome.consumed", "outcome", payload.outcome())
                        .increment();
            }
        } catch (InvalidOutcomeMessageException exception) {
            Map<String, Object> headers = message.getMessageProperties().getHeaders();
            quarantine.record(
                    optionalUuid(headers.get("tenantId")),
                    optionalMessageId(headers.get("messageId")),
                    optionalUuid(headers.get("correlationId")),
                    integrity.digest(body),
                    exception.reasonCode());
            meterRegistry
                    .counter("resilientpay.outcome.rejected", "reason", exception.reasonCode())
                    .increment();
        }
    }

    private Envelope validateEnvelope(Message message, byte[] body) {
        if (body.length == 0 || body.length > properties.maximumPayloadBytes()) {
            throw invalid("PAYLOAD_SIZE_INVALID");
        }
        Map<String, Object> headers = message.getMessageProperties().getHeaders();
        UUID messageId = requiredUuid(headers.get("messageId"), "INVALID_MESSAGE_ID");
        UUID correlationId = requiredUuid(headers.get("correlationId"), "INVALID_CORRELATION_ID");
        UUID causationId = requiredUuid(headers.get("causationId"), "INVALID_CAUSATION_ID");
        UUID tenantId = requiredUuid(headers.get("tenantId"), "INVALID_TENANT_SCOPE");
        String schemaVersion = text(headers.get("schemaVersion"));
        String keyId = text(headers.get("signatureKeyId"));
        String signature = text(headers.get("signature"));
        if (!"1".equals(schemaVersion)) {
            throw invalid("UNSUPPORTED_SCHEMA_VERSION");
        }
        if (!integrity.verify(body, messageId, correlationId, causationId, tenantId, schemaVersion, keyId, signature)) {
            throw invalid("INTEGRITY_CHECK_FAILED");
        }
        return new Envelope(messageId, correlationId, causationId, tenantId);
    }

    private ReversalOutcomePayload readPayload(byte[] body) {
        try {
            ReversalOutcomePayload payload = objectMapper.readValue(body, ReversalOutcomePayload.class);
            if (payload.reversalCommandId() == null
                    || payload.executionAttemptId() == null
                    || payload.observedAt() == null
                    || !OUTCOMES.contains(payload.outcome())
                    || (payload.reasonCode() != null && !payload.reasonCode().matches("[A-Z][A-Z0-9_]{2,63}"))) {
                throw invalid("SCHEMA_VALIDATION_FAILED");
            }
            return payload;
        } catch (JacksonException exception) {
            throw invalid("INVALID_JSON_PAYLOAD");
        }
    }

    private static UUID requiredUuid(Object value, String reason) {
        UUID parsed = optionalUuid(value);
        if (parsed == null || !parsed.toString().equals(text(value))) {
            throw invalid(reason);
        }
        return parsed;
    }

    private static UUID optionalUuid(Object value) {
        try {
            return UUID.fromString(text(value));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String optionalMessageId(Object value) {
        UUID parsed = optionalUuid(value);
        return parsed == null ? null : parsed.toString();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static InvalidOutcomeMessageException invalid(String reasonCode) {
        return new InvalidOutcomeMessageException(reasonCode);
    }

    /**
     * Holds one canonical validated envelope.
     *
     * @param messageId unique outcome identity
     * @param correlationId workflow correlation identity
     * @param causationId execution request identity
     * @param tenantId canonical tenant identity
     * @author Daniel Jr Nkulu
     */
    private record Envelope(UUID messageId, UUID correlationId, UUID causationId, UUID tenantId) {}
}
