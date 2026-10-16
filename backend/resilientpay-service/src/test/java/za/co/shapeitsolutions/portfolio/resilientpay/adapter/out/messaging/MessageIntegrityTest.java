package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.MessagingProperties;

/**
 * Proves that every authenticated envelope field and the exact body contribute to the HMAC.
 *
 * @author Daniel Jr Nkulu
 */
class MessageIntegrityTest {
    private static final UUID MESSAGE_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID CORRELATION_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID CAUSATION_ID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final byte[] BODY = "{\"outcome\":\"EXECUTED\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void matchesIndependentCanonicalTestVector() {
        MessageIntegrity integrity = integrity();

        assertEquals(
                "c9b71c0f8639ce9c891bd004e1461d82b6ac84f3816a8b5bae3c0d8ec5c9a114",
                integrity.sign(BODY, MESSAGE_ID, CORRELATION_ID, CAUSATION_ID, TENANT_ID, "1"));
    }

    @Test
    void rejectsTamperingOfEveryEnvelopeFieldAndBody() {
        MessageIntegrity integrity = integrity();
        String signature = integrity.sign(BODY, MESSAGE_ID, CORRELATION_ID, CAUSATION_ID, TENANT_ID, "1");
        UUID changed = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");

        assertTrue(integrity.verify(
                BODY, MESSAGE_ID, CORRELATION_ID, CAUSATION_ID, TENANT_ID, "1", integrity.keyId(), signature));
        assertFalse(integrity.verify(
                BODY, changed, CORRELATION_ID, CAUSATION_ID, TENANT_ID, "1", integrity.keyId(), signature));
        assertFalse(integrity.verify(
                BODY, MESSAGE_ID, changed, CAUSATION_ID, TENANT_ID, "1", integrity.keyId(), signature));
        assertFalse(integrity.verify(
                BODY, MESSAGE_ID, CORRELATION_ID, changed, TENANT_ID, "1", integrity.keyId(), signature));
        assertFalse(integrity.verify(
                BODY, MESSAGE_ID, CORRELATION_ID, CAUSATION_ID, changed, "1", integrity.keyId(), signature));
        assertFalse(integrity.verify(
                BODY, MESSAGE_ID, CORRELATION_ID, CAUSATION_ID, TENANT_ID, "2", integrity.keyId(), signature));
        assertFalse(integrity.verify(
                BODY, MESSAGE_ID, CORRELATION_ID, CAUSATION_ID, TENANT_ID, "1", "other-key", signature));
        assertFalse(integrity.verify(
                "{\"outcome\":\"AMBIGUOUS\"}".getBytes(StandardCharsets.UTF_8),
                MESSAGE_ID,
                CORRELATION_ID,
                CAUSATION_ID,
                TENANT_ID,
                "1",
                integrity.keyId(),
                signature));
    }

    private static MessageIntegrity integrity() {
        return new MessageIntegrity(new MessagingProperties(
                "resilientpay.reversal.lifecycle.v1",
                "accepted",
                "original-status",
                "execution",
                "outcome",
                "quarantine-exchange",
                "quarantine",
                "reversal.accepted.v1",
                "original.status-changed.v1",
                "execution.requested",
                "outcome.observed",
                "outcome.quarantined",
                "execution.quarantined",
                "outcome-consumer",
                "unit-test-message-key",
                "test-key-v1",
                16384,
                Duration.ofMillis(250),
                Duration.ofSeconds(2),
                3));
    }
}
