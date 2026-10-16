package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

import java.time.Instant;
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
        Instant resolvedAt) {
    public ReconciliationCaseView {
        Objects.requireNonNull(id);
        Objects.requireNonNull(reversalCommandId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(ambiguityReasonCode);
        Objects.requireNonNull(openedAt);
    }
}
