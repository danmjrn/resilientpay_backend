package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.PortfolioSession;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.PortfolioSessionStore;

/**
 * Seeds isolated tenants and serializes session quotas without deleting accepted work or audit.
 *
 * @author Daniel Jr Nkulu
 */
@Repository
@Profile("portfolio")
@Transactional(propagation = Propagation.MANDATORY)
public class PostgresPortfolioSessionStore implements PortfolioSessionStore {
    private final JdbcOperations jdbc;

    /** Creates the PostgreSQL adapter joining application-owned units. */
    public PostgresPortfolioSessionStore(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PortfolioSession create(PortfolioSession session, Instant now) {
        lockAdmission();
        requireQuota(session.gateHash(), now);
        return insertSafely(session, now);
    }

    @Override
    public PortfolioSession active(String cookieHash, String gateHash, Instant now) {
        List<PortfolioSession> matches = jdbc.query(
                """
                SELECT id, tenant_id, cookie_hash, gate_hash, expires_at FROM portfolio_session
                WHERE cookie_hash = ? AND gate_hash = ? AND revoked_at IS NULL AND expires_at > ?
                """,
                (row, number) -> new PortfolioSession(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getString("cookie_hash"),
                        row.getString("gate_hash"),
                        row.getTimestamp("expires_at").toInstant()),
                cookieHash,
                gateHash,
                Timestamp.from(now));
        if (matches.size() != 1) {
            throw inactive();
        }
        return matches.getFirst();
    }

    @Override
    public PortfolioSession reset(String oldCookieHash, PortfolioSession replacement, Instant now) {
        lockAdmission();
        PortfolioSession current = active(oldCookieHash, replacement.gateHash(), now);
        requireQuota(replacement.gateHash(), now);
        if (revoke(current.cookieHash(), current.gateHash(), now) != 1) {
            throw inactive();
        }
        return insertSafely(replacement, now);
    }

    @Override
    public void logout(String cookieHash, String gateHash, Instant now) {
        if (revoke(cookieHash, gateHash, now) != 1) {
            throw inactive();
        }
    }

    private int revoke(String cookieHash, String gateHash, Instant now) {
        return jdbc.update("""
                UPDATE portfolio_session SET revoked_at = ?
                WHERE cookie_hash = ? AND gate_hash = ? AND revoked_at IS NULL AND expires_at > ?
                """, Timestamp.from(now), cookieHash, gateHash, Timestamp.from(now));
    }

    private void lockAdmission() {
        jdbc.query("SELECT pg_advisory_xact_lock(2026102510)", row -> {});
        // Entity inserts acquire this lock before touching a workspace row. Reset must do likewise
        // before revoking the old row, otherwise its replacement seed can form a lock-order cycle.
        jdbc.query("SELECT pg_advisory_xact_lock(2026102511)", row -> {});
    }

    private void requireQuota(String gateHash, Instant now) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM portfolio_session", Long.class);
        Long active = jdbc.queryForObject("""
                SELECT COUNT(*) FROM portfolio_session WHERE revoked_at IS NULL AND expires_at > ?
                """, Long.class, Timestamp.from(now));
        Long recent = jdbc.queryForObject(
                "SELECT COUNT(*) FROM portfolio_session WHERE created_at > ?",
                Long.class,
                Timestamp.from(now.minusSeconds(60)));
        Long visitor =
                jdbc.queryForObject("SELECT COUNT(*) FROM portfolio_session WHERE gate_hash = ?", Long.class, gateHash);
        if (total == null
                || active == null
                || recent == null
                || visitor == null
                || total >= 2000
                || active >= 100
                || recent >= 12
                || visitor >= 5) {
            throw new ApplicationException(
                    ApplicationErrorCode.RATE_LIMITED,
                    "The bounded portfolio workspace capacity is temporarily unavailable");
        }
    }

    private PortfolioSession insert(PortfolioSession session, Instant now) {
        if (!session.expiresAt().isAfter(now)) {
            throw inactive();
        }
        jdbc.update(
                """
                INSERT INTO portfolio_session (id, tenant_id, cookie_hash, gate_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                session.id(),
                session.tenantId(),
                session.cookieHash(),
                session.gateHash(),
                Timestamp.from(now),
                Timestamp.from(session.expiresAt()));
        String[] tokens = {
            "synth-txn-card-0001", "synth-txn-transfer-001", "synth-txn-cash-0001", "synth-txn-merchant-01"
        };
        String[] operations = {"CARD_PURCHASE", "ACCOUNT_TRANSFER", "CASH_WITHDRAWAL", "MERCHANT_PAYOUT"};
        int[] amounts = {120, 1250, 500, 840};
        for (int index = 0; index < tokens.length; index++) {
            jdbc.update(
                    """
                    INSERT INTO original_transaction (id, tenant_id, external_reference_token,
                        public_reference_token, operation_type, amount, currency, status,
                        occurred_at, created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, 'ZAR', 'POSTED', ?, ?, ?, 0)
                    """,
                    UUID.randomUUID(),
                    session.tenantId(),
                    digest(tokens[index]),
                    tokens[index],
                    operations[index],
                    amounts[index],
                    Timestamp.from(now),
                    Timestamp.from(now),
                    Timestamp.from(now));
            jdbc.update(
                    """
                    INSERT INTO operation_policy (id, tenant_id, operation_type, policy_version, status,
                        max_attempts, initial_retry_delay_ms, maximum_retry_delay_ms, result_timeout_ms,
                        effective_from, created_by, submitted_by, approved_by, created_at, updated_at, version)
                    VALUES (?, ?, ?, 1, 'ACTIVE', 3, 500, 10000, 5000, ?,
                        'portfolio-seed-author', 'portfolio-seed-author', 'portfolio-seed-checker', ?, ?, 0)
                    """,
                    UUID.randomUUID(),
                    session.tenantId(),
                    operations[index],
                    Timestamp.from(now),
                    Timestamp.from(now),
                    Timestamp.from(now));
        }
        return session;
    }

    private PortfolioSession insertSafely(PortfolioSession session, Instant now) {
        try {
            return insert(session, now);
        } catch (DataAccessException exception) {
            if (exception.getMostSpecificCause() instanceof java.sql.SQLException database
                    && "P0001".equals(database.getSQLState())) {
                if (database.getMessage().contains("PORTFOLIO_QUOTA_EXCEEDED")) {
                    throw new ApplicationException(
                            ApplicationErrorCode.RATE_LIMITED, "The bounded portfolio data capacity was reached");
                }
                if (database.getMessage().contains("PORTFOLIO_SESSION_INACTIVE")) {
                    throw inactive();
                }
            }
            throw exception;
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static ApplicationException inactive() {
        return new ApplicationException(
                ApplicationErrorCode.AUTHENTICATION_REQUIRED, "An active portfolio workspace is required");
    }
}
