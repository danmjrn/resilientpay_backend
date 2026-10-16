package za.co.shapeitsolutions.portfolio.resilientpay.domain.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Provides the stable reversal identity used as the downstream idempotency key across attempts.
 *
 * @param value globally unique reversal value
 * @author Daniel Jr Nkulu
 */
public record ReversalCommandId(UUID value) {
    public ReversalCommandId {
        Objects.requireNonNull(value, "reversalCommandId must not be null");
    }
}
