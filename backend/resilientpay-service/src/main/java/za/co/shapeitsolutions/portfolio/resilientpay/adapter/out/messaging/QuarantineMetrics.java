package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Restricts invalid-message metrics to an explicit, finite reason-code vocabulary.
 *
 * @author Daniel Jr Nkulu
 */
@Component
@Profile("!test")
public final class QuarantineMetrics {
    private static final String OTHER = "OTHER";
    private static final Set<String> REASONS = Set.of(
            "INTEGRITY_CHECK_FAILED",
            "INVALID_CORRELATION_ID",
            "INVALID_JSON_PAYLOAD",
            "INVALID_MESSAGE_ID",
            "INVALID_TENANT_SCOPE",
            "INVALID_TRACE_CONTEXT",
            "MESSAGE_ID_PAYLOAD_CONFLICT",
            OTHER,
            "OUTBOX_RECOVERY_EXHAUSTED",
            "OUTCOME_SCOPE_MISMATCH",
            "OUTCOME_STATE_CONFLICT",
            "PAYLOAD_SIZE_INVALID",
            "CONTRADICTORY_OUTCOME",
            "SCHEMA_VALIDATION_FAILED",
            "UNSUPPORTED_SCHEMA_VERSION",
            "UNSUPPORTED_SCHEMA_VERSION_OR_EVENT");

    private final Map<String, Counter> counters;

    /** Registers the complete bounded reason-code series once at composition time. */
    public QuarantineMetrics(MeterRegistry registry) {
        Map<String, Counter> registered = new LinkedHashMap<>();
        REASONS.forEach(reason ->
                registered.put(reason, registry.counter("resilientpay.message.quarantine", "reason", reason)));
        counters = Map.copyOf(registered);
    }

    /** Records one quarantine occurrence using the allowlisted reason or a stable fallback. */
    public void increment(String reason) {
        counters.getOrDefault(reason, counters.get(OTHER)).increment();
    }
}
