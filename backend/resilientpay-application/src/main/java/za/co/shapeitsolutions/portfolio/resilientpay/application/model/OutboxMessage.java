package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Carries one leased transactional-outbox event to the RabbitMQ publisher.
 *
 * @param id stable message identifier
 * @param tenantId canonical tenant identifier
 * @param aggregateId owning reversal command identifier
 * @param eventType versioned event type
 * @param schemaVersion positive wire schema version
 * @param correlationId workflow correlation identifier
 * @param causationId preceding workflow identifier
 * @param payload exact JSON bytes to publish and sign
 * @param attemptCount current bounded publication attempt
 * @param traceContext durable W3C request context, possibly absent
 * @author Daniel Jr Nkulu
 */
public record OutboxMessage(
        UUID id,
        UUID tenantId,
        UUID aggregateId,
        String eventType,
        int schemaVersion,
        UUID correlationId,
        UUID causationId,
        byte[] payload,
        int attemptCount,
        OutboxTraceContext traceContext) {
    public OutboxMessage {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(aggregateId);
        Objects.requireNonNull(eventType);
        Objects.requireNonNull(correlationId);
        Objects.requireNonNull(causationId);
        Objects.requireNonNull(traceContext);
        payload = Arrays.copyOf(Objects.requireNonNull(payload), payload.length);
        if (schemaVersion < 1 || attemptCount < 1) {
            throw new IllegalArgumentException("Outbox schema and attempt must be positive");
        }
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
