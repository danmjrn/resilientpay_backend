package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ActorContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AuditEntry;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.AuditStore;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ResilientPaySecurityProperties;

/**
 * Appends pseudonymised, HMAC-protected PostgreSQL evidence in its caller's transaction. Keeping
 * this raw writer separate avoids a dependency cycle between workflow persistence and the
 * application service that independently commits denied-control evidence.
 *
 * @author Daniel Jr Nkulu
 */
@Repository
@Profile("!test")
public class PostgresAuditStore implements AuditStore {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final JdbcOperations jdbc;
    private final ResilientPaySecurityProperties securityProperties;
    private final WorkflowMetrics metrics;

    /** Composes the audit writer without granting it ownership of a transaction boundary. */
    public PostgresAuditStore(
            JdbcOperations jdbc, ResilientPaySecurityProperties securityProperties, WorkflowMetrics metrics) {
        this.jdbc = jdbc;
        this.securityProperties = securityProperties;
        this.metrics = metrics;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(AuditEntry entry) {
        ActorContext actor = entry.actor();
        UUID eventId = UUID.randomUUID();
        String actorPseudonym = sha256(actor.actorId().value()).substring(0, 24);
        String authorities = json(actor.authorities().stream().sorted().toList());
        String details = json(entry.safeDetails());
        String canonical = String.join(
                "|",
                eventId.toString(),
                actor.tenantId().value().toString(),
                entry.resourceType(),
                entry.resourceId().toString(),
                entry.action(),
                entry.outcome(),
                actorPseudonym,
                entry.purpose(),
                entry.correlationId().toString(),
                entry.occurredAt().toString(),
                details);
        String digest = hmac(canonical, securityProperties.evidenceHmacKey());
        jdbc.update(
                """
                INSERT INTO audit_event (
                    id, tenant_id, occurred_at, actor_subject_token, actor_type, authorities,
                    action, resource_type, resource_id, purpose_code, outcome, reason_code,
                    correlation_id, policy_version, safe_details, key_id, evidence_digest
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                eventId,
                actor.tenantId().value(),
                Timestamp.from(entry.occurredAt()),
                actorPseudonym,
                actor.authorities().contains("ROLE_SERVICE") ? "SERVICE" : "HUMAN",
                authorities,
                entry.action(),
                entry.resourceType(),
                entry.resourceId(),
                entry.purpose(),
                entry.outcome(),
                entry.reason(),
                entry.correlationId(),
                entry.policyVersion(),
                details,
                securityProperties.evidenceKeyId(),
                digest);
        if ("AUTHORIZATION".equals(entry.purpose()) && "DENIED".equals(entry.outcome())) {
            metrics.authorizationDenied();
        } else if ("EVIDENCE_VIEWED".equals(entry.action())) {
            metrics.evidenceViewed();
        }
    }

    private static String json(Object value) {
        try {
            return SafeJsonWriter.write(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Safe JSON serialization failed", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }

    private static String hmac(String value, String key) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is required by the Java platform", exception);
        }
    }
}
