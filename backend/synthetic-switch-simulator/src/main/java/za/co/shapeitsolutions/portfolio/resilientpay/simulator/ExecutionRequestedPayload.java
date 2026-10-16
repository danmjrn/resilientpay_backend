package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors the exact AsyncAPI execution-request payload accepted by the simulator.
 *
 * @param reversalCommandId immutable reversal command identity
 * @param executionAttemptId immutable execution-attempt identity used for durable deduplication
 * @param attemptNumber bounded one-based attempt number
 * @param deadline latest instant at which execution remains valid
 * @param demonstrationFailure optional demo-only deterministic fault instruction
 * @author Daniel Jr Nkulu
 */
public record ExecutionRequestedPayload(
        @NotNull UUID reversalCommandId,
        @NotNull UUID executionAttemptId,
        @Min(1) @Max(5) int attemptNumber,
        @NotNull Instant deadline,
        DemonstrationFailure demonstrationFailure) {}
