package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors the exact AsyncAPI reversal-outcome payload emitted by the simulator.
 *
 * @param reversalCommandId immutable reversal command identity
 * @param executionAttemptId immutable execution-attempt identity
 * @param outcome authoritative synthetic switch observation
 * @param reasonCode safe machine-readable reason when an outcome needs one
 * @param observedAt UTC time at which the synthetic switch determined the outcome
 * @author Daniel Jr Nkulu
 */
public record OutcomePayload(
        UUID reversalCommandId,
        UUID executionAttemptId,
        SwitchOutcome outcome,
        String reasonCode,
        Instant observedAt) {}
