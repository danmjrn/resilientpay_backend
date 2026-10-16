package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OperationPolicyView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.ReversalOperations;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ConditionalOnRuntimeRole;

/**
 * Exposes the source-prescribed policy path and its canonical compatibility alias.
 *
 * @author Daniel Jr Nkulu
 */
@RestController
@ConditionalOnRuntimeRole("api")
public final class OperationPolicyController {
    private final ReversalOperations operations;
    private final ActorContextFactory actors;

    /** Creates the operation-policy HTTP adapter. */
    public OperationPolicyController(ReversalOperations operations, ActorContextFactory actors) {
        this.operations = operations;
        this.actors = actors;
    }

    /** Lists policies at the literal source-prescribed resource spelling. */
    @GetMapping("/api/v1/operationpolicys")
    public List<OperationPolicyView> listSourceCompatible(Authentication authentication) {
        return operations.listPolicies(actors.from(authentication));
    }

    /** Lists the same policies at the canonical compatibility alias. */
    @GetMapping("/api/v1/operationpolicies")
    public ResponseEntity<List<OperationPolicyView>> listCanonicalAlias(Authentication authentication) {
        return ResponseEntity.ok()
                .header("Content-Location", "/api/v1/operationpolicys")
                .body(operations.listPolicies(actors.from(authentication)));
    }
}
