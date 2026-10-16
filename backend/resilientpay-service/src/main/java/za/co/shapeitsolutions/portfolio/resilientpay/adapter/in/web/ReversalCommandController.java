package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web;

import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AcceptanceResult;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AuditEvidenceView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ReversalView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.ReversalOperations;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ConditionalOnRuntimeRole;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalCommandState;

/**
 * Implements the canonical reversal-command collection, resource, retry, and evidence endpoints.
 *
 * @author Daniel Jr Nkulu
 */
@Validated
@RestController
@RequestMapping("/api/v1/reversalcommands")
@ConditionalOnRuntimeRole("api")
public class ReversalCommandController {
    private final ReversalOperations operations;
    private final ActorContextFactory actors;
    private final WebCommandMapper commands;

    /** Creates the HTTP adapter around the application-owned inbound port. */
    public ReversalCommandController(
            ReversalOperations operations, ActorContextFactory actors, WebCommandMapper commands) {
        this.operations = operations;
        this.actors = actors;
        this.commands = commands;
    }

    /** Durably accepts one idempotent full reversal for asynchronous processing. */
    @Observed(name = "HTTP_ACCEPT", contextualName = "HTTP_ACCEPT")
    @PostMapping
    public ResponseEntity<ReversalView> accept(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AcceptReversalRequest request) {
        AcceptanceResult result =
                operations.accept(actors.from(authentication), commands.accept(request, idempotencyKey));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create(
                        "/api/v1/reversalcommands/" + result.command().id()))
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.command());
    }

    /** Lists tenant-scoped commands newest first. */
    @GetMapping
    public List<ReversalView> list(
            Authentication authentication,
            @RequestParam(required = false) ReversalCommandState status,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit) {
        return operations.listReversals(actors.from(authentication), status, limit);
    }

    /** Gets one tenant-scoped command without revealing another tenant's resource. */
    @GetMapping("/{commandId}")
    public ReversalView get(Authentication authentication, @PathVariable UUID commandId) {
        return operations.getReversal(actors.from(authentication), commandId);
    }

    /** Requests a bounded retry only after explicit non-execution proof. */
    @PostMapping("/{commandId}/retry")
    public ResponseEntity<ReversalView> retry(
            Authentication authentication,
            @PathVariable UUID commandId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RetryRequest request) {
        ReversalView result =
                operations.retry(actors.from(authentication), commands.retry(commandId, request, idempotencyKey));
        return ResponseEntity.accepted().body(result);
    }

    /** Returns redacted append-only evidence for one tenant-scoped command. */
    @GetMapping("/{commandId}/evidence")
    public List<AuditEvidenceView> evidence(Authentication authentication, @PathVariable UUID commandId) {
        return operations.evidence(actors.from(authentication), commandId);
    }
}
