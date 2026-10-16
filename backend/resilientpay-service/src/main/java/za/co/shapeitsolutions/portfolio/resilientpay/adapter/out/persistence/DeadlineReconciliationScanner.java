package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ConditionalOnRuntimeRole;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ReconciliationProperties;

/**
 * Periodically asks PostgreSQL to reconcile overdue dispatched attempts in bounded batches.
 *
 * @author Daniel Jr Nkulu
 */
@Component
@ConditionalOnRuntimeRole("reconciler")
public final class DeadlineReconciliationScanner {
    private final DeadlineReconciliationStore store;
    private final ReconciliationProperties properties;
    private final MeterRegistry meterRegistry;

    /**
     * Creates the bounded deadline scanner.
     *
     * @param store authoritative PostgreSQL deadline boundary
     * @param properties bounded scan settings
     * @param meterRegistry low-cardinality scan metrics
     */
    public DeadlineReconciliationScanner(
            DeadlineReconciliationStore store, ReconciliationProperties properties, MeterRegistry meterRegistry) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    /** Scans one bounded overdue batch. */
    @Scheduled(fixedDelayString = "${resilientpay.reconciliation.poll-delay:5s}")
    public void scan() {
        int transitioned = store.reconcileOverdueAttempts(properties.batchSize());
        meterRegistry.summary("resilientpay.reconciliation.deadline.scan").record(transitioned);
    }
}
