package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.MessagingProperties;

/**
 * Declares durable lifecycle and bounded dead-letter RabbitMQ topology.
 *
 * @author Daniel Jr Nkulu
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class RabbitTopologyConfiguration {
    static final int WORK_QUEUE_MAX_LENGTH = 1000;
    static final int WORK_QUEUE_MAX_BYTES = 16 * 1024 * 1024;
    static final int QUARANTINE_QUEUE_MAX_LENGTH = 5000;
    static final int QUARANTINE_QUEUE_MAX_BYTES = 64 * 1024 * 1024;
    static final String DEAD_LETTER_EXCHANGE = "resilientpay.reversal.dead-letter.v1";
    static final String DEAD_LETTER_QUEUE = "resilientpay.orchestrator.dead-letter.v1";
    static final String EXECUTION_DEAD_LETTER_ROUTING_KEY = "reversal.execution.dead-lettered.v1";
    static final String OUTCOME_DEAD_LETTER_ROUTING_KEY = "reversal.outcome.dead-lettered.v1";

    /**
     * Creates exchanges, queues, and exact routing bindings.
     *
     * @param properties versioned topology names
     * @return broker declarables
     */
    @Bean
    Declarables resilientPayTopology(MessagingProperties properties) {
        TopicExchange lifecycle = new TopicExchange(properties.exchange(), true, false);
        TopicExchange quarantine = new TopicExchange(properties.quarantineExchange(), true, false);
        TopicExchange deadLetter = new TopicExchange(DEAD_LETTER_EXCHANGE, true, false);
        Queue acceptedQueue = QueueBuilder.durable(properties.acceptedQueue())
                .withArguments(Map.of(
                        "x-max-length",
                        WORK_QUEUE_MAX_LENGTH,
                        "x-max-length-bytes",
                        WORK_QUEUE_MAX_BYTES,
                        "x-overflow",
                        "reject-publish"))
                .build();
        Queue originalStatusQueue = QueueBuilder.durable(properties.originalStatusQueue())
                .withArguments(Map.of(
                        "x-max-length",
                        WORK_QUEUE_MAX_LENGTH,
                        "x-max-length-bytes",
                        WORK_QUEUE_MAX_BYTES,
                        "x-overflow",
                        "reject-publish"))
                .build();
        Queue executionQueue = QueueBuilder.durable(properties.executionQueue())
                .withArguments(Map.of(
                        "x-max-length",
                        WORK_QUEUE_MAX_LENGTH,
                        "x-max-length-bytes",
                        WORK_QUEUE_MAX_BYTES,
                        "x-overflow",
                        "reject-publish-dlx",
                        "x-dead-letter-exchange",
                        DEAD_LETTER_EXCHANGE,
                        "x-dead-letter-routing-key",
                        EXECUTION_DEAD_LETTER_ROUTING_KEY))
                .build();
        Queue outcomeQueue = QueueBuilder.durable(properties.outcomeQueue())
                .withArguments(Map.of(
                        "x-max-length",
                        WORK_QUEUE_MAX_LENGTH,
                        "x-max-length-bytes",
                        WORK_QUEUE_MAX_BYTES,
                        "x-overflow",
                        "reject-publish-dlx",
                        "x-dead-letter-exchange",
                        DEAD_LETTER_EXCHANGE,
                        "x-dead-letter-routing-key",
                        OUTCOME_DEAD_LETTER_ROUTING_KEY))
                .build();
        Queue quarantineQueue = QueueBuilder.durable(properties.quarantineQueue())
                .withArguments(Map.of(
                        "x-max-length",
                        QUARANTINE_QUEUE_MAX_LENGTH,
                        "x-max-length-bytes",
                        QUARANTINE_QUEUE_MAX_BYTES,
                        "x-overflow",
                        "reject-publish"))
                .build();
        Queue deadLetterQueue = QueueBuilder.durable(DEAD_LETTER_QUEUE)
                .withArguments(Map.of(
                        "x-max-length",
                        QUARANTINE_QUEUE_MAX_LENGTH,
                        "x-max-length-bytes",
                        QUARANTINE_QUEUE_MAX_BYTES,
                        "x-overflow",
                        "reject-publish"))
                .build();
        Binding acceptedBinding =
                BindingBuilder.bind(acceptedQueue).to(lifecycle).with(properties.acceptedRoutingKey());
        Binding originalStatusBinding =
                BindingBuilder.bind(originalStatusQueue).to(lifecycle).with(properties.originalStatusRoutingKey());
        Binding executionBinding =
                BindingBuilder.bind(executionQueue).to(lifecycle).with(properties.executionRoutingKey());
        Binding outcomeBinding = BindingBuilder.bind(outcomeQueue).to(lifecycle).with(properties.outcomeRoutingKey());
        Binding quarantineBinding =
                BindingBuilder.bind(quarantineQueue).to(quarantine).with(properties.quarantineRoutingKey());
        Binding executionDeadLetterBinding =
                BindingBuilder.bind(deadLetterQueue).to(deadLetter).with(EXECUTION_DEAD_LETTER_ROUTING_KEY);
        Binding outcomeDeadLetterBinding =
                BindingBuilder.bind(deadLetterQueue).to(deadLetter).with(OUTCOME_DEAD_LETTER_ROUTING_KEY);
        return new Declarables(
                lifecycle,
                quarantine,
                deadLetter,
                acceptedQueue,
                originalStatusQueue,
                executionQueue,
                outcomeQueue,
                quarantineQueue,
                deadLetterQueue,
                acceptedBinding,
                originalStatusBinding,
                executionBinding,
                outcomeBinding,
                quarantineBinding,
                executionDeadLetterBinding,
                outcomeDeadLetterBinding);
    }
}
