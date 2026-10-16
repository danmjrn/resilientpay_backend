package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ReconciliationCaseView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.ReversalOperations;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ConditionalOnRuntimeRole;

/**
 * Implements tenant-scoped reconciliation listing, maker proposal, and checker approval.
 *
 * @author Daniel Jr Nkulu
 */
@RestController
@RequestMapping("/api/v1/reconciliationcases")
@ConditionalOnRuntimeRole("api")
public final class ReconciliationController {
    private final ReversalOperations operations;
    private final ActorContextFactory actors;
    private final WebCommandMapper commands;

    /** Creates the reconciliation HTTP adapter. */
    public ReconciliationController(
            ReversalOperations operations, ActorContextFactory actors, WebCommandMapper commands) {
        this.operations = operations;
        this.actors = actors;
        this.commands = commands;
    }

    /** Lists operator-actionable tenant-scoped reconciliation cases. */
    @GetMapping
    public List<ReconciliationCaseView> list(Authentication authentication) {
        return operations.listReconciliationCases(actors.from(authentication));
    }

    /** Records an evidence-backed maker proposal awaiting distinct approval. */
    @PostMapping("/{caseId}/decisions")
    public ResponseEntity<ReconciliationCaseView> propose(
            Authentication authentication,
            @PathVariable UUID caseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReconciliationDecisionRequest request) {
        ReconciliationCaseView result =
                operations.propose(actors.from(authentication), commands.proposal(caseId, request, idempotencyKey));
        return ResponseEntity.accepted().body(result);
    }

    /** Applies one authorised approval from a checker distinct from the maker. */
    @PostMapping("/{caseId}/approvals")
    public ReconciliationCaseView approve(
            Authentication authentication,
            @PathVariable UUID caseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReconciliationApprovalRequest request) {
        return operations.approve(actors.from(authentication), commands.approval(caseId, request, idempotencyKey));
    }
}
