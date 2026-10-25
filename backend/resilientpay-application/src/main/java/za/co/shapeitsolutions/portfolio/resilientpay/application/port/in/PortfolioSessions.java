package za.co.shapeitsolutions.portfolio.resilientpay.application.port.in;

import java.time.Instant;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.PortfolioSession;

/**
 * Owns bounded visitor workspace creation, lookup, revocation and non-destructive rotation.
 *
 * @author Daniel Jr Nkulu
 */
public interface PortfolioSessions {
    /** Creates one isolated seeded workspace for a validated outer password session. */
    PortfolioSession create(String cookieHash, String gateHash, Instant gateExpiry);

    /** Resolves an active workspace bound to both independent browser credentials. */
    PortfolioSession active(String cookieHash, String gateHash);

    /** Revokes the old workspace and atomically seeds a new tenant without deleting old evidence. */
    PortfolioSession reset(String oldCookieHash, String newCookieHash, String gateHash);

    /** Revokes this workspace; accepted asynchronous work and evidence remain intact. */
    void logout(String cookieHash, String gateHash);
}
