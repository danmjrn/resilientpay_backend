package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

/**
 * Exposes the bounded PostgreSQL deadline scan to the scheduled reconciler.
 *
 * @author Daniel Jr Nkulu
 */
public interface DeadlineReconciliationStore {
    /**
     * Moves a bounded number of overdue dispatched attempts into reconciliation.
     *
     * @param limit maximum rows locked by one scan
     * @return number of transitions committed
     */
    int reconcileOverdueAttempts(int limit);
}
