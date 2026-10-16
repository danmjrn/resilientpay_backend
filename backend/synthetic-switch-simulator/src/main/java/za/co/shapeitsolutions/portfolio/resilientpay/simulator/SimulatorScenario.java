package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

/**
 * Selects the bounded outcome produced by the local synthetic downstream switch.
 *
 * @author Daniel Jr Nkulu
 */
public enum SimulatorScenario {
    SUCCEED,
    CONFIRM_NOT_EXECUTED,
    REJECT,
    AMBIGUOUS,
    INTERRUPT_ONCE
}
