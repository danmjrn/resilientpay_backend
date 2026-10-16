package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

/**
 * Carries one safe reason code for an untrusted or schema-invalid inbound message.
 *
 * @author Daniel Jr Nkulu
 */
public final class InvalidInboundMessageException extends RuntimeException {
    private final String safeReasonCode;

    /** Creates a redaction-safe validation failure. */
    public InvalidInboundMessageException(String safeReasonCode) {
        super(safeReasonCode);
        this.safeReasonCode = safeReasonCode;
    }

    /** Returns the stable quarantine reason code. */
    public String safeReasonCode() {
        return safeReasonCode;
    }
}
