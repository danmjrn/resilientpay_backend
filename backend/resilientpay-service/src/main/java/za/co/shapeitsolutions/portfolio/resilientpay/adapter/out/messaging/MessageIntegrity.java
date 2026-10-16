package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.MessagingProperties;

/**
 * Signs and verifies raw message JSON without logging the payload or secret key.
 *
 * @author Daniel Jr Nkulu
 */
@Component
public final class MessageIntegrity {
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] key;
    private final String keyId;

    /**
     * Creates the integrity boundary from externally supplied configuration.
     *
     * @param properties messaging key and identifier
     */
    public MessageIntegrity(MessagingProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.key = properties.hmacKey().getBytes(StandardCharsets.UTF_8);
        this.keyId = properties.keyId();
    }

    /**
     * Returns the active non-secret key identifier.
     *
     * @return key identifier
     */
    public String keyId() {
        return keyId;
    }

    /**
     * Signs the canonical envelope followed by the exact body bytes.
     *
     * @param body raw JSON body
     * @param messageId canonical message identity
     * @param correlationId canonical workflow correlation identity
     * @param causationId canonical preceding message identity
     * @param tenantId canonical owning tenant identity
     * @param schemaVersion supported wire schema version
     * @return lowercase hexadecimal HMAC
     */
    public String sign(
            byte[] body, UUID messageId, UUID correlationId, UUID causationId, UUID tenantId, String schemaVersion) {
        Objects.requireNonNull(body, "body must not be null");
        byte[] canonical = canonical(body, messageId, correlationId, causationId, tenantId, schemaVersion, keyId);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(canonical));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is required by the Java platform", exception);
        }
    }

    /**
     * Verifies an HMAC without timing-dependent string equality.
     *
     * @param body raw JSON body
     * @param messageId claimed message identity
     * @param correlationId claimed workflow correlation identity
     * @param causationId claimed preceding message identity
     * @param tenantId claimed owning tenant identity
     * @param schemaVersion claimed wire schema version
     * @param claimedKeyId claimed key identifier
     * @param signature claimed lowercase hexadecimal HMAC
     * @return true only for the active key and valid signature
     */
    public boolean verify(
            byte[] body,
            UUID messageId,
            UUID correlationId,
            UUID causationId,
            UUID tenantId,
            String schemaVersion,
            String claimedKeyId,
            String signature) {
        if (!keyId.equals(claimedKeyId) || signature == null || !signature.matches("[a-f0-9]{64}")) {
            return false;
        }
        try {
            byte[] expected =
                    HexFormat.of().parseHex(sign(body, messageId, correlationId, causationId, tenantId, schemaVersion));
            byte[] actual = HexFormat.of().parseHex(signature);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * Computes a non-reversible payload digest for deduplication and quarantine.
     *
     * @param body raw message bytes
     * @return lowercase SHA-256
     */
    public String digest(byte[] body) {
        Objects.requireNonNull(body, "body must not be null");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }

    private static byte[] canonical(
            byte[] body,
            UUID messageId,
            UUID correlationId,
            UUID causationId,
            UUID tenantId,
            String schemaVersion,
            String keyId) {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(causationId, "causationId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (!"1".equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion must be 1");
        }
        if (!keyId.matches("[A-Za-z0-9._-]{1,32}")) {
            throw new IllegalArgumentException("keyId must be a safe bounded identifier");
        }
        String envelope = messageId
                + "\n"
                + correlationId
                + "\n"
                + causationId
                + "\n"
                + schemaVersion
                + "\n"
                + tenantId
                + "\n"
                + keyId
                + "\n";
        byte[] prefix = envelope.getBytes(StandardCharsets.UTF_8);
        byte[] canonical = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, canonical, 0, prefix.length);
        System.arraycopy(body, 0, canonical, prefix.length, body.length);
        return canonical;
    }
}
