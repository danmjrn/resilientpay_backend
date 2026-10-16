package za.co.shapeitsolutions.portfolio.resilientpay.application.port.in;

import java.util.List;
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
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalCommandState;

/**
 * Defines the complete inbound application surface used by HTTP and future message adapters.
 *
 * @author Daniel Jr Nkulu
 */
public interface ReversalOperations {
    /** Accepts one idempotent full reversal. */
    AcceptanceResult accept(ActorContext actor, AcceptReversalCommand command);

    /** Lists tenant-scoped commands newest first. */
    List<ReversalView> listReversals(ActorContext actor, ReversalCommandState status, int limit);

    /** Gets one tenant-scoped reversal. */
    ReversalView getReversal(ActorContext actor, UUID commandId);

    /** Applies a retry only when the store holds confirmed non-execution evidence. */
    ReversalView retry(ActorContext actor, RetryReversalCommand command);

    /** Returns redacted append-only evidence for one command. */
    List<AuditEvidenceView> evidence(ActorContext actor, UUID commandId);

    /** Ingests one synthetic original transaction. */
    OriginalTransactionView ingestOriginal(ActorContext actor, IngestOriginalCommand command);

    /** Lists tenant-scoped original transactions. */
    List<OriginalTransactionView> listOriginals(ActorContext actor);

    /** Lists active policy snapshots. */
    List<OperationPolicyView> listPolicies(ActorContext actor);

    /** Lists operator-actionable reconciliation cases. */
    List<ReconciliationCaseView> listReconciliationCases(ActorContext actor);

    /** Records a maker's reconciliation proposal. */
    ReconciliationCaseView propose(ActorContext actor, ProposeReconciliationCommand command);

    /** Applies a distinct checker's reconciliation approval. */
    ReconciliationCaseView approve(ActorContext actor, ApproveReconciliationCommand command);
}
