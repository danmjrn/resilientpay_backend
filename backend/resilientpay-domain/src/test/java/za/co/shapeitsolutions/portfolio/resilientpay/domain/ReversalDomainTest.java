package za.co.shapeitsolutions.portfolio.resilientpay.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.policy.OperationPolicy;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.policy.RetryPolicy;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation.ReconciliationCase;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation.ReconciliationCaseState;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation.ReconciliationDecision;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ExecutionAttempt;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.NonExecutionEvidence;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalCommandState;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalReason;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ActorId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.CorrelationId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainException;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ExecutionAttemptId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.Money;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationPolicyId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OriginalTransactionId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ReconciliationCaseId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ReversalCommandId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.transaction.OriginalTransaction;

/**
 * Exercises the safety invariants of the framework-free reversal and reconciliation aggregates.
 *
 * @author Daniel Jr Nkulu
 */
class ReversalDomainTest {
    private static final Instant NOW = Instant.parse("2026-08-20T10:15:30Z");
    private static final TenantId TENANT = new TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final Money MONEY = new Money(new BigDecimal("120.00"), Currency.getInstance("ZAR"));
    private static final String HASH = "a".repeat(64);

    @Test
    void definitiveExecutionCompletesAcceptedFullReversal() {
        ReversalCommand command = executingCommand();
        ExecutionAttempt attempt = attempt(command, 1).confirmExecuted(NOW.plusSeconds(3));

        ReversalCommand completed = command.complete(attempt, NOW.plusSeconds(4));

        assertEquals(ReversalCommandState.COMPLETED, completed.state());
        assertEquals(1, completed.operationPolicyVersion());
        assertEquals(3, completed.retryPolicySnapshot().maximumExecutionAttempts());
    }

    @Test
    void acceptanceRejectsPartialOrDifferentCurrencyValue() {
        OriginalTransaction original = original();
        Money partial = new Money(new BigDecimal("119.99"), Currency.getInstance("ZAR"));

        DomainException failure = assertThrows(
                DomainException.class,
                () -> ReversalCommand.accept(
                        new ReversalCommandId(UUID.randomUUID()),
                        original,
                        activePolicy(),
                        partial,
                        new ReversalReason("Synthetic correction"),
                        HASH,
                        "b".repeat(64),
                        new CorrelationId(UUID.randomUUID()),
                        NOW));

        assertEquals(DomainErrorCode.MONEY_MISMATCH, failure.code());
    }

    @Test
    void confirmedNonExecutionAloneAuthorisesBoundedRetry() {
        ReversalCommand command = executingCommand();
        ExecutionAttempt notExecuted =
                attempt(command, 1).confirmNotExecuted("SWITCH_NOT_RECEIVED", NOW.plusSeconds(3));

        ReversalCommand scheduled =
                command.scheduleSafeRetry(NonExecutionEvidence.from(notExecuted), NOW.plusSeconds(4));

        assertEquals(ReversalCommandState.RETRY_SCHEDULED, scheduled.state());
        assertEquals("SWITCH_NOT_RECEIVED", scheduled.failureCode().orElseThrow());
        ExecutionAttempt ambiguous =
                attempt(command, 1).markAmbiguous("INTERRUPTED_AFTER_DISPATCH", NOW.plusSeconds(3));
        DomainException unsafe = assertThrows(DomainException.class, () -> NonExecutionEvidence.from(ambiguous));
        assertEquals(DomainErrorCode.RETRY_NOT_PROVEN_SAFE, unsafe.code());
    }

