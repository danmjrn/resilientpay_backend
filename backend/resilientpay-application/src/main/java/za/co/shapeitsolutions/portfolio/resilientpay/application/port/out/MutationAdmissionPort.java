package za.co.shapeitsolutions.portfolio.resilientpay.application.port.out;

import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Admits a bounded tenant mutation before the application opens authoritative persistence work.
 * The port owns only disposable admission state; PostgreSQL remains authoritative for accepted
 * commands and idempotency.
 *
 * @author Daniel Jr Nkulu
 */
@FunctionalInterface
public interface MutationAdmissionPort {

    /**
     * Requires one ephemeral admission permit.
     *
     * @param tenantId authenticated tenant
     * @param action low-cardinality mutation action
     */
    void requirePermit(TenantId tenantId, String action);
}
