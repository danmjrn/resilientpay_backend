package za.co.shapeitsolutions.portfolio.resilientpay.application.port.out;

import java.util.List;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OutboxMessage;

/**
 * Persists bounded outbox lease transitions within transactions selected by the application.
 * Broker transport and confirmation waits never belong to this persistence port.
 *
 * @author Daniel Jr Nkulu
 */
public interface OutboxStore {
    /** Recovers a bounded batch with recovery audit in the same transaction. */
    int recoverDueDead();

    /** Claims a bounded due batch. */
    List<OutboxMessage> claimDue();

    /** Records the dispatch-relative deadline before an execution is published. */
    OutboxMessage prepareForPublish(OutboxMessage message);

    /** Marks a confirmed publication and advances its owned workflow state. */
    void markPublished(OutboxMessage message);

    /** Releases or terminates one lease, joining any terminal recovery audit. */
    void markFailed(OutboxMessage message, String errorCode, boolean retryable);
}
