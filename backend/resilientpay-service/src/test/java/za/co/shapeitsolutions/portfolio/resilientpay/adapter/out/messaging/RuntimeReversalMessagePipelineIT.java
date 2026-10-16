package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence.PostgresOrchestrationStore;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence.WorkflowMetrics;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AcceptReversalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AcceptanceResult;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ActorContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.DemonstrationFailure;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.MessagingProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.OutboxProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ReconciliationProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ResilientPaySecurityProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.RuntimeMode;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalCommandState;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalReason;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ActorId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.CorrelationId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.Money;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Exercises the real transactional store, confirmed outbox relay, Rabbit route, signed outcome
 * listener, and PostgreSQL inbox as one safety-critical lifecycle.
 *
 * @author Daniel Jr Nkulu
 */
@Testcontainers(disabledWithoutDocker = true)
class RuntimeReversalMessagePipelineIT {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String PIPELINE_ID = UUID.randomUUID().toString();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.2.9-management-alpine");

    private CachingConnectionFactory connectionFactory;
    private SimpleMessageListenerContainer listenerContainer;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ObjectMapper objectMapper;
    private MessagingProperties messaging;
    private RabbitTemplate rabbitTemplate;
    private PostgresOrchestrationStore store;
    private RabbitOutboxPublisher publisher;
    private MessageIntegrity integrity;

