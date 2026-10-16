package za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal;

/**
 * Describes the evidence-bearing lifecycle of one downstream execution attempt. Ambiguous is
 * deliberately distinct from confirmed non-execution so it can never authorize a blind retry.
 *
 * @author Daniel Jr Nkulu
 */
public enum ExecutionAttemptState {
    CREATED,
    DISPATCHED,
    CONFIRMED_EXECUTED,
    CONFIRMED_NOT_EXECUTED,
    AMBIGUOUS
}
