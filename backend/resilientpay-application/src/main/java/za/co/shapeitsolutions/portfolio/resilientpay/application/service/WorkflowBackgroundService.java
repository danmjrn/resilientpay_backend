package za.co.shapeitsolutions.portfolio.resilientpay.application.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OutcomeObservation;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.WorkflowBackgroundOperations;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.DeadlineReconciliationStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.LifecycleProjectionStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.MessageQuarantineStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OutcomeStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.TransactionBoundary;

/**
 * Coordinates durable background workflow mutations through framework-free application ports.
 * Transaction completion precedes listener acknowledgement; failures propagate for broker retry.
 *
 * @author Daniel Jr Nkulu
 */
public final class WorkflowBackgroundService implements WorkflowBackgroundOperations {
    private final TransactionBoundary transactions;
    private final OutcomeStore outcomes;
    private final DeadlineReconciliationStore deadlines;
    private final LifecycleProjectionStore lifecycle;
    private final MessageQuarantineStore quarantine;

    /** Composes the transaction policy and the four background persistence boundaries. */
    public WorkflowBackgroundService(
            TransactionBoundary transactions,
            OutcomeStore outcomes,
            DeadlineReconciliationStore deadlines,
            LifecycleProjectionStore lifecycle,
            MessageQuarantineStore quarantine) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines must not be null");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine must not be null");
    }

    @Override
    public void processOutcome(OutcomeObservation observation) {
        transactions.required(() -> outcomes.processOutcome(observation));
    }

    @Override
    public int recordLifecycle(
            UUID tenantId,
            UUID messageId,
            String payloadHash,
            UUID correlationId,
            String eventType,
            Instant observedAt) {
        return transactions.required(
                () -> lifecycle.record(tenantId, messageId, payloadHash, correlationId, eventType, observedAt));
    }

    @Override
    public void quarantine(
            UUID tenantId, String messageId, UUID correlationId, String payloadDigest, String errorCode) {
        transactions.required(() -> quarantine.record(tenantId, messageId, correlationId, payloadDigest, errorCode));
    }

    @Override
    public int reconcileOverdueAttempts(int limit) {
        return transactions.required(() -> deadlines.reconcileOverdueAttempts(limit));
    }
}
