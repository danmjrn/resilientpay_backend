package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ReconciliationProperties;

/**
 * Proves that the scheduler delegates one configured bounded deadline batch.
 *
 * @author Daniel Jr Nkulu
 */
class DeadlineReconciliationScannerTest {

    @Test
    void scansExactlyTheConfiguredBatchSize() {
        AtomicInteger requested = new AtomicInteger();
        DeadlineReconciliationStore fake = limit -> {
            requested.set(limit);
            return 3;
        };
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        DeadlineReconciliationScanner scanner = new DeadlineReconciliationScanner(
                fake, new ReconciliationProperties(Duration.ofSeconds(5), 25, Duration.ofHours(24)), metrics);

        scanner.scan();

        assertEquals(25, requested.get());
        assertEquals(
                3.0,
                metrics.get("resilientpay.reconciliation.deadline.scan")
                        .summary()
                        .totalAmount());
    }
}
