package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Simulates process loss after confirmed publication and proves restart replay uses one durable
 * synthetic effect, the same body, and the same outcome message identity.
 *
 * @author Daniel Jr Nkulu
 */
class SimulatorDurableJournalIT {
    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    private Path journalDirectory;

    @Test
    @DisplayName("R06: publisher-confirmed outcome crash replay keeps one durable effect identity")
    void confirmedPublicationThenCrashBeforeAckReplaysWithoutExecutingAgain() throws Exception {
        SimulatorProperties properties = properties();
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        CapturingRabbitTemplate rabbit = new CapturingRabbitTemplate();
        ConfirmedRabbitPublisher publisher = new ConfirmedRabbitPublisher(rabbit, properties);
        Message inbound = inbound(objectMapper, properties, DemonstrationFailure.NONE, 1);

        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = validatorFactory.getValidator();
            SyntheticSwitchListener first = listener(
                    validator, processor(properties, new SimulatorExecutionJournal(properties)), publisher, properties);
            first.consume(inbound);

            SyntheticSwitchListener restarted = listener(
                    validator,
                    processor(properties, new SimulatorExecutionJournal(properties)),
                    new ConfirmedRabbitPublisher(rabbit, properties),
                    properties);
            restarted.consume(inbound);
        }

        assertEquals(2, rabbit.published().size());
        Message firstOutcome = rabbit.published().get(0);
        Message replayedOutcome = rabbit.published().get(1);
        assertArrayEquals(firstOutcome.getBody(), replayedOutcome.getBody());
        assertEquals(
                Objects.toString(
                        firstOutcome.getMessageProperties().getHeaders().get("messageId")),
                Objects.toString(
                        replayedOutcome.getMessageProperties().getHeaders().get("messageId")));
        assertEquals(1, journalEntryCount());
    }

    @Test
    @DisplayName("R04: interruption intent survives restart before safe determination")
    void interruptionMarkerSurvivesRestartAndAllowsOneSafeDetermination() {
        SimulatorProperties properties = properties();
        ExecutionRequestedPayload request = new ExecutionRequestedPayload(
                UUID.randomUUID(), UUID.randomUUID(), 1, NOW.plusSeconds(10), DemonstrationFailure.INTERRUPT_ONCE);

        assertThrows(
                SyntheticInterruptionException.class,
                () -> processor(properties, new SimulatorExecutionJournal(properties))
                        .processForPublication(request));
        ProcessedExecution recovered =
                processor(properties, new SimulatorExecutionJournal(properties)).processForPublication(request);
        ProcessedExecution replayed =
                processor(properties, new SimulatorExecutionJournal(properties)).processForPublication(request);

        assertEquals(recovered, replayed);
        assertEquals(SwitchOutcome.CONFIRMED_NOT_EXECUTED, recovered.outcome().outcome());
        assertEquals("SWITCH_NOT_RECEIVED", recovered.outcome().reasonCode());
    }

    @Test
    @DisplayName("R04: injected crash after atomic intent cannot repeat determination on restart")
    void crashAfterAtomicIntentBeforePublicationReplaysPersistedDetermination() {
        SimulatorProperties properties = properties();
        ExecutionRequestedPayload request = new ExecutionRequestedPayload(
                UUID.randomUUID(), UUID.randomUUID(), 1, NOW.plusSeconds(10), DemonstrationFailure.NONE);
        SimulatorExecutionJournal crashingJournal = new SimulatorExecutionJournal(properties, () -> {
            throw new IllegalStateException("simulated process loss after durable intent");
        });

        assertThrows(
                IllegalStateException.class,
                () -> processor(properties, crashingJournal).processForPublication(request));
        ProcessedExecution recovered =
                processor(properties, new SimulatorExecutionJournal(properties)).processForPublication(request);
        ProcessedExecution replayed =
                processor(properties, new SimulatorExecutionJournal(properties)).processForPublication(request);

        assertEquals(recovered, replayed);
        assertEquals(SwitchOutcome.EXECUTED, recovered.outcome().outcome());
        assertEquals(1, journalEntryCount());
    }

    private SyntheticSwitchProcessor processor(SimulatorProperties properties, SimulatorExecutionJournal journal) {
        return new SyntheticSwitchProcessor(Clock.fixed(NOW, ZoneOffset.UTC), properties, journal);
    }

    private long journalEntryCount() {
        try (Stream<Path> entries = Files.list(journalDirectory)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".journal"))
                    .count();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Journal test directory could not be inspected", exception);
        }
    }

    private SyntheticSwitchListener listener(
            Validator validator,
            SyntheticSwitchProcessor processor,
            ConfirmedRabbitPublisher publisher,
            SimulatorProperties properties) {
        return new SyntheticSwitchListener(
                validator,
                processor,
                publisher,
                new MessageIntegrity(properties),
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Message inbound(
            ObjectMapper objectMapper, SimulatorProperties properties, DemonstrationFailure failure, int attemptNumber)
            throws Exception {
        UUID messageId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID causationId = UUID.randomUUID();
        ExecutionRequestedPayload request = new ExecutionRequestedPayload(
                UUID.randomUUID(), UUID.randomUUID(), attemptNumber, NOW.plusSeconds(10), failure);
        byte[] body = objectMapper.writeValueAsBytes(request);
        MessageIntegrity integrity = new MessageIntegrity(properties);
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setHeader("messageId", messageId.toString());
        messageProperties.setHeader("correlationId", correlationId.toString());
        messageProperties.setHeader("causationId", causationId.toString());
        messageProperties.setHeader("schemaVersion", "1");
        messageProperties.setHeader("tenantId", TENANT_ID.toString());
        messageProperties.setHeader("signatureKeyId", properties.signatureKeyId());
        messageProperties.setHeader(
                "signature",
                integrity.sign(
                        body,
                        messageId.toString(),
                        correlationId.toString(),
                        causationId.toString(),
                        "1",
                        TENANT_ID.toString(),
                        properties.signatureKeyId()));
        return MessageBuilder.withBody(body).andProperties(messageProperties).build();
    }

    private SimulatorProperties properties() {
        return new SimulatorProperties(
                SimulatorScenario.SUCCEED,
                Duration.ZERO,
                "durable-journal-message-key",
                "durable-journal-key-v1",
                true,
                Duration.ofSeconds(1),
                journalDirectory);
    }

    /**
     * Models a routed broker-confirmed publication and retains exact messages across restarts.
     *
     * @author Daniel Jr Nkulu
     */
    private static final class CapturingRabbitTemplate extends RabbitTemplate {
        private final List<Message> published = new ArrayList<>();

        @Override
        public void send(String exchange, String routingKey, Message message, CorrelationData correlationData) {
            published.add(message);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
        }

        private List<Message> published() {
            return List.copyOf(published);
        }
    }
}
