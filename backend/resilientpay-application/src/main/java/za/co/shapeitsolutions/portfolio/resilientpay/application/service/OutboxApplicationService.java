package za.co.shapeitsolutions.portfolio.resilientpay.application.service;

import java.util.List;
import java.util.Objects;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OutboxMessage;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.OutboxOperations;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OutboxPublicationAudit;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OutboxStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.TransactionBoundary;

/**
 * Selects short transaction scopes for outbox persistence without enclosing broker I/O.
 * Confirmation audit commits independently before the separately committed published-state update.
 *
 * @author Daniel Jr Nkulu
 */
public final class OutboxApplicationService implements OutboxOperations {
    private final TransactionBoundary transactions;
    private final OutboxStore store;
    private final OutboxPublicationAudit publicationAudit;

    /** Composes transaction policy, outbox persistence and independent confirmation evidence. */
    public OutboxApplicationService(
            TransactionBoundary transactions, OutboxStore store, OutboxPublicationAudit publicationAudit) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.publicationAudit = Objects.requireNonNull(publicationAudit, "publicationAudit must not be null");
    }

    @Override
    public int recoverDueDead() {
        return transactions.required(store::recoverDueDead);
    }

    @Override
    public List<OutboxMessage> claimDue() {
        return transactions.required(store::claimDue);
    }

    @Override
    public OutboxMessage prepareForPublish(OutboxMessage message) {
        return transactions.required(() -> store.prepareForPublish(message));
    }

    @Override
    public void confirmPublished(OutboxMessage message) {
        // Broker confirmation is already known. Preserve the independent durable audit even if
        // the later lease/state update fails; replay still uses the original stable identity.
        transactions.requiresNew(() -> publicationAudit.recordPublished(message));
        transactions.required(() -> store.markPublished(message));
    }

    @Override
    public void markFailed(OutboxMessage message, String errorCode, boolean retryable) {
        transactions.required(() -> store.markFailed(message, errorCode, retryable));
    }
}
