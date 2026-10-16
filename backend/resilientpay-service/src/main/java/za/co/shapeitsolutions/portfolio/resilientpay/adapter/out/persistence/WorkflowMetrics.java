package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Owns the fixed, low-cardinality workflow counters used by the PostgreSQL adapter.
 *
 * @author Daniel Jr Nkulu
 */
@Component
@Profile("!test")
public final class WorkflowMetrics {
    private static final String OTHER = "other";
    private static final Set<String> OPERATIONS =
            Set.of("ACCOUNT_TRANSFER", "CARD_PURCHASE", "CASH_WITHDRAWAL", "MERCHANT_PAYOUT", OTHER);
    private static final Set<String> OUTCOMES = Set.of(
            "ambiguous",
            "confirmed_not_executed",
            "contradictory",
            "duplicate",
            "executed",
            OTHER,
            "rejected",
            "terminal-replay");
    private static final Set<String> RECONCILIATION_SOURCES = Set.of("deadline", "outcome", OTHER);

    private final Counter reconciliationProposed;
    private final Counter reconciliationApproved;
    private final Counter evidenceViewed;
    private final Counter authorizationDenied;
    private final Map<String, Counter> acceptedByOperation;
    private final Map<String, Counter> outcomesByResult;
    private final Map<String, Counter> reconciliationOpenedBySource;

    /** Registers the complete fixed counter set once at composition time. */
    public WorkflowMetrics(MeterRegistry registry) {
        reconciliationProposed = registry.counter("resilientpay.reconciliation.proposed");
        reconciliationApproved = registry.counter("resilientpay.reconciliation.approved");
        evidenceViewed = registry.counter("resilientpay.audit.evidence.viewed");
        authorizationDenied = registry.counter("resilientpay.security.authorization", "outcome", "denied");
        acceptedByOperation = counters(registry, "resilientpay.reversal.accepted", "operation", OPERATIONS);
        outcomesByResult = counters(registry, "resilientpay.outcome.processed", "result", OUTCOMES);
        reconciliationOpenedBySource =
                counters(registry, "resilientpay.reconciliation.opened", "source", RECONCILIATION_SOURCES);
    }

    /** Records one evidence-backed reconciliation proposal. */
    public void reconciliationProposed() {
        reconciliationProposed.increment();
    }

    /** Records one distinct checker approval. */
    public void reconciliationApproved() {
        reconciliationApproved.increment();
    }

    /** Records one authorized evidence-view operation. */
    public void evidenceViewed() {
        evidenceViewed.increment();
    }

    /** Records one ambiguity or deadline-driven reconciliation opening. */
    public void reconciliationOpened(String source) {
        increment(reconciliationOpenedBySource, source);
    }

    /** Records one accepted command by the fixed operation vocabulary. */
    public void reversalAccepted(String operation) {
        increment(acceptedByOperation, operation);
    }

    /** Records one durable authorization denial. */
    public void authorizationDenied() {
        authorizationDenied.increment();
    }

    /** Records one outcome processing result using a finite result vocabulary. */
    public void outcomeProcessed(String result) {
        increment(outcomesByResult, result);
    }

    private static Map<String, Counter> counters(MeterRegistry registry, String name, String tag, Set<String> values) {
        Map<String, Counter> counters = new LinkedHashMap<>();
        values.forEach(value -> counters.put(value, registry.counter(name, tag, value)));
        return Map.copyOf(counters);
    }

    private static void increment(Map<String, Counter> counters, String value) {
        counters.getOrDefault(value, counters.get(OTHER)).increment();
    }
}
