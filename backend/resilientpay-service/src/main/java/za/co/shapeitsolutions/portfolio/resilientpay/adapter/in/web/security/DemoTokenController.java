package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ConditionalOnRuntimeRole;

/**
 * Exposes a narrowly shaped token bootstrap endpoint in the explicit local demo profile only.
 *
 * @author Daniel Jr Nkulu
 */
@RestController
@Profile("demo")
@ConditionalOnRuntimeRole("api")
public final class DemoTokenController {
    private final DemoTokenService tokenService;

    /** Creates the demo-only token endpoint. */
    public DemoTokenController(DemoTokenService tokenService) {
        this.tokenService = tokenService;
    }

    /** Issues a short-lived access token for a bounded local demo persona. */
    @PostMapping("/api/v1/demo/token")
    public DemoTokenResponse issue(@Valid @RequestBody(required = false) DemoTokenRequest request) {
        DemoRole role = request == null || request.role() == null ? DemoRole.REVERSAL_OPERATOR : request.role();
        String tenantValue = request == null ? null : request.tenantId();
        return new DemoTokenResponse(tokenService.issue(role, tokenService.tenant(tenantValue)));
    }
}
