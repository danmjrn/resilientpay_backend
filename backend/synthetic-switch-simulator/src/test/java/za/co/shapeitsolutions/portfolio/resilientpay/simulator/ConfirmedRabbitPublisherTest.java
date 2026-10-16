package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Verifies that request processing can finish only after a routed positive publisher confirmation.
 *
 * @author Daniel Jr Nkulu
 */
class ConfirmedRabbitPublisherTest {

    @Test
    void positiveRoutedConfirmationAllowsListenerCompletion() {
        ConfirmedRabbitPublisher publisher = publisher(PublishMode.ACK, Duration.ofSeconds(1));

        assertDoesNotThrow(() -> publisher.send("exchange", "route", message(), UUID.randomUUID()));
    }

    @Test
    void unroutableAndMissingConfirmationForceRetry() {
        ConfirmedRabbitPublisher returned = publisher(PublishMode.RETURNED, Duration.ofSeconds(1));
        ConfirmedRabbitPublisher lost = publisher(PublishMode.NO_CONFIRM, Duration.ofMillis(5));
        ConfirmedRabbitPublisher nacked = publisher(PublishMode.NACK, Duration.ofSeconds(1));

        assertThrows(
                OutcomePublishException.class,
                () -> returned.send("exchange", "missing-route", message(), UUID.randomUUID()));
        assertThrows(OutcomePublishException.class, () -> lost.send("exchange", "route", message(), UUID.randomUUID()));
        assertThrows(
                OutcomePublishException.class, () -> nacked.send("exchange", "route", message(), UUID.randomUUID()));
    }

    private static ConfirmedRabbitPublisher publisher(PublishMode mode, Duration timeout) {
        SimulatorProperties properties = new SimulatorProperties(
                SimulatorScenario.SUCCEED,
                Duration.ZERO,
                "deterministic-message-integrity-key",
                "demo-v1",
                false,
                timeout);
        return new ConfirmedRabbitPublisher(new StubRabbitTemplate(mode), properties);
    }

    private static Message message() {
        return MessageBuilder.withBody("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .build();
    }

    /**
     * Publisher outcomes exercised without a broker or mocking agent.
     *
     * @author Daniel Jr Nkulu
     */
    private enum PublishMode {
        ACK,
        NACK,
        RETURNED,
        NO_CONFIRM
    }

    /**
     * Completes correlation futures exactly as RabbitTemplate publisher callbacks would.
     *
     * @author Daniel Jr Nkulu
     */
    private static final class StubRabbitTemplate extends RabbitTemplate {
        private final PublishMode mode;

        private StubRabbitTemplate(PublishMode mode) {
            this.mode = mode;
        }

        @Override
        public void send(String exchange, String routingKey, Message message, CorrelationData correlationData) {
            if (mode == PublishMode.NO_CONFIRM) {
                return;
            }
            if (mode == PublishMode.RETURNED) {
                correlationData.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", exchange, routingKey));
            }
            correlationData.getFuture().complete(new CorrelationData.Confirm(mode != PublishMode.NACK, null));
        }
    }
}
