package za.co.shapeitsolutions.portfolio.resilientpay.application.port.out;

import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OutcomeObservation;

/**
 * Applies one validated downstream outcome and inbox marker inside the application's transaction.
 *
 * @author Daniel Jr Nkulu
 */
@FunctionalInterface
public interface OutcomeStore {
    /** Applies an outcome atomically without choosing a transaction boundary. */
    void processOutcome(OutcomeObservation observation);
}
