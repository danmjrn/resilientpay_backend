package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.transaction.OriginalTransactionState;

/**
 * Provides a tokenised public representation of a synthetic original transaction.
 *
 * @param id tenant-scoped internal transaction identity
 * @param transactionToken public synthetic token accepted by reversal commands
 * @param operationType operation category used to select the policy
 * @param amount exact synthetic transaction amount
 * @param currency ISO currency code
 * @param status current original-transaction state
 * @param createdAt UTC creation time
 * @author Daniel Jr Nkulu
 */
public record OriginalTransactionView(
        UUID id,
        String transactionToken,
        OperationType operationType,
        BigDecimal amount,
        String currency,
        OriginalTransactionState status,
        Instant createdAt) {
    public OriginalTransactionView {
        Objects.requireNonNull(id);
        Objects.requireNonNull(transactionToken);
        Objects.requireNonNull(operationType);
        Objects.requireNonNull(amount);
        Objects.requireNonNull(currency);
        Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt);
    }
}
