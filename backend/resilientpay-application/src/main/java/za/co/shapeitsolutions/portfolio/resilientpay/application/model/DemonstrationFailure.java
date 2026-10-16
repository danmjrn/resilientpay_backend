package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

/**
 * Selects a bounded synthetic switch outcome for the local demonstration only.
 *
 * @author Daniel Jr Nkulu
 */
public enum DemonstrationFailure {
    NONE,
    INTERRUPT_ONCE,
    AMBIGUOUS,
    REJECT
}
