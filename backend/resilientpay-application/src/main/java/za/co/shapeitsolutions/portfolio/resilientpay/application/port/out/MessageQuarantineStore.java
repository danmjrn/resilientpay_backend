package za.co.shapeitsolutions.portfolio.resilientpay.application.port.out;

import java.util.UUID;

/**
 * Retains digest-only invalid-message evidence under an application-owned transaction boundary.
 *
 * @author Daniel Jr Nkulu
 */
@FunctionalInterface
public interface MessageQuarantineStore {
    /** Records a bounded rejected envelope without retaining its payload. */
    void record(UUID tenantId, String messageId, UUID correlationId, String payloadDigest, String errorCode);
}
