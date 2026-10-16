package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies deterministic fault injection and attempt-level replay safety in the synthetic switch.
 *
 * @author Daniel Jr Nkulu
 */
class SyntheticSwitchProcessorTest {
    private static final Instant NOW = Instant.parse("2026-08-20T10:15:30Z");

    @Test
    void perCommandScenariosProduceCanonicalBoundedOutcomes() {
        SyntheticSwitchProcessor processor = processor(SimulatorScenario.CONFIRM_NOT_EXECUTED);

        assertEquals(
                SwitchOutcome.EXECUTED,
                processor.process(request(DemonstrationFailure.NONE)).outcome());
        OutcomePayload rejected = processor.process(request(DemonstrationFailure.REJECT));
        assertEquals(SwitchOutcome.REJECTED, rejected.outcome());
        assertEquals("SYNTHETIC_REJECT", rejected.reasonCode());
        OutcomePayload ambiguous = processor.process(request(DemonstrationFailure.AMBIGUOUS));
        assertEquals(SwitchOutcome.AMBIGUOUS, ambiguous.outcome());
        assertEquals("INTERRUPTED_AFTER_DISPATCH", ambiguous.reasonCode());
        OutcomePayload fallback = processor.process(request(null));
        assertEquals(SwitchOutcome.CONFIRMED_NOT_EXECUTED, fallback.outcome());
    }

    @Test
    void interruptOnceProvesFirstAttemptSafeThenSecondAttemptSucceeds() {
        SyntheticSwitchProcessor processor = processor(SimulatorScenario.SUCCEED);
        ExecutionRequestedPayload request = request(DemonstrationFailure.INTERRUPT_ONCE);

        assertThrows(SyntheticInterruptionException.class, () -> processor.process(request));
        OutcomePayload recovered = processor.process(request);
        OutcomePayload replay = processor.process(request);
        ExecutionRequestedPayload secondAttempt = new ExecutionRequestedPayload(
                request.reversalCommandId(),
                UUID.randomUUID(),
                2,
                NOW.plusSeconds(10),
                DemonstrationFailure.INTERRUPT_ONCE);
        OutcomePayload completed = processor.process(secondAttempt);

        assertEquals(SwitchOutcome.CONFIRMED_NOT_EXECUTED, recovered.outcome());
        assertEquals("SWITCH_NOT_RECEIVED", recovered.reasonCode());
        assertEquals(recovered, replay);
        assertEquals(NOW, recovered.observedAt());
        assertEquals(SwitchOutcome.EXECUTED, completed.outcome());
    }

    @Test
    void faultInjectionFailsClosedWithoutExplicitLocalEnablement() {
        SimulatorProperties disabled = new SimulatorProperties(
                SimulatorScenario.SUCCEED,
                Duration.ZERO,
                "deterministic-message-integrity-key",
                "demo-v1",
                false,
                Duration.ofSeconds(1));
        SyntheticSwitchProcessor processor = new SyntheticSwitchProcessor(Clock.fixed(NOW, ZoneOffset.UTC), disabled);

        assertThrows(
                InvalidInboundMessageException.class, () -> processor.process(request(DemonstrationFailure.AMBIGUOUS)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SimulatorProperties(
                        SimulatorScenario.AMBIGUOUS,
                        Duration.ZERO,
                        "deterministic-message-integrity-key",
                        "demo-v1",
                        false,
                        Duration.ofSeconds(1)));
    }

    private static SyntheticSwitchProcessor processor(SimulatorScenario fallback) {
        return new SyntheticSwitchProcessor(
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SimulatorProperties(
                        fallback,
                        Duration.ZERO,
                        "deterministic-message-integrity-key",
                        "demo-v1",
                        true,
                        Duration.ofSeconds(1)));
    }

    private static ExecutionRequestedPayload request(DemonstrationFailure failure) {
        return new ExecutionRequestedPayload(UUID.randomUUID(), UUID.randomUUID(), 1, NOW.plusSeconds(5), failure);
    }
}
