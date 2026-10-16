package za.co.shapeitsolutions.portfolio.resilientpay.domain.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Provides a type-safe identity for the case that contains ambiguous execution evidence.
 *
 * @param value globally unique reconciliation value
 * @author Daniel Jr Nkulu
 */
public record ReconciliationCaseId(UUID value) {
    public ReconciliationCaseId {
        Objects.requireNonNull(value, "reconciliationCaseId must not be null");
    }
}
