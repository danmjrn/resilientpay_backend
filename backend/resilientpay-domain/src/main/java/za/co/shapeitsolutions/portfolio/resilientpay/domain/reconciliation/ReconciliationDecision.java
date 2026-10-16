package za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation;

/**
 * Defines the bounded outcomes that a maker may propose and a distinct checker may approve for an
 * ambiguous reversal.
 *
 * @author Daniel Jr Nkulu
 */
public enum ReconciliationDecision {
    CONFIRM_EXECUTED,
    CONFIRM_NOT_EXECUTED,
    REJECT_REVERSAL,
    QUARANTINE
}
