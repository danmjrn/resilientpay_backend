package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

/**
 * Mirrors the optional demo-only failure control carried by an execution-request payload.
 *
 * @author Daniel Jr Nkulu
 */
public enum DemonstrationFailure {
    NONE,
    INTERRUPT_ONCE,
    AMBIGUOUS,
    REJECT
}
