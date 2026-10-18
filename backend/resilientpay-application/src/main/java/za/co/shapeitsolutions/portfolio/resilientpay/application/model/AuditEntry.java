package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Carries already-minimised audit intent across the application persistence boundary; signing and
 * actor pseudonymisation remain adapter responsibilities, with no credential fields permitted.
 *
 * @param actor authenticated or service actor
 * @param action stable audited action
 * @param resourceType neutral resource category
 * @param resourceId tenant-bound resource identity
 * @param purpose bounded purpose code
 * @param outcome stable operation result
 * @param reason optional redaction-safe reason
 * @param correlationId public workflow correlation
 * @param policyVersion optional immutable policy version
 * @param safeDetails minimised structured evidence
 * @param occurredAt UTC event instant
 * @author Daniel Jr Nkulu
 */
public record AuditEntry(
        ActorContext actor,
        String action,
        String resourceType,
        UUID resourceId,
        String purpose,
        String outcome,
        String reason,
        UUID correlationId,
        Integer policyVersion,
        Map<String, Object> safeDetails,
        Instant occurredAt) {
    public AuditEntry {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(action);
        Objects.requireNonNull(resourceType);
        Objects.requireNonNull(resourceId);
        Objects.requireNonNull(purpose);
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(correlationId);
        Objects.requireNonNull(occurredAt);
        safeDetails = Map.copyOf(Objects.requireNonNull(safeDetails));
    }
}
