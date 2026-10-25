package za.co.shapeitsolutions.portfolio.resilientpay.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ActorContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.MutationAdmissionPort;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OrchestrationStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.SecurityAuditPort;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.TransactionBoundary;
import za.co.shapeitsolutions.portfolio.resilientpay.application.service.ReversalApplicationService;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ActorId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Verifies application-level least privilege before any outbound port invocation.
 *
 * @author Daniel Jr Nkulu
 */
class ReversalApplicationServiceTest {
    private static final TenantId TENANT = new TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final String APPROVAL_ASSURANCE = "urn:resilientpay:aal2";

    @Test
    void readScopePassesOnlyAuthenticatedTenantToStore() {
        AtomicReference<TenantId> observedTenant = new AtomicReference<>();
        OrchestrationStore store = proxy((methodName, arguments) -> {
            if ("listPolicies".equals(methodName)) {
                observedTenant.set((TenantId) arguments[0]);
                return List.of();
            }
            throw new AssertionError("Unexpected outbound method: " + methodName);
        });
        ReversalApplicationService service = service(store, noOpAudit());

        assertEquals(List.of(), service.listPolicies(actor("SCOPE_policies.read")));

        assertEquals(TENANT, observedTenant.get());
    }

    @Test
    void missingScopeFailsBeforeOutboundAdapter() {
        AtomicInteger calls = new AtomicInteger();
        OrchestrationStore store = proxy((methodName, arguments) -> calls.incrementAndGet());
        AtomicReference<String> deniedAction = new AtomicReference<>();
        ReversalApplicationService service = service(
                store,
                (actor, action, resourceId, correlationId, reasonCode) -> deniedAction.set(action + ":" + reasonCode));

        ApplicationException failure = assertThrows(
                ApplicationException.class, () -> service.listReconciliationCases(actor("SCOPE_reversals.read")));

        assertEquals(ApplicationErrorCode.ACCESS_DENIED, failure.code());
        assertEquals(0, calls.get());
        assertEquals("LIST_RECONCILIATION_CASES:MISSING_REQUIRED_AUTHORITY", deniedAction.get());
    }

    @Test
    void approvalScopeDoesNotImplicitlyGrantMakerAuthority() {
        AtomicInteger calls = new AtomicInteger();
        OrchestrationStore store = proxy((methodName, arguments) -> calls.incrementAndGet());
        ReversalApplicationService service = service(store, noOpAudit());

        ApplicationException failure = assertThrows(
                ApplicationException.class, () -> service.propose(actor("SCOPE_reconciliation.approve"), null));

        assertEquals(ApplicationErrorCode.ACCESS_DENIED, failure.code());
        assertEquals(0, calls.get());
    }

    @Test
    void checkerApprovalRequiresConfiguredAuthenticationAssurance() {
        AtomicInteger storeCalls = new AtomicInteger();
        AtomicInteger admissionCalls = new AtomicInteger();
        AtomicReference<String> deniedReason = new AtomicReference<>();
        OrchestrationStore store = proxy((methodName, arguments) -> {
            storeCalls.incrementAndGet();
            return null;
        });
        ReversalApplicationService service = service(
                store,
                (tenantId, action) -> admissionCalls.incrementAndGet(),
                (actor, action, resourceId, correlationId, reasonCode) -> deniedReason.set(reasonCode));

        ApplicationException missing = assertThrows(
                ApplicationException.class, () -> service.approve(actor("SCOPE_reconciliation.approve"), null));
        ApplicationException insufficient = assertThrows(
                ApplicationException.class,
                () -> service.approve(actor("SCOPE_reconciliation.approve", "urn:resilientpay:aal1"), null));
        service.approve(actor("SCOPE_reconciliation.approve", APPROVAL_ASSURANCE), null);

        assertEquals(ApplicationErrorCode.ACCESS_DENIED, missing.code());
        assertEquals(ApplicationErrorCode.ACCESS_DENIED, insufficient.code());
        assertEquals("INSUFFICIENT_AUTHENTICATION_ASSURANCE", deniedReason.get());
        assertEquals(1, admissionCalls.get());
        assertEquals(1, storeCalls.get());
    }

