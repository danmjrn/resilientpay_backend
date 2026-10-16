package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AcceptReversalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AcceptanceResult;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ActorContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApproveReconciliationCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.DemonstrationFailure;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ProposeReconciliationCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ReconciliationCaseView;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation.ReconciliationCaseState;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation.ReconciliationDecision;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalCommandState;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalReason;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ActorId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.CorrelationId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.Money;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Verifies deterministic test fixtures, tenant isolation, and action-scoped idempotency replay.
 *
 * @author Daniel Jr Nkulu
 */
class DemoOrchestrationStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-20T10:15:30Z");
    private static final TenantId TENANT = new TenantId(DemoOrchestrationStore.DEMO_TENANT_VALUE);
    private DemoOrchestrationStore store;

    @BeforeEach
    void setUp() {
        store = new DemoOrchestrationStore(Clock.fixed(NOW, ZoneOffset.UTC), "deterministic-evidence-test-key");
    }

    @Test
    void canonicalFixturesExposeExactTypesAndAmounts() {
        assertEquals(4, store.listPolicies(TENANT).size());
        assertEquals(4, store.listOriginals(TENANT).size());
        assertTrue(store.listOriginals(TENANT).stream()
                .anyMatch(original -> original.transactionToken().equals("synth-txn-transfer-001")
                        && original.amount().compareTo(new BigDecimal("1250")) == 0));
        assertTrue(store.listOriginals(TENANT).stream()
                .anyMatch(original -> original.transactionToken().equals("synth-txn-merchant-01")
                        && original.amount().compareTo(new BigDecimal("840")) == 0));
    }

    @Test
    void acceptanceReplaysSameRequestAndRejectsKeyReuse() {
        String key = "accept-key-0000001";
        AcceptReversalCommand command = acceptCommand(
                "synth-txn-card-0001", OperationType.CARD_PURCHASE, "120.00", DemonstrationFailure.NONE, key);

        AcceptanceResult accepted = store.accept(actor("operator"), command);
        AcceptanceResult replayed = store.accept(actor("operator"), command);

        assertFalse(accepted.replayed());
        assertTrue(replayed.replayed());
        assertEquals(accepted.command().id(), replayed.command().id());
        assertEquals(ReversalCommandState.COMPLETED, accepted.command().status());
        ApplicationException conflict = assertThrows(
                ApplicationException.class,
                () -> store.accept(
                        actor("operator"),
                        acceptCommand(
                                "synth-txn-transfer-001",
                                OperationType.ACCOUNT_TRANSFER,
                                "1250.00",
                                DemonstrationFailure.NONE,
                                key)));
        assertEquals(ApplicationErrorCode.IDEMPOTENCY_KEY_REUSED, conflict.code());
    }

    @Test
    void ambiguousOutcomeUsesIdempotentMakerCheckerCommands() {
        AcceptanceResult accepted = store.accept(
                actor("operator"),
                acceptCommand(
                        "synth-txn-transfer-001",
                        OperationType.ACCOUNT_TRANSFER,
                        "1250.00",
                        DemonstrationFailure.AMBIGUOUS,
                        "ambiguous-key-001"));
        ReconciliationCaseView opened = store.listReconciliationCases(TENANT).getFirst();
        ProposeReconciliationCommand proposal = new ProposeReconciliationCommand(
                opened.id(),
                ReconciliationDecision.CONFIRM_NOT_EXECUTED,
                "Evidence confirms no execution",
                "a".repeat(64),
                "proposal-key-0001",
                new CorrelationId(UUID.randomUUID()));

        ReconciliationCaseView proposed = store.propose(actor("maker"), proposal);
        ReconciliationCaseView replay = store.propose(actor("maker"), proposal);

        assertEquals(proposed, replay);
        ApplicationException sameActor = assertThrows(
                ApplicationException.class,
                () -> store.approve(
                        actor("maker"),
                        new ApproveReconciliationCommand(
                                opened.id(),
                                "Approve verified evidence",
                                "approval-key-0001",
                                new CorrelationId(UUID.randomUUID()))));
        assertEquals(ApplicationErrorCode.RECONCILIATION_APPROVER_CONFLICT, sameActor.code());
        ReconciliationCaseView resolved = store.approve(
                actor("checker"),
                new ApproveReconciliationCommand(
                        opened.id(),
                        "Approve verified evidence",
                        "approval-key-0002",
                        new CorrelationId(UUID.randomUUID())));
        assertEquals(ReconciliationCaseState.RESOLVED, resolved.status());
        assertEquals(
                ReversalCommandState.RETRY_SCHEDULED,
                store.getReversal(TENANT, accepted.command().id()).status());
    }

    @Test
    void crossTenantLookupIsIndistinguishableFromMissingResource() {
        AcceptanceResult accepted = store.accept(
                actor("operator"),
                acceptCommand(
                        "synth-txn-cash-0001",
                        OperationType.CASH_WITHDRAWAL,
                        "500.00",
                        DemonstrationFailure.NONE,
                        "cash-key-00000001"));

        ApplicationException missing = assertThrows(
                ApplicationException.class,
                () -> store.getReversal(
                        new TenantId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                        accepted.command().id()));

        assertEquals(ApplicationErrorCode.RESOURCE_NOT_FOUND, missing.code());
    }

    private static AcceptReversalCommand acceptCommand(
            String token, OperationType type, String amount, DemonstrationFailure failure, String idempotencyKey) {
        return new AcceptReversalCommand(
                token,
                type,
                new Money(new BigDecimal(amount), Currency.getInstance("ZAR")),
                new ReversalReason("Synthetic correction"),
                failure,
                idempotencyKey,
                new CorrelationId(UUID.randomUUID()));
    }

    private static ActorContext actor(String subject) {
        return new ActorContext(TENANT, new ActorId(subject), Set.of());
    }
}
