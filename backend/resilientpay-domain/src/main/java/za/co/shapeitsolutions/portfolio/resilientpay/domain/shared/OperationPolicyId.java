package za.co.shapeitsolutions.portfolio.resilientpay.domain.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Provides a type-safe identity for a versioned operation policy at the domain boundary.
 *
 * @param value globally unique policy value
 * @author Daniel Jr Nkulu
 */
public record OperationPolicyId(UUID value) {
    public OperationPolicyId {
        Objects.requireNonNull(value, "operationPolicyId must not be null");
    }
}