    @Test
    void snapshottedRetryCeilingRejectsAnotherAttemptAfterTheFinalPermittedAttempt() {
        ReversalCommand command = executingCommand();
        ExecutionAttempt penultimate =
                attempt(command, 2).confirmNotExecuted("SWITCH_NOT_RECEIVED", NOW.plusSeconds(3));
        ExecutionAttempt exhausted = attempt(command, 3).confirmNotExecuted("SWITCH_NOT_RECEIVED", NOW.plusSeconds(3));

        assertEquals(
                ReversalCommandState.RETRY_SCHEDULED,
                command.scheduleSafeRetry(NonExecutionEvidence.from(penultimate), NOW.plusSeconds(4))
                        .state());
        DomainException failure = assertThrows(
                DomainException.class,
                () -> command.scheduleSafeRetry(NonExecutionEvidence.from(exhausted), NOW.plusSeconds(4)));

        assertEquals(DomainErrorCode.ATTEMPT_LIMIT_EXCEEDED, failure.code());
        assertEquals(3, command.retryPolicySnapshot().maximumExecutionAttempts());
        assertEquals(ReversalCommandState.EXECUTING, command.state());
    }

    @Test
    void earlierExecutionWindowEvidenceCannotAuthoriseRetryOrRejection() {
        ReversalCommand command = executingCommand();
        NonExecutionEvidence previousEvidence = NonExecutionEvidence.from(
                attempt(command, 1).confirmNotExecuted("SWITCH_NOT_RECEIVED", NOW.plusSeconds(3)));
        ReversalCommand secondExecution = command.scheduleSafeRetry(previousEvidence, NOW.plusSeconds(4))
                .releaseScheduledRetry(NOW.plusSeconds(5))
                .startExecution(NOW.plusSeconds(6));

        assertEvidenceRejected(secondExecution, previousEvidence, NOW.plusSeconds(7));
    }

    @Test
    void anotherCommandEvidenceCannotAuthoriseRetryOrRejection() {
        ReversalCommand command = executingCommand();
        NonExecutionEvidence foreignEvidence = NonExecutionEvidence.from(
                attempt(executingCommand(), 1).confirmNotExecuted("SWITCH_NOT_RECEIVED", NOW.plusSeconds(3)));

        assertEvidenceRejected(command, foreignEvidence, NOW.plusSeconds(4));
    }

    @Test
    void futureEvidenceCannotAuthoriseRetryOrRejection() {
        ReversalCommand command = executingCommand();
        NonExecutionEvidence futureEvidence = NonExecutionEvidence.from(
                attempt(command, 1).confirmNotExecuted("SWITCH_NOT_RECEIVED", NOW.plusSeconds(5)));

        assertEvidenceRejected(command, futureEvidence, NOW.plusSeconds(4));
    }

    @Test
    void contradictoryMatchingTerminalEvidenceWithdrawsScheduledRetry() {
        ReversalCommand command = executingCommand();
        ExecutionAttempt notExecuted =
                attempt(command, 1).confirmNotExecuted("SWITCH_NOT_RECEIVED", NOW.plusSeconds(3));
        ReversalCommand scheduled =
                command.scheduleSafeRetry(NonExecutionEvidence.from(notExecuted), NOW.plusSeconds(4));

        ReversalCommand uncertain =
                scheduled.requireReconciliationForConflictingOutcome(notExecuted, NOW.plusSeconds(5));

        assertEquals(ReversalCommandState.RECONCILIATION_REQUIRED, uncertain.state());
        assertEquals("CONTRADICTORY_OUTCOME", uncertain.failureCode().orElseThrow());
        assertEquals(scheduled.version() + 1, uncertain.version());
        ExecutionAttempt ambiguous = attempt(command, 1).markAmbiguous("SWITCH_OUTCOME_UNKNOWN", NOW.plusSeconds(3));
        ReversalCommand reconciledRetry = command.requireReconciliation(ambiguous, NOW.plusSeconds(4))
                .scheduleRetryFromReconciliation(1, "RECONCILIATION_CONFIRMED_NOT_EXECUTED", NOW.plusSeconds(5));
        assertEquals(
                ReversalCommandState.RECONCILIATION_REQUIRED,
                reconciledRetry
                        .requireReconciliationForConflictingOutcome(ambiguous, NOW.plusSeconds(6))
                        .state());
    }

