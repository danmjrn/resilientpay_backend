package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OriginalTransactionView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.ReversalOperations;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ConditionalOnRuntimeRole;

/**
 * Implements the canonical synthetic original-transaction endpoints.
 *
 * @author Daniel Jr Nkulu
 */
@RestController
@RequestMapping("/api/v1/originaltransactions")
@ConditionalOnRuntimeRole("api")
public final class OriginalTransactionController {
    private final ReversalOperations operations;
    private final ActorContextFactory actors;
    private final WebCommandMapper commands;

    /** Creates the original-transaction HTTP adapter. */
    public OriginalTransactionController(
            ReversalOperations operations, ActorContextFactory actors, WebCommandMapper commands) {
        this.operations = operations;
        this.actors = actors;
        this.commands = commands;
    }

    /** Ingests one idempotent synthetic original transaction. */
    @PostMapping
    public ResponseEntity<OriginalTransactionView> ingest(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody OriginalTransactionRequest request) {
        OriginalTransactionView result =
                operations.ingestOriginal(actors.from(authentication), commands.original(request, idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /** Lists tenant-scoped synthetic original transactions. */
    @GetMapping
    public List<OriginalTransactionView> list(Authentication authentication) {
        return operations.listOriginals(actors.from(authentication));
    }
}
