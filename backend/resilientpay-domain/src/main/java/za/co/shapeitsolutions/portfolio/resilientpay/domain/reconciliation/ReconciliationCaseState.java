package za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation;

/**
 * Enumerates the evidence and approval lifecycle of an ambiguous reversal case.
 *
 * @author Daniel Jr Nkulu
 */
public enum ReconciliationCaseState {
    OPEN,
    EVIDENCE_PENDING,
    DECISION_PROPOSED,
    RESOLVED,
    QUARANTINED
}