    @Test
    void conflictTransitionRejectsForeignOrNonTerminalAttemptEvidence() {
        ReversalCommand command = executingCommand();
        ExecutionAttempt notExecuted =
                attempt(command, 1).confirmNotExecuted("SWITCH_NOT_RECEIVED", NOW.plusSeconds(3));
        ReversalCommand scheduled =
                command.scheduleSafeRetry(NonExecutionEvidence.from(notExecuted), NOW.plusSeconds(4));
        ExecutionAttempt foreign =
                attempt(executingCommand(), 1).confirmNotExecuted("SWITCH_NOT_RECEIVED", NOW.plusSeconds(3));

        DomainException foreignFailure = assertThrows(
                DomainException.class,
                () -> scheduled.requireReconciliationForConflictingOutcome(foreign, NOW.plusSeconds(5)));
        DomainException activeFailure = assertThrows(
                DomainException.class,
                () -> scheduled.requireReconciliationForConflictingOutcome(attempt(command, 1), NOW.plusSeconds(5)));

        assertEquals(DomainErrorCode.TENANT_SCOPE_MISMATCH, foreignFailure.code());
        assertEquals(DomainErrorCode.INVALID_VALUE, activeFailure.code());
        assertEquals(ReversalCommandState.RETRY_SCHEDULED, scheduled.state());
    }

    @Test
    void ambiguityRequiresReconciliationAndDistinctChecker() {
        ReversalCommand command = executingCommand();
        ExecutionAttempt ambiguous =
                attempt(command, 1).markAmbiguous("INTERRUPTED_AFTER_DISPATCH", NOW.plusSeconds(3));
        ReversalCommand awaitingDecision = command.requireReconciliation(ambiguous, NOW.plusSeconds(4));
        ActorId maker = new ActorId("maker-subject");
        ReconciliationCase proposed = ReconciliationCase.open(
                        new ReconciliationCaseId(UUID.randomUUID()),
                        awaitingDecision,
                        "INTERRUPTED_AFTER_DISPATCH",
                        NOW.plusSeconds(4))
                .proposeDecision(ReconciliationDecision.QUARANTINE, maker, "CONFLICTING_EVIDENCE", NOW.plusSeconds(5));

        DomainException sameActor =
                assertThrows(DomainException.class, () -> proposed.approveDecision(maker, NOW.plusSeconds(6)));
        assertEquals(DomainErrorCode.RECONCILIATION_APPROVER_CONFLICT, sameActor.code());
        ReconciliationCase approved = proposed.approveDecision(new ActorId("checker-subject"), NOW.plusSeconds(6));
        assertEquals(ReconciliationCaseState.QUARANTINED, approved.state());
        assertTrue(approved.approvedBy().isPresent());
    }

    @Test
    void approvedNonExecutionReconciliationSchedulesBoundedRetry() {
        ReversalCommand command = executingCommand();
        ExecutionAttempt ambiguous =
                attempt(command, 1).markAmbiguous("INTERRUPTED_AFTER_DISPATCH", NOW.plusSeconds(3));
        ReversalCommand awaitingDecision = command.requireReconciliation(ambiguous, NOW.plusSeconds(4));

        ReversalCommand scheduled = awaitingDecision.scheduleRetryFromReconciliation(
                1, "RECONCILIATION_CONFIRMED_NOT_EXECUTED", NOW.plusSeconds(7));

        assertEquals(ReversalCommandState.RETRY_SCHEDULED, scheduled.state());
        assertEquals(
                "RECONCILIATION_CONFIRMED_NOT_EXECUTED", scheduled.failureCode().orElseThrow());
    }

    @Test
    void reconciliationCannotExceedTheAcceptanceTimeAttemptCeiling() {
        ReversalCommand command = executingCommand();
        ExecutionAttempt ambiguous =
                attempt(command, 3).markAmbiguous("INTERRUPTED_AFTER_DISPATCH", NOW.plusSeconds(3));
        ReversalCommand awaitingDecision = command.requireReconciliation(ambiguous, NOW.plusSeconds(4));

        DomainException failure = assertThrows(
                DomainException.class,
                () -> awaitingDecision.scheduleRetryFromReconciliation(
                        3, "RECONCILIATION_CONFIRMED_NOT_EXECUTED", NOW.plusSeconds(7)));

        assertEquals(DomainErrorCode.ATTEMPT_LIMIT_EXCEEDED, failure.code());
        assertEquals(ReversalCommandState.RECONCILIATION_REQUIRED, awaitingDecision.state());
    }

