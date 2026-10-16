package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence.PostgresOrchestrationStore;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.MessagingProperties;

/**
 * Proves the public-correlation log bridge runs only after outcome envelope and payload validation.
 * These broker-free boundary tests do not assert that logging proves transaction commit or delivery.
 *
 * @author Daniel Jr Nkulu
 */
class ReversalOutcomeCorrelationTest {
    private static final UUID MESSAGE_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID CORRELATION_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID CAUSATION_ID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String VALID_BODY = """
            {"reversalCommandId":"dddddddd-dddd-4ddd-8ddd-dddddddddddd",
             "executionAttemptId":"eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
             "outcome":"EXECUTED","reasonCode":null,"observedAt":"2026-09-05T20:00:00Z"}
            """;

    private RabbitTraceContext traceContext;
    private PostgresOrchestrationStore store;
    private MessageIntegrity integrity;
    private ReversalOutcomeListener listener;

    @BeforeEach
    void configureListener() {
        traceContext = mock(RabbitTraceContext.class);
        when(traceContext.isValid(any(MessageProperties.class))).thenReturn(true);
        store = mock(PostgresOrchestrationStore.class);
        MessagingProperties properties = messaging();
        integrity = new MessageIntegrity(properties);
        listener = new ReversalOutcomeListener(
                JsonMapper.builder().findAndAddModules().build(),
                integrity,
                properties,
                store,
                mock(PostgresMessageQuarantineRepository.class),
                new SimpleMeterRegistry(),
                traceContext);
    }

    @Test
    void validatedEnvelopeIsLinkedBeforeProcessingWithoutClaimingCommit() {
        listener.consume(signedMessage(VALID_BODY));

        InOrder order = inOrder(traceContext, store);
        order.verify(traceContext).recordValidatedCorrelation(CORRELATION_ID);
        order.verify(store).processOutcome(argThat(observation -> CORRELATION_ID.equals(observation.correlationId())));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "invalid-signature",
                "noncanonical-correlation",
                "missing-correlation",
                "invalid-payload",
                "invalid-trace"
            })
    void unvalidatedInputCannotEmitAPublicCorrelationBridge(String failure) {
        Message message = signedMessage("invalid-payload".equals(failure) ? "{}" : VALID_BODY);
        MessageProperties properties = message.getMessageProperties();
        switch (failure) {
            case "invalid-signature" -> properties.setHeader("signature", "0".repeat(64));
            case "noncanonical-correlation" ->
                properties.setHeader("correlationId", CORRELATION_ID.toString().toUpperCase(java.util.Locale.ROOT));
            case "missing-correlation" -> properties.getHeaders().remove("correlationId");
            case "invalid-trace" -> when(traceContext.isValid(properties)).thenReturn(false);
            default -> {
                // An authenticated but schema-invalid body must also remain outside the bridge.
            }
        }

        listener.consume(message);

        verify(traceContext, never()).recordValidatedCorrelation(nullable(UUID.class));
        verifyNoInteractions(store);
    }

    private Message signedMessage(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        MessageProperties properties = new MessageProperties();
        properties.setHeader("messageId", MESSAGE_ID.toString());
        properties.setHeader("correlationId", CORRELATION_ID.toString());
        properties.setHeader("causationId", CAUSATION_ID.toString());
        properties.setHeader("tenantId", TENANT_ID.toString());
        properties.setHeader("schemaVersion", "1");
        properties.setHeader("signatureKeyId", integrity.keyId());
        properties.setHeader(
                "signature", integrity.sign(body, MESSAGE_ID, CORRELATION_ID, CAUSATION_ID, TENANT_ID, "1"));
        return new Message(body, properties);
    }

    private static MessagingProperties messaging() {
        return new MessagingProperties(
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
                3);
    }
}
