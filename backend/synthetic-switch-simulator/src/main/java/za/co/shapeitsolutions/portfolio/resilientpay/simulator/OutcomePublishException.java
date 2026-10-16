package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

/**
 * Signals that an outcome or quarantine publication was not durably confirmed by RabbitMQ.
 *
 * @author Daniel Jr Nkulu
 */
public final class OutcomePublishException extends RuntimeException {
    /** Creates a redacted publication failure that forces listener retry instead of request ACK. */
    public OutcomePublishException(String message) {
        super(message);
    }

    /** Creates a redacted publication failure while retaining its local diagnostic cause. */
    public OutcomePublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
