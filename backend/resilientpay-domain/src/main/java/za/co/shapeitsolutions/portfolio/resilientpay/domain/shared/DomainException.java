package za.co.shapeitsolutions.portfolio.resilientpay.domain.shared;

/**
 * Represents an explicit, stable domain failure raised while enforcing a business invariant. It
 * carries a code suitable for adapter translation but deliberately contains no protocol details.
 *
 * @author Daniel Jr Nkulu
 */
public sealed class DomainException extends RuntimeException permits InvalidStateTransitionException {
    private final DomainErrorCode code;

    /**
     * Creates a domain failure with a stable code and safe explanatory message.
     *
     * @param code stable domain error code
     * @param message non-sensitive explanation
     */
    public DomainException(DomainErrorCode code, String message) {
        super(message);
        this.code = code == null ? DomainErrorCode.INVALID_VALUE : code;
    }

    /**
     * Returns the stable error code.
     *
     * @return domain error code
     */
    public DomainErrorCode code() {
        return code;
    }
}