    @Test
    void deniedMutationNeverConsumesAdmissionCapacity() {
        AtomicInteger admissionCalls = new AtomicInteger();
        AtomicInteger storeCalls = new AtomicInteger();
        ReversalApplicationService service = service(
                proxy((methodName, arguments) -> storeCalls.incrementAndGet()),
                (tenantId, action) -> admissionCalls.incrementAndGet(),
                noOpAudit());

        ApplicationException failure =
                assertThrows(ApplicationException.class, () -> service.retry(actor("SCOPE_reversals.read"), null));

        assertEquals(ApplicationErrorCode.ACCESS_DENIED, failure.code());
        assertEquals(0, admissionCalls.get());
        assertEquals(0, storeCalls.get());
    }

    @Test
    void successfulEvidenceReadIsAuditedAfterTenantScopedStoreAccess() {
        AtomicInteger storeCalls = new AtomicInteger();
        AtomicReference<UUID> auditedCommand = new AtomicReference<>();
        UUID commandId = UUID.randomUUID();
        OrchestrationStore store = proxy((methodName, arguments) -> {
            if (!"evidence".equals(methodName)) {
                throw new AssertionError("Unexpected outbound method: " + methodName);
            }
            storeCalls.incrementAndGet();
            return List.of();
        });
        SecurityAuditPort audit = new SecurityAuditPort() {
            @Override
            public void recordAuthorizationDenied(
                    ActorContext actor, String action, UUID resourceId, UUID correlationId, String reasonCode) {}

            @Override
            public void recordEvidenceViewed(ActorContext actor, UUID resourceId, UUID correlationId) {
                auditedCommand.set(resourceId);
            }
        };
        ReversalApplicationService service = service(store, audit);

        assertEquals(List.of(), service.evidence(actor("SCOPE_audit.read"), commandId));
        assertEquals(1, storeCalls.get());
        assertEquals(commandId, auditedCommand.get());
    }

    @Test
    void mutationAdmissionRunsAfterAuthorizationAndBeforePersistence() {
        List<String> order = new ArrayList<>();
        OrchestrationStore store = proxy((methodName, arguments) -> {
            assertEquals("retry", methodName);
            order.add("store");
            return null;
        });
        MutationAdmissionPort admission = (tenantId, action) -> {
            assertEquals(TENANT, tenantId);
            assertEquals("retry-reversal", action);
            order.add("admission");
        };
        ReversalApplicationService service = service(store, admission, noOpAudit());

        service.retry(actor("SCOPE_reversals.retry"), null);

        assertEquals(List.of("admission", "store"), order);
    }

    @Test
    void unavailableMutationAdmissionFailsBeforePersistence() {
        AtomicInteger storeCalls = new AtomicInteger();
        OrchestrationStore store = proxy((methodName, arguments) -> storeCalls.incrementAndGet());
        MutationAdmissionPort admission = (tenantId, action) -> {
            throw new ApplicationException(
                    ApplicationErrorCode.DEPENDENCY_UNAVAILABLE, "Mutation admission is temporarily unavailable");
        };
        ReversalApplicationService service = service(store, admission, noOpAudit());

        ApplicationException failure =
                assertThrows(ApplicationException.class, () -> service.retry(actor("SCOPE_reversals.retry"), null));

        assertEquals(ApplicationErrorCode.DEPENDENCY_UNAVAILABLE, failure.code());
        assertEquals(0, storeCalls.get());
    }

