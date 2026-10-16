package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

/**
 * Records a broker-confirmed outbox publication before its lease becomes terminally published.
 *
 * @author Daniel Jr Nkulu
 */
public interface OutboxPublicationAudit {
    /**
     * Appends safe evidence for a confirmed, routable event.
     *
     * @param message confirmed outbox message
     */
    void recordPublished(OutboxMessage message);
}
