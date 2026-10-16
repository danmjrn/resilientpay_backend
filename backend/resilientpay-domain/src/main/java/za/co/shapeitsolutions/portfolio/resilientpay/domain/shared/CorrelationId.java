package za.co.shapeitsolutions.portfolio.resilientpay.domain.shared;

import java.util.UUID;

/**
 * Carries a non-sensitive correlation identifier across application and event boundaries. It is
 * not used as an authorization or idempotency credential.
 *
 * @param value globally unique correlation value
 * @author Daniel Jr Nkulu
 */
public record CorrelationId(UUID value) {
    public CorrelationId {
        DomainChecks.required(value, "correlationId");
    }
}