    @Test
    void everyMutationOwnsOneWriteScopeAfterAdmission() {
        RecordingTransactions transactions = new RecordingTransactions();
        OrchestrationStore store = proxy((method, arguments) -> {
            assertTrue(transactions.active);
            assertEquals("required", transactions.current);
            transactions.order.add(method);
            return null;
        });
        ReversalApplicationService service = new ReversalApplicationService(
                store,
                (tenant, action) -> {
                    assertFalse(transactions.active);
                    transactions.order.add("admission");
                },
                noOpAudit(),
                transactions,
                APPROVAL_ASSURANCE);
        service.accept(actor("SCOPE_reversals.write"), null);
        service.retry(actor("SCOPE_reversals.retry"), null);
        service.ingestOriginal(actor("SCOPE_transactions.write"), null);
        service.propose(actor("SCOPE_reconciliation.decide"), null);
        service.approve(actor("SCOPE_reconciliation.approve", APPROVAL_ASSURANCE), null);
        List<String> expected = new ArrayList<>();
        for (String method : List.of("accept", "retry", "ingestOriginal", "propose", "approve")) {
            expected.addAll(List.of("admission", "begin-required", method, "end-required"));
        }
        assertEquals(expected, transactions.order);
    }

    @Test
    void everyQueryOwnsOneReadOnlyScopeAndEvidenceAuditRunsAfterIt() {
        RecordingTransactions transactions = new RecordingTransactions();
        OrchestrationStore store = proxy((method, arguments) -> {
            assertTrue(transactions.active);
            assertEquals("readOnly", transactions.current);
            return "getReversal".equals(method) ? null : List.of();
        });
        SecurityAuditPort audit = new SecurityAuditPort() {
            @Override
            public void recordAuthorizationDenied(
                    ActorContext actor, String action, UUID resource, UUID correlation, String reason) {
                throw new AssertionError("No authorized query should be denied");
            }

            @Override
            public void recordEvidenceViewed(ActorContext actor, UUID resource, UUID correlation) {
                assertFalse(transactions.active);
                transactions.order.add("independent-evidence");
            }
        };
        ReversalApplicationService service = new ReversalApplicationService(
                store,
                (tenant, action) -> {
                    throw new AssertionError("Queries do not consume admission");
                },
                audit,
                transactions,
                APPROVAL_ASSURANCE);
        service.listReversals(actor("SCOPE_reversals.read"), null, 50);
        service.getReversal(actor("SCOPE_reversals.read"), UUID.randomUUID());
        service.listOriginals(actor("SCOPE_transactions.read"));
        service.listPolicies(actor("SCOPE_policies.read"));
        service.listReconciliationCases(actor("SCOPE_reconciliation.read"));
        service.evidence(actor("SCOPE_audit.read"), UUID.randomUUID());
        assertEquals(
                6, transactions.order.stream().filter("begin-readOnly"::equals).count());
        assertEquals("independent-evidence", transactions.order.getLast());
    }

    @Test
    void authorizationAndAdmissionFailuresNeverOpenDatabaseScope() {
        RecordingTransactions transactions = new RecordingTransactions();
        ReversalApplicationService service = new ReversalApplicationService(
                proxy((method, arguments) -> {
                    throw new AssertionError("Store must not be invoked");
                }),
                (tenant, action) -> {
                    throw new IllegalStateException("synthetic admission outage");
                },
                noOpAudit(),
                transactions,
                APPROVAL_ASSURANCE);
        assertThrows(ApplicationException.class, () -> service.retry(actor("SCOPE_reversals.read"), null));
        assertThrows(IllegalStateException.class, () -> service.retry(actor("SCOPE_reversals.retry"), null));
        assertTrue(transactions.order.isEmpty());
    }

    @Test
    void storeFailurePropagatesThroughOwnedScopeWithoutSuccess() {
        RecordingTransactions transactions = new RecordingTransactions();
        IllegalStateException expected = new IllegalStateException("synthetic persistence failure");
        ReversalApplicationService service = new ReversalApplicationService(
                proxy((method, arguments) -> {
                    throw expected;
                }),
                (tenant, action) -> {},
                noOpAudit(),
                transactions,
                APPROVAL_ASSURANCE);
        assertSame(
                expected,
                assertThrows(IllegalStateException.class, () -> service.retry(actor("SCOPE_reversals.retry"), null)));
        assertFalse(transactions.active);
        assertEquals(List.of("begin-required", "end-required"), transactions.order);
    }

