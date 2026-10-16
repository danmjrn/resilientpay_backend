package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.MessagingProperties;

/**
 * Stores only digests and bounded envelope identifiers for permanently invalid outcome messages.
 *
 * @author Daniel Jr Nkulu
 */
@Repository
@Profile("!test")
public class PostgresMessageQuarantineRepository {
    private final JdbcOperations jdbc;
    private final Clock clock;
    private final MessagingProperties properties;
    private final QuarantineMetrics metrics;

    /**
     * Creates the payload-minimising quarantine repository.
     *
     * @param jdbc PostgreSQL access
     * @param clock UTC time boundary
     * @param properties stable consumer identity
     * @param metrics bounded quarantine metrics
     */
    public PostgresMessageQuarantineRepository(
            JdbcOperations jdbc, Clock clock, MessagingProperties properties, QuarantineMetrics metrics) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * Records or increments one open quarantine identity without retaining raw payload bytes.
     *
     * @param tenantId validated tenant when available
     * @param messageId validated message identity when available
     * @param correlationId validated correlation identity when available
     * @param payloadDigest SHA-256 of the exact rejected body
     * @param errorCode stable validation reason
     */
    @Transactional
    public void record(UUID tenantId, String messageId, UUID correlationId, String payloadDigest, String errorCode) {
        String identity = properties.consumerName()
                + "|"
                + Objects.toString(tenantId, "")
                + "|"
                + Objects.toString(messageId, "")
                + "|"
                + errorCode;
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, identity);
                statement.execute();
            }
            return null;
        });
        List<UUID> existing = jdbc.query(
                """
                SELECT id
                FROM message_quarantine
                WHERE consumer_name = ?
                  AND tenant_id IS NOT DISTINCT FROM ?
                  AND message_id IS NOT DISTINCT FROM ?
                  AND error_code = ? AND state = 'OPEN'
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                properties.consumerName(),
                tenantId,
                messageId,
                errorCode);
        Instant now = clock.instant();
        if (existing.isEmpty()) {
            jdbc.update(
                    """
                    INSERT INTO message_quarantine (
                        id, tenant_id, consumer_name, message_id, correlation_id, payload_digest,
                        error_code, state, occurrence_count, first_seen_at, last_seen_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', 1, ?, ?)
                    """,
                    UUID.randomUUID(),
                    tenantId,
                    properties.consumerName(),
                    messageId,
                    correlationId,
                    payloadDigest,
                    errorCode,
                    Timestamp.from(now),
                    Timestamp.from(now));
        } else {
            jdbc.update("""
                    UPDATE message_quarantine
                    SET occurrence_count = occurrence_count + 1, last_seen_at = ?,
                        payload_digest = ?, correlation_id = COALESCE(correlation_id, ?)
                    WHERE id = ?
                    """, Timestamp.from(now), payloadDigest, correlationId, existing.getFirst());
        }
        metrics.increment(errorCode);
    }
}
