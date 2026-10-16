package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web;

import za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation.ReconciliationDecision;

/**
 * Preserves the OpenAPI decision spelling while mapping to explicit domain terminology.
 *
 * @author Daniel Jr Nkulu
 */
public enum PublicReconciliationDecision {
    CONFIRM_EXECUTED,
    CONFIRM_NOT_EXECUTED,
    REJECT,
    QUARANTINE;

    /** Maps the public contract value to the domain decision. */
    public ReconciliationDecision toDomain() {
        return this == REJECT ? ReconciliationDecision.REJECT_REVERSAL : ReconciliationDecision.valueOf(name());
    }
}
