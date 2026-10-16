package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Signs and verifies exact serialized message bytes with HMAC-SHA256 in constant time.
 *
 * @author Daniel Jr Nkulu
 */
@Component
public final class MessageIntegrity {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final byte[] key;

    /** Creates the integrity boundary from external simulator configuration. */
    public MessageIntegrity(SimulatorProperties properties) {
        this.key = properties.messageHmacKey().getBytes(StandardCharsets.UTF_8);
    }

    /** Returns the lowercase HMAC for the canonical envelope and exact raw JSON bytes. */
    public String sign(
            byte[] payload,
            String messageId,
            String correlationId,
            String causationId,
            String schemaVersion,
            String tenantId,
            String signatureKeyId) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return HexFormat.of()
                    .formatHex(mac.doFinal(signingInput(
                            payload, messageId, correlationId, causationId, schemaVersion, tenantId, signatureKeyId)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is required by the Java platform", exception);
        }
    }

    /** Verifies a bounded hexadecimal signature without timing-sensitive string equality. */
    public boolean verifies(
            byte[] payload,
            String messageId,
            String correlationId,
            String causationId,
            String schemaVersion,
            String tenantId,
            String signatureKeyId,
            String signature) {
        if (signature == null || !signature.matches("^[a-f0-9]{64}$")) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(payload, messageId, correlationId, causationId, schemaVersion, tenantId, signatureKeyId)
                        .getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }

    /** Returns a non-secret SHA-256 payload digest for quarantine evidence. */
    public String digest(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }

    private static byte[] signingInput(
            byte[] payload,
            String messageId,
            String correlationId,
            String causationId,
            String schemaVersion,
            String tenantId,
            String signatureKeyId) {
        String envelope =
                String.join("\n", messageId, correlationId, causationId, schemaVersion, tenantId, signatureKeyId)
                        + "\n";
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write(envelope.getBytes(StandardCharsets.UTF_8));
            output.write(payload);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Canonical message signing input could not be built", exception);
        }
    }
}
