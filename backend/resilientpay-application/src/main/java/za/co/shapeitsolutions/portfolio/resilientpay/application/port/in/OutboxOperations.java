package za.co.shapeitsolutions.portfolio.resilientpay.application.port.in;

import java.util.List;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OutboxMessage;

/**
 * Exposes short application-owned outbox transitions to the broker transport adapter.
 * Each call commits before transport work resumes; no call waits for a broker confirmation.
 *
 * @author Daniel Jr Nkulu
 */
public interface OutboxOperations {
    /** Recovers a bounded batch of exhausted retryable publications. */
    int recoverDueDead();

    /** Commits a bounded batch of publication leases. */
    List<OutboxMessage> claimDue();

    /** Commits dispatch-relative execution preparation. */
    OutboxMessage prepareForPublish(OutboxMessage message);

    /** Independently commits confirmation audit before updating publication state. */
    void confirmPublished(OutboxMessage message);

    /** Commits failure/backoff state and any joined terminal recovery audit. */
    void markFailed(OutboxMessage message, String errorCode, boolean retryable);
}
