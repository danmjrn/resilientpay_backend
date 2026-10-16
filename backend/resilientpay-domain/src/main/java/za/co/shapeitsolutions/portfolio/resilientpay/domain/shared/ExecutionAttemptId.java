package za.co.shapeitsolutions.portfolio.resilientpay.domain.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Provides a unique identity for one immutable downstream execution attempt.
 *
 * @param value globally unique attempt value
 * @author Daniel Jr Nkulu
 */
public record ExecutionAttemptId(UUID value) {
    public ExecutionAttemptId {
        Objects.requireNonNull(value, "executionAttemptId must not be null");
    }
}