    @Test
    void executionAttemptLimitIsFiveAcrossPolicyAndAttemptIdentity() {
        RetryPolicy boundary = new RetryPolicy(5, Duration.ofSeconds(5), Duration.ofMillis(250), Duration.ofSeconds(2));
        ExecutionAttempt fifth = ExecutionAttempt.create(
                new ExecutionAttemptId(UUID.randomUUID()),
                TENANT,
                new ReversalCommandId(UUID.randomUUID()),
                5,
                NOW,
                NOW.plusSeconds(5));

        assertEquals(5, boundary.maximumExecutionAttempts());
        assertEquals(5, fifth.attemptNumber());
        DomainException policyOverflow = assertThrows(
                DomainException.class,
                () -> new RetryPolicy(6, Duration.ofSeconds(5), Duration.ofMillis(250), Duration.ofSeconds(2)));
        DomainException attemptOverflow = assertThrows(
                DomainException.class,
                () -> ExecutionAttempt.create(
                        new ExecutionAttemptId(UUID.randomUUID()),
                        TENANT,
                        new ReversalCommandId(UUID.randomUUID()),
                        6,
                        NOW,
                        NOW.plusSeconds(5)));
        assertEquals(DomainErrorCode.INVALID_VALUE, policyOverflow.code());
        assertEquals(DomainErrorCode.INVALID_VALUE, attemptOverflow.code());
    }

    private static void assertEvidenceRejected(
            ReversalCommand command, NonExecutionEvidence evidence, Instant decisionAt) {
        DomainException retryFailure =
                assertThrows(DomainException.class, () -> command.scheduleSafeRetry(evidence, decisionAt));
        DomainException rejectionFailure = assertThrows(
                DomainException.class, () -> command.rejectAfterNonExecution(evidence, "SWITCH_REJECTED", decisionAt));

        assertEquals(DomainErrorCode.RETRY_NOT_PROVEN_SAFE, retryFailure.code());
        assertEquals(DomainErrorCode.RETRY_NOT_PROVEN_SAFE, rejectionFailure.code());
        assertEquals(ReversalCommandState.EXECUTING, command.state());
    }

    private static ReversalCommand executingCommand() {
        return ReversalCommand.accept(
                        new ReversalCommandId(UUID.randomUUID()),
                        original(),
                        activePolicy(),
                        MONEY,
                        new ReversalReason("Synthetic correction"),
                        HASH,
                        "b".repeat(64),
                        new CorrelationId(UUID.randomUUID()),
                        NOW)
                .route(NOW.plusSeconds(1))
                .markExecutionPending(NOW.plusSeconds(1))
                .startExecution(NOW.plusSeconds(2));
    }

    private static ExecutionAttempt attempt(ReversalCommand command, int number) {
        return ExecutionAttempt.create(
                        new ExecutionAttemptId(UUID.randomUUID()),
                        TENANT,
                        command.id(),
                        number,
                        NOW.plusSeconds(2),
                        NOW.plusSeconds(30))
                .dispatch(NOW.plusSeconds(2));
    }

    private static OriginalTransaction original() {
        return OriginalTransaction.post(
                new OriginalTransactionId(UUID.randomUUID()),
                TENANT,
                "synth-txn-card-0001",
                OperationType.CARD_PURCHASE,
                MONEY,
                NOW.minusSeconds(60));
    }

    private static OperationPolicy activePolicy() {
        return OperationPolicy.draft(
                        new OperationPolicyId(UUID.randomUUID()),
                        TENANT,
                        OperationType.CARD_PURCHASE,
                        1,
                        new RetryPolicy(3, Duration.ofSeconds(5), Duration.ofMillis(250), Duration.ofSeconds(2)),
                        NOW.minusSeconds(3600),
                        null,
                        new ActorId("policy-author"),
                        NOW.minusSeconds(120))
                .submit(new ActorId("policy-maker"), NOW.minusSeconds(90))
                .approve(new ActorId("policy-checker"), NOW.minusSeconds(80));
    }
}
