package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalCommandState;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType;

/**
 * Provides the redacted public representation of a reversal command.
 *
 * @param id tenant-scoped reversal command identity
 * @param originalTransactionToken public synthetic original-transaction token
 * @param operationType operation category governed by the policy snapshot
 * @param amount exact reversal amount
 * @param currency ISO currency code
 * @param status current reversal lifecycle state
 * @param statusReasonCode safe machine-readable state reason, or null
 * @param correlationId end-to-end correlation identity
 * @param attemptCount number of reserved execution attempts
 * @param createdAt UTC command acceptance time
 * @param updatedAt UTC last-transition time
 * @param version optimistic-concurrency version
 * @param allowedActions state- and policy-derived actions available to the caller
 * @author Daniel Jr Nkulu
 */
public record ReversalView(
        UUID id,
        String originalTransactionToken,
        OperationType operationType,
        BigDecimal amount,
        String currency,
        ReversalCommandState status,
        String statusReasonCode,
        UUID correlationId,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt,
        long version,
        List<String> allowedActions) {
    public ReversalView {
        Objects.requireNonNull(id);
        Objects.requireNonNull(originalTransactionToken);
        Objects.requireNonNull(operationType);
        Objects.requireNonNull(amount);
        Objects.requireNonNull(currency);
        Objects.requireNonNull(status);
        Objects.requireNonNull(correlationId);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
        allowedActions = List.copyOf(Objects.requireNonNull(allowedActions));
    }
}