    @BeforeEach
    void configureRuntimeAdapters() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/devseed")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/devseed")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
        transaction = new TransactionTemplate(transactionManager);
        TransactionTemplate independentAudit = new TransactionTemplate(transactionManager);
        independentAudit.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        messaging = messagingProperties();
        integrity = new MessageIntegrity(messaging);

        connectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connectionFactory.setUsername(RABBIT.getAdminUsername());
        connectionFactory.setPassword(RABBIT.getAdminPassword());
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        rabbitTemplate = new RabbitTemplate(connectionFactory);
        declareTopology();

        RabbitTraceContext traceContext = mock(RabbitTraceContext.class);
        when(traceContext.captureCurrent()).thenReturn(OutboxTraceContext.empty());
        when(traceContext.isValid(any(MessageProperties.class))).thenReturn(true);
        Clock clock = Clock.systemUTC();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        QuarantineMetrics quarantineMetrics = new QuarantineMetrics(meters);
        store = new PostgresOrchestrationStore(
                jdbc,
                clock,
                new ReconciliationProperties(Duration.ofMillis(100), 20, Duration.ofMinutes(10)),
                new ResilientPaySecurityProperties(
                        "resilientpay-api",
                        "pipeline-evidence-integrity-key",
                        "pipeline-evidence-key-v1",
                        "urn:resilientpay:aal2"),
                new WorkflowMetrics(meters),
                quarantineMetrics,
                traceContext,
                independentAudit,
                new RuntimeMode(true));
        PostgresOutboxRepository repository = new PostgresOutboxRepository(
                jdbc,
                clock,
                new OutboxProperties(
                        20,
                        Duration.ofSeconds(30),
                        Duration.ofMillis(50),
                        3,
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(5),
                        3),
                store);
        publisher = new RabbitOutboxPublisher(
                repository,
                rabbitTemplate,
                integrity,
                store,
                messaging,
                new OutboxProperties(
                        20,
                        Duration.ofSeconds(30),
                        Duration.ofMillis(50),
                        3,
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(5),
                        3),
                meters,
                traceContext);
        ReversalOutcomeListener listener = new ReversalOutcomeListener(
                objectMapper,
                integrity,
                messaging,
                store,
                new PostgresMessageQuarantineRepository(jdbc, clock, messaging, quarantineMetrics),
                meters,
                traceContext);
        listenerContainer = new SimpleMessageListenerContainer(connectionFactory);
        listenerContainer.setQueueNames(messaging.outcomeQueue());
        listenerContainer.setAcknowledgeMode(AcknowledgeMode.AUTO);
        listenerContainer.setDefaultRequeueRejected(false);
        // This fixture constructs the store directly, so its production @Transactional
        // proxy is absent. Preserve the same commit-before-ack outcome/inbox boundary.
        TransactionTemplate outcomeTransaction = new TransactionTemplate(transactionManager);
        listenerContainer.setMessageListener(
                message -> outcomeTransaction.executeWithoutResult(status -> listener.consume(message)));
        listenerContainer.start();
    }

    @AfterEach
    void closeRuntimeAdapters() {
        if (listenerContainer != null) {
            listenerContainer.stop();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("R04: confirmed dispatch and duplicate outcome complete one durable workflow")
    void confirmedExecutionAndDuplicateSignedOutcomeCompleteExactlyOnce() throws Exception {
        verifyConfirmedOutcome(Duration.ZERO);
    }

    @Test
    @DisplayName("CP08: delayed signed Rabbit outcome leaves one in-flight attempt and completes exactly once")
    void delayedSignedOutcomePreservesOneInFlightAttemptThenCompletesExactlyOnce() throws Exception {
        verifyConfirmedOutcome(Duration.ofMillis(250));
    }

    private void verifyConfirmedOutcome(Duration outcomeDelay) throws Exception {
        UUID correlationId = UUID.randomUUID();
        AcceptanceResult accepted = transaction.execute(status -> store.accept(
                new ActorContext(
                        new TenantId(TENANT_ID), new ActorId("pipeline-operator"), Set.of("SCOPE_reversal.execute")),
                new AcceptReversalCommand(
                        "synth-txn-card-0001",
                        OperationType.CARD_PURCHASE,
                        new Money(new BigDecimal("120.00"), Currency.getInstance("ZAR")),
                        new ReversalReason("Synthetic pipeline verification"),
                        DemonstrationFailure.NONE,
                        "pipeline-idempotency-0001",
                        new CorrelationId(correlationId))));
        assertNotNull(accepted);

        publisher.publishDue();

        Message execution = rabbitTemplate.receive(messaging.executionQueue(), 5000);
        assertNotNull(execution);
        JsonNode executionPayload = objectMapper.readTree(execution.getBody());
        UUID commandId =
                UUID.fromString(executionPayload.get("reversalCommandId").asText());
        UUID attemptId =
                UUID.fromString(executionPayload.get("executionAttemptId").asText());
        assertEquals(accepted.command().id(), commandId);
        assertEquals(
                "DISPATCHED",
                jdbc.queryForObject(
                        "SELECT state FROM execution_attempt WHERE tenant_id = ? AND id = ?",
                        String.class,
                        TENANT_ID,
                        attemptId));

        if (!outcomeDelay.isZero()) {
            // Hold the real switch-bound delivery before its result deadline. No outcome or
            // retry is fabricated during the interval; PostgreSQL must retain one in-flight attempt.
            await().during(outcomeDelay).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertEquals(
                        ReversalCommandState.EXECUTING,
                        store.getReversal(new TenantId(TENANT_ID), commandId).status());
                assertEquals(
                        "DISPATCHED",
                        jdbc.queryForObject(
                                "SELECT state FROM execution_attempt WHERE tenant_id = ? AND id = ?",
                                String.class,
                                TENANT_ID,
                                attemptId));
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM execution_attempt WHERE tenant_id = ?"
                                        + " AND reversal_command_id = ?",
                                Integer.class,
                                TENANT_ID,
                                commandId));
                assertEquals(
                        0,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM execution_attempt WHERE tenant_id = ?"
                                        + " AND reversal_command_id = ? AND state = 'SUCCEEDED'",
                                Integer.class,
                                TENANT_ID,
                                commandId));
            });
        }

        UUID outcomeMessageId = UUID.randomUUID();
        UUID requestMessageId = UUID.fromString(execution.getMessageProperties().getHeader("messageId"));
        byte[] outcomeBody = ("{\"reversalCommandId\":\"%s\",\"executionAttemptId\":\"%s\","
                        + "\"outcome\":\"EXECUTED\",\"reasonCode\":null,\"observedAt\":\"%s\"}")
                .formatted(commandId, attemptId, Instant.now())
                .getBytes(StandardCharsets.UTF_8);
        sendOutcome(outcomeBody, outcomeMessageId, correlationId, requestMessageId);
        if (outcomeDelay.isZero()) {
            sendOutcome(outcomeBody, outcomeMessageId, correlationId, requestMessageId);
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertEquals(
                    ReversalCommandState.COMPLETED,
                    store.getReversal(new TenantId(TENANT_ID), commandId).status());
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM execution_attempt WHERE tenant_id = ? AND reversal_command_id = ?",
                            Integer.class,
                            TENANT_ID,
                            commandId));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM execution_attempt WHERE tenant_id = ?"
                                    + " AND reversal_command_id = ? AND state = 'SUCCEEDED'",
                            Integer.class,
                            TENANT_ID,
                            commandId));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM inbox_message WHERE tenant_id = ?"
                                    + " AND consumer_name = 'resilientpay-outcome-v1' AND message_id = ?"
                                    + " AND processing_outcome = 'PROCESSED'",
                            Integer.class,
                            TENANT_ID,
                            outcomeMessageId));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM audit_event WHERE tenant_id = ? AND action = 'SWITCH_EXECUTED'"
                                    + " AND resource_type = 'REVERSAL_COMMAND' AND resource_id = ?",
                            Integer.class,
                            TENANT_ID,
                            commandId));
        });
    }

    @Test
    @DisplayName("R10: malformed outcomes are acknowledged only after bounded safe quarantine")
    void tamperedUnsupportedAndOversizedOutcomesAreQuarantinedWithoutRequeue() throws Exception {
        UUID correlationId = UUID.randomUUID();
        UUID causationId = UUID.randomUUID();
        sendInvalidOutcome(
                "{}".getBytes(StandardCharsets.UTF_8),
                UUID.randomUUID(),
                correlationId,
                causationId,
                "1",
                "0".repeat(64));
        sendInvalidOutcome(
                "{}".getBytes(StandardCharsets.UTF_8),
                UUID.randomUUID(),
                correlationId,
                causationId,
                "2",
                "0".repeat(64));
        sendInvalidOutcome(
                new byte[messaging.maximumPayloadBytes() + 1],
                UUID.randomUUID(),
                correlationId,
                causationId,
                "1",
                "0".repeat(64));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertEquals(
                    List.of("INTEGRITY_CHECK_FAILED", "PAYLOAD_SIZE_INVALID", "UNSUPPORTED_SCHEMA_VERSION"),
                    jdbc.queryForList("SELECT error_code FROM message_quarantine ORDER BY error_code", String.class));
            assertEquals(
                    3,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM message_quarantine WHERE char_length(payload_digest) = 64",
                            Integer.class));
            assertEquals(
                    0,
                    ((Number) new RabbitAdmin(connectionFactory)
                                    .getQueueProperties(messaging.outcomeQueue())
                                    .get(RabbitAdmin.QUEUE_MESSAGE_COUNT))
                            .intValue());
        });
    }

    private void declareTopology() {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        Declarables topology = new RabbitTopologyConfiguration().resilientPayTopology(messaging);
        topology.getDeclarablesByType(Exchange.class).forEach(admin::declareExchange);
        topology.getDeclarablesByType(Queue.class).forEach(admin::declareQueue);
        topology.getDeclarablesByType(Binding.class).forEach(admin::declareBinding);
        topology.getDeclarablesByType(Queue.class).forEach(queue -> admin.purgeQueue(queue.getName(), false));
    }

    private void sendOutcome(byte[] body, UUID messageId, UUID correlationId, UUID causationId) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader("messageId", messageId.toString());
        properties.setHeader("correlationId", correlationId.toString());
        properties.setHeader("causationId", causationId.toString());
        properties.setHeader("schemaVersion", "1");
        properties.setHeader("tenantId", TENANT_ID.toString());
        properties.setHeader("signatureKeyId", integrity.keyId());
        properties.setHeader("signature", integrity.sign(body, messageId, correlationId, causationId, TENANT_ID, "1"));
        CorrelationData confirm = new CorrelationData(messageId + "-" + UUID.randomUUID());
        rabbitTemplate.send(
                messaging.exchange(), messaging.outcomeRoutingKey(), new Message(body, properties), confirm);
        assertTrue(confirm.getFuture().get(5, TimeUnit.SECONDS).ack());
    }

    private void sendInvalidOutcome(
            byte[] body, UUID messageId, UUID correlationId, UUID causationId, String schemaVersion, String signature)
            throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader("messageId", messageId.toString());
        properties.setHeader("correlationId", correlationId.toString());
        properties.setHeader("causationId", causationId.toString());
        properties.setHeader("schemaVersion", schemaVersion);
        properties.setHeader("tenantId", TENANT_ID.toString());
        properties.setHeader("signatureKeyId", integrity.keyId());
        properties.setHeader("signature", signature);
        CorrelationData confirm = new CorrelationData(messageId.toString());
        rabbitTemplate.send(
                messaging.exchange(), messaging.outcomeRoutingKey(), new Message(body, properties), confirm);
        assertTrue(confirm.getFuture().get(5, TimeUnit.SECONDS).ack());
    }

    private static MessagingProperties messagingProperties() {
        String prefix = "resilientpay.pipeline." + PIPELINE_ID;
        return new MessagingProperties(
                prefix + ".lifecycle",
                prefix + ".accepted",
                prefix + ".original-status",
                prefix + ".execution",
                prefix + ".outcome",
                prefix + ".quarantine-exchange",
                prefix + ".quarantine",
                "reversal.accepted.v1",
                "original-transaction.status-changed.v1",
                "reversal.execution.requested.v1",
                "reversal.outcome.observed.v1",
                "reversal.outcome.quarantined.v1",
                "reversal.execution.quarantined.v1",
                "resilientpay-outcome-v1",
                "pipeline-message-integrity-key",
                "pipeline-key-v1",
                16384,
                Duration.ofMillis(100),
                Duration.ofSeconds(1),
                3);
    }
}
