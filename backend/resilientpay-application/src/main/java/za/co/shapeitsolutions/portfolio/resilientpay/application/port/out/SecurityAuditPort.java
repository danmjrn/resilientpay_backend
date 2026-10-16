package za.co.shapeitsolutions.portfolio.resilientpay.application.port.out;

import java.util.UUID;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ActorContext;

/**
 * Persists a safe, tenant-bound audit event when action authorization is denied before a use-case
 * store method can run.
 *
 * @author Daniel Jr Nkulu
 */
@FunctionalInterface
public interface SecurityAuditPort {
    /** Records a denied action without persisting credentials or raw token claims. */
    void recordAuthorizationDenied(
            ActorContext actor, String action, UUID resourceId, UUID correlationId, String reasonCode);

    /** Records successful access to sensitive audit evidence after the read completes. */
    default void recordEvidenceViewed(ActorContext actor, UUID commandId, UUID correlationId) {}
}
