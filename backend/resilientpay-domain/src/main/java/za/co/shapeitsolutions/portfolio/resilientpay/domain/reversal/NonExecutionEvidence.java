package za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal;

import java.time.Instant;
import java.util.Objects;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainException;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ExecutionAttemptId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ReversalCommandId;

/**
 * Represents the only evidence capable of authorising a retry: a terminal attempt that proves the
 * downstream action did not execute. Instances can only be derived from such an attempt.
 *
 * @author Daniel Jr Nkulu
 */
public final class NonExecutionEvidence {
    private final ExecutionAttemptId attemptId;
    private final ReversalCommandId reversalCommandId;
    private final int attemptNumber;
    private final String proofCode;
    private final Instant observedAt;

    private NonExecutionEvidence(
            ExecutionAttemptId attemptId,
            ReversalCommandId reversalCommandId,
            int attemptNumber,
            String proofCode,
            Instant observedAt) {
        this.attemptId = Objects.requireNonNull(attemptId);
        this.reversalCommandId = Objects.requireNonNull(reversalCommandId);
        this.attemptNumber = attemptNumber;
        this.proofCode = Objects.requireNonNull(proofCode);
        this.observedAt = Objects.requireNonNull(observedAt);
    }

    /** Derives safe-retry evidence only from a confirmed non-executed attempt. */
    public static NonExecutionEvidence from(ExecutionAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        if (attempt.state() != ExecutionAttemptState.CONFIRMED_NOT_EXECUTED) {
            throw new DomainException(
                    DomainErrorCode.RETRY_NOT_PROVEN_SAFE, "safe retry requires confirmed non-execution evidence");
        }
        return new NonExecutionEvidence(
                attempt.id(),
                attempt.reversalCommandId(),
                attempt.attemptNumber(),
                attempt.failureCode().orElseThrow(),
                attempt.completedAt().orElseThrow());
    }

    /** Returns the source execution attempt identity. */
    public ExecutionAttemptId attemptId() {
        return attemptId;
    }

    /** Returns the reversal identity proven not to have executed. */
    public ReversalCommandId reversalCommandId() {
        return reversalCommandId;
    }

    /** Returns the completed attempt number. */
    public int attemptNumber() {
        return attemptNumber;
    }

    /** Returns the stable proof code. */
    public String proofCode() {
        return proofCode;
    }

    /** Returns the time the evidence was observed. */
    public Instant observedAt() {
        return observedAt;
    }
}
