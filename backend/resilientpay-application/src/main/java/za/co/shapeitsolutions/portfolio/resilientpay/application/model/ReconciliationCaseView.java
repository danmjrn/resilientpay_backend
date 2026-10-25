package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation.ReconciliationCaseState;

/**
 * Provides redacted, tenant-scoped reconciliation work to authorised operators.
 *
 * @param id tenant-scoped reconciliation case identity
 * @param reversalCommandId related tenant-scoped reversal identity
 * @param status current maker-checker lifecycle state
 * @param ambiguityReasonCode safe reason that opened the case
 * @param proposedDecision maker's proposed resolution, or null before proposal
 * @param makerPseudonym non-reversible review identity, or null before proposal
 * @param proposalPurpose reviewable business purpose, or null before proposal
 * @param evidenceDigest reviewable evidence digest, or null before proposal
 * @param openedAt UTC case creation time
 * @param resolvedAt UTC resolution time, or null while open
 * @param allowedActions scope-, assurance- and state-derived review capabilities
 * @author Daniel Jr Nkulu
 */
public record ReconciliationCaseView(
        UUID id,
        UUID reversalCommandId,
        ReconciliationCaseState status,
        String ambiguityReasonCode,
        String proposedDecision,
        String makerPseudonym,
        String proposalPurpose,
        String evidenceDigest,
        Instant openedAt,
        Instant resolvedAt,
        List<String> allowedActions) {
    public ReconciliationCaseView {
        Objects.requireNonNull(id);
        Objects.requireNonNull(reversalCommandId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(ambiguityReasonCode);
        Objects.requireNonNull(openedAt);
        allowedActions = List.copyOf(allowedActions);
    }

    /** Preserves the persistence and historical test representation before caller projection. */
    public ReconciliationCaseView(
            UUID id,
            UUID reversalCommandId,
            ReconciliationCaseState status,
            String ambiguityReasonCode,
            String proposedDecision,
            String makerPseudonym,
            String proposalPurpose,
            String evidenceDigest,
            Instant openedAt,
            Instant resolvedAt) {
        this(
                id,
                reversalCommandId,
                status,
                ambiguityReasonCode,
                proposedDecision,
                makerPseudonym,
                proposalPurpose,
                evidenceDigest,
                openedAt,
                resolvedAt,
                List.of());
    }

    /** Projects capabilities without granting authority or bypassing the aggregate's actor checks. */
    public ReconciliationCaseView authorizedFor(ActorContext actor, String requiredAssurance) {
        List<String> actions = new ArrayList<>();
        if (actor.authorities().contains("SCOPE_reconciliation.read")) {
            actions.add("VIEW");
        }
        if ((status == ReconciliationCaseState.OPEN || status == ReconciliationCaseState.EVIDENCE_PENDING)
                && actor.authorities().contains("SCOPE_reconciliation.decide")) {
            actions.add("PROPOSE_DECISION");
        }
        if (status == ReconciliationCaseState.DECISION_PROPOSED
                && actor.authorities().contains("SCOPE_reconciliation.approve")
                && requiredAssurance.equals(actor.assuranceLevel())) {
            actions.add("APPROVE_DECISION");
        }
        if (actor.authorities().contains("SCOPE_audit.read")) {
            actions.add("VIEW_EVIDENCE");
        }
        return new ReconciliationCaseView(
                id,
                reversalCommandId,
                status,
                ambiguityReasonCode,
                proposedDecision,
                makerPseudonym,
                proposalPurpose,
                evidenceDigest,
                openedAt,
                resolvedAt,
                actions);
    }
}
