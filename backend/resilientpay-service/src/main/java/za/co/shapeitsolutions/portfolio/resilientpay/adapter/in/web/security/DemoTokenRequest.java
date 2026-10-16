package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

import jakarta.validation.constraints.Pattern;

/**
 * Selects a bounded persona and synthetic tenant for local demonstration only.
 *
 * @param role local demonstration role to encode in the token
 * @param tenantId allowlisted synthetic tenant identity
 * @author Daniel Jr Nkulu
 */
public record DemoTokenRequest(
        DemoRole role,

        @Pattern(regexp = "^(demo-tenant|00000000-0000-0000-0000-000000000001)$")
        String tenantId) {}
