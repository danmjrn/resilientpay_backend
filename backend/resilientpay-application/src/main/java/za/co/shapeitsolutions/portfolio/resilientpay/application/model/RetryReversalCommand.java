package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

import java.util.Objects;
import java.util.UUID;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.CorrelationId;

/**
 * Requests one bounded retry after confirmed non-execution evidence has been recorded.
 *
 * @param reversalCommandId tenant-scoped reversal identity
 * @param proof safe evidence reference supporting non-execution
 * @param purpose bounded business purpose recorded in audit evidence
 * @param idempotencyKey action-scoped replay key
 * @param correlationId end-to-end correlation identity
 * @author Daniel Jr Nkulu
 */
public record RetryReversalCommand(
        UUID reversalCommandId, String proof, String purpose, String idempotencyKey, CorrelationId correlationId) {
    public RetryReversalCommand {
        Objects.requireNonNull(reversalCommandId);
        proof = Objects.requireNonNull(proof).strip();
        purpose = Objects.requireNonNull(purpose).strip();
        idempotencyKey = Objects.requireNonNull(idempotencyKey).strip();
        Objects.requireNonNull(correlationId);
    }
}
