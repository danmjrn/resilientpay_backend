package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.WorkflowBackgroundOperations;
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
    private final WorkflowBackgroundOperations operations;
    private final ReconciliationProperties properties;
    private final MeterRegistry meterRegistry;

    /**
     * Creates the bounded deadline scanner.
     *
     * @param operations application-owned deadline transaction boundary
     * @param properties bounded scan settings
     * @param meterRegistry low-cardinality scan metrics
     */
    public DeadlineReconciliationScanner(
            WorkflowBackgroundOperations operations, ReconciliationProperties properties, MeterRegistry meterRegistry) {
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    /** Scans one bounded overdue batch. */
    @Scheduled(fixedDelayString = "${resilientpay.reconciliation.poll-delay:5s}")
    public void scan() {
        int transitioned = operations.reconcileOverdueAttempts(properties.batchSize());
        meterRegistry.summary("resilientpay.reconciliation.deadline.scan").record(transitioned);
    }
}
