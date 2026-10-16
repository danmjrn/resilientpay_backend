package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

/**
 * Centralises the versioned exchange, queue, and routing identities shared with the orchestrator.
 *
 * @author Daniel Jr Nkulu
 */
public final class RabbitTopology {
    public static final String LIFECYCLE_EXCHANGE = "resilientpay.reversal.lifecycle.v1";
    public static final String QUARANTINE_EXCHANGE = "resilientpay.reversal.quarantine.v1";
    public static final String DEAD_LETTER_EXCHANGE = "resilientpay.reversal.dead-letter.v1";
    public static final String EXECUTION_QUEUE = "resilientpay.synthetic-switch.execution.v1";
    public static final String QUARANTINE_QUEUE = "resilientpay.synthetic-switch.quarantine.v1";
    public static final String DEAD_LETTER_QUEUE = "resilientpay.orchestrator.dead-letter.v1";
    public static final String EXECUTION_ROUTING_KEY = "reversal.execution.requested.v1";
    public static final String OUTCOME_ROUTING_KEY = "reversal.outcome.observed.v1";
    public static final String QUARANTINE_ROUTING_KEY = "message.quarantined.v1";
    public static final String EXECUTION_DEAD_LETTER_ROUTING_KEY = "reversal.execution.dead-lettered.v1";

    private RabbitTopology() {}
}
