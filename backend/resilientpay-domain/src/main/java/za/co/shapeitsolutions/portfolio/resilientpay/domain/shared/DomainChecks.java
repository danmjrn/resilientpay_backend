package za.co.shapeitsolutions.portfolio.resilientpay.domain.shared;

import java.time.Instant;
import java.util.Objects;

/**
 * Centralises small construction checks used by immutable domain values. This package-private
 * helper keeps validation messages consistent without becoming a public utility API.
 *
 * @author Daniel Jr Nkulu
 */
final class DomainChecks {
    private DomainChecks() {}

    static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }

    static String boundedText(String value, String name, int minimum, int maximum) {
        String normalised = required(value, name).strip();
        if (normalised.length() < minimum || normalised.length() > maximum) {
            throw new DomainException(
                    DomainErrorCode.INVALID_VALUE,
                    "%s length must be between %d and %d".formatted(name, minimum, maximum));
        }
        return normalised;
    }

    static long nonNegative(long value, String name) {
        if (value < 0) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, name + " must not be negative");
        }
        return value;
    }

    static void notBefore(Instant candidate, Instant lowerBound, String name) {
        required(candidate, name);
        required(lowerBound, "lowerBound");
        if (candidate.isBefore(lowerBound)) {
            throw new DomainException(
                    DomainErrorCode.INVALID_VALUE, name + " must not be before the preceding timestamp");
        }
    }
}
