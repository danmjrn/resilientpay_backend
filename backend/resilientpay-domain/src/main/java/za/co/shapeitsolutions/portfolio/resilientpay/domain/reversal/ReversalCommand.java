package za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.policy.OperationPolicy;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.policy.RetryPolicy;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.CorrelationId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainException;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.InvalidStateTransitionException;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.Money;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationPolicyId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OriginalTransactionId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ReversalCommandId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.transaction.OriginalTransaction;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.transaction.OriginalTransactionState;

/**
 * Coordinates the domain lifecycle of one full reversal without invoking external systems. It
 * snapshots the governing policy, accepts only an eligible original transaction, and separates
 * proven-safe retry from ambiguous execution that requires reconciliation.
 *
 * @author Daniel Jr Nkulu
 */
public final class ReversalCommand {
    private final ReversalCommandId id;
    private final TenantId tenantId;
    private final OriginalTransactionId originalTransactionId;
    private final OperationPolicyId operationPolicyId;
    private final int operationPolicyVersion;
    private final RetryPolicy retryPolicySnapshot;
    private final OperationType operationType;
    private final Money money;
    private final ReversalReason reason;
    private final ReversalCommandState state;
    private final String failureCode;
    private final String idempotencyKeyHash;
    private final String requestHash;
    private final CorrelationId correlationId;
    private final Instant acceptedAt;
    private final Instant updatedAt;
    private final long version;

