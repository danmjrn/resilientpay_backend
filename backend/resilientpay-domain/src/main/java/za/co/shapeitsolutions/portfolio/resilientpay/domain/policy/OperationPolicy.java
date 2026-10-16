package za.co.shapeitsolutions.portfolio.resilientpay.domain.policy;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ActorId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainException;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.InvalidStateTransitionException;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationPolicyId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Models one immutable-by-submission policy version for a tenant and operation type. Drafts may be
 * amended, but submission freezes the retry rules and activation requires a different actor.
 *
 * @author Daniel Jr Nkulu
 */
public final class OperationPolicy {
    private final OperationPolicyId id;
    private final TenantId tenantId;
    private final OperationType operationType;
    private final int policyVersion;
    private final OperationPolicyState state;
    private final RetryPolicy retryPolicy;
    private final Instant effectiveFrom;
    private final Instant effectiveUntil;
    private final ActorId createdBy;
    private final ActorId submittedBy;
    private final ActorId approvedBy;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;

    private OperationPolicy(
            OperationPolicyId id,
            TenantId tenantId,
            OperationType operationType,
            int policyVersion,
            OperationPolicyState state,
            RetryPolicy retryPolicy,
            Instant effectiveFrom,
            Instant effectiveUntil,
            ActorId createdBy,
            ActorId submittedBy,
            ActorId approvedBy,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.operationType = Objects.requireNonNull(operationType, "operationType must not be null");
        if (policyVersion < 1) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "policyVersion must be positive");
        }
        this.policyVersion = policyVersion;
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        if (effectiveUntil != null && !effectiveUntil.isAfter(effectiveFrom)) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "effectiveUntil must be after effectiveFrom");
        }
        this.effectiveUntil = effectiveUntil;
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
        this.submittedBy = submittedBy;
        this.approvedBy = approvedBy;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt) || version < 0) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "policy chronology and version must be valid");
        }
        validateLifecycleActors(state, submittedBy, approvedBy);
        this.version = version;
    }

    /** Creates a mutable draft policy version before maker-checker submission. */
    public static OperationPolicy draft(
            OperationPolicyId id,
            TenantId tenantId,
            OperationType operationType,
            int policyVersion,
            RetryPolicy retryPolicy,
            Instant effectiveFrom,
            Instant effectiveUntil,
            ActorId createdBy,
            Instant createdAt) {
        return new OperationPolicy(
                id,
                tenantId,
                operationType,
                policyVersion,
                OperationPolicyState.DRAFT,
                retryPolicy,
                effectiveFrom,
                effectiveUntil,
                createdBy,
                null,
                null,
                createdAt,
                createdAt,
                0);
    }

    /** Reconstitutes a persisted policy while rechecking all structural invariants. */
    public static OperationPolicy restore(
            OperationPolicyId id,
            TenantId tenantId,
            OperationType operationType,
            int policyVersion,
            OperationPolicyState state,
            RetryPolicy retryPolicy,
            Instant effectiveFrom,
            Instant effectiveUntil,
            ActorId createdBy,
            ActorId submittedBy,
            ActorId approvedBy,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        return new OperationPolicy(
                id,
                tenantId,
                operationType,
                policyVersion,
                state,
                retryPolicy,
                effectiveFrom,
                effectiveUntil,
                createdBy,
                submittedBy,
                approvedBy,
                createdAt,
                updatedAt,
                version);
    }

    /** Updates retry limits while and only while the version remains a draft. */
    public OperationPolicy reviseRetryPolicy(RetryPolicy revised, Instant at) {
        requireState(OperationPolicyState.DRAFT, "reviseRetryPolicy");
        return copy(OperationPolicyState.DRAFT, revised, submittedBy, approvedBy, at);
    }

    /** Freezes the draft rules and records the submitting maker. */
    public OperationPolicy submit(ActorId actor, Instant at) {
        requireState(OperationPolicyState.DRAFT, "submit");
        return copy(OperationPolicyState.PENDING_APPROVAL, retryPolicy, Objects.requireNonNull(actor), null, at);
    }

    /** Activates a submitted policy only when a distinct checker approves it. */
    public OperationPolicy approve(ActorId actor, Instant at) {
        requireState(OperationPolicyState.PENDING_APPROVAL, "approve");
        Objects.requireNonNull(actor, "actor must not be null");
        if (actor.equals(submittedBy)) {
            throw new DomainException(
                    DomainErrorCode.POLICY_APPROVER_CONFLICT,
                    "policy approval requires an actor distinct from the submitter");
        }
        return copy(OperationPolicyState.ACTIVE, retryPolicy, submittedBy, actor, at);
    }

    /** Retires an active policy version so it cannot govern newly accepted work. */
    public OperationPolicy retire(Instant at) {
        requireState(OperationPolicyState.ACTIVE, "retire");
        return copy(OperationPolicyState.RETIRED, retryPolicy, submittedBy, approvedBy, at);
    }

    /** Returns whether this approved version is active at the supplied business instant. */
    public boolean isEffectiveAt(Instant instant) {
        Objects.requireNonNull(instant, "instant must not be null");
        return state == OperationPolicyState.ACTIVE
                && !instant.isBefore(effectiveFrom)
                && (effectiveUntil == null || instant.isBefore(effectiveUntil));
    }

    /** Returns the policy identity. */
    public OperationPolicyId id() {
        return id;
    }

    /** Returns the owning tenant. */
    public TenantId tenantId() {
        return tenantId;
    }

    /** Returns the governed operation type. */
    public OperationType operationType() {
        return operationType;
    }

    /** Returns the immutable business policy version. */
    public int policyVersion() {
        return policyVersion;
    }

    /** Returns the policy lifecycle state. */
    public OperationPolicyState state() {
        return state;
    }

    /** Returns the bounded retry configuration. */
    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    /** Returns the inclusive effective start instant. */
    public Instant effectiveFrom() {
        return effectiveFrom;
    }

    /** Returns the optional exclusive effective end instant. */
    public Optional<Instant> effectiveUntil() {
        return Optional.ofNullable(effectiveUntil);
    }

    /** Returns the draft creator. */
    public ActorId createdBy() {
        return createdBy;
    }

    /** Returns the maker when the version has been submitted. */
    public Optional<ActorId> submittedBy() {
        return Optional.ofNullable(submittedBy);
    }

    /** Returns the checker when the version has been approved. */
    public Optional<ActorId> approvedBy() {
        return Optional.ofNullable(approvedBy);
    }

    /** Returns the creation instant. */
    public Instant createdAt() {
        return createdAt;
    }

    /** Returns the last transition instant. */
    public Instant updatedAt() {
        return updatedAt;
    }

    /** Returns the optimistic aggregate version. */
    public long version() {
        return version;
    }

    private OperationPolicy copy(
            OperationPolicyState target,
            RetryPolicy targetRetryPolicy,
            ActorId targetSubmitter,
            ActorId targetApprover,
            Instant at) {
        Objects.requireNonNull(at, "transition time must not be null");
        if (at.isBefore(updatedAt)) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "transition time must not move backwards");
        }
        return new OperationPolicy(
                id,
                tenantId,
                operationType,
                policyVersion,
                target,
                targetRetryPolicy,
                effectiveFrom,
                effectiveUntil,
                createdBy,
                targetSubmitter,
                targetApprover,
                createdAt,
                at,
                version + 1);
    }

    private void requireState(OperationPolicyState expected, String transition) {
        if (state != expected) {
            throw new InvalidStateTransitionException("OperationPolicy", state, transition);
        }
    }

    private static void validateLifecycleActors(OperationPolicyState state, ActorId submitter, ActorId approver) {
        if (state == OperationPolicyState.DRAFT && (submitter != null || approver != null)) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "draft policy cannot contain submission actors");
        }
        if (state != OperationPolicyState.DRAFT && submitter == null) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "submitted policy requires a submitter");
        }
        if ((state == OperationPolicyState.ACTIVE || state == OperationPolicyState.RETIRED) && approver == null) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "approved policy requires an approver");
        }
        if (submitter != null && submitter.equals(approver)) {
            throw new DomainException(
                    DomainErrorCode.POLICY_APPROVER_CONFLICT, "policy submitter and approver must be different actors");
        }
    }
}
