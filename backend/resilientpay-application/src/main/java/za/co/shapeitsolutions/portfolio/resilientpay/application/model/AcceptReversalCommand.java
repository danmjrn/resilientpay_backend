package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

import java.util.Objects;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalReason;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.CorrelationId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.Money;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType;

/**
 * Defines a validated, tenant-neutral request to accept one full reversal.
 *
 * @param originalTransactionToken tokenised synthetic original reference
 * @param operationType expected original operation type
 * @param money full reversal amount and currency
 * @param reason bounded synthetic reason
 * @param demonstrationFailure local-only failure scenario
 * @param idempotencyKey opaque mutation identity
 * @param correlationId safe cross-boundary correlation identity
 * @author Daniel Jr Nkulu
 */
public record AcceptReversalCommand(
        String originalTransactionToken,
        OperationType operationType,
        Money money,
        ReversalReason reason,
        DemonstrationFailure demonstrationFailure,
        String idempotencyKey,
        CorrelationId correlationId) {
    public AcceptReversalCommand {
        originalTransactionToken =
                Objects.requireNonNull(originalTransactionToken).strip();
        Objects.requireNonNull(operationType);
        Objects.requireNonNull(money);
        Objects.requireNonNull(reason);
        demonstrationFailure = demonstrationFailure == null ? DemonstrationFailure.NONE : demonstrationFailure;
        idempotencyKey = Objects.requireNonNull(idempotencyKey).strip();
        Objects.requireNonNull(correlationId);
    }
}
