package za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainException;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ExecutionAttemptId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.InvalidStateTransitionException;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ReversalCommandId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Models a uniquely identified, one-shot request to the synthetic payment switch. Results are
 * immutable terminal evidence; timeouts become ambiguous and do not imply non-execution.
 *
 * @author Daniel Jr Nkulu
 */
public final class ExecutionAttempt {
    private final ExecutionAttemptId id;
    private final TenantId tenantId;
    private final ReversalCommandId reversalCommandId;
    private final int attemptNumber;
    private final ExecutionAttemptState state;
    private final String failureCode;
    private final Instant createdAt;
    private final Instant dispatchedAt;
    private final Instant resultDeadline;
    private final Instant completedAt;
    private final long version;

    private ExecutionAttempt(
            ExecutionAttemptId id,
            TenantId tenantId,
            ReversalCommandId reversalCommandId,
            int attemptNumber,
            ExecutionAttemptState state,
            String failureCode,
            Instant createdAt,
            Instant dispatchedAt,
            Instant resultDeadline,
            Instant completedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.reversalCommandId = Objects.requireNonNull(reversalCommandId, "reversalCommandId must not be null");
        if (attemptNumber < 1 || attemptNumber > 5) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "attemptNumber must be between 1 and 5");
        }
        this.attemptNumber = attemptNumber;
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.failureCode = normaliseOptionalCode(failureCode);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.dispatchedAt = dispatchedAt;
        this.resultDeadline = Objects.requireNonNull(resultDeadline, "resultDeadline must not be null");
        this.completedAt = completedAt;
        if (!resultDeadline.isAfter(createdAt) || version < 0) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "attempt deadline and version must be valid");
        }
        validateLifecycleTimes(state, createdAt, dispatchedAt, resultDeadline, completedAt);
        this.version = version;
    }

    /** Creates a new, not-yet-dispatched attempt with a bounded result deadline. */
    public static ExecutionAttempt create(
            ExecutionAttemptId id,
            TenantId tenantId,
            ReversalCommandId reversalCommandId,
            int attemptNumber,
            Instant createdAt,
            Instant resultDeadline) {
        return new ExecutionAttempt(
                id,
                tenantId,
                reversalCommandId,
                attemptNumber,
                ExecutionAttemptState.CREATED,
                null,
                createdAt,
                null,
                resultDeadline,
                null,
                0);
    }

    /** Reconstitutes a persisted attempt while preserving its evidence state. */
    public static ExecutionAttempt restore(
            ExecutionAttemptId id,
            TenantId tenantId,
            ReversalCommandId reversalCommandId,
            int attemptNumber,
            ExecutionAttemptState state,
            String failureCode,
            Instant createdAt,
            Instant dispatchedAt,
            Instant resultDeadline,
            Instant completedAt,
            long version) {
        return new ExecutionAttempt(
                id,
                tenantId,
                reversalCommandId,
                attemptNumber,
                state,
                failureCode,
                createdAt,
                dispatchedAt,
                resultDeadline,
                completedAt,
                version);
    }

    /** Marks the immutable attempt as dispatched once. */
    public ExecutionAttempt dispatch(Instant at) {
        requireState(ExecutionAttemptState.CREATED, "dispatch");
        requireNotBefore(at, createdAt);
        return copy(ExecutionAttemptState.DISPATCHED, null, at, null);
    }

    /** Records definitive downstream execution evidence. */
    public ExecutionAttempt confirmExecuted(Instant at) {
        requireState(ExecutionAttemptState.DISPATCHED, "confirmExecuted");
        requireNotBefore(at, dispatchedAt);
        return copy(ExecutionAttemptState.CONFIRMED_EXECUTED, null, dispatchedAt, at);
    }

    /** Records definitive proof that the switch did not execute the financial action. */
    public ExecutionAttempt confirmNotExecuted(String proofCode, Instant at) {
        requireState(ExecutionAttemptState.DISPATCHED, "confirmNotExecuted");
        requireNotBefore(at, dispatchedAt);
        return copy(ExecutionAttemptState.CONFIRMED_NOT_EXECUTED, requireCode(proofCode), dispatchedAt, at);
    }

    /** Records an uncertain outcome caused by a lost or conflicting result. */
    public ExecutionAttempt markAmbiguous(String reasonCode, Instant at) {
        requireState(ExecutionAttemptState.DISPATCHED, "markAmbiguous");
        requireNotBefore(at, dispatchedAt);
        return copy(ExecutionAttemptState.AMBIGUOUS, requireCode(reasonCode), dispatchedAt, at);
    }

    /** Converts a passed result deadline into ambiguity rather than unsafe non-execution. */
    public ExecutionAttempt markTimedOut(Instant at) {
        requireState(ExecutionAttemptState.DISPATCHED, "markTimedOut");
        Objects.requireNonNull(at, "transition time must not be null");
        if (at.isBefore(resultDeadline)) {
            throw new DomainException(
                    DomainErrorCode.INVALID_VALUE, "attempt cannot time out before its result deadline");
        }
        return copy(ExecutionAttemptState.AMBIGUOUS, "RESULT_TIMEOUT", dispatchedAt, at);
    }

    /** Returns the unique attempt identity. */
    public ExecutionAttemptId id() {
        return id;
    }

    /** Returns the owning tenant. */
    public TenantId tenantId() {
        return tenantId;
    }

    /** Returns the stable parent reversal identity. */
    public ReversalCommandId reversalCommandId() {
        return reversalCommandId;
    }

    /** Returns the one-based bounded attempt number. */
    public int attemptNumber() {
        return attemptNumber;
    }

    /** Returns the evidence lifecycle state. */
    public ExecutionAttemptState state() {
        return state;
    }

    /** Returns a safe failure or ambiguity code when present. */
    public Optional<String> failureCode() {
        return Optional.ofNullable(failureCode);
    }

    /** Returns the attempt creation time. */
    public Instant createdAt() {
        return createdAt;
    }

    /** Returns the optional dispatch time. */
    public Optional<Instant> dispatchedAt() {
        return Optional.ofNullable(dispatchedAt);
    }

    /** Returns the absolute result deadline. */
    public Instant resultDeadline() {
        return resultDeadline;
    }

    /** Returns the optional terminal evidence time. */
    public Optional<Instant> completedAt() {
        return Optional.ofNullable(completedAt);
    }

    /** Returns the optimistic aggregate version. */
    public long version() {
        return version;
    }

    private ExecutionAttempt copy(
            ExecutionAttemptState target,
            String targetFailureCode,
            Instant targetDispatchedAt,
            Instant targetCompletedAt) {
        return new ExecutionAttempt(
                id,
                tenantId,
                reversalCommandId,
                attemptNumber,
                target,
                targetFailureCode,
                createdAt,
                targetDispatchedAt,
                resultDeadline,
                targetCompletedAt,
                version + 1);
    }

    private void requireState(ExecutionAttemptState expected, String transition) {
        if (state != expected) {
            throw new InvalidStateTransitionException("ExecutionAttempt", state, transition);
        }
    }

    private static void requireNotBefore(Instant candidate, Instant lowerBound) {
        Objects.requireNonNull(candidate, "transition time must not be null");
        if (candidate.isBefore(lowerBound)) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "attempt transition time must not move backwards");
        }
    }

    private static String requireCode(String value) {
        String code = Objects.requireNonNull(value, "code must not be null").strip();
        if (!code.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "evidence code must be a stable uppercase code");
        }
        return code;
    }

    private static String normaliseOptionalCode(String value) {
        return value == null ? null : requireCode(value);
    }

    private static void validateLifecycleTimes(
            ExecutionAttemptState state,
            Instant createdAt,
            Instant dispatchedAt,
            Instant resultDeadline,
            Instant completedAt) {
        if (state == ExecutionAttemptState.CREATED && (dispatchedAt != null || completedAt != null)) {
            throw new DomainException(
                    DomainErrorCode.INVALID_VALUE, "created attempt cannot contain result timestamps");
        }
        if (state != ExecutionAttemptState.CREATED && dispatchedAt == null) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "dispatched attempt requires dispatchedAt");
        }
        if (dispatchedAt != null && (dispatchedAt.isBefore(createdAt) || dispatchedAt.isAfter(resultDeadline))) {
            throw new DomainException(
                    DomainErrorCode.INVALID_VALUE,
                    "attempt dispatch must fall between creation and the result deadline");
        }
        if (state != ExecutionAttemptState.CREATED
                && state != ExecutionAttemptState.DISPATCHED
                && completedAt == null) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "terminal attempt requires completedAt");
        }
        if (completedAt != null && completedAt.isBefore(dispatchedAt)) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "attempt completion must not precede dispatch");
        }
    }
}
