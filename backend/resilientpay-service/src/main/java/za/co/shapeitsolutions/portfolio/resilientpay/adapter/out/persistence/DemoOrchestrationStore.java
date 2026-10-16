package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AcceptReversalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AcceptanceResult;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ActorContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApproveReconciliationCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AuditEvidenceView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.DemonstrationFailure;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.IngestOriginalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OperationPolicyView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OriginalTransactionView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ProposeReconciliationCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ReconciliationCaseView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.RetryReversalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ReversalView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OrchestrationStore;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.policy.OperationPolicy;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.policy.RetryPolicy;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation.ReconciliationCase;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation.ReconciliationCaseState;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation.ReconciliationDecision;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ExecutionAttempt;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.NonExecutionEvidence;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalCommandState;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ActorId;
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
 * Supplies a deterministic, process-local adapter for the explicitly local demo and test profiles.
 * Production intentionally has no fallback bean: it must use an externally durable adapter.
 * All public methods are synchronized so acceptance, reservation, idempotency, and evidence updates
 * form one atomic critical section in this bounded implementation.
 *
 * @author Daniel Jr Nkulu
 */
public final class DemoOrchestrationStore implements OrchestrationStore {
    /** Stable tenant encoded into locally issued demo tokens and synthetic seed data. */
    public static final UUID DEMO_TENANT_VALUE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final RetryPolicy DEMO_RETRY_POLICY =
            new RetryPolicy(3, Duration.ofSeconds(5), Duration.ofMillis(250), Duration.ofSeconds(2));

    private final Clock clock;
    private final byte[] evidenceKey;
    private final Map<TenantId, TenantLedger> ledgers = new HashMap<>();

