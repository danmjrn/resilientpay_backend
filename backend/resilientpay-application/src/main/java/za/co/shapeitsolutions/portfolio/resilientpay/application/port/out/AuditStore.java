package za.co.shapeitsolutions.portfolio.resilientpay.application.port.out;

import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AuditEntry;

/**
 * Appends integrity-protected audit intent inside the transaction supplied by its caller.
 * It never creates or independently commits a business transaction.
 *
 * @author Daniel Jr Nkulu
 */
@FunctionalInterface
public interface AuditStore {
    /** Appends one safe entry to the existing unit of work. */
    void append(AuditEntry entry);
}
