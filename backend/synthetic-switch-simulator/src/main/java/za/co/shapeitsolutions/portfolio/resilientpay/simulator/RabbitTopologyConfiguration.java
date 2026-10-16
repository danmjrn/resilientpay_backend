package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares durable lifecycle and quarantine topology without broker-generated identifiers.
 *
 * @author Daniel Jr Nkulu
 */
@Configuration(proxyBeanMethods = false)
public class RabbitTopologyConfiguration {

    /** Declares the versioned lifecycle topic exchange. */
    @Bean
    TopicExchange reversalLifecycleExchange() {
        return new TopicExchange(RabbitTopology.LIFECYCLE_EXCHANGE, true, false);
    }

    /** Declares the versioned quarantine topic exchange. */
    @Bean
    TopicExchange reversalQuarantineExchange() {
        return new TopicExchange(RabbitTopology.QUARANTINE_EXCHANGE, true, false);
    }

    /** Declares the internal raw-message dead-letter exchange shared with the orchestrator. */
    @Bean
    TopicExchange reversalDeadLetterExchange() {
        return new TopicExchange(RabbitTopology.DEAD_LETTER_EXCHANGE, true, false);
    }

    /** Declares the durable simulator request queue shared exactly with the orchestrator. */
    @Bean
    Queue syntheticSwitchExecutionQueue() {
        return QueueBuilder.durable(RabbitTopology.EXECUTION_QUEUE)
                .withArguments(Map.of(
                        "x-max-length",
                        1000,
                        "x-max-length-bytes",
                        16777216,
                        "x-overflow",
                        "reject-publish-dlx",
                        "x-dead-letter-exchange",
                        RabbitTopology.DEAD_LETTER_EXCHANGE,
                        "x-dead-letter-routing-key",
                        RabbitTopology.EXECUTION_DEAD_LETTER_ROUTING_KEY))
                .build();
    }

    /** Declares the durable operator-inspectable simulator quarantine queue. */
    @Bean
    Queue syntheticSwitchQuarantineQueue() {
        return QueueBuilder.durable(RabbitTopology.QUARANTINE_QUEUE)
                .withArguments(
                        Map.of("x-max-length", 5000, "x-max-length-bytes", 67108864, "x-overflow", "reject-publish"))
                .build();
    }

    /** Declares the bounded internal queue for operator inspection of raw dead letters. */
    @Bean
    Queue reversalDeadLetterQueue() {
        return QueueBuilder.durable(RabbitTopology.DEAD_LETTER_QUEUE)
                .withArguments(
                        Map.of("x-max-length", 5000, "x-max-length-bytes", 67108864, "x-overflow", "reject-publish"))
                .build();
    }

    /** Routes only execution-request events to the synthetic switch. */
    @Bean
    Binding executionRequestBinding(Queue syntheticSwitchExecutionQueue, TopicExchange reversalLifecycleExchange) {
        return BindingBuilder.bind(syntheticSwitchExecutionQueue)
                .to(reversalLifecycleExchange)
                .with(RabbitTopology.EXECUTION_ROUTING_KEY);
    }

    /** Routes safe quarantine metadata and exhausted deliveries to the quarantine queue. */
    @Bean
    Binding simulatorQuarantineBinding(Queue syntheticSwitchQuarantineQueue, TopicExchange reversalQuarantineExchange) {
        return BindingBuilder.bind(syntheticSwitchQuarantineQueue)
                .to(reversalQuarantineExchange)
                .with(RabbitTopology.QUARANTINE_ROUTING_KEY);
    }

    /** Routes exhausted raw execution deliveries to the internal operator dead-letter queue. */
    @Bean
    Binding exhaustedExecutionBinding(Queue reversalDeadLetterQueue, TopicExchange reversalDeadLetterExchange) {
        return BindingBuilder.bind(reversalDeadLetterQueue)
                .to(reversalDeadLetterExchange)
                .with(RabbitTopology.EXECUTION_DEAD_LETTER_ROUTING_KEY);
    }
}
