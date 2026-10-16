package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import java.time.Instant;
import java.util.UUID;

/**
 * Emits only safe digest metadata when an inbound message cannot be trusted or parsed.
 *
 * @param originalMessageId bounded best-effort identity of the rejected message
 * @param safeReasonCode non-sensitive machine-readable rejection reason
 * @param payloadDigest SHA-256 digest used for operator correlation without retaining raw content
 * @param quarantinedAt UTC rejection time
 * @author Daniel Jr Nkulu
 */
public record QuarantinePayload(
        UUID originalMessageId, String safeReasonCode, String payloadDigest, Instant quarantinedAt) {}
