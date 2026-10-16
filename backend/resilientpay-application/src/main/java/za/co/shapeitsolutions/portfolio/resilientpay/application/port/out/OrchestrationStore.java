package za.co.shapeitsolutions.portfolio.resilientpay.application.port.out;

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
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Owns atomic persistence and process-manager mutations behind a replaceable hexagonal port.
 * Implementations must enforce tenant predicates and idempotency at their transaction boundary.
 *
 * @author Daniel Jr Nkulu
 */
public interface OrchestrationStore {
    /** Atomically accepts or safely replays a full reversal. */
    AcceptanceResult accept(ActorContext actor, AcceptReversalCommand command);

    /** Lists tenant-owned commands. */
    List<ReversalView> listReversals(TenantId tenantId, ReversalCommandState status, int limit);

    /** Gets a tenant-owned command or fails without revealing cross-tenant existence. */
    ReversalView getReversal(TenantId tenantId, UUID commandId);

    /** Executes a proven-safe bounded retry. */
    ReversalView retry(ActorContext actor, RetryReversalCommand command);

    /** Reads tenant-owned audit evidence. */
    List<AuditEvidenceView> evidence(TenantId tenantId, UUID commandId);

    /** Atomically ingests or safely replays a synthetic original. */
    OriginalTransactionView ingestOriginal(ActorContext actor, IngestOriginalCommand command);

    /** Lists tenant-owned originals. */
    List<OriginalTransactionView> listOriginals(TenantId tenantId);

    /** Lists tenant-owned active policy snapshots. */
    List<OperationPolicyView> listPolicies(TenantId tenantId);

    /** Lists tenant-owned reconciliation work. */
    List<ReconciliationCaseView> listReconciliationCases(TenantId tenantId);

    /** Records a tenant-owned maker proposal. */
    ReconciliationCaseView propose(ActorContext actor, ProposeReconciliationCommand command);

    /** Applies a tenant-owned distinct checker approval. */
    ReconciliationCaseView approve(ActorContext actor, ApproveReconciliationCommand command);
}
