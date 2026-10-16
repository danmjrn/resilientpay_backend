package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import java.util.Objects;
import java.util.UUID;

/**
 * Carries one durably determined synthetic effect and its stable outbound message identity.
 *
 * @param outcomeMessageId replay-stable outcome message identity
 * @param outcome durably determined synthetic switch result
 * @author Daniel Jr Nkulu
 */
record ProcessedExecution(UUID outcomeMessageId, OutcomePayload outcome) {
    ProcessedExecution {
        Objects.requireNonNull(outcomeMessageId, "outcomeMessageId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}
