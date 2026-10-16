package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.ratelimit.RedisMutationRateLimiter;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.MessagingProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.RateLimitProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Exercises the signed RabbitMQ route and disposable Redis admission boundary against containers.
 *
 * @author Daniel Jr Nkulu
 */
@Testcontainers(disabledWithoutDocker = true)
class RuntimeMessagingAndRateLimitIT {
    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.2.9-management-alpine");

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8.8.1-alpine").withExposedPorts(6379);

    @Test
    void rabbitRoutePreservesVerifiableEnvelopeAndBody() {
        CachingConnectionFactory connectionFactory =
                new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connectionFactory.setUsername(RABBIT.getAdminUsername());
        connectionFactory.setPassword(RABBIT.getAdminPassword());
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        TopicExchange exchange = new TopicExchange("test.lifecycle", false, true);
        Queue queue = new Queue("test.route.execution", false, false, true);
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("execution.requested"));
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        MessageIntegrity integrity = new MessageIntegrity(properties());
        byte[] body = "{\"attemptNumber\":1}".getBytes(StandardCharsets.UTF_8);
        UUID messageId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID causationId = UUID.randomUUID();
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setHeader("messageId", messageId.toString());
        messageProperties.setHeader("correlationId", correlationId.toString());
        messageProperties.setHeader("causationId", causationId.toString());
        messageProperties.setHeader("schemaVersion", "1");
        messageProperties.setHeader("tenantId", tenantId.toString());
        messageProperties.setHeader("signatureKeyId", integrity.keyId());
        messageProperties.setHeader(
                "signature", integrity.sign(body, messageId, correlationId, causationId, tenantId, "1"));

        template.send(exchange.getName(), "execution.requested", new Message(body, messageProperties));
        Message received = template.receive(queue.getName(), 5000);

