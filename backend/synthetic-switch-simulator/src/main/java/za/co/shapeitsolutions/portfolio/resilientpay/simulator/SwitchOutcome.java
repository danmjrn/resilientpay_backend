package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

/**
 * Uses the exact bounded outcome vocabulary from the AsyncAPI contract.
 *
 * @author Daniel Jr Nkulu
 */
public enum SwitchOutcome {
    EXECUTED,
    CONFIRMED_NOT_EXECUTED,
    REJECTED,
    AMBIGUOUS
}
