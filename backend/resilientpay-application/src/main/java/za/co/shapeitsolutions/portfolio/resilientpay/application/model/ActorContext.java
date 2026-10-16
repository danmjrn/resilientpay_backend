package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

import java.util.Objects;
import java.util.Set;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ActorId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Carries the authenticated pseudonymous actor, tenant scope, and granted authorities.
 *
 * @param tenantId authenticated tenant boundary
 * @param actorId pseudonymous actor identity
 * @param authorities immutable granted authorities
 * @param assuranceLevel validated OIDC authentication-context value, when supplied
 * @author Daniel Jr Nkulu
 */
public record ActorContext(TenantId tenantId, ActorId actorId, Set<String> authorities, String assuranceLevel) {
    public ActorContext {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        authorities = Set.copyOf(Objects.requireNonNull(authorities, "authorities must not be null"));
        assuranceLevel = assuranceLevel == null || assuranceLevel.isBlank() ? null : assuranceLevel.strip();
    }

    /** Creates a context without an assurance claim for service actors and non-approval flows. */
    public ActorContext(TenantId tenantId, ActorId actorId, Set<String> authorities) {
        this(tenantId, actorId, authorities, null);
    }

    /** Fails closed when the authenticated principal lacks a required scope. */
    public void requireAuthority(String authority) {
        if (!authorities.contains(authority)) {
            throw new ApplicationException(
                    ApplicationErrorCode.ACCESS_DENIED,
                    "The authenticated actor is not permitted to perform this action");
        }
    }

    /** Fails closed unless the validated OIDC authentication context exactly meets policy. */
    public void requireAssurance(String requiredAssuranceLevel) {
        String required = Objects.requireNonNull(requiredAssuranceLevel, "requiredAssuranceLevel must not be null")
                .strip();
        if (required.isEmpty() || !required.equals(assuranceLevel)) {
            throw new ApplicationException(
                    ApplicationErrorCode.ACCESS_DENIED,
                    "The authenticated actor does not meet the required approval assurance");
        }
    }
}
