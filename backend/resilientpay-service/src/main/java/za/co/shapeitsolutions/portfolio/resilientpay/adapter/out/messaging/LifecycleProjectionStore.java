package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Deduplicates lifecycle evidence consumed by the bounded internal projection queues.
 *
 * @author Daniel Jr Nkulu
 */
@FunctionalInterface
public interface LifecycleProjectionStore {
    int RECORDED = 1;
    int DUPLICATE = 0;
    int CONFLICT = -1;

    /**
     * Records one integrity-checked lifecycle event.
     *
     * @param tenantId canonical tenant identity
     * @param messageId stable message identity
     * @param payloadHash SHA-256 payload digest
     * @param correlationId workflow correlation identity
     * @param eventType versioned lifecycle event type
     * @param observedAt consumer observation time
     * @return {@link #RECORDED}, {@link #DUPLICATE}, or {@link #CONFLICT}
     */
    int record(
            UUID tenantId,
            UUID messageId,
            String payloadHash,
            UUID correlationId,
            String eventType,
            Instant observedAt);
}
