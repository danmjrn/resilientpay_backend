package za.co.shapeitsolutions.portfolio.resilientpay.domain.shared;

import java.util.UUID;

/**
 * Identifies the tenant that owns an aggregate. Tenant identifiers are carried on every aggregate
 * so application and persistence boundaries can fail closed on cross-tenant access.
 *
 * @param value globally unique tenant value
 * @author Daniel Jr Nkulu
 */
public record TenantId(UUID value) {
    public TenantId {
        DomainChecks.required(value, "tenantId");
    }
}
