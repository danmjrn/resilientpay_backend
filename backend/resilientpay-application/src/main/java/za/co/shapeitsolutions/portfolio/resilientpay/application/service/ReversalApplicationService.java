package za.co.shapeitsolutions.portfolio.resilientpay.application.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AcceptReversalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AcceptanceResult;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ActorContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApproveReconciliationCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AuditEvidenceView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.IngestOriginalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OperationPolicyView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OriginalTransactionView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ProposeReconciliationCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ReconciliationCaseView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.RetryReversalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ReversalView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.ReversalOperations;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.MutationAdmissionPort;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OrchestrationStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.SecurityAuditPort;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.TransactionBoundary;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalCommandState;

/**
 * Owns database transaction scope after action-level authorization and mutation admission. It
 * keeps Redis admission outside the database unit and uses separate read-only query scopes.
 *
 * @author Daniel Jr Nkulu
 */
public final class ReversalApplicationService implements ReversalOperations {
    private final OrchestrationStore store;
    private final MutationAdmissionPort mutationAdmission;
    private final SecurityAuditPort securityAudit;
    private final TransactionBoundary transactions;
    private final String requiredApprovalAssurance;

    /** Creates the use-case service around replaceable admission, persistence and audit adapters. */
    public ReversalApplicationService(
            OrchestrationStore store,
            MutationAdmissionPort mutationAdmission,
            SecurityAuditPort securityAudit,
            TransactionBoundary transactions,
            String requiredApprovalAssurance) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.mutationAdmission = Objects.requireNonNull(mutationAdmission, "mutationAdmission must not be null");
        this.securityAudit = Objects.requireNonNull(securityAudit, "securityAudit must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.requiredApprovalAssurance = Objects.requireNonNull(
                        requiredApprovalAssurance, "requiredApprovalAssurance must not be null")
                .strip();
        if (this.requiredApprovalAssurance.isEmpty()) {
            throw new IllegalArgumentException("requiredApprovalAssurance must not be blank");
        }
    }

    @Override
    public AcceptanceResult accept(ActorContext actor, AcceptReversalCommand command) {
        authorize(
                actor,
                "SCOPE_reversals.write",
                "ACCEPT_REVERSAL",
                actor.tenantId().value(),
                correlation(actor, command));
        mutationAdmission.requirePermit(actor.tenantId(), "accept-reversal");
        return transactions.required(() -> store.accept(actor, command));
    }

    @Override
    public List<ReversalView> listReversals(ActorContext actor, ReversalCommandState status, int limit) {
        authorize(
                actor,
                "SCOPE_reversals.read",
                "LIST_REVERSALS",
                actor.tenantId().value(),
                actor.tenantId().value());
        return transactions.readOnly(() -> store.listReversals(actor.tenantId(), status, limit));
    }

    @Override
    public ReversalView getReversal(ActorContext actor, UUID commandId) {
        authorize(actor, "SCOPE_reversals.read", "READ_REVERSAL", commandId, commandId);
        return transactions.readOnly(() -> store.getReversal(actor.tenantId(), commandId));
    }

    @Override
    public ReversalView retry(ActorContext actor, RetryReversalCommand command) {
        UUID resourceId = command == null ? actor.tenantId().value() : command.reversalCommandId();
        UUID correlationId =
                command == null ? resourceId : command.correlationId().value();
        authorize(actor, "SCOPE_reversals.retry", "RETRY_REVERSAL", resourceId, correlationId);
        mutationAdmission.requirePermit(actor.tenantId(), "retry-reversal");
        return transactions.required(() -> store.retry(actor, command));
    }

    @Override
    public List<AuditEvidenceView> evidence(ActorContext actor, UUID commandId) {
        authorize(actor, "SCOPE_audit.read", "READ_AUDIT_EVIDENCE", commandId, commandId);
        List<AuditEvidenceView> evidence = transactions.readOnly(() -> store.evidence(actor.tenantId(), commandId));
        securityAudit.recordEvidenceViewed(actor, commandId, commandId);
        return evidence;
    }

    @Override
    public OriginalTransactionView ingestOriginal(ActorContext actor, IngestOriginalCommand command) {
        authorize(
                actor,
                "SCOPE_transactions.write",
                "INGEST_ORIGINAL",
                actor.tenantId().value(),
                correlation(actor, command));
        mutationAdmission.requirePermit(actor.tenantId(), "ingest-original");
        return transactions.required(() -> store.ingestOriginal(actor, command));
    }

    @Override
    public List<OriginalTransactionView> listOriginals(ActorContext actor) {
        authorize(
                actor,
                "SCOPE_transactions.read",
                "LIST_ORIGINALS",
                actor.tenantId().value(),
                actor.tenantId().value());
        return transactions.readOnly(() -> store.listOriginals(actor.tenantId()));
    }

    @Override
    public List<OperationPolicyView> listPolicies(ActorContext actor) {
        authorize(
                actor,
                "SCOPE_policies.read",
                "LIST_POLICIES",
                actor.tenantId().value(),
                actor.tenantId().value());
        return transactions.readOnly(() -> store.listPolicies(actor.tenantId()));
    }

    @Override
    public List<ReconciliationCaseView> listReconciliationCases(ActorContext actor) {
        authorize(
                actor,
                "SCOPE_reconciliation.read",
                "LIST_RECONCILIATION_CASES",
                actor.tenantId().value(),
                actor.tenantId().value());
        return transactions.readOnly(() -> store.listReconciliationCases(actor.tenantId()));
    }

    @Override
    public ReconciliationCaseView propose(ActorContext actor, ProposeReconciliationCommand command) {
        UUID resourceId = command == null ? actor.tenantId().value() : command.caseId();
        UUID correlationId =
                command == null ? resourceId : command.correlationId().value();
        authorize(actor, "SCOPE_reconciliation.decide", "PROPOSE_RECONCILIATION", resourceId, correlationId);
        mutationAdmission.requirePermit(actor.tenantId(), "propose-reconciliation");
        return transactions.required(() -> store.propose(actor, command));
    }

    @Override
    public ReconciliationCaseView approve(ActorContext actor, ApproveReconciliationCommand command) {
        UUID resourceId = command == null ? actor.tenantId().value() : command.caseId();
        UUID correlationId =
                command == null ? resourceId : command.correlationId().value();
        authorize(actor, "SCOPE_reconciliation.approve", "APPROVE_RECONCILIATION", resourceId, correlationId);
        requireApprovalAssurance(actor, resourceId, correlationId);
        mutationAdmission.requirePermit(actor.tenantId(), "approve-reconciliation");
        return transactions.required(() -> store.approve(actor, command));
    }

    private void authorize(ActorContext actor, String authority, String action, UUID resourceId, UUID correlationId) {
        try {
            actor.requireAuthority(authority);
        } catch (za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException failure) {
            securityAudit.recordAuthorizationDenied(
                    actor, action, resourceId, correlationId, "MISSING_REQUIRED_AUTHORITY");
            throw failure;
        }
    }

    private static UUID correlation(ActorContext actor, AcceptReversalCommand command) {
        return command == null
                ? actor.tenantId().value()
                : command.correlationId().value();
    }

    private static UUID correlation(ActorContext actor, IngestOriginalCommand command) {
        return command == null
                ? actor.tenantId().value()
                : command.correlationId().value();
    }

    private void requireApprovalAssurance(ActorContext actor, UUID resourceId, UUID correlationId) {
        try {
            actor.requireAssurance(requiredApprovalAssurance);
        } catch (za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException failure) {
            securityAudit.recordAuthorizationDenied(
                    actor,
                    "APPROVE_RECONCILIATION",
                    resourceId,
                    correlationId,
                    "INSUFFICIENT_AUTHENTICATION_ASSURANCE");
            throw failure;
        }
    }
}
