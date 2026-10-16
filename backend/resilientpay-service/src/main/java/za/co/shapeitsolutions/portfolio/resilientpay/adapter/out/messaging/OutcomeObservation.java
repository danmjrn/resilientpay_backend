package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Carries one validated and integrity-checked downstream result into the transactional inbox.
 *
 * @param tenantId canonical owning tenant
 * @param messageId unique outcome message identifier
 * @param correlationId workflow correlation identifier
 * @param causationId execution-request message identifier
 * @param payloadHash exact raw-body SHA-256
 * @param reversalCommandId command identity
 * @param executionAttemptId attempt identity
 * @param outcome stable downstream outcome
 * @param reasonCode optional stable safe reason
 * @param observedAt downstream observation time
 * @author Daniel Jr Nkulu
 */
public record OutcomeObservation(
        TenantId tenantId,
        UUID messageId,
        UUID correlationId,
        UUID causationId,
        String payloadHash,
        UUID reversalCommandId,
        UUID executionAttemptId,
        String outcome,
        String reasonCode,
        Instant observedAt) {
    public OutcomeObservation {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(messageId);
        Objects.requireNonNull(correlationId);
        Objects.requireNonNull(causationId);
        Objects.requireNonNull(payloadHash);
        Objects.requireNonNull(reversalCommandId);
        Objects.requireNonNull(executionAttemptId);
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(observedAt);
    }
}
