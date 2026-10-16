package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Exposes integrity-verifiable lifecycle evidence without raw identity or financial payloads.
 *
 * @param eventId immutable tenant-scoped audit event identity
 * @param action privileged action or lifecycle observation
 * @param outcome stable audited result
 * @param actorPseudonym non-reversible actor identity
 * @param purpose bounded business purpose
 * @param correlationId end-to-end correlation identity
 * @param occurredAt UTC audit event time
 * @param digest integrity digest over the safe evidence fields
 * @author Daniel Jr Nkulu
 */
public record AuditEvidenceView(
        UUID eventId,
        String action,
        String outcome,
        String actorPseudonym,
        String purpose,
        UUID correlationId,
        Instant occurredAt,
        String digest) {
    public AuditEvidenceView {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(action);
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(actorPseudonym);
        Objects.requireNonNull(purpose);
        Objects.requireNonNull(correlationId);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(digest);
    }
}