    /** Creates the local adapter and seeds four neutral operation types for the demo tenant. */
    public DemoOrchestrationStore(Clock clock, String evidenceHmacKey) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.evidenceKey = Objects.requireNonNull(evidenceHmacKey, "evidenceHmacKey must not be null")
                .getBytes(StandardCharsets.UTF_8);
        seedDemoTenant();
    }

    @Override
    public synchronized AcceptanceResult accept(ActorContext actor, AcceptReversalCommand command) {
        requireMutationKey(command.idempotencyKey());
        TenantLedger ledger = ledger(actor.tenantId());
        String keyHash = sha256(command.idempotencyKey());
        String requestHash = sha256(String.join(
                "|",
                command.originalTransactionToken(),
                command.operationType().name(),
                command.money().amount().toPlainString(),
                command.money().currency().getCurrencyCode(),
                command.reason().value(),
                command.demonstrationFailure().name()));
        IdempotencyRecord replay = ledger.idempotency.get("ACCEPT:" + keyHash);
        if (replay != null) {
            if (!replay.requestHash.equals(requestHash)) {
                throw failure(
                        ApplicationErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "The idempotency key was already used for a different request");
            }
            return new AcceptanceResult(view(ledger, requireWorkflow(ledger, replay.resourceId)), true);
        }

        OriginalTransaction original = Optional.ofNullable(
                        ledger.originalsByToken.get(command.originalTransactionToken()))
                .orElseThrow(() -> failure(
                        ApplicationErrorCode.REVERSAL_NOT_ELIGIBLE,
                        "The synthetic original transaction is not eligible for reversal"));
        if (original.operationType() != command.operationType()
                || !original.money().sameValueAs(command.money())) {
            throw failure(
                    ApplicationErrorCode.REVERSAL_NOT_ELIGIBLE,
                    "The requested operation and money must match the original transaction");
        }
        if (original.state()
                != za.co.shapeitsolutions.portfolio.resilientpay.domain.transaction.OriginalTransactionState.POSTED) {
            throw failure(
                    ApplicationErrorCode.REVERSAL_ALREADY_EXISTS,
                    "A reversal already reserves or has completed for this original transaction");
        }
        OperationPolicy policy = Optional.ofNullable(ledger.policies.get(command.operationType()))
                .orElseThrow(() -> failure(
                        ApplicationErrorCode.POLICY_NOT_ACTIVE, "No active operation policy governs this request"));
        Instant now = clock.instant();
        ReversalCommand reversal = ReversalCommand.accept(
                new ReversalCommandId(UUID.randomUUID()),
                original,
                policy,
                command.money(),
                command.reason(),
                keyHash,
                requestHash,
                command.correlationId(),
                now);
        Workflow workflow = new Workflow(
                reversal,
                original.reserveForReversal(now),
                command.originalTransactionToken(),
                new ArrayList<>(),
                null);
        ledger.workflows.put(reversal.id().value(), workflow);
        ledger.originalsByToken.put(command.originalTransactionToken(), workflow.original);
        ledger.idempotency.put(
                "ACCEPT:" + keyHash,
                new IdempotencyRecord(requestHash, reversal.id().value()));
        audit(ledger, workflow, actor, "REVERSAL_ACCEPTED", "ACCEPTED", "REVERSAL_REQUEST", now);
        progressFirstAttempt(ledger, workflow, actor, command.demonstrationFailure(), now);
        return new AcceptanceResult(view(ledger, workflow), false);
    }

    @Override
    public synchronized List<ReversalView> listReversals(TenantId tenantId, ReversalCommandState status, int limit) {
        if (limit < 1 || limit > 100) {
            throw failure(ApplicationErrorCode.INVALID_REQUEST, "limit must be between 1 and 100");
        }
        return ledger(tenantId).workflows.values().stream()
                .filter(workflow -> status == null || workflow.command.state() == status)
                .sorted(Comparator.comparing((Workflow workflow) -> workflow.command.acceptedAt())
                        .reversed())
                .limit(limit)
                .map(workflow -> view(ledger(tenantId), workflow))
                .toList();
    }

    @Override
    public synchronized ReversalView getReversal(TenantId tenantId, UUID commandId) {
        TenantLedger ledger = ledger(tenantId);
        return view(ledger, requireWorkflow(ledger, commandId));
    }

    @Override
    public synchronized ReversalView retry(ActorContext actor, RetryReversalCommand command) {
        requireMutationKey(command.idempotencyKey());
        if (!"CONFIRMED_NOT_EXECUTED".equals(command.proof())
                || command.purpose().length() < 8
                || command.purpose().length() > 160) {
            throw failure(
                    ApplicationErrorCode.INVALID_REQUEST,
                    "Retry requires confirmed non-execution proof and a bounded purpose");
        }
        TenantLedger ledger = ledger(actor.tenantId());
        Workflow workflow = requireWorkflow(ledger, command.reversalCommandId());
        String keyHash = sha256(command.idempotencyKey());
        String requestHash = sha256(command.reversalCommandId() + "|" + command.proof() + "|" + command.purpose());
        IdempotencyRecord replay = ledger.idempotency.get("RETRY:" + keyHash);
        if (replay != null) {
            if (!replay.requestHash.equals(requestHash)) {
                throw failure(
                        ApplicationErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "The idempotency key was already used for a different request");
            }
            return view(ledger, requireWorkflow(ledger, replay.resourceId));
        }
        if (workflow.command.state() != ReversalCommandState.RETRY_SCHEDULED) {
            throw failure(
                    ApplicationErrorCode.STATE_TRANSITION_NOT_ALLOWED,
                    "A retry is allowed only after current confirmed non-execution evidence");
        }
        Instant now = clock.instant();
        workflow.command = workflow.command.releaseScheduledRetry(now).startExecution(now);
        int nextAttemptNumber = workflow.attempts.size() + 1;
        ExecutionAttempt attempt = ExecutionAttempt.create(
                        new ExecutionAttemptId(UUID.randomUUID()),
                        actor.tenantId(),
                        workflow.command.id(),
                        nextAttemptNumber,
                        now,
                        now.plus(workflow.command.retryPolicySnapshot().resultTimeout()))
                .dispatch(now)
                .confirmExecuted(now);
        workflow.attempts.add(attempt);
        workflow.command = workflow.command.complete(attempt, now);
        workflow.original = workflow.original.confirmReversed(now);
        ledger.originalsByToken.put(workflow.originalToken, workflow.original);
        ledger.idempotency.put(
                "RETRY:" + keyHash,
                new IdempotencyRecord(requestHash, workflow.command.id().value()));
        audit(ledger, workflow, actor, "SAFE_RETRY_COMPLETED", "SUCCEEDED", command.purpose(), now);
        return view(ledger, workflow);
    }

    @Override
    public synchronized List<AuditEvidenceView> evidence(TenantId tenantId, UUID commandId) {
        TenantLedger ledger = ledger(tenantId);
        requireWorkflow(ledger, commandId);
        return List.copyOf(ledger.evidence.getOrDefault(commandId, List.of()));
    }

    @Override
    public synchronized OriginalTransactionView ingestOriginal(ActorContext actor, IngestOriginalCommand command) {
        requireMutationKey(command.idempotencyKey());
        if (!command.transactionToken().matches("^synth-[a-z0-9-]{8,64}$")) {
            throw failure(
                    ApplicationErrorCode.INVALID_REQUEST, "transactionToken must be a bounded synthetic reference");
        }
        TenantLedger ledger = ledger(actor.tenantId());
        String keyHash = sha256(command.idempotencyKey());
        String requestHash = sha256(String.join(
                "|",
                command.transactionToken(),
                command.operationType().name(),
                command.money().amount().toPlainString(),
                command.money().currency().getCurrencyCode()));
        IdempotencyRecord replay = ledger.idempotency.get("INGEST:" + keyHash);
        if (replay != null) {
            if (!replay.requestHash.equals(requestHash)) {
                throw failure(
                        ApplicationErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "The idempotency key was already used for a different request");
            }
            return originalView(ledger.originalsById.get(replay.resourceId), command.transactionToken());
        }
        if (ledger.originalsByToken.containsKey(command.transactionToken())) {
            throw failure(
                    ApplicationErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "The synthetic transaction token already identifies another request");
        }
        Instant now = clock.instant();
        OriginalTransaction original = OriginalTransaction.post(
                new OriginalTransactionId(UUID.randomUUID()),
                actor.tenantId(),
                command.transactionToken(),
                command.operationType(),
                command.money(),
                now);
        ledger.originalsByToken.put(command.transactionToken(), original);
        ledger.originalsById.put(original.id().value(), original);
        ledger.idempotency.put(
                "INGEST:" + keyHash,
                new IdempotencyRecord(requestHash, original.id().value()));
        return originalView(original, command.transactionToken());
    }

    @Override
    public synchronized List<OriginalTransactionView> listOriginals(TenantId tenantId) {
        return ledger(tenantId).originalsByToken.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> originalView(entry.getValue(), entry.getKey()))
                .toList();
    }

    @Override
    public synchronized List<OperationPolicyView> listPolicies(TenantId tenantId) {
        return ledger(tenantId).policies.values().stream()
                .sorted(Comparator.comparing(policy -> policy.operationType().name()))
                .map(DemoOrchestrationStore::policyView)
                .toList();
    }

    @Override
    public synchronized List<ReconciliationCaseView> listReconciliationCases(TenantId tenantId) {
        return ledger(tenantId).workflows.values().stream()
                .filter(workflow -> workflow.reconciliationCase != null)
                .sorted(Comparator.comparing((Workflow workflow) -> workflow.reconciliationCase.openedAt())
                        .reversed())
                .map(DemoOrchestrationStore::caseView)
                .toList();
    }

    @Override
    public synchronized ReconciliationCaseView propose(ActorContext actor, ProposeReconciliationCommand command) {
        requireMutationKey(command.idempotencyKey());
        if (command.purpose().length() < 8
                || command.purpose().length() > 160
                || !command.evidenceDigest().matches("[a-f0-9]{64}")) {
            throw failure(
                    ApplicationErrorCode.INVALID_REQUEST, "A bounded purpose and SHA-256 evidence digest are required");
        }
        TenantLedger ledger = ledger(actor.tenantId());
        String keyHash = sha256(command.idempotencyKey());
        String requestHash = sha256(
                command.caseId() + "|" + command.decision() + "|" + command.purpose() + "|" + command.evidenceDigest());
        IdempotencyRecord replay = ledger.idempotency.get("PROPOSE:" + keyHash);
        if (replay != null) {
            if (!replay.requestHash.equals(requestHash)) {
                throw failure(
                        ApplicationErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "The idempotency key was already used for a different request");
            }
            return caseView(requireCaseWorkflow(ledger, replay.resourceId));
        }
        Workflow workflow = requireCaseWorkflow(ledger, command.caseId());
        Instant now = clock.instant();
        workflow.reconciliationCase = workflow.reconciliationCase.proposeDecision(
                command.decision(), actor.actorId(), "OPERATOR_DECISION", now);
        workflow.proposalPurpose = command.purpose();
        workflow.proposalEvidenceDigest = command.evidenceDigest();
        ledger.idempotency.put(
                "PROPOSE:" + keyHash,
                new IdempotencyRecord(
                        requestHash, workflow.reconciliationCase.id().value()));
        audit(ledger, workflow, actor, "RECONCILIATION_PROPOSED", "ACCEPTED", command.purpose(), now);
        return caseView(workflow);
    }

    @Override
    public synchronized ReconciliationCaseView approve(ActorContext actor, ApproveReconciliationCommand command) {
        requireMutationKey(command.idempotencyKey());
        if (command.purpose().length() < 8 || command.purpose().length() > 160) {
            throw failure(ApplicationErrorCode.INVALID_REQUEST, "A bounded approval purpose is required");
        }
        TenantLedger ledger = ledger(actor.tenantId());
        String keyHash = sha256(command.idempotencyKey());
        String requestHash = sha256(command.caseId() + "|" + command.purpose());
        IdempotencyRecord replay = ledger.idempotency.get("APPROVE:" + keyHash);
        if (replay != null) {
            if (!replay.requestHash.equals(requestHash)) {
                throw failure(
                        ApplicationErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "The idempotency key was already used for a different request");
            }
            return caseView(requireCaseWorkflow(ledger, replay.resourceId));
        }
        Workflow workflow = requireCaseWorkflow(ledger, command.caseId());
        Instant now = clock.instant();
        try {
            workflow.reconciliationCase = workflow.reconciliationCase.approveDecision(actor.actorId(), now);
        } catch (za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainException exception) {
            throw failure(
                    ApplicationErrorCode.RECONCILIATION_APPROVER_CONFLICT,
                    "Reconciliation approval requires a distinct authorised checker");
        }
        applyApprovedDecision(workflow, now);
        ledger.originalsByToken.put(workflow.originalToken, workflow.original);
        ledger.idempotency.put(
                "APPROVE:" + keyHash,
                new IdempotencyRecord(
                        requestHash, workflow.reconciliationCase.id().value()));
        audit(ledger, workflow, actor, "RECONCILIATION_APPROVED", "SUCCEEDED", command.purpose(), now);
        return caseView(workflow);
    }

    private void progressFirstAttempt(
            TenantLedger ledger, Workflow workflow, ActorContext actor, DemonstrationFailure scenario, Instant now) {
        workflow.command = workflow.command.route(now).markExecutionPending(now).startExecution(now);
        ExecutionAttempt attempt = ExecutionAttempt.create(
                        new ExecutionAttemptId(UUID.randomUUID()),
                        actor.tenantId(),
                        workflow.command.id(),
                        1,
                        now,
                        now.plus(workflow.command.retryPolicySnapshot().resultTimeout()))
                .dispatch(now);
        switch (scenario) {
            case NONE -> {
                attempt = attempt.confirmExecuted(now);
                workflow.command = workflow.command.complete(attempt, now);
                workflow.original = workflow.original.confirmReversed(now);
                audit(ledger, workflow, actor, "SWITCH_OUTCOME", "SUCCEEDED", "SYNTHETIC_EXECUTED", now);
            }
            case INTERRUPT_ONCE -> {
                attempt = attempt.confirmNotExecuted("SWITCH_NOT_RECEIVED", now);
                workflow.command = workflow.command.scheduleSafeRetry(NonExecutionEvidence.from(attempt), now);
                audit(ledger, workflow, actor, "SWITCH_OUTCOME", "FAILED", "CONFIRMED_NOT_EXECUTED", now);
            }
            case AMBIGUOUS -> {
                attempt = attempt.markAmbiguous("INTERRUPTED_AFTER_DISPATCH", now);
                workflow.command = workflow.command.requireReconciliation(attempt, now);
                workflow.reconciliationCase = ReconciliationCase.open(
                        new ReconciliationCaseId(UUID.randomUUID()),
                        workflow.command,
                        "INTERRUPTED_AFTER_DISPATCH",
                        now);
                audit(ledger, workflow, actor, "SWITCH_OUTCOME", "FAILED", "AMBIGUOUS_OUTCOME", now);
            }
            case REJECT -> {
                attempt = attempt.confirmNotExecuted("SWITCH_REJECTED", now);
                workflow.command = workflow.command.rejectAfterNonExecution(
                        NonExecutionEvidence.from(attempt), "SWITCH_REJECTED", now);
                workflow.original = workflow.original.releaseReservation(now);
                audit(ledger, workflow, actor, "SWITCH_OUTCOME", "REJECTED", "SYNTHETIC_REJECT", now);
            }
        }
        workflow.attempts.add(attempt);
        ledger.originalsByToken.put(workflow.originalToken, workflow.original);
    }

    private static void applyApprovedDecision(Workflow workflow, Instant at) {
        ReconciliationDecision decision =
                workflow.reconciliationCase.proposedDecision().orElseThrow();
        switch (decision) {
            case CONFIRM_EXECUTED -> {
                workflow.command = workflow.command.completeFromReconciliation(at);
                workflow.original = workflow.original.confirmReversed(at);
            }
            case CONFIRM_NOT_EXECUTED ->
                workflow.command = workflow.command.scheduleRetryFromReconciliation(
                        workflow.attempts.size(), "RECONCILIATION_CONFIRMED_NOT_EXECUTED", at);
            case REJECT_REVERSAL -> {
                workflow.command = workflow.command.rejectFromReconciliation(decision.name(), at);
                workflow.original = workflow.original.releaseReservation(at);
            }
            case QUARANTINE -> workflow.command = workflow.command.quarantine("CONFLICTING_EVIDENCE", at);
        }
    }

    private void audit(
            TenantLedger ledger,
            Workflow workflow,
            ActorContext actor,
            String action,
            String outcome,
            String purpose,
            Instant at) {
        UUID eventId = UUID.randomUUID();
        String actorPseudonym = sha256(actor.actorId().value()).substring(0, 16);
        String canonical = String.join(
                "|",
                eventId.toString(),
                workflow.command.id().value().toString(),
                action,
                outcome,
                actorPseudonym,
                purpose,
                workflow.command.correlationId().value().toString(),
                at.toString());
        AuditEvidenceView evidence = new AuditEvidenceView(
                eventId,
                action,
                outcome,
                actorPseudonym,
                purpose,
                workflow.command.correlationId().value(),
                at,
                hmac(canonical));
        ledger.evidence
                .computeIfAbsent(workflow.command.id().value(), ignored -> new ArrayList<>())
                .add(evidence);
    }

    private ReversalView view(TenantLedger ledger, Workflow workflow) {
        List<String> actions = new ArrayList<>(List.of("VIEW", "VIEW_EVIDENCE"));
        if (workflow.command.state() == ReversalCommandState.RETRY_SCHEDULED) {
            actions.add("REQUEST_SAFE_RETRY");
        }
        if (workflow.command.state() == ReversalCommandState.RECONCILIATION_REQUIRED) {
            actions.add("CHECK_OUTCOME");
        }
        return new ReversalView(
                workflow.command.id().value(),
                workflow.originalToken,
                workflow.command.operationType(),
                workflow.command.money().amount(),
                workflow.command.money().currency().getCurrencyCode(),
                workflow.command.state(),
                workflow.command.failureCode().orElse(null),
                workflow.command.correlationId().value(),
                workflow.attempts.size(),
                workflow.command.acceptedAt(),
                workflow.command.updatedAt(),
                workflow.command.version(),
                actions);
    }

    private static OriginalTransactionView originalView(OriginalTransaction original, String token) {
        if (original == null) {
            throw failure(
                    ApplicationErrorCode.RESOURCE_NOT_FOUND,
                    "The requested synthetic original transaction was not found");
        }
        return new OriginalTransactionView(
                original.id().value(),
                token,
                original.operationType(),
                original.money().amount(),
                original.money().currency().getCurrencyCode(),
                original.state(),
                original.occurredAt());
    }

    private static OperationPolicyView policyView(OperationPolicy policy) {
        return new OperationPolicyView(
                policy.id().value(),
                policy.operationType(),
                policy.policyVersion(),
                policy.state(),
                policy.retryPolicy().maximumExecutionAttempts(),
                policy.retryPolicy().resultTimeout().toMillis());
    }

    private static ReconciliationCaseView caseView(Workflow workflow) {
        ReconciliationCase reconciliationCase = workflow.reconciliationCase;
        Instant resolvedAt = reconciliationCase.state() == ReconciliationCaseState.RESOLVED
                        || reconciliationCase.state() == ReconciliationCaseState.QUARANTINED
                ? reconciliationCase.updatedAt()
                : null;
        return new ReconciliationCaseView(
                reconciliationCase.id().value(),
                reconciliationCase.reversalCommandId().value(),
                reconciliationCase.state(),
                reconciliationCase.ambiguityReasonCode(),
                reconciliationCase
                        .proposedDecision()
                        .map(decision ->
                                decision == ReconciliationDecision.REJECT_REVERSAL ? "REJECT" : decision.name())
                        .orElse(null),
                reconciliationCase
                        .proposedBy()
                        .map(actor -> sha256(actor.value()).substring(0, 24))
                        .orElse(null),
                workflow.proposalPurpose,
                workflow.proposalEvidenceDigest,
                reconciliationCase.openedAt(),
                resolvedAt);
    }

    private TenantLedger ledger(TenantId tenantId) {
        return ledgers.computeIfAbsent(tenantId, ignored -> new TenantLedger());
    }

    private static Workflow requireWorkflow(TenantLedger ledger, UUID commandId) {
        Workflow workflow = ledger.workflows.get(commandId);
        if (workflow == null) {
            throw failure(
                    ApplicationErrorCode.RESOURCE_NOT_FOUND,
                    "The requested tenant-scoped reversal command was not found");
        }
        return workflow;
    }

    private static Workflow requireCaseWorkflow(TenantLedger ledger, UUID caseId) {
        return ledger.workflows.values().stream()
                .filter(workflow -> workflow.reconciliationCase != null
                        && workflow.reconciliationCase.id().value().equals(caseId))
                .findFirst()
                .orElseThrow(() -> failure(
                        ApplicationErrorCode.RESOURCE_NOT_FOUND,
                        "The requested tenant-scoped reconciliation case was not found"));
    }

    private static void requireMutationKey(String value) {
        if (!value.matches("[A-Za-z0-9._~-]{16,128}")) {
            throw failure(
                    ApplicationErrorCode.INVALID_REQUEST, "Idempotency-Key must contain 16 to 128 safe characters");
        }
    }

    private void seedDemoTenant() {
        TenantId tenantId = new TenantId(DEMO_TENANT_VALUE);
        TenantLedger ledger = ledger(tenantId);
        Instant seedTime = clock.instant().minus(1, ChronoUnit.DAYS);
        ActorId maker = new ActorId("demo-policy-maker");
        ActorId checker = new ActorId("demo-policy-checker");
        for (OperationType operationType : OperationType.values()) {
            OperationPolicy policy = OperationPolicy.draft(
                            new OperationPolicyId(UUID.nameUUIDFromBytes(
                                    ("policy-" + operationType).getBytes(StandardCharsets.UTF_8))),
                            tenantId,
                            operationType,
                            1,
                            DEMO_RETRY_POLICY,
                            seedTime,
                            null,
                            maker,
                            seedTime)
                    .submit(maker, seedTime)
                    .approve(checker, seedTime);
            ledger.policies.put(operationType, policy);
        }
        seedOriginal(ledger, tenantId, "synth-txn-card-0001", OperationType.CARD_PURCHASE, "120.00", seedTime);
        seedOriginal(ledger, tenantId, "synth-txn-transfer-001", OperationType.ACCOUNT_TRANSFER, "1250.00", seedTime);
        seedOriginal(ledger, tenantId, "synth-txn-cash-0001", OperationType.CASH_WITHDRAWAL, "500.00", seedTime);
        seedOriginal(ledger, tenantId, "synth-txn-merchant-01", OperationType.MERCHANT_PAYOUT, "840.00", seedTime);
    }

    private static void seedOriginal(
            TenantLedger ledger, TenantId tenantId, String token, OperationType type, String amount, Instant at) {
        OriginalTransaction original = OriginalTransaction.post(
                new OriginalTransactionId(
                        UUID.nameUUIDFromBytes(("original-" + token).getBytes(StandardCharsets.UTF_8))),
                tenantId,
                token,
                type,
                new Money(new BigDecimal(amount), Currency.getInstance("ZAR")),
                at);
        ledger.originalsByToken.put(token, original);
        ledger.originalsById.put(original.id().value(), original);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(evidenceKey, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is required by the Java platform", exception);
        }
    }

    private static ApplicationException failure(ApplicationErrorCode code, String message) {
        return new ApplicationException(code, message);
    }

    /**
     * Holds all process-local records for one strongly isolated demo tenant.
     *
     * @author Daniel Jr Nkulu
     */
    private static final class TenantLedger {
        private final Map<String, OriginalTransaction> originalsByToken = new LinkedHashMap<>();
        private final Map<UUID, OriginalTransaction> originalsById = new HashMap<>();
        private final Map<OperationType, OperationPolicy> policies = new EnumMap<>(OperationType.class);
        private final Map<UUID, Workflow> workflows = new LinkedHashMap<>();
        private final Map<String, IdempotencyRecord> idempotency = new HashMap<>();
        private final Map<UUID, List<AuditEvidenceView>> evidence = new HashMap<>();
    }

    /**
     * Keeps the aggregate graph updated atomically inside one tenant ledger.
     *
     * @author Daniel Jr Nkulu
     */
    private static final class Workflow {
        private ReversalCommand command;
        private OriginalTransaction original;
        private final String originalToken;
        private final List<ExecutionAttempt> attempts;
        private ReconciliationCase reconciliationCase;
        private String proposalPurpose;
        private String proposalEvidenceDigest;

        private Workflow(
                ReversalCommand command,
                OriginalTransaction original,
                String originalToken,
                List<ExecutionAttempt> attempts,
                ReconciliationCase reconciliationCase) {
            this.command = command;
            this.original = original;
            this.originalToken = originalToken;
            this.attempts = attempts;
            this.reconciliationCase = reconciliationCase;
        }
    }

    /**
     * Stores a request fingerprint and the safely replayable resource identity.
     *
     * @param requestHash canonical hash of the accepted request
     * @param resourceId stable identity returned for an identical replay
     * @author Daniel Jr Nkulu
     */
    private record IdempotencyRecord(String requestHash, UUID resourceId) {}
}
