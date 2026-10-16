package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

/**
 * Guards shared RabbitMQ declaration durability and the canonical execution queue name.
 *
 * @author Daniel Jr Nkulu
 */
class RabbitTopologyConfigurationTest {

    @Test
    void sharedExecutionQueueHasConflictFreeDurableDeclaration() {
        RabbitTopologyConfiguration configuration = new RabbitTopologyConfiguration();
        Queue queue = configuration.syntheticSwitchExecutionQueue();
        TopicExchange exchange = configuration.reversalLifecycleExchange();

        assertEquals("resilientpay.synthetic-switch.execution.v1", queue.getName());
        assertTrue(queue.isDurable());
        assertFalse(queue.isExclusive());
        assertFalse(queue.isAutoDelete());
        assertEquals(1000, queue.getArguments().get("x-max-length"));
        assertEquals(16777216, queue.getArguments().get("x-max-length-bytes"));
        assertEquals("reject-publish-dlx", queue.getArguments().get("x-overflow"));
        assertEquals(
                "resilientpay.reversal.dead-letter.v1", queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals("reversal.execution.dead-lettered.v1", queue.getArguments().get("x-dead-letter-routing-key"));
        assertEquals("resilientpay.reversal.lifecycle.v1", exchange.getName());
        assertTrue(exchange.isDurable());
        assertFalse(exchange.isAutoDelete());
    }

    @Test
    void terminalQuarantineIsBoundedAndRejectsOverflowWithoutRecursiveDeadLettering() {
        RabbitTopologyConfiguration configuration = new RabbitTopologyConfiguration();
        Queue quarantine = configuration.syntheticSwitchQuarantineQueue();
        Queue deadLetter = configuration.reversalDeadLetterQueue();

        assertBoundedTerminalQueue(quarantine);
        assertBoundedTerminalQueue(deadLetter);
        assertEquals("resilientpay.orchestrator.dead-letter.v1", deadLetter.getName());
    }

    private static void assertBoundedTerminalQueue(Queue queue) {
        assertEquals(5000, queue.getArguments().get("x-max-length"));
        assertEquals(67108864, queue.getArguments().get("x-max-length-bytes"));
        assertEquals("reject-publish", queue.getArguments().get("x-overflow"));
        assertFalse(queue.getArguments().containsKey("x-dead-letter-exchange"));
    }
}
