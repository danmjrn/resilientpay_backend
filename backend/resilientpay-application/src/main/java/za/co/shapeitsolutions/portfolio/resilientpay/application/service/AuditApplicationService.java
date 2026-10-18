package za.co.shapeitsolutions.portfolio.resilientpay.application.service;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ActorContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AuditEntry;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.AuditStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.IndependentAuditPort;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.SecurityAuditPort;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.TransactionBoundary;

/**
 * Owns independent security-evidence transactions, deliberately suspending rejected business work
 * so durable denial evidence is not lost with its rollback. Audit failure remains fail-closed.
 *
 * @author Daniel Jr Nkulu
 */
public final class AuditApplicationService implements SecurityAuditPort, IndependentAuditPort {
    private final AuditStore store;
    private final TransactionBoundary transactions;
    private final Clock clock;

    /** Composes raw audit persistence with explicit application transaction policy. */
    public AuditApplicationService(AuditStore store, TransactionBoundary transactions, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void recordIndependent(AuditEntry entry) {
        transactions.requiresNew(() -> store.append(entry));
    }

    @Override
    public void recordAuthorizationDenied(
            ActorContext actor, String action, UUID resourceId, UUID correlationId, String reasonCode) {
        recordIndependent(new AuditEntry(
                actor,
                action,
                "REVERSAL_COMMAND",
                resourceId,
                "AUTHORIZATION",
                "DENIED",
                reasonCode,
                correlationId,
                null,
                Map.of("decision", "DENIED"),
                clock.instant()));
    }

    @Override
    public void recordEvidenceViewed(ActorContext actor, UUID commandId, UUID correlationId) {
        recordIndependent(new AuditEntry(
                actor,
                "EVIDENCE_VIEWED",
                "REVERSAL_COMMAND",
                commandId,
                "AUDIT_REVIEW",
                "SUCCEEDED",
                null,
                correlationId,
                null,
                Map.of("view", "REDACTED_AUDIT_TIMELINE"),
                clock.instant()));
    }
}
