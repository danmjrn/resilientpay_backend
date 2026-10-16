package za.co.shapeitsolutions.portfolio.resilientpay.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ActorContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.MutationAdmissionPort;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OrchestrationStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.SecurityAuditPort;
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
        return new ReversalApplicationService(store, admission, audit, APPROVAL_ASSURANCE);
    }

    private static OrchestrationStore proxy(Invocation invocation) {
        return (OrchestrationStore) Proxy.newProxyInstance(
                OrchestrationStore.class.getClassLoader(),
                new Class<?>[] {OrchestrationStore.class},
                (proxy, method, arguments) -> invocation.invoke(method.getName(), arguments));
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
