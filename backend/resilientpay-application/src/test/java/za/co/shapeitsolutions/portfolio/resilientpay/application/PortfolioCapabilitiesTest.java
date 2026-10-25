package za.co.shapeitsolutions.portfolio.resilientpay.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ActorContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ReconciliationCaseView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ReversalView;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation.ReconciliationCaseState;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalCommandState;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ActorId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Prevents server-rendered capabilities from advertising actions outside persona scopes and state.
 *
 * @author Daniel Jr Nkulu
 */
class PortfolioCapabilitiesTest {
    private static final String AAL2 = "urn:resilientpay:aal2";

    @Test
    void auditorCanReadEvidenceButCannotRequestRetryEvenWhenRetryIsDue() {
        ReversalView view = reversal(List.of("VIEW", "VIEW_EVIDENCE", "REQUEST_SAFE_RETRY", "INVENTED_ACTION"));
        assertEquals(
                List.of("VIEW", "VIEW_EVIDENCE"),
                view.authorizedFor(actor(Set.of("SCOPE_reversals.read", "SCOPE_audit.read"), null))
                        .allowedActions());
        assertEquals(
                List.of("VIEW", "REQUEST_SAFE_RETRY"),
                view.authorizedFor(actor(Set.of("SCOPE_reversals.read", "SCOPE_reversals.retry"), null))
                        .allowedActions());
        assertEquals(List.of(), view.authorizedFor(actor(Set.of(), null)).allowedActions());
    }

    @Test
    void scopeCannotCreateAnActionAbsentFromTheStatePolicyRepresentation() {
        ReversalView view = reversal(List.of("VIEW", "VIEW_EVIDENCE"));
        assertFalse(view.authorizedFor(actor(Set.of("SCOPE_reversals.read", "SCOPE_reversals.retry"), null))
                .allowedActions()
                .contains("REQUEST_SAFE_RETRY"));
    }

    @Test
    void makerAndCheckerReceiveOnlyStateAppropriateReconciliationActions() {
        ActorContext maker =
                actor(Set.of("SCOPE_reconciliation.read", "SCOPE_reconciliation.decide", "SCOPE_audit.read"), null);
        ActorContext checker =
                actor(Set.of("SCOPE_reconciliation.read", "SCOPE_reconciliation.approve", "SCOPE_audit.read"), AAL2);
        assertTrue(reconciliation(ReconciliationCaseState.OPEN)
                .authorizedFor(maker, AAL2)
                .allowedActions()
                .contains("PROPOSE_DECISION"));
        assertFalse(reconciliation(ReconciliationCaseState.OPEN)
                .authorizedFor(checker, AAL2)
                .allowedActions()
                .contains("APPROVE_DECISION"));
        assertFalse(reconciliation(ReconciliationCaseState.DECISION_PROPOSED)
                .authorizedFor(maker, AAL2)
                .allowedActions()
                .contains("APPROVE_DECISION"));
        assertTrue(reconciliation(ReconciliationCaseState.DECISION_PROPOSED)
                .authorizedFor(checker, AAL2)
                .allowedActions()
                .contains("APPROVE_DECISION"));
        assertFalse(reconciliation(ReconciliationCaseState.RESOLVED)
                .authorizedFor(checker, AAL2)
                .allowedActions()
                .contains("APPROVE_DECISION"));
        assertFalse(reconciliation(ReconciliationCaseState.DECISION_PROPOSED)
                .authorizedFor(actor(checker.authorities(), "urn:resilientpay:aal1"), AAL2)
                .allowedActions()
                .contains("APPROVE_DECISION"));
    }

    private static ReversalView reversal(List<String> actions) {
        return new ReversalView(
                UUID.randomUUID(),
                "synth-capability-fixture-01",
                OperationType.CARD_PURCHASE,
                BigDecimal.ONE,
                "ZAR",
                ReversalCommandState.RETRY_SCHEDULED,
                "SWITCH_NOT_RECEIVED",
                UUID.randomUUID(),
                1,
                Instant.EPOCH,
                Instant.EPOCH,
                1,
                actions);
    }

    private static ReconciliationCaseView reconciliation(ReconciliationCaseState state) {
        return new ReconciliationCaseView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                state,
                "SYNTHETIC_AMBIGUITY",
                null,
                null,
                null,
                null,
                Instant.EPOCH,
                null);
    }

    private static ActorContext actor(Set<String> scopes, String assurance) {
        return new ActorContext(new TenantId(UUID.randomUUID()), new ActorId("synthetic-persona"), scopes, assurance);
    }
}
