package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

import java.util.Objects;

/**
 * Returns a durable command representation and whether an earlier safe response was replayed.
 *
 * @param command durable redacted reversal command view
 * @param replayed whether action-scoped idempotency returned an existing result
 * @author Daniel Jr Nkulu
 */
public record AcceptanceResult(ReversalView command, boolean replayed) {
    public AcceptanceResult {
        Objects.requireNonNull(command);
    }
}
