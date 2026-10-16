package za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalCommandState;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ActorId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainException;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.InvalidStateTransitionException;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ReconciliationCaseId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ReversalCommandId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Contains the evidence-decision lifecycle for one ambiguous reversal. A proposal is not effective
 * until a different actor approves it; quarantine remains a first-class terminal outcome.
 *
 * @author Daniel Jr Nkulu
 */
public final class ReconciliationCase {
    private final ReconciliationCaseId id;
    private final TenantId tenantId;
    private final ReversalCommandId reversalCommandId;
    private final String ambiguityReasonCode;
    private final ReconciliationCaseState state;
    private final ReconciliationDecision proposedDecision;
    private final ActorId proposedBy;
    private final ActorId approvedBy;
    private final String purposeCode;
    private final Instant openedAt;
    private final Instant updatedAt;
    private final long version;

    private ReconciliationCase(
            ReconciliationCaseId id,
            TenantId tenantId,
            ReversalCommandId reversalCommandId,
            String ambiguityReasonCode,
            ReconciliationCaseState state,
            ReconciliationDecision proposedDecision,
            ActorId proposedBy,
            ActorId approvedBy,
            String purposeCode,
            Instant openedAt,
            Instant updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.reversalCommandId = Objects.requireNonNull(reversalCommandId, "reversalCommandId must not be null");
        this.ambiguityReasonCode = requireCode(ambiguityReasonCode, "ambiguityReasonCode");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.proposedDecision = proposedDecision;
        this.proposedBy = proposedBy;
        this.approvedBy = approvedBy;
        this.purposeCode = purposeCode == null ? null : requireCode(purposeCode, "purposeCode");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(openedAt) || version < 0) {
            throw new DomainException(
                    DomainErrorCode.INVALID_VALUE, "reconciliation chronology and version must be valid");
        }
        validateDecisionActors(state, proposedDecision, proposedBy, approvedBy, this.purposeCode);
        this.version = version;
    }

    /** Opens a case only for a reversal that has entered the ambiguous-outcome state. */
    public static ReconciliationCase open(
            ReconciliationCaseId id, ReversalCommand command, String ambiguityReasonCode, Instant openedAt) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.state() != ReversalCommandState.RECONCILIATION_REQUIRED) {
            throw new DomainException(
                    DomainErrorCode.INVALID_VALUE, "a reconciliation case requires a reversal awaiting reconciliation");
        }
        return new ReconciliationCase(
                id,
                command.tenantId(),
                command.id(),
                ambiguityReasonCode,
                ReconciliationCaseState.OPEN,
                null,
                null,
                null,
                null,
                openedAt,
                openedAt,
                0);
    }

    /** Reconstitutes persisted case evidence and actor decisions. */
    public static ReconciliationCase restore(
            ReconciliationCaseId id,
            TenantId tenantId,
            ReversalCommandId reversalCommandId,
            String ambiguityReasonCode,
            ReconciliationCaseState state,
            ReconciliationDecision proposedDecision,
            ActorId proposedBy,
            ActorId approvedBy,
            String purposeCode,
            Instant openedAt,
            Instant updatedAt,
            long version) {
        return new ReconciliationCase(
                id,
                tenantId,
                reversalCommandId,
                ambiguityReasonCode,
                state,
                proposedDecision,
                proposedBy,
                approvedBy,
                purposeCode,
                openedAt,
                updatedAt,
                version);
    }

    /** Records that an automated or manual evidence query is in progress. */
    public ReconciliationCase awaitEvidence(Instant at) {
        requireState(ReconciliationCaseState.OPEN, "awaitEvidence");
        return copy(ReconciliationCaseState.EVIDENCE_PENDING, null, null, null, null, at);
    }

    /** Records a maker's proposed outcome and mandatory purpose code. */
    public ReconciliationCase proposeDecision(
            ReconciliationDecision decision, ActorId actor, String decisionPurposeCode, Instant at) {
        if (state != ReconciliationCaseState.OPEN && state != ReconciliationCaseState.EVIDENCE_PENDING) {
            throw new InvalidStateTransitionException("ReconciliationCase", state, "proposeDecision");
        }
        return copy(
                ReconciliationCaseState.DECISION_PROPOSED,
                Objects.requireNonNull(decision, "decision must not be null"),
                Objects.requireNonNull(actor, "actor must not be null"),
                null,
                requireCode(decisionPurposeCode, "purposeCode"),
                at);
    }

    /** Approves a proposed decision with a checker distinct from the maker. */
    public ReconciliationCase approveDecision(ActorId actor, Instant at) {
        requireState(ReconciliationCaseState.DECISION_PROPOSED, "approveDecision");
        Objects.requireNonNull(actor, "actor must not be null");
        if (actor.equals(proposedBy)) {
            throw new DomainException(
                    DomainErrorCode.RECONCILIATION_APPROVER_CONFLICT,
                    "reconciliation approval requires an actor distinct from the proposer");
        }
        ReconciliationCaseState target = proposedDecision == ReconciliationDecision.QUARANTINE
                ? ReconciliationCaseState.QUARANTINED
                : ReconciliationCaseState.RESOLVED;
        return copy(target, proposedDecision, proposedBy, actor, purposeCode, at);
    }

    /** Returns the case identity. */
    public ReconciliationCaseId id() {
        return id;
    }

    /** Returns the owning tenant. */
    public TenantId tenantId() {
        return tenantId;
    }

    /** Returns the ambiguous reversal identity. */
    public ReversalCommandId reversalCommandId() {
        return reversalCommandId;
    }

    /** Returns the initial stable ambiguity reason code. */
    public String ambiguityReasonCode() {
        return ambiguityReasonCode;
    }

    /** Returns the current case state. */
    public ReconciliationCaseState state() {
        return state;
    }

    /** Returns the proposed or approved decision when present. */
    public Optional<ReconciliationDecision> proposedDecision() {
        return Optional.ofNullable(proposedDecision);
    }

    /** Returns the maker when a decision has been proposed. */
    public Optional<ActorId> proposedBy() {
        return Optional.ofNullable(proposedBy);
    }

    /** Returns the checker when a decision has been approved. */
    public Optional<ActorId> approvedBy() {
        return Optional.ofNullable(approvedBy);
    }

    /** Returns the bounded operator purpose code. */
    public Optional<String> purposeCode() {
        return Optional.ofNullable(purposeCode);
    }

    /** Returns the case creation time. */
    public Instant openedAt() {
        return openedAt;
    }

    /** Returns the last case transition time. */
    public Instant updatedAt() {
        return updatedAt;
    }

    /** Returns the optimistic aggregate version. */
    public long version() {
        return version;
    }

    private ReconciliationCase copy(
            ReconciliationCaseState target,
            ReconciliationDecision targetDecision,
            ActorId targetProposer,
            ActorId targetApprover,
            String targetPurposeCode,
            Instant at) {
        Objects.requireNonNull(at, "transition time must not be null");
        if (at.isBefore(updatedAt)) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "transition time must not move backwards");
        }
        return new ReconciliationCase(
                id,
                tenantId,
                reversalCommandId,
                ambiguityReasonCode,
                target,
                targetDecision,
                targetProposer,
                targetApprover,
                targetPurposeCode,
                openedAt,
                at,
                version + 1);
    }

    private void requireState(ReconciliationCaseState expected, String transition) {
        if (state != expected) {
            throw new InvalidStateTransitionException("ReconciliationCase", state, transition);
        }
    }

    private static String requireCode(String value, String name) {
        String code = Objects.requireNonNull(value, name + " must not be null").strip();
        if (!code.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, name + " must be a stable uppercase code");
        }
        return code;
    }

    private static void validateDecisionActors(
            ReconciliationCaseState state,
            ReconciliationDecision decision,
            ActorId proposer,
            ActorId approver,
            String purposeCode) {
        boolean hasProposal = decision != null && proposer != null && purposeCode != null;
        if (state == ReconciliationCaseState.OPEN || state == ReconciliationCaseState.EVIDENCE_PENDING) {
            if (decision != null || proposer != null || approver != null || purposeCode != null) {
                throw new DomainException(
                        DomainErrorCode.INVALID_VALUE, "unproposed case cannot contain decision actors");
            }
            return;
        }
        if (!hasProposal) {
            throw new DomainException(
                    DomainErrorCode.INVALID_VALUE, "proposed case requires decision, maker and purpose");
        }
        if ((state == ReconciliationCaseState.RESOLVED || state == ReconciliationCaseState.QUARANTINED)
                && approver == null) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "terminal case requires an approving checker");
        }
        if (proposer.equals(approver)) {
            throw new DomainException(
                    DomainErrorCode.RECONCILIATION_APPROVER_CONFLICT,
                    "reconciliation maker and checker must be different actors");
        }
    }
}
