package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

/**
 * Signals an expected lost execution eligibility or publication lease before broker dispatch.
 * The publisher suppresses only this message and continues the remaining claimed batch.
 *
 * @author Daniel Jr Nkulu
 */
public final class ExecutionPublicationSuppressedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates a redaction-safe signal without exposing message payload or database details. */
    public ExecutionPublicationSuppressedException() {
        super("Execution publication is no longer eligible");
    }
}
