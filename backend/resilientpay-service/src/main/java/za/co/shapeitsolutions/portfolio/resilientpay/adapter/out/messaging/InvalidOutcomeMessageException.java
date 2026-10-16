package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

/**
 * Identifies a permanent, redaction-safe inbound message validation failure.
 *
 * @author Daniel Jr Nkulu
 */
final class InvalidOutcomeMessageException extends RuntimeException {
    private final String reasonCode;

    /**
     * Creates a permanent validation failure.
     *
     * @param reasonCode stable quarantine reason
     */
    InvalidOutcomeMessageException(String reasonCode) {
        super(reasonCode);
        this.reasonCode = reasonCode;
    }

    /**
     * Returns the low-cardinality quarantine reason.
     *
     * @return stable reason code
     */
    String reasonCode() {
        return reasonCode;
    }
}
