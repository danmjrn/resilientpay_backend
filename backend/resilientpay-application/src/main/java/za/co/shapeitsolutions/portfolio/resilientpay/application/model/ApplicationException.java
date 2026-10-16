package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

import java.util.Objects;

/**
 * Carries a stable application failure code and a redaction-safe explanation to inbound adapters.
 *
 * @author Daniel Jr Nkulu
 */
public final class ApplicationException extends RuntimeException {
    private final ApplicationErrorCode code;

    /** Creates a redaction-safe application failure. */
    public ApplicationException(ApplicationErrorCode code, String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    /** Returns the stable failure code. */
    public ApplicationErrorCode code() {
        return code;
    }
}
