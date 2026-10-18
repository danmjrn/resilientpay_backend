package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.WorkflowBackgroundOperations;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ReconciliationProperties;

/**
 * Proves that the scheduler delegates one configured bounded deadline batch.
 *
 * @author Daniel Jr Nkulu
 */
class DeadlineReconciliationScannerTest {

    @Test
    void scansExactlyTheConfiguredBatchSize() {
        WorkflowBackgroundOperations fake = mock(WorkflowBackgroundOperations.class);
        when(fake.reconcileOverdueAttempts(25)).thenReturn(3);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        DeadlineReconciliationScanner scanner = new DeadlineReconciliationScanner(
                fake, new ReconciliationProperties(Duration.ofSeconds(5), 25, Duration.ofHours(24)), metrics);

        scanner.scan();

        verify(fake).reconcileOverdueAttempts(25);
        assertEquals(
                3.0,
                metrics.get("resilientpay.reconciliation.deadline.scan")
                        .summary()
                        .totalAmount());
    }
}
