package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

/**
 * Records one bounded transition from cold outbox recovery back to the hot publication queue.
 *
 * @author Daniel Jr Nkulu
 */
public interface OutboxRecoveryAudit {
    /**
     * Appends safe evidence before an exhausted retryable event is made publishable again.
     *
     * @param message exhausted event selected for cold recovery
     * @param recoveryCount one-based cold recovery cycle
     */
    void recordRecovered(OutboxMessage message, int recoveryCount);

    /**
     * Records and quarantines an event after its finite cold recovery budget is exhausted.
     *
     * @param message exhausted event
     * @param recoveryCount completed cold recovery cycles
     * @param errorCode final redaction-safe publisher error
     */
    default void recordTerminalFailure(OutboxMessage message, int recoveryCount, String errorCode) {}
}
