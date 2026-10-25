package za.co.shapeitsolutions.portfolio.resilientpay.application.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.PortfolioSession;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.PortfolioSessions;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.PortfolioSessionStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.TransactionBoundary;

/**
 * Selects short atomic workspace units while keeping all HTTP credentials outside the core.
 *
 * @author Daniel Jr Nkulu
 */
public final class PortfolioSessionApplicationService implements PortfolioSessions {
    private static final Duration LIFETIME = Duration.ofHours(2);
    private final PortfolioSessionStore store;
    private final TransactionBoundary transactions;
    private final Clock clock;

    /** Composes durable portfolio sessions around explicit time and transaction boundaries. */
    public PortfolioSessionApplicationService(
            PortfolioSessionStore store, TransactionBoundary transactions, Clock clock) {
        this.store = store;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public PortfolioSession create(String cookieHash, String gateHash, Instant gateExpiry) {
        // PostgreSQL persists microseconds. Canonicalise before returning the created record so
        // different JDK clock resolutions cannot change its representation on the next read.
        // Truncation also guarantees that storage precision never extends the outer deadline.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant gateDeadline = gateExpiry.truncatedTo(ChronoUnit.MICROS);
        Instant expiry = now.plus(LIFETIME).isBefore(gateDeadline) ? now.plus(LIFETIME) : gateDeadline;
        return transactions.required(() -> store.create(fresh(cookieHash, gateHash, expiry), now));
    }

    @Override
    public PortfolioSession active(String cookieHash, String gateHash) {
        return transactions.readOnly(() -> store.active(cookieHash, gateHash, clock.instant()));
    }

    @Override
    public PortfolioSession reset(String oldCookieHash, String newCookieHash, String gateHash) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return transactions.required(() -> {
            PortfolioSession current = store.active(oldCookieHash, gateHash, now);
            return store.reset(oldCookieHash, fresh(newCookieHash, gateHash, current.expiresAt()), now);
        });
    }

    @Override
    public void logout(String cookieHash, String gateHash) {
        transactions.required(() -> store.logout(cookieHash, gateHash, clock.instant()));
    }

    private static PortfolioSession fresh(String cookieHash, String gateHash, Instant expiry) {
        return new PortfolioSession(UUID.randomUUID(), UUID.randomUUID(), cookieHash, gateHash, expiry);
    }
}
