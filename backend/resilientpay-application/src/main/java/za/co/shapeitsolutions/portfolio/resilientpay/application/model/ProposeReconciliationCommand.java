package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

import java.util.Objects;
import java.util.UUID;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation.ReconciliationDecision;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.CorrelationId;

/**
 * Records a maker's evidence-backed reconciliation proposal.
 *
 * @param caseId tenant-scoped reconciliation case identity
 * @param decision maker's proposed resolution
 * @param purpose bounded business purpose recorded in audit evidence
 * @param evidenceDigest digest of evidence belonging to the same tenant and case
 * @param idempotencyKey action-scoped replay key
 * @param correlationId end-to-end correlation identity
 * @author Daniel Jr Nkulu
 */
public record ProposeReconciliationCommand(
        UUID caseId,
        ReconciliationDecision decision,
        String purpose,
        String evidenceDigest,
        String idempotencyKey,
        CorrelationId correlationId) {
    public ProposeReconciliationCommand {
        Objects.requireNonNull(caseId);
        Objects.requireNonNull(decision);
        purpose = Objects.requireNonNull(purpose).strip();
        evidenceDigest = Objects.requireNonNull(evidenceDigest).strip();
        idempotencyKey = Objects.requireNonNull(idempotencyKey).strip();
        Objects.requireNonNull(correlationId);
    }
}
