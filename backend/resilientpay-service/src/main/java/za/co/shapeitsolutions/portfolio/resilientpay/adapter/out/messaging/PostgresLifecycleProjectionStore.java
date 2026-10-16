package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a payload-free, tenant-scoped inbox marker for lifecycle projection delivery.
 *
 * @author Daniel Jr Nkulu
 */
@Repository
@Profile("!test")
public class PostgresLifecycleProjectionStore implements LifecycleProjectionStore {
    static final String CONSUMER = "resilientpay-lifecycle-projection-v1";

    private final JdbcOperations jdbc;

    /** Creates the PostgreSQL lifecycle inbox adapter. */
    public PostgresLifecycleProjectionStore(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public int record(
            UUID tenantId,
            UUID messageId,
            String payloadHash,
            UUID correlationId,
            String eventType,
            Instant observedAt) {
        int inserted = jdbc.update(
                """
                INSERT INTO inbox_message (
                    tenant_id, consumer_name, message_id, payload_hash, correlation_id,
                    processing_outcome, first_seen_at, processed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, consumer_name, message_id) DO NOTHING
                """,
                tenantId,
                CONSUMER,
                messageId,
                payloadHash,
                correlationId,
                eventType,
                Timestamp.from(observedAt),
                Timestamp.from(observedAt));
        if (inserted == 1) {
            return RECORDED;
        }
        String existingHash = jdbc.queryForObject("""
                SELECT payload_hash FROM inbox_message
                WHERE tenant_id = ? AND consumer_name = ? AND message_id = ?
                """, String.class, tenantId, CONSUMER, messageId);
        return payloadHash.equals(existingHash) ? DUPLICATE : CONFLICT;
    }
}
