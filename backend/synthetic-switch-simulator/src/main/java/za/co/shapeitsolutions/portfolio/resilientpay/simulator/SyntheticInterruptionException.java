package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

/**
 * Triggers the broker listener's bounded local retry for the INTERRUPT_ONCE scenario.
 *
 * @author Daniel Jr Nkulu
 */
public final class SyntheticInterruptionException extends RuntimeException {
    /** Creates the single bounded synthetic interruption signal. */
    public SyntheticInterruptionException() {
        super("Synthetic switch interrupted once before recording an outcome");
    }
}
