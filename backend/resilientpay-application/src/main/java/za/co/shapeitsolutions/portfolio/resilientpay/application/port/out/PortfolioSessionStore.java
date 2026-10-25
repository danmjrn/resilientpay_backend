package za.co.shapeitsolutions.portfolio.resilientpay.application.port.out;

import java.time.Instant;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.PortfolioSession;

/**
 * Persists bounded portfolio workspaces within application-selected transactions.
 *
 * @author Daniel Jr Nkulu
 */
public interface PortfolioSessionStore {
    /** Creates the workspace and all synthetic seed entities atomically under global quotas. */
    PortfolioSession create(PortfolioSession session, Instant now);

    /** Returns an active matching workspace or fails without disclosing its existence. */
    PortfolioSession active(String cookieHash, String gateHash, Instant now);

    /** Serializes concurrent resets and creates a replacement while retaining old tenant rows. */
    PortfolioSession reset(String oldCookieHash, PortfolioSession replacement, Instant now);

    /** Revokes only the exactly matching active browser workspace. */
    void logout(String cookieHash, String gateHash, Instant now);
}
