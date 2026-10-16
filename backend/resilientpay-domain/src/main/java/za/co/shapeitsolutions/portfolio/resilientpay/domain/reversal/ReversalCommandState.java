package za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal;

/**
 * Enumerates the reversal process-manager lifecycle, including explicit safe retry and ambiguous
 * outcome states rather than reducing them to a generic failure flag.
 *
 * @author Daniel Jr Nkulu
 */
public enum ReversalCommandState {
    ACCEPTED,
    ROUTED,
    EXECUTION_PENDING,
    EXECUTING,
    RETRY_SCHEDULED,
    COMPLETED,
    REJECTED,
    RECONCILIATION_REQUIRED,
    QUARANTINED
}
