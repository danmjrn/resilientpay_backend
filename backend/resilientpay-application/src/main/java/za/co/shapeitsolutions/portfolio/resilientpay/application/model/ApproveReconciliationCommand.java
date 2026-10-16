package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

import java.util.Objects;
import java.util.UUID;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.CorrelationId;

/**
 * Records a distinct checker's approval for a proposed reconciliation decision.
 *
 * @param caseId tenant-scoped reconciliation case identity
 * @param purpose bounded checker purpose recorded in audit evidence
 * @param idempotencyKey action-scoped replay key
 * @param correlationId end-to-end correlation identity
 * @author Daniel Jr Nkulu
 */
public record ApproveReconciliationCommand(
        UUID caseId, String purpose, String idempotencyKey, CorrelationId correlationId) {
    public ApproveReconciliationCommand {
        Objects.requireNonNull(caseId);
        purpose = Objects.requireNonNull(purpose).strip();
        idempotencyKey = Objects.requireNonNull(idempotencyKey).strip();
        Objects.requireNonNull(correlationId);
    }
}
