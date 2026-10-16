package za.co.shapeitsolutions.portfolio.resilientpay.domain.policy;

import java.time.Duration;
import java.util.Objects;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainException;

/**
 * Captures bounded retry and result-deadline rules for an operation-policy snapshot. Retries remain
 * subject to confirmed non-execution; these numeric limits never make an ambiguous retry safe.
 *
 * @param maximumExecutionAttempts total execution attempts including the first
 * @param resultTimeout duration after dispatch before the outcome becomes ambiguous
 * @param initialBackoff initial retry delay
 * @param maximumBackoff upper retry delay bound
 * @author Daniel Jr Nkulu
 */
public record RetryPolicy(
        int maximumExecutionAttempts, Duration resultTimeout, Duration initialBackoff, Duration maximumBackoff) {
    public RetryPolicy {
        Objects.requireNonNull(resultTimeout, "resultTimeout must not be null");
        Objects.requireNonNull(initialBackoff, "initialBackoff must not be null");
        Objects.requireNonNull(maximumBackoff, "maximumBackoff must not be null");
        if (maximumExecutionAttempts < 1 || maximumExecutionAttempts > 5) {
            throw new DomainException(
                    DomainErrorCode.INVALID_VALUE, "maximumExecutionAttempts must be between 1 and 5");
        }
        if (resultTimeout.isZero() || resultTimeout.isNegative()) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "resultTimeout must be positive");
        }
        if (initialBackoff.isNegative() || maximumBackoff.isNegative()) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "backoff durations must not be negative");
        }
        if (maximumBackoff.compareTo(initialBackoff) < 0) {
            throw new DomainException(
                    DomainErrorCode.INVALID_VALUE, "maximumBackoff must not be shorter than initialBackoff");
        }
    }

    /**
     * Determines whether another execution attempt remains within the policy bound.
     *
     * @param completedAttemptNumber one-based number of the completed attempt
     * @return true when a later attempt is permitted
     */
    public boolean permitsAttemptAfter(int completedAttemptNumber) {
        return completedAttemptNumber >= 1 && completedAttemptNumber < maximumExecutionAttempts;
    }
}
