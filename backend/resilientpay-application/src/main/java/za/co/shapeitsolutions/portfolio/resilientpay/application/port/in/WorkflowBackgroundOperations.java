package za.co.shapeitsolutions.portfolio.resilientpay.application.port.in;

import java.time.Instant;
import java.util.UUID;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OutcomeObservation;

/**
 * Owns transactional entry points for validated message delivery and bounded deadline scanning.
 * A successful return means durable processing committed before an adapter may acknowledge work.
 *
 * @author Daniel Jr Nkulu
 */
public interface WorkflowBackgroundOperations {
    /** Atomically applies a validated outcome with its inbox marker. */
    void processOutcome(OutcomeObservation observation);

    /** Atomically records a payload-free lifecycle projection. */
    int recordLifecycle(
            UUID tenantId,
            UUID messageId,
            String payloadHash,
            UUID correlationId,
            String eventType,
            Instant observedAt);

    /** Commits invalid-message evidence before the transport acknowledges rejection. */
    void quarantine(UUID tenantId, String messageId, UUID correlationId, String payloadDigest, String errorCode);

    /** Commits a bounded batch of overdue-attempt reconciliation transitions. */
    int reconcileOverdueAttempts(int limit);
}
