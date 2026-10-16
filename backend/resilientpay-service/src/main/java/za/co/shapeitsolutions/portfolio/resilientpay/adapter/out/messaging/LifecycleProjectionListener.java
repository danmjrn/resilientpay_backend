package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ConditionalOnRuntimeRole;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.MessagingProperties;

/**
 * Drains accepted and original-status lifecycle queues into a deduplicated payload-free projection.
 *
 * @author Daniel Jr Nkulu
 */
@Component
@Profile("!test")
@ConditionalOnRuntimeRole("worker")
public final class LifecycleProjectionListener {
    private static final String ACCEPTED_EVENT = "ReversalAccepted.v1";
    private static final String ORIGINAL_STATUS_EVENT = "OriginalTransactionStatusChanged.v1";
    private static final Set<String> OPERATIONS =
            Set.of("CARD_PURCHASE", "ACCOUNT_TRANSFER", "CASH_WITHDRAWAL", "MERCHANT_PAYOUT");
    private static final Set<String> ORIGINAL_STATES = Set.of("POSTED", "REVERSAL_RESERVED", "REVERSED");

    private final ObjectMapper objectMapper;
    private final MessageIntegrity integrity;
    private final MessagingProperties properties;
    private final LifecycleProjectionStore projection;
    private final PostgresMessageQuarantineRepository quarantine;
    private final MeterRegistry meterRegistry;
    private final RabbitTraceContext traceContext;
    private final Clock clock;

    /** Creates the strict lifecycle projection consumer. */
    public LifecycleProjectionListener(
            ObjectMapper objectMapper,
            MessageIntegrity integrity,
            MessagingProperties properties,
            LifecycleProjectionStore projection,
            PostgresMessageQuarantineRepository quarantine,
            MeterRegistry meterRegistry,
            RabbitTraceContext traceContext,
            Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.integrity = Objects.requireNonNull(integrity, "integrity must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.projection = Objects.requireNonNull(projection, "projection must not be null");
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.traceContext = Objects.requireNonNull(traceContext, "traceContext must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Consumes one signed lifecycle event into the bounded evidence projection. */
    @RabbitListener(
            queues = {"${resilientpay.messaging.accepted-queue}", "${resilientpay.messaging.original-status-queue}"})
    public void consume(Message message) {
        byte[] body = message.getBody();
        try {
            if (!traceContext.isValid(message.getMessageProperties())) {
                throw invalid("INVALID_TRACE_CONTEXT");
            }
            Envelope envelope = validateEnvelope(message, body);
            validatePayload(envelope.eventType(), body);
            try (RabbitTraceContext.TraceScope ignored =
                    traceContext.startConsumer(message.getMessageProperties(), envelope.eventType())) {
                int result = projection.record(
                        envelope.tenantId(),
                        envelope.messageId(),
                        integrity.digest(body),
                        envelope.correlationId(),
                        envelope.eventType(),
                        clock.instant());
                if (result == LifecycleProjectionStore.CONFLICT) {
                    throw invalid("MESSAGE_ID_PAYLOAD_CONFLICT");
                }
                meterRegistry
                        .counter(
                                "resilientpay.lifecycle.projection",
                                "event",
                                envelope.eventType(),
                                "outcome",
                                result == LifecycleProjectionStore.RECORDED ? "recorded" : "duplicate")
                        .increment();
            }
        } catch (InvalidOutcomeMessageException exception) {
            Map<String, Object> headers = message.getMessageProperties().getHeaders();
            quarantine.record(
                    optionalUuid(headers.get("tenantId")),
                    optionalUuidText(headers.get("messageId")),
                    optionalUuid(headers.get("correlationId")),
                    integrity.digest(body),
                    exception.reasonCode());
            meterRegistry
                    .counter("resilientpay.lifecycle.projection", "event", "invalid", "outcome", "quarantined")
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
        String eventType = text(message.getMessageProperties().getType());
        String keyId = text(headers.get("signatureKeyId"));
        String signature = text(headers.get("signature"));
        if (!"1".equals(schemaVersion)
                || (!ACCEPTED_EVENT.equals(eventType) && !ORIGINAL_STATUS_EVENT.equals(eventType))) {
            throw invalid("UNSUPPORTED_SCHEMA_VERSION_OR_EVENT");
        }
        if (!integrity.verify(body, messageId, correlationId, causationId, tenantId, schemaVersion, keyId, signature)) {
            throw invalid("INTEGRITY_CHECK_FAILED");
        }
        return new Envelope(messageId, correlationId, tenantId, eventType);
    }

    @SuppressWarnings("unchecked")
    private void validatePayload(String eventType, byte[] body) {
        try {
            Map<String, Object> payload = objectMapper.readValue(body, Map.class);
            if (ACCEPTED_EVENT.equals(eventType)) {
                if (!payload.keySet()
                                .equals(Set.of("reversalCommandId", "operationType", "policyVersion", "acceptedAt"))
                        || optionalUuid(payload.get("reversalCommandId")) == null
                        || !OPERATIONS.contains(text(payload.get("operationType")))
                        || !(payload.get("policyVersion") instanceof Number version)
                        || version.intValue() < 1) {
                    throw invalid("SCHEMA_VALIDATION_FAILED");
                }
                Instant.parse(text(payload.get("acceptedAt")));
                return;
            }
            Set<String> keys = payload.keySet();
            if (!(keys.equals(Set.of("originalTransactionId", "status", "changedAt"))
                            || keys.equals(Set.of("originalTransactionId", "status", "changedAt", "reversalCommandId")))
                    || optionalUuid(payload.get("originalTransactionId")) == null
                    || !ORIGINAL_STATES.contains(text(payload.get("status")))
                    || (payload.containsKey("reversalCommandId")
                            && optionalUuid(payload.get("reversalCommandId")) == null)) {
                throw invalid("SCHEMA_VALIDATION_FAILED");
            }
            Instant.parse(text(payload.get("changedAt")));
        } catch (JacksonException | DateTimeParseException exception) {
            throw invalid("SCHEMA_VALIDATION_FAILED");
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

    private static String optionalUuidText(Object value) {
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
     * Holds validated lifecycle envelope fields without retaining payloads.
     *
     * @param messageId unique event identity
     * @param correlationId workflow correlation identity
     * @param tenantId canonical tenant identity
     * @param eventType versioned lifecycle event type
     * @author Daniel Jr Nkulu
     */
    private record Envelope(UUID messageId, UUID correlationId, UUID tenantId, String eventType) {}
}
