package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Carries a server-owned synthetic workspace; credential hashes never enter HTTP representations.
 *
 * @param id opaque session identity
 * @param tenantId isolated synthetic tenant
 * @param cookieHash SHA-256 of the opaque workspace credential
 * @param gateHash SHA-256 of the independently validated shared-password session
 * @param expiresAt absolute workspace deadline
 * @author Daniel Jr Nkulu
 */
public record PortfolioSession(UUID id, UUID tenantId, String cookieHash, String gateHash, Instant expiresAt) {
    public PortfolioSession {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(expiresAt);
        if (cookieHash == null
                || !cookieHash.matches("[a-f0-9]{64}")
                || gateHash == null
                || !gateHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid portfolio credential digest");
        }
    }

    @Override
    public String toString() {
        return "PortfolioSession[credentials=REDACTED]";
    }
}