        assertNotNull(received);
        assertTrue(integrity.verify(
                received.getBody(),
                messageId,
                correlationId,
                causationId,
                tenantId,
                "1",
                integrity.keyId(),
                received.getMessageProperties().getHeader("signature")));
        connectionFactory.destroy();
    }

    @Test
    void serviceAndSimulatorCanRedeclareTheSharedExecutionQueue() {
        CachingConnectionFactory connectionFactory =
                new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connectionFactory.setUsername(RABBIT.getAdminUsername());
        connectionFactory.setPassword(RABBIT.getAdminPassword());
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        MessagingProperties properties = properties();
        Declarables serviceTopology = new RabbitTopologyConfiguration().resilientPayTopology(properties);
        serviceTopology.getDeclarablesByType(Exchange.class).forEach(admin::declareExchange);
        serviceTopology.getDeclarablesByType(Queue.class).forEach(admin::declareQueue);
        serviceTopology.getDeclarablesByType(Binding.class).forEach(admin::declareBinding);

        admin.declareExchange(new TopicExchange(properties.exchange(), true, false));
        admin.declareExchange(new TopicExchange(RabbitTopologyConfiguration.DEAD_LETTER_EXCHANGE, true, false));
        Queue simulatorDeclaration = QueueBuilder.durable(properties.executionQueue())
                .withArguments(Map.of(
                        "x-max-length",
                        RabbitTopologyConfiguration.WORK_QUEUE_MAX_LENGTH,
                        "x-max-length-bytes",
                        RabbitTopologyConfiguration.WORK_QUEUE_MAX_BYTES,
                        "x-overflow",
                        "reject-publish-dlx",
                        "x-dead-letter-exchange",
                        RabbitTopologyConfiguration.DEAD_LETTER_EXCHANGE,
                        "x-dead-letter-routing-key",
                        RabbitTopologyConfiguration.EXECUTION_DEAD_LETTER_ROUTING_KEY))
                .build();
        admin.declareQueue(simulatorDeclaration);

        assertNotNull(admin.getQueueProperties(properties.executionQueue()));
        connectionFactory.destroy();
    }

    @Test
    void everyRuntimeQueueHasFiniteByteAndMessageCapacityWithRejectingOverflow() {
        MessagingProperties properties = properties("bounded-shape");
        Declarables topology = new RabbitTopologyConfiguration().resilientPayTopology(properties);
        Map<String, Queue> queues = topology.getDeclarablesByType(Queue.class).stream()
                .collect(java.util.stream.Collectors.toMap(Queue::getName, queue -> queue));

        for (String queueName : java.util.List.of(properties.acceptedQueue(), properties.originalStatusQueue())) {
            Queue queue = queues.get(queueName);
            assertEquals(
                    RabbitTopologyConfiguration.WORK_QUEUE_MAX_LENGTH,
                    queue.getArguments().get("x-max-length"));
            assertEquals(
                    RabbitTopologyConfiguration.WORK_QUEUE_MAX_BYTES,
                    queue.getArguments().get("x-max-length-bytes"));
            assertEquals("reject-publish", queue.getArguments().get("x-overflow"));
        }
        for (String queueName : java.util.List.of(properties.executionQueue(), properties.outcomeQueue())) {
            Queue queue = queues.get(queueName);
            assertEquals(
                    RabbitTopologyConfiguration.WORK_QUEUE_MAX_LENGTH,
                    queue.getArguments().get("x-max-length"));
            assertEquals(
                    RabbitTopologyConfiguration.WORK_QUEUE_MAX_BYTES,
                    queue.getArguments().get("x-max-length-bytes"));
            assertEquals("reject-publish-dlx", queue.getArguments().get("x-overflow"));
            assertEquals(
                    RabbitTopologyConfiguration.DEAD_LETTER_EXCHANGE,
                    queue.getArguments().get("x-dead-letter-exchange"));
        }
        Queue quarantine = queues.get(properties.quarantineQueue());
        assertEquals(
                RabbitTopologyConfiguration.QUARANTINE_QUEUE_MAX_LENGTH,
                quarantine.getArguments().get("x-max-length"));
        assertEquals(
                RabbitTopologyConfiguration.QUARANTINE_QUEUE_MAX_BYTES,
                quarantine.getArguments().get("x-max-length-bytes"));
        assertEquals("reject-publish", quarantine.getArguments().get("x-overflow"));
        Queue deadLetter = queues.get(RabbitTopologyConfiguration.DEAD_LETTER_QUEUE);
        assertEquals(
                RabbitTopologyConfiguration.QUARANTINE_QUEUE_MAX_LENGTH,
                deadLetter.getArguments().get("x-max-length"));
        assertEquals(
                RabbitTopologyConfiguration.QUARANTINE_QUEUE_MAX_BYTES,
                deadLetter.getArguments().get("x-max-length-bytes"));
        assertEquals("reject-publish", deadLetter.getArguments().get("x-overflow"));
    }

    @Test
    void boundedLifecycleQueueRejectsOverflowSoTransactionalOutboxCanRetryAfterDrain() throws Exception {
        String suffix = UUID.randomUUID().toString();
        MessagingProperties properties = properties("overflow-" + suffix);
        CachingConnectionFactory connectionFactory =
                new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connectionFactory.setUsername(RABBIT.getAdminUsername());
        connectionFactory.setPassword(RABBIT.getAdminPassword());
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        Declarables topology = new RabbitTopologyConfiguration().resilientPayTopology(properties);
        topology.getDeclarablesByType(Exchange.class).forEach(admin::declareExchange);
        topology.getDeclarablesByType(Queue.class).forEach(admin::declareQueue);
        topology.getDeclarablesByType(Binding.class).forEach(admin::declareBinding);
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        byte[] ordinary = "bounded-event".getBytes(StandardCharsets.UTF_8);

        for (int index = 0; index < 1000; index++) {
            template.send(properties.exchange(), properties.acceptedRoutingKey(), new Message(ordinary));
        }
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertEquals(
                        1000, admin.getQueueInfo(properties.acceptedQueue()).getMessageCount()));

        byte[] overflow = "overflow-event".getBytes(StandardCharsets.UTF_8);
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        template.send(properties.exchange(), properties.acceptedRoutingKey(), new Message(overflow), correlation);
        CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
        assertFalse(confirm.ack());
        assertEquals(null, template.receive(properties.quarantineQueue(), 100));
        assertEquals(1000, admin.getQueueInfo(properties.acceptedQueue()).getMessageCount());
        for (int index = 0; index < 1000; index++) {
            assertNotNull(template.receive(properties.acceptedQueue(), 1000));
        }
        CorrelationData afterDrain = new CorrelationData(UUID.randomUUID().toString());
        template.send(properties.exchange(), properties.acceptedRoutingKey(), new Message(overflow), afterDrain);
        assertTrue(afterDrain.getFuture().get(5, TimeUnit.SECONDS).ack());
        assertEquals(1, admin.getQueueInfo(properties.acceptedQueue()).getMessageCount());
        connectionFactory.destroy();
    }

    @Test
    void redisAdmitsOneRequestThenReturnsStableRateLimitError() {
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        RedisMutationRateLimiter limiter = new RedisMutationRateLimiter(
                template, new RateLimitProperties(1, Duration.ofMinutes(1)), new SimpleMeterRegistry());
        TenantId tenant = new TenantId(UUID.randomUUID());

        limiter.requirePermit(tenant, "accept-reversal");
        ApplicationException failure =
                assertThrows(ApplicationException.class, () -> limiter.requirePermit(tenant, "accept-reversal"));

        assertEquals(ApplicationErrorCode.RATE_LIMITED, failure.code());
        connectionFactory.destroy();
    }

    private static MessagingProperties properties() {
        return properties("test");
    }

    private static MessagingProperties properties(String prefix) {
        return new MessagingProperties(
                prefix + ".lifecycle",
                prefix + ".accepted",
                prefix + ".original-status",
                prefix + ".execution",
                prefix + ".outcome",
                prefix + ".quarantine.exchange",
                prefix + ".quarantine",
                "accepted",
                "original.status-changed",
                "execution.requested",
                "outcome.observed",
                "outcome.quarantined",
                "execution.quarantined",
                "test-consumer",
                "container-test-message-key",
                "container-key-v1",
                16384,
                Duration.ofMillis(250),
                Duration.ofSeconds(2),
                3);
    }
}
