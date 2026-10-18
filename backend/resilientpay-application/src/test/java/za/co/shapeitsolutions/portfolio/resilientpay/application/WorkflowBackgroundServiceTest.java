package za.co.shapeitsolutions.portfolio.resilientpay.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OutcomeObservation;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.TransactionBoundary;
import za.co.shapeitsolutions.portfolio.resilientpay.application.service.WorkflowBackgroundService;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Verifies application-owned background transaction choices and fail-closed completion semantics.
 * Persistence doubles assert scope, not database correctness; container suites prove the latter.
 *
 * @author Daniel Jr Nkulu
 */
class WorkflowBackgroundServiceTest {
    @Test
    void everyBackgroundMutationCompletesItsOwnRequiredScopeBeforeReturning() {
        RecordingBoundary boundary = new RecordingBoundary();
        UUID tenant = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        Instant observed = Instant.parse("2026-10-18T00:00:00Z");
        OutcomeObservation outcome = new OutcomeObservation(
                new TenantId(tenant),
                message,
                correlation,
                UUID.randomUUID(),
                "a".repeat(64),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "EXECUTED",
                null,
                observed);
        List<String> calls = new ArrayList<>();
        WorkflowBackgroundService service = new WorkflowBackgroundService(
                boundary,
                value -> {
                    assertTrue(boundary.active);
                    assertSame(outcome, value);
                    calls.add("outcome");
                },
                limit -> {
                    assertTrue(boundary.active);
                    assertEquals(17, limit);
                    calls.add("deadline");
                    return 3;
                },
                (actualTenant, actualMessage, hash, actualCorrelation, event, at) -> {
                    assertTrue(boundary.active);
                    assertEquals(tenant, actualTenant);
                    assertEquals(message, actualMessage);
                    assertEquals(correlation, actualCorrelation);
                    assertEquals("b".repeat(64), hash);
                    assertEquals("ReversalAccepted.v1", event);
                    assertEquals(observed, at);
                    calls.add("lifecycle");
                    return 1;
                },
                (actualTenant, actualMessage, actualCorrelation, digest, error) -> {
                    assertTrue(boundary.active);
                    assertEquals(tenant, actualTenant);
                    assertEquals(message.toString(), actualMessage);
                    assertEquals(correlation, actualCorrelation);
                    assertEquals("c".repeat(64), digest);
                    assertEquals("INVALID_JSON_PAYLOAD", error);
                    calls.add("quarantine");
                });

        service.processOutcome(outcome);
        assertFalse(boundary.active);
        assertEquals(
                1,
                service.recordLifecycle(tenant, message, "b".repeat(64), correlation, "ReversalAccepted.v1", observed));
        assertFalse(boundary.active);
        service.quarantine(tenant, message.toString(), correlation, "c".repeat(64), "INVALID_JSON_PAYLOAD");
        assertFalse(boundary.active);
        assertEquals(3, service.reconcileOverdueAttempts(17));

        assertFalse(boundary.active);
        assertEquals(4, boundary.committed);
        assertEquals(List.of("outcome", "lifecycle", "quarantine", "deadline"), calls);
    }

    @Test
    void persistenceFailureEscapesTheUseCaseWithoutSuccessfulCompletion() {
        RecordingBoundary boundary = new RecordingBoundary();
        IllegalStateException failure = new IllegalStateException("synthetic persistence failure");
        WorkflowBackgroundService service = new WorkflowBackgroundService(
                boundary,
                observation -> {
                    throw failure;
                },
                limit -> 0,
                (tenant, message, hash, correlation, event, at) -> 0,
                (tenant, message, correlation, digest, error) -> {
                    throw failure;
                });

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.processOutcome(null)));
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () -> service.quarantine(null, null, null, "a".repeat(64), "INVALID_JSON_PAYLOAD")));

        assertFalse(boundary.active);
        assertEquals(0, boundary.committed);
        assertEquals(2, boundary.rolledBack);
    }

    /**
     * Records only required-scope selection and propagates failures without acknowledging work.
     *
     * @author Daniel Jr Nkulu
     */
    private static final class RecordingBoundary implements TransactionBoundary {
        private boolean active;
        private int committed;
        private int rolledBack;

        @Override
        public <T> T required(Supplier<T> work) {
            assertFalse(active, "each adapter invocation must enter a fresh application scope");
            active = true;
            try {
                T result = work.get();
                committed++;
                return result;
            } catch (RuntimeException failure) {
                rolledBack++;
                throw failure;
            } finally {
                active = false;
            }
        }

        @Override
        public <T> T readOnly(Supplier<T> work) {
            throw new AssertionError("background mutations cannot use a read-only scope");
        }

        @Override
        public <T> T requiresNew(Supplier<T> work) {
            throw new AssertionError("background workflow writes must not independently commit");
        }
    }
}
