package za.co.shapeitsolutions.portfolio.resilientpay.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ActorContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AuditEntry;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.TransactionBoundary;
import za.co.shapeitsolutions.portfolio.resilientpay.application.service.AuditApplicationService;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ActorId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Verifies independent audit scope selection and minimised evidence without simulating database
 * durability; the real PostgreSQL application-entry tests exercise commit and rollback.
 *
 * @author Daniel Jr Nkulu
 */
class AuditApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void securityAndEvidenceAuditUseIndependentTransactionsWithUnchangedFields() {
        IndependentBoundary boundary = new IndependentBoundary();
        List<AuditEntry> entries = new ArrayList<>();
        AuditApplicationService service = new AuditApplicationService(
                entry -> {
                    assertTrue(boundary.active);
                    entries.add(entry);
                },
                boundary,
                CLOCK);
        ActorContext actor =
                new ActorContext(new TenantId(UUID.randomUUID()), new ActorId("synthetic-actor"), Set.of());
        UUID resource = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        service.recordAuthorizationDenied(actor, "RETRY_REVERSAL", resource, correlation, "MISSING_REQUIRED_AUTHORITY");
        service.recordEvidenceViewed(actor, resource, correlation);
        assertEquals(2, boundary.calls);
        assertEquals(
                new AuditEntry(
                        actor,
                        "RETRY_REVERSAL",
                        "REVERSAL_COMMAND",
                        resource,
                        "AUTHORIZATION",
                        "DENIED",
                        "MISSING_REQUIRED_AUTHORITY",
                        correlation,
                        null,
                        Map.of("decision", "DENIED"),
                        NOW),
                entries.getFirst());
        assertEquals("EVIDENCE_VIEWED", entries.getLast().action());
        assertEquals(
                Map.of("view", "REDACTED_AUDIT_TIMELINE"), entries.getLast().safeDetails());
        assertEquals("SUCCEEDED", entries.getLast().outcome());
    }

    @Test
    void rawControlEvidenceRetainsItsOriginalFields() {
        IndependentBoundary boundary = new IndependentBoundary();
        List<AuditEntry> entries = new ArrayList<>();
        AuditApplicationService service = new AuditApplicationService(entries::add, boundary, CLOCK);
        AuditEntry entry = new AuditEntry(
                new ActorContext(new TenantId(UUID.randomUUID()), new ActorId("synthetic-actor"), Set.of()),
                "IDEMPOTENCY_KEY_REUSE_DENIED",
                "REVERSAL_COMMAND",
                UUID.randomUUID(),
                "CONTROL_ENFORCEMENT",
                "DENIED",
                "IDEMPOTENCY_KEY_REUSED",
                UUID.randomUUID(),
                null,
                Map.of("idempotencyAction", "ACCEPT_REVERSAL"),
                NOW);
        service.recordIndependent(entry);
        assertSame(entry, entries.getFirst());
        assertEquals(1, boundary.calls);
    }

    @Test
    void failedIndependentAuditPropagatesWithoutSuccessfulCompletion() {
        IndependentBoundary boundary = new IndependentBoundary();
        IllegalStateException expected = new IllegalStateException("synthetic audit outage");
        AuditApplicationService service = new AuditApplicationService(
                entry -> {
                    throw expected;
                },
                boundary,
                CLOCK);
        ActorContext actor =
                new ActorContext(new TenantId(UUID.randomUUID()), new ActorId("synthetic-actor"), Set.of());
        assertSame(
                expected,
                assertThrows(
                        IllegalStateException.class,
                        () -> service.recordEvidenceViewed(actor, UUID.randomUUID(), UUID.randomUUID())));
        assertEquals(0, boundary.completed);
    }

    /**
     * Rejects a mistaken joined/read-only policy and observes only explicitly independent work.
     *
     * @author Daniel Jr Nkulu
     */
    private static final class IndependentBoundary implements TransactionBoundary {
        private int calls;
        private int completed;
        private boolean active;

        @Override
        public <T> T required(Supplier<T> work) {
            throw new AssertionError("Audit must not join a rollback-prone transaction");
        }

        @Override
        public <T> T readOnly(Supplier<T> work) {
            throw new AssertionError("Audit requires a writable independent transaction");
        }

        @Override
        public <T> T requiresNew(Supplier<T> work) {
            calls++;
            active = true;
            try {
                T result = work.get();
                completed++;
                return result;
            } finally {
                active = false;
            }
        }
    }
}
