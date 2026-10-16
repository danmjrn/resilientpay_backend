package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Requires a positive RabbitMQ publisher confirmation and no mandatory return before a consumed
 * request may be acknowledged.
 *
 * @author Daniel Jr Nkulu
 */
@Component
public final class ConfirmedRabbitPublisher {
    private final RabbitOperations rabbitOperations;
    private final Duration confirmTimeout;

    /** Enables mandatory publishing and captures the bounded confirmation deadline. */
    public ConfirmedRabbitPublisher(RabbitTemplate rabbitTemplate, SimulatorProperties properties) {
        rabbitTemplate.setMandatory(true);
        this.rabbitOperations = rabbitTemplate;
        this.confirmTimeout = properties.publishConfirmTimeout();
    }

    /** Publishes only successfully when RabbitMQ confirms a routed message. */
    public void send(String exchange, String routingKey, Message message, UUID messageId) {
        CorrelationData correlation = new CorrelationData(messageId.toString());
        try {
            rabbitOperations.send(exchange, routingKey, message, correlation);
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                throw new OutcomePublishException("RabbitMQ negatively acknowledged the publication");
            }
            if (correlation.getReturned() != null) {
                throw new OutcomePublishException("RabbitMQ returned an unroutable mandatory publication");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OutcomePublishException("RabbitMQ publication confirmation was interrupted", exception);
        } catch (TimeoutException exception) {
            throw new OutcomePublishException("RabbitMQ publication confirmation timed out", exception);
        } catch (ExecutionException | AmqpException exception) {
            throw new OutcomePublishException("RabbitMQ publication confirmation failed", exception);
        }
    }
}
