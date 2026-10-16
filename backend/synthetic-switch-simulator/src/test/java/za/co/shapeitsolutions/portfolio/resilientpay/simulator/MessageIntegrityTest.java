package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves that both the exact JSON bytes and every tenant-routing envelope value are authenticated.
 *
 * @author Daniel Jr Nkulu
 */
class MessageIntegrityTest {
    private static final byte[] BODY = "{\"outcome\":\"EXECUTED\"}".getBytes(StandardCharsets.UTF_8);
    private static final String MESSAGE_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CORRELATION_ID = "22222222-2222-2222-2222-222222222222";
    private static final String CAUSATION_ID = "33333333-3333-3333-3333-333333333333";
    private static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String KEY_ID = "demo-v1";

    @Test
    void canonicalSignatureRejectsBodyAndEnvelopeTampering() {
        MessageIntegrity integrity = integrity();
        String signature = integrity.sign(BODY, MESSAGE_ID, CORRELATION_ID, CAUSATION_ID, "1", TENANT_ID, KEY_ID);

        assertTrue(
                integrity.verifies(BODY, MESSAGE_ID, CORRELATION_ID, CAUSATION_ID, "1", TENANT_ID, KEY_ID, signature));
        assertFalse(integrity.verifies(
                "{}".getBytes(StandardCharsets.UTF_8),
                MESSAGE_ID,
                CORRELATION_ID,
                CAUSATION_ID,
                "1",
                TENANT_ID,
                KEY_ID,
                signature));
        assertFalse(integrity.verifies(
                BODY,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                CORRELATION_ID,
                CAUSATION_ID,
                "1",
                TENANT_ID,
                KEY_ID,
                signature));
        assertFalse(integrity.verifies(
                BODY,
                MESSAGE_ID,
                CORRELATION_ID,
                CAUSATION_ID,
                "1",
                "00000000-0000-0000-0000-000000000002",
                KEY_ID,
                signature));
        assertFalse(
                integrity.verifies(BODY, MESSAGE_ID, CORRELATION_ID, CAUSATION_ID, "2", TENANT_ID, KEY_ID, signature));
    }

    private static MessageIntegrity integrity() {
        return new MessageIntegrity(new SimulatorProperties(
                SimulatorScenario.SUCCEED,
                Duration.ZERO,
                "deterministic-message-integrity-key",
                KEY_ID,
                true,
                Duration.ofSeconds(1)));
    }
}