    private static ActorContext actor(String authority) {
        return new ActorContext(TENANT, new ActorId("subject-123"), Set.of(authority));
    }

    private static ActorContext actor(String authority, String assurance) {
        return new ActorContext(TENANT, new ActorId("subject-123"), Set.of(authority), assurance);
    }

    private static ReversalApplicationService service(OrchestrationStore store, SecurityAuditPort audit) {
        return service(store, (tenantId, action) -> {}, audit);
    }

    private static ReversalApplicationService service(
            OrchestrationStore store, MutationAdmissionPort admission, SecurityAuditPort audit) {
        return new ReversalApplicationService(store, admission, audit, new RecordingTransactions(), APPROVAL_ASSURANCE);
    }

    /**
     * Observes application-selected scope and ordering without pretending to exercise a database.
     *
     * @author Daniel Jr Nkulu
     */
    private static final class RecordingTransactions implements TransactionBoundary {
        private final List<String> order = new ArrayList<>();
        private boolean active;
        private String current;

        @Override
        public <T> T required(Supplier<T> work) {
            return execute("required", work);
        }

        @Override
        public <T> T readOnly(Supplier<T> work) {
            return execute("readOnly", work);
        }

        @Override
        public <T> T requiresNew(Supplier<T> work) {
            return execute("requiresNew", work);
        }

        private <T> T execute(String mode, Supplier<T> work) {
            assertFalse(active);
            order.add("begin-" + mode);
            active = true;
            current = mode;
            try {
                return work.get();
            } finally {
                active = false;
                current = null;
                order.add("end-" + mode);
            }
        }
    }

    private static OrchestrationStore proxy(Invocation invocation) {
        return (OrchestrationStore) Proxy.newProxyInstance(
                OrchestrationStore.class.getClassLoader(),
                new Class<?>[] {OrchestrationStore.class},
                (proxy, method, arguments) -> {
                    Object result = invocation.invoke(method.getName(), arguments);
                    return result == null ? validResponse(method.getName()) : result;
                });
    }

    private static Object validResponse(String method) {
        var view = new za.co.shapeitsolutions.portfolio.resilientpay.application.model.ReversalView(
                UUID.randomUUID(),
                "synth-application-fixture-0001",
                za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType.CARD_PURCHASE,
                java.math.BigDecimal.ONE,
                "ZAR",
                za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalCommandState.COMPLETED,
                null,
                UUID.randomUUID(),
                1,
                java.time.Instant.EPOCH,
                java.time.Instant.EPOCH,
                1,
                List.of("VIEW"));
        return switch (method) {
            case "accept" ->
                new za.co.shapeitsolutions.portfolio.resilientpay.application.model.AcceptanceResult(view, false);
            case "retry", "getReversal" -> view;
            case "propose", "approve" ->
                new za.co.shapeitsolutions.portfolio.resilientpay.application.model.ReconciliationCaseView(
                        UUID.randomUUID(),
                        view.id(),
                        za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation.ReconciliationCaseState
                                .RESOLVED,
                        "SYNTHETIC_AMBIGUITY",
                        "CONFIRM_EXECUTED",
                        "synthetic-maker",
                        "Synthetic fixture purpose",
                        "a".repeat(64),
                        java.time.Instant.EPOCH,
                        java.time.Instant.EPOCH);
            default -> null;
        };
    }

    private static SecurityAuditPort noOpAudit() {
        return (actor, action, resourceId, correlationId, reasonCode) -> {};
    }

    /**
     * Supplies a tiny dependency-free invocation hook for application-port tests.
     *
     * @author Daniel Jr Nkulu
     */
    @FunctionalInterface
    private interface Invocation {
        Object invoke(String methodName, Object[] arguments);
    }
}
