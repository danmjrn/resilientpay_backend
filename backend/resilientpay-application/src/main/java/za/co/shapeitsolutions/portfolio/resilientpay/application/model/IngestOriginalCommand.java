package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

import java.util.Objects;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.CorrelationId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.Money;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType;

/**
 * Defines a synthetic original-transaction ingestion request.
 *
 * @param transactionToken public synthetic transaction token
 * @param operationType operation category used to select reversal policy
 * @param money exact amount and currency
 * @param idempotencyKey action-scoped replay key
 * @param correlationId end-to-end correlation identity
 * @author Daniel Jr Nkulu
 */
public record IngestOriginalCommand(
        String transactionToken,
        OperationType operationType,
        Money money,
        String idempotencyKey,
        CorrelationId correlationId) {
    public IngestOriginalCommand {
        transactionToken = Objects.requireNonNull(transactionToken).strip();
        Objects.requireNonNull(operationType);
        Objects.requireNonNull(money);
        idempotencyKey = Objects.requireNonNull(idempotencyKey).strip();
        Objects.requireNonNull(correlationId);
    }
}
