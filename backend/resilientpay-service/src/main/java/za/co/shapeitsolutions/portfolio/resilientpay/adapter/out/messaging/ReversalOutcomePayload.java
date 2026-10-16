package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Deserializes the exact version-one synthetic-switch outcome payload.
 *
 * @param reversalCommandId reversal command identity
 * @param executionAttemptId execution attempt identity
 * @param outcome downstream result classification
 * @param reasonCode nullable stable safe reason code
 * @param observedAt downstream observation timestamp
 * @author Daniel Jr Nkulu
 */
record ReversalOutcomePayload(
        UUID reversalCommandId, UUID executionAttemptId, String outcome, String reasonCode, Instant observedAt) {}