    private ReversalCommand(
            ReversalCommandId id,
            TenantId tenantId,
            OriginalTransactionId originalTransactionId,
            OperationPolicyId operationPolicyId,
            int operationPolicyVersion,
            RetryPolicy retryPolicySnapshot,
            OperationType operationType,
            Money money,
            ReversalReason reason,
            ReversalCommandState state,
            String failureCode,
            String idempotencyKeyHash,
            String requestHash,
            CorrelationId correlationId,
            Instant acceptedAt,
            Instant updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.originalTransactionId =
                Objects.requireNonNull(originalTransactionId, "originalTransactionId must not be null");
        this.operationPolicyId = Objects.requireNonNull(operationPolicyId, "operationPolicyId must not be null");
        if (operationPolicyVersion < 1) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "operationPolicyVersion must be positive");
        }
        this.operationPolicyVersion = operationPolicyVersion;
        this.retryPolicySnapshot = Objects.requireNonNull(retryPolicySnapshot, "retryPolicySnapshot must not be null");
        this.operationType = Objects.requireNonNull(operationType, "operationType must not be null");
        this.money = Objects.requireNonNull(money, "money must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.failureCode = normaliseOptionalCode(failureCode);
        this.idempotencyKeyHash = requireHash(idempotencyKeyHash, "idempotencyKeyHash");
        this.requestHash = requireHash(requestHash, "requestHash");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId must not be null");
        this.acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(acceptedAt) || version < 0) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "reversal chronology and version must be valid");
        }
        this.version = version;
    }

    /**
     * Accepts a full reversal after validating tenant, operation, value, original state and active
     * policy. The caller persists the returned command and reserved original atomically.
     */
    public static ReversalCommand accept(
            ReversalCommandId id,
            OriginalTransaction original,
            OperationPolicy policy,
            Money requestedMoney,
            ReversalReason reason,
            String idempotencyKeyHash,
            String requestHash,
            CorrelationId correlationId,
            Instant acceptedAt) {
        Objects.requireNonNull(original, "original must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(requestedMoney, "requestedMoney must not be null");
        if (!original.tenantId().equals(policy.tenantId())) {
            throw new DomainException(
                    DomainErrorCode.TENANT_SCOPE_MISMATCH,
                    "original transaction and policy must belong to the same tenant");
        }
        if (original.operationType() != policy.operationType()) {
            throw new DomainException(
                    DomainErrorCode.INVALID_VALUE, "operation policy must govern the original transaction type");
        }
        if (original.state() != OriginalTransactionState.POSTED) {
            throw new DomainException(
                    DomainErrorCode.INVALID_VALUE, "original transaction is not available for reversal");
        }
        if (!original.money().sameValueAs(requestedMoney)) {
            throw new DomainException(
                    DomainErrorCode.MONEY_MISMATCH,
                    "MVP reversal amount and currency must match the original transaction");
        }
        if (!policy.isEffectiveAt(acceptedAt)) {
            throw new DomainException(
                    DomainErrorCode.POLICY_NOT_ACTIVE,
                    "an active effective operation policy is required for acceptance");
        }
        return new ReversalCommand(
                id,
                original.tenantId(),
                original.id(),
                policy.id(),
                policy.policyVersion(),
                policy.retryPolicy(),
                original.operationType(),
                requestedMoney,
                reason,
                ReversalCommandState.ACCEPTED,
                null,
                idempotencyKeyHash,
                requestHash,
                correlationId,
                acceptedAt,
                acceptedAt,
                0);
    }

    /** Reconstitutes a persisted command and its acceptance-time policy snapshot. */
    public static ReversalCommand restore(
            ReversalCommandId id,
            TenantId tenantId,
            OriginalTransactionId originalTransactionId,
            OperationPolicyId operationPolicyId,
            int operationPolicyVersion,
            RetryPolicy retryPolicySnapshot,
            OperationType operationType,
            Money money,
            ReversalReason reason,
            ReversalCommandState state,
            String failureCode,
            String idempotencyKeyHash,
            String requestHash,
            CorrelationId correlationId,
            Instant acceptedAt,
            Instant updatedAt,
            long version) {
        return new ReversalCommand(
                id,
                tenantId,
                originalTransactionId,
                operationPolicyId,
                operationPolicyVersion,
                retryPolicySnapshot,
                operationType,
                money,
                reason,
                state,
                failureCode,
                idempotencyKeyHash,
                requestHash,
                correlationId,
                acceptedAt,
                updatedAt,
                version);
    }

    /** Records deterministic policy-based routing. */
    public ReversalCommand route(Instant at) {
        requireState(ReversalCommandState.ACCEPTED, "route");
        return transitionTo(ReversalCommandState.ROUTED, null, at);
    }

    /** Marks a routed command ready for an execution attempt. */
    public ReversalCommand markExecutionPending(Instant at) {
        requireState(ReversalCommandState.ROUTED, "markExecutionPending");
        return transitionTo(ReversalCommandState.EXECUTION_PENDING, null, at);
    }

    /** Marks a pending command as having one active downstream attempt. */
    public ReversalCommand startExecution(Instant at) {
        requireState(ReversalCommandState.EXECUTION_PENDING, "startExecution");
        return transitionTo(ReversalCommandState.EXECUTING, null, at);
    }

    /** Completes a command only on definitive executed evidence. */
    public ReversalCommand complete(ExecutionAttempt attempt, Instant at) {
        requireState(ReversalCommandState.EXECUTING, "complete");
        requireMatchingAttempt(attempt, ExecutionAttemptState.CONFIRMED_EXECUTED);
        return transitionTo(ReversalCommandState.COMPLETED, null, at);
    }

    /**
     * Schedules a bounded retry only from explicit non-execution evidence tied to this command and
     * covered by the acceptance-time policy snapshot.
     */
    public ReversalCommand scheduleSafeRetry(NonExecutionEvidence evidence, Instant at) {
        requireState(ReversalCommandState.EXECUTING, "scheduleSafeRetry");
        Objects.requireNonNull(evidence, "evidence must not be null");
        if (!id.equals(evidence.reversalCommandId())) {
            throw new DomainException(
                    DomainErrorCode.RETRY_NOT_PROVEN_SAFE, "non-execution evidence belongs to a different reversal");
        }
        if (!retryPolicySnapshot.permitsAttemptAfter(evidence.attemptNumber())) {
            throw new DomainException(
                    DomainErrorCode.ATTEMPT_LIMIT_EXCEEDED,
                    "operation policy does not permit another execution attempt");
        }
        if (evidence.observedAt().isBefore(updatedAt) || evidence.observedAt().isAfter(at)) {
            throw new DomainException(
                    DomainErrorCode.RETRY_NOT_PROVEN_SAFE,
                    "non-execution evidence must be observed during the active execution window");
        }
        return transitionTo(ReversalCommandState.RETRY_SCHEDULED, evidence.proofCode(), at);
    }

    /** Releases a due safe retry back to the execution queue. */
    public ReversalCommand releaseScheduledRetry(Instant at) {
        requireState(ReversalCommandState.RETRY_SCHEDULED, "releaseScheduledRetry");
        return transitionTo(ReversalCommandState.EXECUTION_PENDING, null, at);
    }

    /** Moves a command with an unknown execution result to reconciliation without retrying it. */
    public ReversalCommand requireReconciliation(ExecutionAttempt attempt, Instant at) {
        requireState(ReversalCommandState.EXECUTING, "requireReconciliation");
        requireMatchingAttempt(attempt, ExecutionAttemptState.AMBIGUOUS);
        return transitionTo(
                ReversalCommandState.RECONCILIATION_REQUIRED,
                attempt.failureCode().orElse("AMBIGUOUS_OUTCOME"),
                at);
    }

    /** Rejects work only before downstream execution has begun. */
    public ReversalCommand reject(String reasonCode, Instant at) {
        if (state != ReversalCommandState.ACCEPTED
                && state != ReversalCommandState.ROUTED
                && state != ReversalCommandState.EXECUTION_PENDING
                && state != ReversalCommandState.RETRY_SCHEDULED) {
            throw new InvalidStateTransitionException("ReversalCommand", state, "reject");
        }
        return transitionTo(ReversalCommandState.REJECTED, requireCode(reasonCode), at);
    }

    /** Rejects an executing command only when evidence proves that action did not execute. */
    public ReversalCommand rejectAfterNonExecution(NonExecutionEvidence evidence, String reasonCode, Instant at) {
        requireState(ReversalCommandState.EXECUTING, "rejectAfterNonExecution");
        Objects.requireNonNull(evidence, "evidence must not be null");
        if (!id.equals(evidence.reversalCommandId())
                || evidence.observedAt().isBefore(updatedAt)
                || evidence.observedAt().isAfter(at)) {
            throw new DomainException(
                    DomainErrorCode.RETRY_NOT_PROVEN_SAFE,
                    "rejection requires current confirmed non-execution evidence");
        }
        return transitionTo(ReversalCommandState.REJECTED, requireCode(reasonCode), at);
    }

    /** Resolves an ambiguous command as executed from approved reconciliation evidence. */
    public ReversalCommand completeFromReconciliation(Instant at) {
        requireState(ReversalCommandState.RECONCILIATION_REQUIRED, "completeFromReconciliation");
        return transitionTo(ReversalCommandState.COMPLETED, null, at);
    }

    /**
     * Schedules a retry when an approved reconciliation decision establishes non-execution and the
     * snapshotted attempt bound still permits another downstream action.
     */
    public ReversalCommand scheduleRetryFromReconciliation(int completedAttemptNumber, String proofCode, Instant at) {
        requireState(ReversalCommandState.RECONCILIATION_REQUIRED, "scheduleRetryFromReconciliation");
        if (!retryPolicySnapshot.permitsAttemptAfter(completedAttemptNumber)) {
            throw new DomainException(
                    DomainErrorCode.ATTEMPT_LIMIT_EXCEEDED,
                    "operation policy does not permit another execution attempt");
        }
        return transitionTo(ReversalCommandState.RETRY_SCHEDULED, requireCode(proofCode), at);
    }

    /** Resolves an ambiguous command as rejected from approved reconciliation evidence. */
    public ReversalCommand rejectFromReconciliation(String reasonCode, Instant at) {
        requireState(ReversalCommandState.RECONCILIATION_REQUIRED, "rejectFromReconciliation");
        return transitionTo(ReversalCommandState.REJECTED, requireCode(reasonCode), at);
    }

    /** Quarantines conflicting reconciliation evidence for explicit operator follow-up. */
    public ReversalCommand quarantine(String reasonCode, Instant at) {
        requireState(ReversalCommandState.RECONCILIATION_REQUIRED, "quarantine");
        return transitionTo(ReversalCommandState.QUARANTINED, requireCode(reasonCode), at);
    }

    /** Returns the stable downstream idempotency identity. */
    public ReversalCommandId id() {
        return id;
    }

    /** Returns the owning tenant. */
    public TenantId tenantId() {
        return tenantId;
    }

    /** Returns the reserved original transaction identity. */
    public OriginalTransactionId originalTransactionId() {
        return originalTransactionId;
    }

    /** Returns the snapshotted policy identity. */
    public OperationPolicyId operationPolicyId() {
        return operationPolicyId;
    }

    /** Returns the snapshotted business policy version. */
    public int operationPolicyVersion() {
        return operationPolicyVersion;
    }

    /** Returns the retry rules frozen at acceptance. */
    public RetryPolicy retryPolicySnapshot() {
        return retryPolicySnapshot;
    }

    /** Returns the operation type copied from the original transaction. */
    public OperationType operationType() {
        return operationType;
    }

    /** Returns the full reversal amount and currency. */
    public Money money() {
        return money;
    }

    /** Returns the bounded synthetic reason. */
    public ReversalReason reason() {
        return reason;
    }

    /** Returns the current process state. */
    public ReversalCommandState state() {
        return state;
    }

    /** Returns the safe stable failure code when present. */
    public Optional<String> failureCode() {
        return Optional.ofNullable(failureCode);
    }

    /** Returns the one-way hash of the scoped idempotency key. */
    public String idempotencyKeyHash() {
        return idempotencyKeyHash;
    }

    /** Returns the deterministic acceptance request fingerprint. */
    public String requestHash() {
        return requestHash;
    }

    /** Returns the non-sensitive workflow correlation identity. */
    public CorrelationId correlationId() {
        return correlationId;
    }

    /** Returns the acceptance time. */
    public Instant acceptedAt() {
        return acceptedAt;
    }

    /** Returns the last state transition time. */
    public Instant updatedAt() {
        return updatedAt;
    }

    /** Returns the optimistic aggregate version. */
    public long version() {
        return version;
    }

    /** Returns whether no further automated transition should be scheduled. */
    public boolean isTerminal() {
        return state == ReversalCommandState.COMPLETED
                || state == ReversalCommandState.REJECTED
                || state == ReversalCommandState.QUARANTINED;
    }

    private ReversalCommand transitionTo(ReversalCommandState target, String targetFailureCode, Instant at) {
        Objects.requireNonNull(at, "transition time must not be null");
        if (at.isBefore(updatedAt)) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "transition time must not move backwards");
        }
        return new ReversalCommand(
                id,
                tenantId,
                originalTransactionId,
                operationPolicyId,
                operationPolicyVersion,
                retryPolicySnapshot,
                operationType,
                money,
                reason,
                target,
                targetFailureCode,
                idempotencyKeyHash,
                requestHash,
                correlationId,
                acceptedAt,
                at,
                version + 1);
    }

    private void requireState(ReversalCommandState expected, String transition) {
        if (state != expected) {
            throw new InvalidStateTransitionException("ReversalCommand", state, transition);
        }
    }

    private void requireMatchingAttempt(ExecutionAttempt attempt, ExecutionAttemptState expected) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        if (!tenantId.equals(attempt.tenantId()) || !id.equals(attempt.reversalCommandId())) {
            throw new DomainException(
                    DomainErrorCode.TENANT_SCOPE_MISMATCH,
                    "execution attempt does not belong to this reversal command");
        }
        if (attempt.state() != expected) {
            throw new DomainException(
                    DomainErrorCode.INVALID_VALUE, "execution attempt does not contain the required terminal evidence");
        }
    }

    private static String requireHash(String value, String name) {
        String hash = Objects.requireNonNull(value, name + " must not be null")
                .strip()
                .toLowerCase();
        if (!hash.matches("[a-f0-9]{64}")) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, name + " must be a SHA-256 hash");
        }
        return hash;
    }

    private static String requireCode(String value) {
        String code = Objects.requireNonNull(value, "code must not be null").strip();
        if (!code.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "failure code must be a stable uppercase code");
        }
        return code;
    }

    private static String normaliseOptionalCode(String value) {
        return value == null ? null : requireCode(value);
    }
}
