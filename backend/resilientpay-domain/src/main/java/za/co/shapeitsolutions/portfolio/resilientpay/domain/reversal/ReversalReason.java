package za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal;

import java.util.Objects;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainException;

/**
 * Holds the bounded, synthetic reason for requesting a reversal. It intentionally excludes raw
 * account references and other financial payloads from the aggregate and its events.
 *
 * @param value normalised reason text
 * @author Daniel Jr Nkulu
 */
public record ReversalReason(String value) {
    public ReversalReason {
        value = Objects.requireNonNull(value, "reason must not be null").strip();
        if (value.length() < 3 || value.length() > 256) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "reason length must be between 3 and 256");
        }
    }
}
