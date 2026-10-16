package za.co.shapeitsolutions.portfolio.resilientpay.domain.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Provides a type-safe identity for an original transaction and prevents accidental identifier
 * interchange at domain and port boundaries.
 *
 * @param value globally unique transaction value
 * @author Daniel Jr Nkulu
 */
public record OriginalTransactionId(UUID value) {
    public OriginalTransactionId {
        Objects.requireNonNull(value, "originalTransactionId must not be null");
    }
}
