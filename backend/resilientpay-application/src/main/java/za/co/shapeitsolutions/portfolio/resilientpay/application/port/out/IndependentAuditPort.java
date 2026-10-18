package za.co.shapeitsolutions.portfolio.resilientpay.application.port.out;

import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AuditEntry;

/**
 * Requests application-owned independent evidence for a control decision that must survive
 * rollback of a rejected workflow mutation.
 *
 * @author Daniel Jr Nkulu
 */
@FunctionalInterface
public interface IndependentAuditPort {
    /** Commits the entry independently before returning, or propagates persistence failure. */
    void recordIndependent(AuditEntry entry);
}
