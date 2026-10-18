package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Checks the Spring adapter's exact propagation/read-only requests and failure propagation.
 * Real PostgreSQL tests separately prove physical rollback and independent evidence commits.
 *
 * @author Daniel Jr Nkulu
 */
class SpringTransactionBoundaryTest {
    @Test
    void requiredAndReadOnlyUseExplicitDistinctDefinitions() {
        RecordingManager manager = new RecordingManager();
        SpringTransactionBoundary boundary = new SpringTransactionBoundary(manager);
        assertEquals("written", boundary.required(() -> "written"));
        assertEquals("read", boundary.readOnly(() -> "read"));
        assertEquals(
                List.of(TransactionDefinition.PROPAGATION_REQUIRED, TransactionDefinition.PROPAGATION_REQUIRED),
                manager.propagations);
        assertFalse(manager.readOnly.getFirst());
        assertTrue(manager.readOnly.getLast());
        assertEquals(2, manager.commits);
    }

    @Test
    void independentScopeRequestsRequiresNewAndCommits() {
        RecordingManager manager = new RecordingManager();
        new SpringTransactionBoundary(manager).requiresNew(() -> {});
        assertEquals(List.of(TransactionDefinition.PROPAGATION_REQUIRES_NEW), manager.propagations);
        assertEquals(1, manager.commits);
        assertFalse(manager.readOnly.getFirst());
    }

    @Test
    void runtimeFailureRollsBackAndIsNotSwallowed() {
        RecordingManager manager = new RecordingManager();
        SpringTransactionBoundary boundary = new SpringTransactionBoundary(manager);
        IllegalStateException expected = new IllegalStateException("synthetic persistence failure");
        assertSame(
                expected,
                assertThrows(
                        IllegalStateException.class,
                        () -> boundary.required(() -> {
                            throw expected;
                        })));
        assertEquals(0, manager.commits);
        assertEquals(1, manager.rollbacks);
    }

    /**
     * Captures manager invocations without claiming to simulate physical database transactions.
     *
     * @author Daniel Jr Nkulu
     */
    private static final class RecordingManager implements PlatformTransactionManager {
        private final List<Integer> propagations = new ArrayList<>();
        private final List<Boolean> readOnly = new ArrayList<>();
        private int commits;
        private int rollbacks;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            propagations.add(definition.getPropagationBehavior());
            readOnly.add(definition.isReadOnly());
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            commits++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbacks++;
        }
    }
}
