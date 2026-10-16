package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Produces deterministic synthetic outcomes and makes duplicate attempt deliveries replay-safe.
 *
 * @author Daniel Jr Nkulu
 */
@Component
public final class SyntheticSwitchProcessor {
    private final Clock clock;
    private final SimulatorProperties properties;
    private final SimulatorExecutionJournal journal;

    /** Creates the deterministic processor around explicit time and scenario configuration. */
    @Autowired
    public SyntheticSwitchProcessor(Clock clock, SimulatorProperties properties, SimulatorExecutionJournal journal) {
        this.clock = clock;
        this.properties = properties;
        this.journal = journal;
    }

    SyntheticSwitchProcessor(Clock clock, SimulatorProperties properties) {
        this(clock, properties, new SimulatorExecutionJournal(properties));
    }

    /** Produces or safely replays one outcome keyed by immutable execution-attempt identity. */
    public OutcomePayload process(ExecutionRequestedPayload request) {
        return processForPublication(request).outcome();
    }

    /** Produces a result with a stable message identity for confirmed broker publication. */
    public ProcessedExecution processForPublication(ExecutionRequestedPayload request) {
        pauseIfConfigured();
        SimulatorScenario scenario = scenario(request.demonstrationFailure());
        boolean interruptFirst = scenario == SimulatorScenario.INTERRUPT_ONCE && request.attemptNumber() == 1;
        OutcomePayload outcome =
                switch (scenario) {
                    case SUCCEED -> createOutcome(request, SwitchOutcome.EXECUTED, null);
                    case INTERRUPT_ONCE ->
                        request.attemptNumber() == 1
                                ? createOutcome(request, SwitchOutcome.CONFIRMED_NOT_EXECUTED, "SWITCH_NOT_RECEIVED")
                                : createOutcome(request, SwitchOutcome.EXECUTED, null);
                    case CONFIRM_NOT_EXECUTED ->
                        createOutcome(request, SwitchOutcome.CONFIRMED_NOT_EXECUTED, "SWITCH_NOT_RECEIVED");
                    case REJECT -> createOutcome(request, SwitchOutcome.REJECTED, "SYNTHETIC_REJECT");
                    case AMBIGUOUS -> createOutcome(request, SwitchOutcome.AMBIGUOUS, "INTERRUPTED_AFTER_DISPATCH");
                };
        return journal.resolve(request, interruptFirst, outcome);
    }

    private OutcomePayload createOutcome(ExecutionRequestedPayload request, SwitchOutcome outcome, String reasonCode) {
        return new OutcomePayload(
                request.reversalCommandId(), request.executionAttemptId(), outcome, reasonCode, clock.instant());
    }

    private SimulatorScenario scenario(DemonstrationFailure failure) {
        if (failure != null && failure != DemonstrationFailure.NONE && !properties.faultInjectionEnabled()) {
            throw new InvalidInboundMessageException("FAULT_INJECTION_DISABLED");
        }
        if (failure == null) {
            return properties.scenario();
        }
        return switch (failure) {
            case NONE -> SimulatorScenario.SUCCEED;
            case INTERRUPT_ONCE -> SimulatorScenario.INTERRUPT_ONCE;
            case AMBIGUOUS -> SimulatorScenario.AMBIGUOUS;
            case REJECT -> SimulatorScenario.REJECT;
        };
    }

    private void pauseIfConfigured() {
        long delayMillis = properties.delay().toMillis();
        if (delayMillis == 0) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SyntheticInterruptionException();
        }
    }
}
