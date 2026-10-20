package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging.QuarantineMetrics;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging.RabbitTraceContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AcceptReversalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AcceptanceResult;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ActorContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApproveReconciliationCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AuditEntry;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AuditEvidenceView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.DemonstrationFailure;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.IngestOriginalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OperationPolicyView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OriginalTransactionView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OutboxMessage;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OutboxTraceContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OutcomeObservation;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ProposeReconciliationCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ReconciliationCaseView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.RetryReversalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ReversalView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.AuditStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.DeadlineReconciliationStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.IndependentAuditPort;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OrchestrationStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OutboxPublicationAudit;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OutboxRecoveryAudit;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OutcomeStore;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ReconciliationProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.RuntimeMode;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.policy.OperationPolicy;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.policy.OperationPolicyState;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.policy.RetryPolicy;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation.ReconciliationCase;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation.ReconciliationCaseState;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation.ReconciliationDecision;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ExecutionAttempt;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ExecutionAttemptState;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.NonExecutionEvidence;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalCommandState;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalReason;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ActorId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.CorrelationId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ExecutionAttemptId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.Money;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationPolicyId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OriginalTransactionId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ReconciliationCaseId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ReversalCommandId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.transaction.OriginalTransaction;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.transaction.OriginalTransactionState;

/**
 * Persists the reversal process in PostgreSQL with tenant predicates, advisory idempotency
 * serialization, optimistic aggregate versions, append-only audit, and transactional outbox intent.
 *
 * @author Daniel Jr Nkulu
 */
@Repository
@Profile("!test")
public class PostgresOrchestrationStore
        implements OrchestrationStore,
                DeadlineReconciliationStore,
                OutcomeStore,
                OutboxPublicationAudit,
                OutboxRecoveryAudit {
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofDays(30);
    private static final String ACCEPT_ACTION = "ACCEPT_REVERSAL";
    private static final String INGEST_ACTION = "INGEST_ORIGINAL";
    private static final String RETRY_ACTION = "RETRY_REVERSAL";
    private static final String PROPOSE_ACTION = "PROPOSE_RECONCILIATION";
    private static final String APPROVE_ACTION = "APPROVE_RECONCILIATION";

    private final JdbcOperations jdbc;
    private final Clock clock;
    private final ReconciliationProperties reconciliationProperties;
    private final AuditStore auditStore;
    private final WorkflowMetrics metrics;
    private final QuarantineMetrics quarantineMetrics;
    private final RabbitTraceContext traceContext;
    private final IndependentAuditPort independentAudit;
    private final RuntimeMode runtimeMode;

    /**
     * Creates the durable persistence adapter.
     *
     * @param jdbc transactional JDBC access
     * @param clock UTC time boundary
     * @param reconciliationProperties bounded evidence window
     * @param auditStore raw audit writer joining the application transaction
     * @param metrics fixed low-cardinality lifecycle metrics
     * @param quarantineMetrics bounded invalid-message metrics
     * @param traceContext durable W3C outbox context capture
     * @param independentAudit application-owned independently durable control evidence
     * @param runtimeMode immutable profile-derived capability boundary
     */
    public PostgresOrchestrationStore(
            JdbcOperations jdbc,
            Clock clock,
            ReconciliationProperties reconciliationProperties,
            AuditStore auditStore,
            WorkflowMetrics metrics,
            QuarantineMetrics quarantineMetrics,
            RabbitTraceContext traceContext,
            IndependentAuditPort independentAudit,
            RuntimeMode runtimeMode) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.reconciliationProperties = reconciliationProperties;
        this.auditStore = auditStore;
        this.metrics = metrics;
        this.quarantineMetrics = quarantineMetrics;
        this.traceContext = traceContext;
        this.independentAudit = independentAudit;
        this.runtimeMode = runtimeMode;
    }

    @Override
    @Observed(name = "OUTBOX_PERSIST", contextualName = "OUTBOX_PERSIST")
    @Transactional(propagation = Propagation.MANDATORY)
    public AcceptanceResult accept(ActorContext actor, AcceptReversalCommand request) {
        requireMutationKey(request.idempotencyKey());
        if (request.reason().value().length() > 120) {
            throw failure(ApplicationErrorCode.INVALID_REQUEST, "reason must contain at most 120 characters");
        }
        if (request.demonstrationFailure() != DemonstrationFailure.NONE
                && !runtimeMode.demonstrationFailuresAllowed()) {
            throw failure(
                    ApplicationErrorCode.INVALID_REQUEST,
                    "Demonstration failure selection is available only in the demo profile");
        }
        String keyHash = sha256(request.idempotencyKey());
        String tokenDigest = tokenDigest(request.originalTransactionToken());
        String requestHash = sha256(String.join(
                "|",
                tokenDigest,
                request.operationType().name(),
                canonicalAmount(request.money().amount()),
                request.money().currency().getCurrencyCode(),
                request.reason().value(),
                request.demonstrationFailure().name()));
        lockIdempotency(actor.tenantId(), ACCEPT_ACTION, keyHash);
        IdempotencyHit replay = idempotency(actor.tenantId(), ACCEPT_ACTION, keyHash);
        if (replay != null) {
            requireMatchingRequest(
                    actor,
                    replay,
                    requestHash,
                    ACCEPT_ACTION,
                    "REVERSAL_COMMAND",
                    request.correlationId().value());
            auditReplay(
                    actor,
                    "REVERSAL_ACCEPT_REPLAYED",
                    "REVERSAL_COMMAND",
                    replay.resourceId(),
                    request.correlationId().value());
            return new AcceptanceResult(getReversal(actor.tenantId(), replay.resourceId()), true);
        }

        Instant now = clock.instant();
        OriginalTransaction original = originalByTokenForUpdate(actor.tenantId(), tokenDigest);
        if (original == null) {
            throw failure(
                    ApplicationErrorCode.REVERSAL_NOT_ELIGIBLE,
                    "The synthetic original transaction is not eligible for reversal");
        }
        if (original.operationType() != request.operationType()
                || !original.money().sameValueAs(request.money())) {
            throw failure(
                    ApplicationErrorCode.REVERSAL_NOT_ELIGIBLE,
                    "The requested operation and money must match the original transaction");
        }
        if (original.state() != OriginalTransactionState.POSTED) {
            throw failure(
                    ApplicationErrorCode.REVERSAL_ALREADY_EXISTS,
                    "A reversal already reserves or completed for this original transaction");
        }
        OperationPolicy policy = activePolicy(actor.tenantId(), request.operationType(), now);
        if (policy == null) {
            throw failure(ApplicationErrorCode.POLICY_NOT_ACTIVE, "No active operation policy governs this request");
        }

        ReversalCommand command = ReversalCommand.accept(
                        new ReversalCommandId(UUID.randomUUID()),
                        original,
                        policy,
                        request.money(),
                        request.reason(),
                        keyHash,
                        requestHash,
                        request.correlationId(),
                        now)
                .route(now)
                .markExecutionPending(now);
        OriginalTransaction reserved = original.reserveForReversal(now);
        ExecutionAttempt attempt = ExecutionAttempt.create(
                new ExecutionAttemptId(UUID.randomUUID()),
                actor.tenantId(),
                command.id(),
                1,
                now,
                now.plus(command.retryPolicySnapshot().resultTimeout()));

        try {
            insertReversal(command, request.demonstrationFailure());
            updateOriginal(original, reserved);
            insertOriginalStatusOutbox(
                    reserved, command.correlationId().value(), command.id().value(), now);
            insertReservation(
                    actor.tenantId(), original.id().value(), command.id().value(), now);
            insertAttempt(attempt);
            insertAcceptedOutbox(command, now);
            insertExecutionOutbox(command, attempt, request.demonstrationFailure(), now);
            insertIdempotency(
                    actor.tenantId(),
                    ACCEPT_ACTION,
                    keyHash,
                    requestHash,
                    command.id().value(),
                    202,
                    now);
            writeAudit(
                    actor,
                    "REVERSAL_ACCEPTED",
                    "REVERSAL_COMMAND",
                    command.id().value(),
                    "REVERSAL_REQUEST",
                    "ACCEPTED",
                    null,
                    command.correlationId().value(),
                    policy.policyVersion(),
                    Map.of("state", command.state().name()),
                    now);
            writeAudit(
                    actor,
                    "REVERSAL_ROUTED",
                    "REVERSAL_COMMAND",
                    command.id().value(),
                    "ACTIVE_POLICY",
                    "ACCEPTED",
                    null,
                    command.correlationId().value(),
                    policy.policyVersion(),
                    Map.of("state", command.state().name()),
                    now);
        } catch (DataIntegrityViolationException exception) {
            throw failure(
                    ApplicationErrorCode.REVERSAL_ALREADY_EXISTS,
                    "A reversal already reserves or completed for this original transaction");
        }
        metrics.reversalAccepted(request.operationType().name());
        return new AcceptanceResult(getReversal(actor.tenantId(), command.id().value()), false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<ReversalView> listReversals(TenantId tenantId, ReversalCommandState status, int limit) {
        if (limit < 1 || limit > 100) {
            throw failure(ApplicationErrorCode.INVALID_REQUEST, "limit must be between 1 and 100");
        }
        String statusPredicate = status == null ? "" : " AND rc.state = ?";
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenantId.value());
        if (status != null) {
            arguments.add(status.name());
        }
        arguments.add(limit);
        return jdbc.query(
                reversalViewSql() + statusPredicate + " ORDER BY rc.accepted_at DESC, rc.id DESC LIMIT ?",
                REVERSAL_VIEW_MAPPER,
                arguments.toArray());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public ReversalView getReversal(TenantId tenantId, UUID commandId) {
        ReversalView result =
                queryOne(reversalViewSql() + " AND rc.id = ?", REVERSAL_VIEW_MAPPER, tenantId.value(), commandId);
        if (result == null) {
            throw failure(
                    ApplicationErrorCode.RESOURCE_NOT_FOUND,
                    "The requested tenant-scoped reversal command was not found");
        }
        return result;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReversalView retry(ActorContext actor, RetryReversalCommand request) {
        requireMutationKey(request.idempotencyKey());
        if (!"CONFIRMED_NOT_EXECUTED".equals(request.proof())
                || request.purpose().length() < 8
                || request.purpose().length() > 160) {
            throw failure(
                    ApplicationErrorCode.INVALID_REQUEST,
                    "Retry requires confirmed non-execution proof and a bounded purpose");
        }
        String keyHash = sha256(request.idempotencyKey());
        String requestHash =
                sha256(String.join("|", request.reversalCommandId().toString(), request.proof(), request.purpose()));
        lockIdempotency(actor.tenantId(), RETRY_ACTION, keyHash);
        IdempotencyHit replay = idempotency(actor.tenantId(), RETRY_ACTION, keyHash);
        if (replay != null) {
            requireMatchingRequest(
                    actor,
                    replay,
                    requestHash,
                    RETRY_ACTION,
                    "REVERSAL_COMMAND",
                    request.correlationId().value());
            auditReplay(
                    actor,
                    "SAFE_RETRY_REPLAYED",
                    "REVERSAL_COMMAND",
                    replay.resourceId(),
                    request.correlationId().value());
            return getReversal(actor.tenantId(), replay.resourceId());
        }

        ReversalCommand current = reversalForUpdate(actor.tenantId(), request.reversalCommandId());
        if (current == null) {
            throw failure(
                    ApplicationErrorCode.RESOURCE_NOT_FOUND,
                    "The requested tenant-scoped reversal command was not found");
        }
        if (current.state() != ReversalCommandState.RETRY_SCHEDULED) {
            throw failure(
                    ApplicationErrorCode.STATE_TRANSITION_NOT_ALLOWED,
                    "A retry is allowed only after current confirmed non-execution evidence");
        }
        Instant now = clock.instant();
        Instant nextAttemptAt = jdbc.queryForObject(
                """
                SELECT next_attempt_at
                FROM reversal_command
                WHERE tenant_id = ? AND id = ?
                """,
                (resultSet, rowNumber) -> nullableInstant(resultSet, "next_attempt_at"),
                actor.tenantId().value(),
                current.id().value());
        if (nextAttemptAt != null && now.isBefore(nextAttemptAt)) {
            throw failure(ApplicationErrorCode.STATE_TRANSITION_NOT_ALLOWED, "The safe retry backoff has not elapsed");
        }
        int nextAttemptNumber = attemptCount(actor.tenantId(), current.id().value()) + 1;
        if (nextAttemptNumber > current.retryPolicySnapshot().maximumExecutionAttempts()) {
            throw failure(
                    ApplicationErrorCode.STATE_TRANSITION_NOT_ALLOWED,
                    "The snapshotted policy attempt limit was reached");
        }
        ReversalCommand pending = current.releaseScheduledRetry(now);
        ExecutionAttempt attempt = ExecutionAttempt.create(
                new ExecutionAttemptId(UUID.randomUUID()),
                actor.tenantId(),
                current.id(),
                nextAttemptNumber,
                now,
                now.plus(current.retryPolicySnapshot().resultTimeout()));
        updateReversal(current, pending, null);
        insertAttempt(attempt);
        DemonstrationFailure demonstrationFailure =
                demonstrationFailure(actor.tenantId(), current.id().value());
        insertExecutionOutbox(pending, attempt, demonstrationFailure, now);
        insertIdempotency(
                actor.tenantId(),
                RETRY_ACTION,
                keyHash,
                requestHash,
                current.id().value(),
                202,
                now);
        writeAudit(
                actor,
                "SAFE_RETRY_REQUESTED",
                "REVERSAL_COMMAND",
                current.id().value(),
                "CONFIRMED_NOT_EXECUTED",
                "ACCEPTED",
                null,
                request.correlationId().value(),
                current.operationPolicyVersion(),
                Map.of("attemptNumber", nextAttemptNumber),
                now);
        return getReversal(actor.tenantId(), current.id().value());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<AuditEvidenceView> evidence(TenantId tenantId, UUID commandId) {
        requireCommandExists(tenantId, commandId);
        return jdbc.query(
                """
                SELECT id, action, outcome, actor_subject_token, purpose_code,
                       correlation_id, occurred_at, evidence_digest
                FROM audit_event
                WHERE tenant_id = ? AND resource_type = 'REVERSAL_COMMAND' AND resource_id = ?
                ORDER BY occurred_at, id
                """,
                (resultSet, rowNumber) -> new AuditEvidenceView(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("action"),
                        resultSet.getString("outcome"),
                        resultSet.getString("actor_subject_token"),
                        resultSet.getString("purpose_code"),
                        resultSet.getObject("correlation_id", UUID.class),
                        instant(resultSet, "occurred_at"),
                        resultSet.getString("evidence_digest")),
                tenantId.value(),
                commandId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public OriginalTransactionView ingestOriginal(ActorContext actor, IngestOriginalCommand request) {
        requireMutationKey(request.idempotencyKey());
        if (!request.transactionToken().matches("^synth-[a-z0-9-]{8,64}$")) {
            throw failure(
                    ApplicationErrorCode.INVALID_REQUEST, "transactionToken must be a bounded synthetic reference");
        }
        String keyHash = sha256(request.idempotencyKey());
        String requestHash = sha256(String.join(
                "|",
                request.transactionToken(),
                request.operationType().name(),
                canonicalAmount(request.money().amount()),
                request.money().currency().getCurrencyCode()));
        lockIdempotency(actor.tenantId(), INGEST_ACTION, keyHash);
        IdempotencyHit replay = idempotency(actor.tenantId(), INGEST_ACTION, keyHash);
        if (replay != null) {
            requireMatchingRequest(
                    actor,
                    replay,
                    requestHash,
                    INGEST_ACTION,
                    "ORIGINAL_TRANSACTION",
                    request.correlationId().value());
            auditReplay(
                    actor,
                    "ORIGINAL_INGEST_REPLAYED",
                    "ORIGINAL_TRANSACTION",
                    replay.resourceId(),
                    request.correlationId().value());
            return originalViewById(actor.tenantId(), replay.resourceId());
        }
        String tokenDigest = sha256(request.transactionToken());
        if (originalByTokenForUpdate(actor.tenantId(), tokenDigest) != null) {
            throw failure(
                    ApplicationErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "The synthetic transaction token already identifies another request");
        }
        Instant now = clock.instant();
        OriginalTransaction original = OriginalTransaction.post(
                new OriginalTransactionId(UUID.randomUUID()),
                actor.tenantId(),
                tokenDigest,
                request.operationType(),
                request.money(),
                now);
        update(
                """
                INSERT INTO original_transaction (
                    id, tenant_id, external_reference_token, public_reference_token,
                    operation_type, amount, currency, status, occurred_at, created_at, updated_at,
                    version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                original.id().value(),
                actor.tenantId().value(),
                tokenDigest,
                request.transactionToken(),
                original.operationType().name(),
                original.money().amount(),
                original.money().currency().getCurrencyCode(),
                original.state().name(),
                now,
                now,
                now,
                original.version());
        insertOriginalStatusOutbox(original, request.correlationId().value(), null, now);
        insertIdempotency(
                actor.tenantId(),
                INGEST_ACTION,
                keyHash,
                requestHash,
                original.id().value(),
                201,
                now);
        writeAudit(
                actor,
                "ORIGINAL_TRANSACTION_INGESTED",
                "ORIGINAL_TRANSACTION",
                original.id().value(),
                "SYNTHETIC_INGEST",
                "SUCCEEDED",
                null,
                request.correlationId().value(),
                null,
                Map.of("operation", request.operationType().name()),
                now);
        return originalViewById(actor.tenantId(), original.id().value());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<OriginalTransactionView> listOriginals(TenantId tenantId) {
        return jdbc.query("""
                SELECT id, COALESCE(public_reference_token, external_reference_token)
                           AS external_reference_token,
                       operation_type, amount, currency,
                       status, occurred_at
                FROM original_transaction
                WHERE tenant_id = ?
                ORDER BY created_at DESC, id
                """, ORIGINAL_VIEW_MAPPER, tenantId.value());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<OperationPolicyView> listPolicies(TenantId tenantId) {
        return jdbc.query(
                """
                SELECT id, operation_type, policy_version, status, max_attempts, result_timeout_ms
                FROM operation_policy
                WHERE tenant_id = ? AND status = 'ACTIVE'
                ORDER BY operation_type
                """,
                (resultSet, rowNumber) -> new OperationPolicyView(
                        resultSet.getObject("id", UUID.class),
                        OperationType.valueOf(resultSet.getString("operation_type")),
                        resultSet.getInt("policy_version"),
                        OperationPolicyState.valueOf(resultSet.getString("status")),
                        resultSet.getInt("max_attempts"),
                        resultSet.getLong("result_timeout_ms")),
                tenantId.value());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<ReconciliationCaseView> listReconciliationCases(TenantId tenantId) {
        return jdbc.query(
                reconciliationViewSql() + " WHERE rc.tenant_id = ? ORDER BY rc.opened_at DESC",
                RECONCILIATION_VIEW_MAPPER,
                tenantId.value());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReconciliationCaseView propose(ActorContext actor, ProposeReconciliationCommand request) {
        requireMutationKey(request.idempotencyKey());
        if (request.purpose().length() < 8
                || request.purpose().length() > 160
                || !request.evidenceDigest().matches("[a-f0-9]{64}")) {
            throw failure(
                    ApplicationErrorCode.INVALID_REQUEST, "A bounded purpose and SHA-256 evidence digest are required");
        }
        String keyHash = sha256(request.idempotencyKey());
        String requestHash = sha256(String.join(
                "|",
                request.caseId().toString(),
                request.decision().name(),
                request.purpose(),
                request.evidenceDigest()));
        lockIdempotency(actor.tenantId(), PROPOSE_ACTION, keyHash);
        IdempotencyHit replay = idempotency(actor.tenantId(), PROPOSE_ACTION, keyHash);
        if (replay != null) {
            requireMatchingRequest(
                    actor,
                    replay,
                    requestHash,
                    PROPOSE_ACTION,
                    "RECONCILIATION_CASE",
                    request.correlationId().value());
            auditReplay(
                    actor,
                    "RECONCILIATION_PROPOSAL_REPLAYED",
                    "RECONCILIATION_CASE",
                    replay.resourceId(),
                    request.correlationId().value());
            return caseViewById(actor.tenantId(), replay.resourceId());
        }
        ReconciliationCase current = reconciliationCaseForUpdate(actor.tenantId(), request.caseId());
        if (current == null) {
            throw failure(
                    ApplicationErrorCode.RESOURCE_NOT_FOUND,
                    "The requested tenant-scoped reconciliation case was not found");
        }
        if (!evidenceBelongsToCase(actor.tenantId(), current, request.evidenceDigest())) {
            throw failure(
                    ApplicationErrorCode.INVALID_REQUEST,
                    "The evidence digest is not part of this tenant-scoped reconciliation case");
        }
        Instant now = clock.instant();
        ReconciliationCase proposed =
                current.proposeDecision(request.decision(), actor.actorId(), "OPERATOR_DECISION", now);
        updateCase(current, proposed);
        update(
                """
                INSERT INTO reconciliation_decision (
                    id, tenant_id, reconciliation_case_id, proposed_outcome, proposed_by,
                    purpose_code, domain_purpose_code, evidence_digest, proposed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                actor.tenantId().value(),
                request.caseId(),
                request.decision().name(),
                actor.actorId().value(),
                request.purpose(),
                "OPERATOR_DECISION",
                request.evidenceDigest(),
                now);
        insertIdempotency(actor.tenantId(), PROPOSE_ACTION, keyHash, requestHash, request.caseId(), 202, now);
        ReversalCommand command =
                reversalForUpdate(actor.tenantId(), current.reversalCommandId().value());
        writeAudit(
                actor,
                "RECONCILIATION_PROPOSED",
                "REVERSAL_COMMAND",
                current.reversalCommandId().value(),
                request.purpose(),
                "ACCEPTED",
                null,
                request.correlationId().value(),
                command == null ? null : command.operationPolicyVersion(),
                Map.of("decision", request.decision().name()),
                now);
        metrics.reconciliationProposed();
        return caseViewById(actor.tenantId(), request.caseId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReconciliationCaseView approve(ActorContext actor, ApproveReconciliationCommand request) {
        requireMutationKey(request.idempotencyKey());
        if (request.purpose().length() < 8 || request.purpose().length() > 160) {
            throw failure(ApplicationErrorCode.INVALID_REQUEST, "A bounded approval purpose is required");
        }
        String keyHash = sha256(request.idempotencyKey());
        String requestHash = sha256(request.caseId() + "|" + request.purpose());
        lockIdempotency(actor.tenantId(), APPROVE_ACTION, keyHash);
        IdempotencyHit replay = idempotency(actor.tenantId(), APPROVE_ACTION, keyHash);
        if (replay != null) {
            requireMatchingRequest(
                    actor,
                    replay,
                    requestHash,
                    APPROVE_ACTION,
                    "RECONCILIATION_CASE",
                    request.correlationId().value());
            auditReplay(
                    actor,
                    "RECONCILIATION_APPROVAL_REPLAYED",
                    "RECONCILIATION_CASE",
                    replay.resourceId(),
                    request.correlationId().value());
            return caseViewById(actor.tenantId(), replay.resourceId());
        }
        ReconciliationCase current = reconciliationCaseForUpdate(actor.tenantId(), request.caseId());
        if (current == null) {
            throw failure(
                    ApplicationErrorCode.RESOURCE_NOT_FOUND,
                    "The requested tenant-scoped reconciliation case was not found");
        }
        Instant now = clock.instant();
        ReconciliationCase approved;
        try {
            approved = current.approveDecision(actor.actorId(), now);
        } catch (za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainException exception) {
            auditDenialInNewTransaction(
                    actor,
                    "RECONCILIATION_APPROVAL_MAKER_CHECKER_DENIED",
                    "RECONCILIATION_CASE",
                    request.caseId(),
                    request.correlationId().value(),
                    ApplicationErrorCode.RECONCILIATION_APPROVER_CONFLICT.name(),
                    Map.of("control", "MAKER_CHECKER"));
            throw failure(
                    ApplicationErrorCode.RECONCILIATION_APPROVER_CONFLICT,
                    "Reconciliation approval requires a distinct authorised checker");
        }
        updateCase(current, approved);
        update("""
                UPDATE reconciliation_decision
                SET approved_by = ?, approved_at = ?
                WHERE tenant_id = ? AND reconciliation_case_id = ? AND approved_by IS NULL
                """, actor.actorId().value(), now, actor.tenantId().value(), request.caseId());
        applyApprovedDecision(actor.tenantId(), approved, now);
        insertIdempotency(actor.tenantId(), APPROVE_ACTION, keyHash, requestHash, request.caseId(), 202, now);
        writeAudit(
                actor,
                approved.proposedDecision().orElseThrow() == ReconciliationDecision.QUARANTINE
                        ? "RECONCILIATION_QUARANTINED"
                        : "RECONCILIATION_RESOLVED",
                "REVERSAL_COMMAND",
                approved.reversalCommandId().value(),
                request.purpose(),
                "SUCCEEDED",
                null,
                request.correlationId().value(),
                null,
                Map.of("decision", approved.proposedDecision().orElseThrow().name()),
                now);
        metrics.reconciliationApproved();
        return caseViewById(actor.tenantId(), request.caseId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int reconcileOverdueAttempts(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("deadline scan limit must be between 1 and 200");
        }
        Instant now = clock.instant();
        List<Map.Entry<TenantId, UUID>> due = jdbc.query(
                """
                SELECT ea.tenant_id, ea.id
                FROM execution_attempt ea
                JOIN reversal_command rc
                  ON rc.tenant_id = ea.tenant_id AND rc.id = ea.reversal_command_id
                WHERE ea.state = 'DISPATCHED' AND ea.result_deadline <= ?
                  AND rc.state = 'EXECUTING'
                ORDER BY ea.result_deadline, ea.id
                FOR UPDATE OF ea, rc SKIP LOCKED
                LIMIT ?
                """,
                (resultSet, rowNumber) -> Map.entry(
                        new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                        resultSet.getObject("id", UUID.class)),
                Timestamp.from(now),
                limit);
        for (Map.Entry<TenantId, UUID> item : due) {
            ExecutionAttempt attempt = attemptForUpdate(item.getKey(), item.getValue());
            ReversalCommand command =
                    reversalForUpdate(item.getKey(), attempt.reversalCommandId().value());
            if (attempt.state() != ExecutionAttemptState.DISPATCHED
                    || command.state() != ReversalCommandState.EXECUTING) {
                continue;
            }
            ExecutionAttempt timedOut = attempt.markTimedOut(now);
            ReversalCommand uncertain = command.requireReconciliation(timedOut, now);
            ReconciliationCase reconciliationCase = ReconciliationCase.open(
                    new ReconciliationCaseId(UUID.randomUUID()), uncertain, "RESULT_TIMEOUT", now);
            updateAttempt(attempt, timedOut, "RESULT_TIMEOUT");
            updateReversal(command, uncertain, null);
            insertCase(reconciliationCase);
            ActorContext reconciler =
                    new ActorContext(item.getKey(), new ActorId("deadline-reconciler"), Set.of("ROLE_SERVICE"));
            writeAudit(
                    reconciler,
                    "EXECUTION_RESULT_TIMED_OUT",
                    "REVERSAL_COMMAND",
                    command.id().value(),
                    "AUTOMATED_RECONCILIATION",
                    "FAILED",
                    "RESULT_TIMEOUT",
                    command.correlationId().value(),
                    command.operationPolicyVersion(),
                    Map.of("attemptNumber", attempt.attemptNumber()),
                    now);
            metrics.reconciliationOpened("deadline");
        }
        return due.size();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPublished(OutboxMessage message) {
        ActorContext publisher = new ActorContext(
                new TenantId(message.tenantId()), new ActorId("outbox-publisher"), Set.of("ROLE_SERVICE"));
        writeAudit(
                publisher,
                "OUTBOX_PUBLISH_CONFIRMED",
                "OriginalTransactionStatusChanged.v1".equals(message.eventType())
                        ? "ORIGINAL_TRANSACTION"
                        : "REVERSAL_COMMAND",
                message.aggregateId(),
                "MESSAGE_DISPATCH",
                "SUCCEEDED",
                null,
                message.correlationId(),
                null,
                Map.of("eventType", message.eventType(), "attempt", message.attemptCount()),
                clock.instant());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordRecovered(OutboxMessage message, int recoveryCount) {
        ActorContext publisher = new ActorContext(
                new TenantId(message.tenantId()), new ActorId("outbox-recovery"), Set.of("ROLE_SERVICE"));
        writeAudit(
                publisher,
                "OUTBOX_COLD_RECOVERY_SCHEDULED",
                "OriginalTransactionStatusChanged.v1".equals(message.eventType())
                        ? "ORIGINAL_TRANSACTION"
                        : "REVERSAL_COMMAND",
                message.aggregateId(),
                "MESSAGE_RECOVERY",
                "ACCEPTED",
                "HOT_ATTEMPTS_EXHAUSTED",
                message.correlationId(),
                null,
                Map.of("eventType", message.eventType(), "recoveryCycle", recoveryCount),
                clock.instant());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordTerminalFailure(OutboxMessage message, int recoveryCount, String errorCode) {
        ActorContext publisher = new ActorContext(
                new TenantId(message.tenantId()), new ActorId("outbox-recovery"), Set.of("ROLE_SERVICE"));
        writeAudit(
                publisher,
                "OUTBOX_COLD_RECOVERY_EXHAUSTED",
                "OriginalTransactionStatusChanged.v1".equals(message.eventType())
                        ? "ORIGINAL_TRANSACTION"
                        : "REVERSAL_COMMAND",
                message.aggregateId(),
                "MESSAGE_RECOVERY",
                "FAILED",
                errorCode,
                message.correlationId(),
                null,
                Map.of("eventType", message.eventType(), "recoveryCycle", recoveryCount),
                clock.instant());
        quarantineOutbox(message);
    }

    /**
     * Atomically deduplicates and applies one integrity-checked switch result.
     *
     * @param observation validated downstream observation
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void processOutcome(OutcomeObservation observation) {
        int inserted = update(
                """
                INSERT INTO inbox_message (
                    tenant_id, consumer_name, message_id, payload_hash, correlation_id,
                    first_seen_at
                ) VALUES (?, 'resilientpay-outcome-v1', ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                observation.tenantId().value(),
                observation.messageId(),
                observation.payloadHash(),
                observation.correlationId(),
                clock.instant());
        if (inserted == 0) {
            String existingHash = queryOne(
                    """
                    SELECT payload_hash FROM inbox_message
                    WHERE tenant_id = ? AND consumer_name = 'resilientpay-outcome-v1'
                      AND message_id = ?
                    """,
                    (resultSet, rowNumber) -> resultSet.getString(1),
                    observation.tenantId().value(),
                    observation.messageId());
            if (!observation.payloadHash().equals(existingHash)) {
                quarantine(
                        observation.tenantId(),
                        observation.messageId().toString(),
                        observation.correlationId(),
                        observation.payloadHash(),
                        "MESSAGE_ID_PAYLOAD_CONFLICT");
            }
            metrics.outcomeProcessed("duplicate");
            return;
        }
        applyOutcome(observation);
        update("""
                UPDATE inbox_message
                SET processing_outcome = 'PROCESSED', processed_at = ?
                WHERE tenant_id = ? AND consumer_name = 'resilientpay-outcome-v1'
                  AND message_id = ?
                """, clock.instant(), observation.tenantId().value(), observation.messageId());
        metrics.outcomeProcessed(observation.outcome().toLowerCase(java.util.Locale.ROOT));
    }

    private void applyOutcome(OutcomeObservation observation) {
        ReversalCommand current = reversalForUpdate(observation.tenantId(), observation.reversalCommandId());
        ExecutionAttempt attempt = attemptForUpdate(observation.tenantId(), observation.executionAttemptId());
        if (current == null
                || attempt == null
                || !attempt.reversalCommandId().value().equals(observation.reversalCommandId())
                || !current.correlationId().value().equals(observation.correlationId())) {
            quarantine(
                    observation.tenantId(),
                    observation.messageId().toString(),
                    observation.correlationId(),
                    observation.payloadHash(),
                    "OUTCOME_SCOPE_MISMATCH");
            return;
        }
        if (attempt.state() != ExecutionAttemptState.CREATED && attempt.state() != ExecutionAttemptState.DISPATCHED) {
            String recordedOutcome = jdbc.queryForObject(
                    """
                    SELECT result_code
                    FROM execution_attempt
                    WHERE tenant_id = ? AND id = ?
                    """, String.class, observation.tenantId().value(), observation.executionAttemptId());
            if (!observation.outcome().equals(recordedOutcome)) {
                quarantine(
                        observation.tenantId(),
                        observation.messageId().toString(),
                        observation.correlationId(),
                        observation.payloadHash(),
                        "CONTRADICTORY_OUTCOME");
                Instant conflictAt = latest(observation.observedAt(), current.updatedAt(), clock.instant());
                ReversalCommand conflicted = current;
                if (current.state() == ReversalCommandState.RETRY_SCHEDULED
                        || current.state() == ReversalCommandState.EXECUTION_PENDING
                        || current.state() == ReversalCommandState.EXECUTING) {
                    conflicted = current.requireReconciliationForConflictingOutcome(attempt, conflictAt);
                    Integer existingCases = jdbc.queryForObject(
                            """
                            SELECT COUNT(*) FROM reconciliation_case
                            WHERE tenant_id = ? AND reversal_command_id = ?
                            """,
                            Integer.class,
                            observation.tenantId().value(),
                            current.id().value());
                    if (Objects.requireNonNull(existingCases) > 0) {
                        conflicted = conflicted.quarantine("CONTRADICTORY_OUTCOME", conflictAt);
                    } else {
                        insertCase(ReconciliationCase.open(
                                new ReconciliationCaseId(UUID.randomUUID()),
                                conflicted,
                                "CONTRADICTORY_OUTCOME",
                                conflictAt));
                        metrics.reconciliationOpened("contradictory-outcome");
                    }
                    updateReversal(current, conflicted, null);
                    update("""
                            UPDATE outbox_event
                            SET status = 'DEAD', retryable = FALSE, lease_owner = NULL, lease_until = NULL,
                                last_error_code = 'CONTRADICTORY_OUTCOME'
                            WHERE tenant_id = ? AND aggregate_id = ?
                              AND event_type = 'ReversalExecutionRequested.v1' AND status <> 'PUBLISHED'
                            """, observation.tenantId().value(), current.id().value());
                }
                outcomeAudit(observation, conflicted, "CONTRADICTORY_SWITCH_OUTCOME", "FAILED", conflictAt);
                metrics.outcomeProcessed("contradictory");
                return;
            }
            metrics.outcomeProcessed("terminal-replay");
            return;
        }
        Instant transitionAt = latest(observation.observedAt(), current.updatedAt(), attempt.createdAt());
        ExecutionAttempt dispatched =
                attempt.state() == ExecutionAttemptState.CREATED ? attempt.dispatch(attempt.createdAt()) : attempt;
        ReversalCommand executing = current.state() == ReversalCommandState.EXECUTION_PENDING
                ? current.startExecution(current.updatedAt())
                : current;
        if (executing.state() != ReversalCommandState.EXECUTING) {
            quarantine(
                    observation.tenantId(),
                    observation.messageId().toString(),
                    observation.correlationId(),
                    observation.payloadHash(),
                    "OUTCOME_STATE_CONFLICT");
            return;
        }
        OriginalTransaction original = originalByIdForUpdate(
                observation.tenantId(), executing.originalTransactionId().value());
        String reason = safeReason(observation.reasonCode(), observation.outcome());
        switch (observation.outcome()) {
            case "EXECUTED" -> {
                ExecutionAttempt completedAttempt = dispatched.confirmExecuted(transitionAt);
                ReversalCommand completed = executing.complete(completedAttempt, transitionAt);
                OriginalTransaction reversed = original.confirmReversed(transitionAt);
                updateAttempt(attempt, completedAttempt, "EXECUTED");
                updateReversal(current, completed, null);
                updateOriginal(original, reversed);
                insertOriginalStatusOutbox(
                        reversed,
                        completed.correlationId().value(),
                        completed.id().value(),
                        transitionAt);
                outcomeAudit(observation, completed, "SWITCH_EXECUTED", "SUCCEEDED", transitionAt);
            }
            case "CONFIRMED_NOT_EXECUTED" -> {
                ExecutionAttempt safeAttempt = dispatched.confirmNotExecuted(reason, transitionAt);
                updateAttempt(attempt, safeAttempt, "CONFIRMED_NOT_EXECUTED");
                if (executing.retryPolicySnapshot().permitsAttemptAfter(safeAttempt.attemptNumber())) {
                    ReversalCommand retryScheduled =
                            executing.scheduleSafeRetry(NonExecutionEvidence.from(safeAttempt), transitionAt);
                    Instant nextAttemptAt =
                            transitionAt.plus(executing.retryPolicySnapshot().initialBackoff());
                    updateReversal(current, retryScheduled, nextAttemptAt);
                    outcomeAudit(observation, retryScheduled, "SWITCH_CONFIRMED_NOT_EXECUTED", "FAILED", transitionAt);
                } else {
                    ReversalCommand exhausted = executing.rejectAfterNonExecution(
                            NonExecutionEvidence.from(safeAttempt), "ATTEMPT_LIMIT_EXCEEDED", transitionAt);
                    OriginalTransaction released = original.releaseReservation(transitionAt);
                    updateReversal(current, exhausted, null);
                    updateOriginal(original, released);
                    releaseReservation(observation.tenantId(), original.id().value(), transitionAt);
                    insertOriginalStatusOutbox(
                            released,
                            exhausted.correlationId().value(),
                            exhausted.id().value(),
                            transitionAt);
                    outcomeAudit(observation, exhausted, "SWITCH_RETRY_EXHAUSTED", "REJECTED", transitionAt);
                }
            }
            case "REJECTED" -> {
                ExecutionAttempt rejectedAttempt = dispatched.confirmNotExecuted(reason, transitionAt);
                ReversalCommand rejected = executing.rejectAfterNonExecution(
                        NonExecutionEvidence.from(rejectedAttempt), reason, transitionAt);
                OriginalTransaction released = original.releaseReservation(transitionAt);
                updateAttempt(attempt, rejectedAttempt, "REJECTED");
                updateReversal(current, rejected, null);
                updateOriginal(original, released);
                insertOriginalStatusOutbox(
                        released,
                        rejected.correlationId().value(),
                        rejected.id().value(),
                        transitionAt);
                releaseReservation(observation.tenantId(), original.id().value(), transitionAt);
                outcomeAudit(observation, rejected, "SWITCH_REJECTED", "REJECTED", transitionAt);
            }
            case "AMBIGUOUS" -> {
                ExecutionAttempt ambiguousAttempt = dispatched.markAmbiguous(reason, transitionAt);
                ReversalCommand uncertain = executing.requireReconciliation(ambiguousAttempt, transitionAt);
                ReconciliationCase reconciliationCase = ReconciliationCase.open(
                        new ReconciliationCaseId(UUID.randomUUID()), uncertain, reason, transitionAt);
                updateAttempt(attempt, ambiguousAttempt, "AMBIGUOUS");
                updateReversal(current, uncertain, null);
                insertCase(reconciliationCase);
                outcomeAudit(observation, uncertain, "SWITCH_OUTCOME_AMBIGUOUS", "FAILED", transitionAt);
                metrics.reconciliationOpened("outcome");
            }
            default -> throw new IllegalArgumentException("Unsupported outcome");
        }
    }

    private void applyApprovedDecision(TenantId tenantId, ReconciliationCase approved, Instant at) {
        ReversalCommand current =
                reversalForUpdate(tenantId, approved.reversalCommandId().value());
        if (current == null) {
            throw failure(
                    ApplicationErrorCode.RESOURCE_NOT_FOUND, "The tenant-scoped reconciliation command was not found");
        }
        OriginalTransaction original =
                originalByIdForUpdate(tenantId, current.originalTransactionId().value());
        ReconciliationDecision decision = approved.proposedDecision().orElseThrow();
        switch (decision) {
            case CONFIRM_EXECUTED -> {
                ReversalCommand completed = current.completeFromReconciliation(at);
                OriginalTransaction reversed = original.confirmReversed(at);
                updateReversal(current, completed, null);
                updateOriginal(original, reversed);
                insertOriginalStatusOutbox(
                        reversed,
                        completed.correlationId().value(),
                        completed.id().value(),
                        at);
            }
            case CONFIRM_NOT_EXECUTED -> {
                Integer completedAttemptNumber = jdbc.queryForObject(
                        """
                        SELECT COALESCE(MAX(attempt_number), 0)
                        FROM execution_attempt
                        WHERE tenant_id = ? AND reversal_command_id = ?
                        """, Integer.class, tenantId.value(), current.id().value());
                if (current.retryPolicySnapshot().permitsAttemptAfter(Objects.requireNonNull(completedAttemptNumber))) {
                    ReversalCommand retryScheduled = current.scheduleRetryFromReconciliation(
                            completedAttemptNumber, "RECONCILIATION_CONFIRMED_NOT_EXECUTED", at);
                    updateReversal(
                            current,
                            retryScheduled,
                            at.plus(current.retryPolicySnapshot().initialBackoff()));
                } else {
                    ReversalCommand exhausted = current.rejectFromReconciliation("ATTEMPT_LIMIT_EXCEEDED", at);
                    OriginalTransaction released = original.releaseReservation(at);
                    updateReversal(current, exhausted, null);
                    updateOriginal(original, released);
                    releaseReservation(tenantId, original.id().value(), at);
                    insertOriginalStatusOutbox(
                            released,
                            exhausted.correlationId().value(),
                            exhausted.id().value(),
                            at);
                }
            }
            case REJECT_REVERSAL -> {
                ReversalCommand rejected = current.rejectFromReconciliation(decision.name(), at);
                OriginalTransaction released = original.releaseReservation(at);
                updateReversal(current, rejected, null);
                updateOriginal(original, released);
                insertOriginalStatusOutbox(
                        released,
                        rejected.correlationId().value(),
                        rejected.id().value(),
                        at);
                releaseReservation(tenantId, original.id().value(), at);
            }
            case QUARANTINE -> {
                ReversalCommand quarantined = current.quarantine("CONFLICTING_EVIDENCE", at);
                updateReversal(current, quarantined, null);
            }
        }
    }

    private void outcomeAudit(
            OutcomeObservation observation, ReversalCommand command, String action, String outcome, Instant at) {
        ActorContext serviceActor =
                new ActorContext(observation.tenantId(), new ActorId("synthetic-switch"), Set.of("ROLE_SERVICE"));
        writeAudit(
                serviceActor,
                action,
                "REVERSAL_COMMAND",
                command.id().value(),
                "SWITCH_OUTCOME",
                outcome,
                command.failureCode().orElse(null),
                observation.correlationId(),
                command.operationPolicyVersion(),
                Map.of("state", command.state().name()),
                at);
    }

    private void quarantine(
            TenantId tenantId, String messageId, UUID correlationId, String payloadDigest, String errorCode) {
        Instant now = clock.instant();
        Integer updated = jdbc.query("""
                SELECT id FROM message_quarantine
                WHERE tenant_id = ? AND consumer_name = 'resilientpay-outcome-v1'
                  AND message_id = ? AND state = 'OPEN'
                FOR UPDATE
                """, resultSet -> resultSet.next() ? 1 : 0, tenantId.value(), messageId);
        if (updated == 1) {
            update("""
                    UPDATE message_quarantine
                    SET occurrence_count = occurrence_count + 1, last_seen_at = ?
                    WHERE tenant_id = ? AND consumer_name = 'resilientpay-outcome-v1'
                      AND message_id = ? AND state = 'OPEN'
                    """, now, tenantId.value(), messageId);
        } else {
            update(
                    """
                    INSERT INTO message_quarantine (
                        id, tenant_id, consumer_name, message_id, correlation_id, payload_digest,
                        error_code, state, occurrence_count, first_seen_at, last_seen_at
                    ) VALUES (?, ?, 'resilientpay-outcome-v1', ?, ?, ?, ?, 'OPEN', 1, ?, ?)
                    """,
                    UUID.randomUUID(),
                    tenantId.value(),
                    messageId,
                    correlationId,
                    payloadDigest,
                    errorCode,
                    now,
                    now);
        }
        quarantineMetrics.increment(errorCode);
    }

    private void quarantineOutbox(OutboxMessage message) {
        Instant now = clock.instant();
        update(
                """
                INSERT INTO message_quarantine (
                    id, tenant_id, consumer_name, message_id, correlation_id, payload_digest,
                    error_code, state, occurrence_count, first_seen_at, last_seen_at
                ) VALUES (?, ?, 'resilientpay-outbox-publisher-v1', ?, ?, ?,
                          'OUTBOX_RECOVERY_EXHAUSTED', 'OPEN', 1, ?, ?)
                """,
                UUID.randomUUID(),
                message.tenantId(),
                message.id().toString(),
                message.correlationId(),
                sha256(new String(message.payload(), StandardCharsets.UTF_8)),
                now,
                now);
        quarantineMetrics.increment("OUTBOX_RECOVERY_EXHAUSTED");
    }

    private void insertReversal(ReversalCommand command, DemonstrationFailure failure) {
        String actionFingerprint = sha256(command.tenantId().value() + ":"
                + command.originalTransactionId().value() + ":FULL_REVERSAL");
        update(
                """
                INSERT INTO reversal_command (
                    id, tenant_id, original_transaction_id, operation_policy_id,
                    policy_version_snapshot, max_attempts_snapshot, result_timeout_ms_snapshot,
                    initial_retry_delay_ms_snapshot, maximum_retry_delay_ms_snapshot,
                    idempotency_key_hash, request_hash, action_fingerprint, correlation_id,
                    operation_type, amount, currency, reason_code, state, failure_code,
                    demonstration_failure, accepted_at, created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                command.id().value(),
                command.tenantId().value(),
                command.originalTransactionId().value(),
                command.operationPolicyId().value(),
                command.operationPolicyVersion(),
                command.retryPolicySnapshot().maximumExecutionAttempts(),
                command.retryPolicySnapshot().resultTimeout().toMillis(),
                command.retryPolicySnapshot().initialBackoff().toMillis(),
                command.retryPolicySnapshot().maximumBackoff().toMillis(),
                command.idempotencyKeyHash(),
                command.requestHash(),
                actionFingerprint,
                command.correlationId().value(),
                command.operationType().name(),
                command.money().amount(),
                command.money().currency().getCurrencyCode(),
                command.reason().value(),
                command.state().name(),
                command.failureCode().orElse(null),
                failure.name(),
                command.acceptedAt(),
                command.acceptedAt(),
                command.updatedAt(),
                command.version());
    }

    private void updateReversal(ReversalCommand previous, ReversalCommand updated, Instant nextAttemptAt) {
        int rows = update(
                """
                UPDATE reversal_command
                SET state = ?, failure_code = ?, completed_at = ?, next_attempt_at = ?,
                    updated_at = ?, version = ?
                WHERE tenant_id = ? AND id = ? AND version = ?
                """,
                updated.state().name(),
                updated.failureCode().orElse(null),
                updated.isTerminal() ? updated.updatedAt() : null,
                nextAttemptAt,
                updated.updatedAt(),
                updated.version(),
                updated.tenantId().value(),
                updated.id().value(),
                previous.version());
        requireSingleUpdate(rows, "reversal command");
    }

    private void updateOriginal(OriginalTransaction previous, OriginalTransaction updated) {
        int rows = update(
                """
                UPDATE original_transaction
                SET status = ?, updated_at = ?, version = ?
                WHERE tenant_id = ? AND id = ? AND version = ?
                """,
                updated.state().name(),
                updated.updatedAt(),
                updated.version(),
                updated.tenantId().value(),
                updated.id().value(),
                previous.version());
        requireSingleUpdate(rows, "original transaction");
    }

    private void insertReservation(TenantId tenantId, UUID originalId, UUID commandId, Instant at) {
        update("""
                INSERT INTO reversal_reservation (
                    tenant_id, original_transaction_id, reversal_command_id, reserved_at
                ) VALUES (?, ?, ?, ?)
                """, tenantId.value(), originalId, commandId, at);
    }

    private void releaseReservation(TenantId tenantId, UUID originalId, Instant at) {
        update("""
                UPDATE reversal_reservation
                SET released_at = ?
                WHERE tenant_id = ? AND original_transaction_id = ? AND released_at IS NULL
                """, at, tenantId.value(), originalId);
    }

    private void insertAttempt(ExecutionAttempt attempt) {
        update(
                """
                INSERT INTO execution_attempt (
                    id, tenant_id, reversal_command_id, attempt_number, dispatch_id, state,
                    result_deadline, started_at, created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                attempt.id().value(),
                attempt.tenantId().value(),
                attempt.reversalCommandId().value(),
                attempt.attemptNumber(),
                attempt.id().value(),
                databaseAttemptState(attempt.state()),
                attempt.resultDeadline(),
                attempt.createdAt(),
                attempt.createdAt(),
                attempt.createdAt(),
                attempt.version());
    }

    private void updateAttempt(ExecutionAttempt previous, ExecutionAttempt updated, String resultCode) {
        int rows = update(
                """
                UPDATE execution_attempt
                SET state = ?, result_code = ?, error_code = ?, finished_at = ?,
                    updated_at = ?, version = ?
                WHERE tenant_id = ? AND id = ? AND version = ?
                """,
                databaseAttemptState(updated.state()),
                resultCode,
                updated.failureCode().orElse(null),
                updated.completedAt().orElse(null),
                updated.completedAt().orElse(updated.dispatchedAt().orElse(updated.createdAt())),
                updated.version(),
                updated.tenantId().value(),
                updated.id().value(),
                previous.version());
        requireSingleUpdate(rows, "execution attempt");
    }

    private void insertExecutionOutbox(
            ReversalCommand command, ExecutionAttempt attempt, DemonstrationFailure demonstrationFailure, Instant at) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reversalCommandId", command.id().value());
        payload.put("executionAttemptId", attempt.id().value());
        payload.put("attemptNumber", attempt.attemptNumber());
        payload.put("deadline", attempt.resultDeadline());
        payload.put("demonstrationFailure", demonstrationFailure.name());
        OutboxTraceContext trace = traceContext.captureCurrent();
        update(
                """
                INSERT INTO outbox_event (
                    id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type,
                    schema_version, correlation_id, causation_id, payload, traceparent, tracestate,
                    status, attempt_count,
                    available_at, created_at
                ) VALUES (?, ?, 'REVERSAL_COMMAND', ?, ?, 'ReversalExecutionRequested.v1',
                          1, ?, ?, ?::jsonb, ?, ?, 'PENDING', 0, ?, ?)
                """,
                UUID.randomUUID(),
                command.tenantId().value(),
                command.id().value(),
                command.version(),
                command.correlationId().value(),
                command.correlationId().value(),
                json(payload),
                trace.traceparent(),
                trace.tracestate(),
                at,
                at);
    }

    private void insertAcceptedOutbox(ReversalCommand command, Instant at) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reversalCommandId", command.id().value());
        payload.put("operationType", command.operationType().name());
        payload.put("policyVersion", command.operationPolicyVersion());
        payload.put("acceptedAt", command.acceptedAt());
        OutboxTraceContext trace = traceContext.captureCurrent();
        update(
                """
                INSERT INTO outbox_event (
                    id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type,
                    schema_version, correlation_id, causation_id, payload, traceparent, tracestate,
                    status, attempt_count,
                    available_at, created_at
                ) VALUES (?, ?, 'REVERSAL_COMMAND', ?, ?, 'ReversalAccepted.v1',
                          1, ?, ?, ?::jsonb, ?, ?, 'PENDING', 0, ?, ?)
                """,
                UUID.randomUUID(),
                command.tenantId().value(),
                command.id().value(),
                command.version(),
                command.correlationId().value(),
                command.correlationId().value(),
                json(payload),
                trace.traceparent(),
                trace.tracestate(),
                at,
                at);
    }

    private void insertOriginalStatusOutbox(
            OriginalTransaction original, UUID correlationId, UUID reversalCommandId, Instant at) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("originalTransactionId", original.id().value());
        payload.put("status", original.state().name());
        payload.put("changedAt", at);
        if (reversalCommandId != null) {
            payload.put("reversalCommandId", reversalCommandId);
        }
        OutboxTraceContext trace = traceContext.captureCurrent();
        update(
                """
                INSERT INTO outbox_event (
                    id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type,
                    schema_version, correlation_id, causation_id, payload, traceparent, tracestate,
                    status, attempt_count,
                    available_at, created_at
                ) VALUES (?, ?, 'ORIGINAL_TRANSACTION', ?, ?,
                          'OriginalTransactionStatusChanged.v1', 1, ?, ?, ?::jsonb,
                          ?, ?, 'PENDING', 0, ?, ?)
                """,
                UUID.randomUUID(),
                original.tenantId().value(),
                original.id().value(),
                original.version(),
                correlationId,
                correlationId,
                json(payload),
                trace.traceparent(),
                trace.tracestate(),
                at,
                at);
    }

    private void insertCase(ReconciliationCase reconciliationCase) {
        Instant deadline = reconciliationCase.openedAt().plus(reconciliationProperties.evidenceWindow());
        update(
                """
                INSERT INTO reconciliation_case (
                    id, tenant_id, reversal_command_id, state, ambiguity_reason,
                    evidence_deadline, opened_at, created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                reconciliationCase.id().value(),
                reconciliationCase.tenantId().value(),
                reconciliationCase.reversalCommandId().value(),
                reconciliationCase.state().name(),
                reconciliationCase.ambiguityReasonCode(),
                deadline,
                reconciliationCase.openedAt(),
                reconciliationCase.openedAt(),
                reconciliationCase.updatedAt(),
                reconciliationCase.version());
    }

    private void updateCase(ReconciliationCase previous, ReconciliationCase updated) {
        int rows = update(
                """
                UPDATE reconciliation_case
                SET state = ?, resolved_at = ?, updated_at = ?, version = ?
                WHERE tenant_id = ? AND id = ? AND version = ?
                """,
                updated.state().name(),
                updated.state() == ReconciliationCaseState.RESOLVED
                                || updated.state() == ReconciliationCaseState.QUARANTINED
                        ? updated.updatedAt()
                        : null,
                updated.updatedAt(),
                updated.version(),
                updated.tenantId().value(),
                updated.id().value(),
                previous.version());
        requireSingleUpdate(rows, "reconciliation case");
    }

    private OriginalTransaction originalByTokenForUpdate(TenantId tenantId, String tokenDigest) {
        return queryOne(
                originalAggregateSql() + " AND external_reference_token = ? FOR UPDATE",
                ORIGINAL_AGGREGATE_MAPPER,
                tenantId.value(),
                tokenDigest);
    }

    private OriginalTransaction originalByIdForUpdate(TenantId tenantId, UUID id) {
        OriginalTransaction result = queryOne(
                originalAggregateSql() + " AND id = ? FOR UPDATE", ORIGINAL_AGGREGATE_MAPPER, tenantId.value(), id);
        if (result == null) {
            throw failure(
                    ApplicationErrorCode.RESOURCE_NOT_FOUND, "The tenant-scoped original transaction was not found");
        }
        return result;
    }

    private OperationPolicy activePolicy(TenantId tenantId, OperationType operationType, Instant at) {
        return queryOne("""
                SELECT id, tenant_id, operation_type, policy_version, status, max_attempts,
                       initial_retry_delay_ms, maximum_retry_delay_ms, result_timeout_ms,
                       effective_from, effective_to, created_by, submitted_by, approved_by,
                       created_at, updated_at, version
                FROM operation_policy
                WHERE tenant_id = ? AND operation_type = ? AND status = 'ACTIVE'
                  AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
                """, OPERATION_POLICY_MAPPER, tenantId.value(), operationType.name(), at, at);
    }

    private ReversalCommand reversalForUpdate(TenantId tenantId, UUID id) {
        return queryOne(
                reversalAggregateSql() + " AND rc.id = ? FOR UPDATE OF rc",
                REVERSAL_AGGREGATE_MAPPER,
                tenantId.value(),
                id);
    }

    private ExecutionAttempt attemptForUpdate(TenantId tenantId, UUID id) {
        return queryOne("""
                SELECT id, tenant_id, reversal_command_id, attempt_number, state, error_code,
                       started_at, result_deadline, finished_at, updated_at, version
                FROM execution_attempt
                WHERE tenant_id = ? AND id = ?
                FOR UPDATE
                """, EXECUTION_ATTEMPT_MAPPER, tenantId.value(), id);
    }

    private ReconciliationCase reconciliationCaseForUpdate(TenantId tenantId, UUID caseId) {
        return queryOne("""
                SELECT rc.id, rc.tenant_id, rc.reversal_command_id, rc.ambiguity_reason,
                       rc.state, rc.opened_at, rc.updated_at, rc.version,
                       rd.proposed_outcome, rd.proposed_by, rd.approved_by,
                       rd.domain_purpose_code
                FROM reconciliation_case rc
                LEFT JOIN reconciliation_decision rd
                  ON rd.tenant_id = rc.tenant_id AND rd.reconciliation_case_id = rc.id
                WHERE rc.tenant_id = ? AND rc.id = ?
                FOR UPDATE OF rc
                """, RECONCILIATION_AGGREGATE_MAPPER, tenantId.value(), caseId);
    }

    private OriginalTransactionView originalViewById(TenantId tenantId, UUID id) {
        OriginalTransactionView result = queryOne("""
                SELECT id, COALESCE(public_reference_token, external_reference_token)
                           AS external_reference_token,
                       operation_type, amount, currency,
                       status, occurred_at
                FROM original_transaction
                WHERE tenant_id = ? AND id = ?
                """, ORIGINAL_VIEW_MAPPER, tenantId.value(), id);
        if (result == null) {
            throw failure(
                    ApplicationErrorCode.RESOURCE_NOT_FOUND, "The tenant-scoped original transaction was not found");
        }
        return result;
    }

    private ReconciliationCaseView caseViewById(TenantId tenantId, UUID id) {
        ReconciliationCaseView result = queryOne(
                reconciliationViewSql() + " WHERE rc.tenant_id = ? AND rc.id = ?",
                RECONCILIATION_VIEW_MAPPER,
                tenantId.value(),
                id);
        if (result == null) {
            throw failure(
                    ApplicationErrorCode.RESOURCE_NOT_FOUND,
                    "The requested tenant-scoped reconciliation case was not found");
        }
        return result;
    }

    private DemonstrationFailure demonstrationFailure(TenantId tenantId, UUID commandId) {
        String result = queryOne("""
                SELECT demonstration_failure FROM reversal_command
                WHERE tenant_id = ? AND id = ?
                """, (resultSet, rowNumber) -> resultSet.getString(1), tenantId.value(), commandId);
        return DemonstrationFailure.valueOf(result);
    }

    private int attemptCount(TenantId tenantId, UUID commandId) {
        Integer result = jdbc.queryForObject("""
                SELECT COUNT(*) FROM execution_attempt
                WHERE tenant_id = ? AND reversal_command_id = ?
                """, Integer.class, tenantId.value(), commandId);
        return result == null ? 0 : result;
    }

    private void requireCommandExists(TenantId tenantId, UUID commandId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reversal_command WHERE tenant_id = ? AND id = ?",
                Integer.class,
                tenantId.value(),
                commandId);
        if (count == null || count == 0) {
            throw failure(
                    ApplicationErrorCode.RESOURCE_NOT_FOUND,
                    "The requested tenant-scoped reversal command was not found");
        }
    }

    private void lockIdempotency(TenantId tenantId, String action, String keyHash) {
        long lockKey = ByteBuffer.wrap(HexFormat.of().parseHex(keyHash.substring(0, 16)))
                .getLong();
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1, lockKey ^ tenantId.value().getMostSignificantBits() ^ action.hashCode());
                statement.execute();
            }
            return null;
        });
    }

    private IdempotencyHit idempotency(TenantId tenantId, String action, String keyHash) {
        return queryOne(
                """
                SELECT request_hash, resource_id
                FROM idempotency_record
                WHERE tenant_id = ? AND action = ? AND key_hash = ?
                """,
                (resultSet, rowNumber) -> new IdempotencyHit(
                        resultSet.getString("request_hash"), resultSet.getObject("resource_id", UUID.class)),
                tenantId.value(),
                action,
                keyHash);
    }

    private void insertIdempotency(
            TenantId tenantId,
            String action,
            String keyHash,
            String requestHash,
            UUID resourceId,
            int responseStatus,
            Instant at) {
        update(
                """
                INSERT INTO idempotency_record (
                    tenant_id, action, key_hash, request_hash, resource_id, response_status,
                    created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId.value(),
                action,
                keyHash,
                requestHash,
                resourceId,
                responseStatus,
                at,
                at.plus(IDEMPOTENCY_RETENTION));
    }

    private void auditReplay(
            ActorContext actor, String action, String resourceType, UUID resourceId, UUID correlationId) {
        writeAudit(
                actor,
                action,
                resourceType,
                resourceId,
                "IDEMPOTENT_REPLAY",
                "SUCCEEDED",
                null,
                correlationId,
                null,
                Map.of("replayed", true),
                clock.instant());
    }

    private void writeAudit(
            ActorContext actor,
            String action,
            String resourceType,
            UUID resourceId,
            String purpose,
            String outcome,
            String reason,
            UUID correlationId,
            Integer policyVersion,
            Map<String, Object> safeDetails,
            Instant at) {
        auditStore.append(new AuditEntry(
                actor,
                action,
                resourceType,
                resourceId,
                purpose,
                outcome,
                reason,
                correlationId,
                policyVersion,
                safeDetails,
                at));
    }

    private <T> T queryOne(String sql, RowMapper<T> mapper, Object... arguments) {
        List<T> rows = jdbc.query(sql, mapper, sqlArguments(arguments));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private int update(String sql, Object... arguments) {
        return jdbc.update(sql, sqlArguments(arguments));
    }

    private static Object[] sqlArguments(Object... arguments) {
        Object[] values = arguments.clone();
        for (int index = 0; index < values.length; index++) {
            if (values[index] instanceof Instant instant) {
                values[index] = Timestamp.from(instant);
            }
        }
        return values;
    }

    private String json(Object value) {
        try {
            return SafeJsonWriter.write(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Safe JSON serialization failed", exception);
        }
    }

    private void requireMatchingRequest(
            ActorContext actor,
            IdempotencyHit replay,
            String requestHash,
            String idempotencyAction,
            String resourceType,
            UUID correlationId) {
        if (!replay.requestHash().equals(requestHash)) {
            auditDenialInNewTransaction(
                    actor,
                    "IDEMPOTENCY_KEY_REUSE_DENIED",
                    resourceType,
                    replay.resourceId(),
                    correlationId,
                    ApplicationErrorCode.IDEMPOTENCY_KEY_REUSED.name(),
                    Map.of("idempotencyAction", idempotencyAction));
            throw failure(
                    ApplicationErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "The idempotency key was already used for a different request");
        }
    }

    private void auditDenialInNewTransaction(
            ActorContext actor,
            String action,
            String resourceType,
            UUID resourceId,
            UUID correlationId,
            String reasonCode,
            Map<String, Object> details) {
        independentAudit.recordIndependent(new AuditEntry(
                actor,
                action,
                resourceType,
                resourceId,
                "CONTROL_ENFORCEMENT",
                "DENIED",
                reasonCode,
                correlationId,
                null,
                details,
                clock.instant()));
    }

    private static ApplicationException failure(ApplicationErrorCode code, String message) {
        return new ApplicationException(code, message);
    }

    private static void requireMutationKey(String value) {
        if (!value.matches("[A-Za-z0-9._~-]{16,128}")) {
            throw failure(
                    ApplicationErrorCode.INVALID_REQUEST, "Idempotency-Key must contain 16 to 128 safe characters");
        }
    }

    private static void requireSingleUpdate(int rows, String aggregate) {
        if (rows != 1) {
            throw failure(
                    ApplicationErrorCode.STATE_TRANSITION_NOT_ALLOWED,
                    "Concurrent " + aggregate + " update was rejected");
        }
    }

    private static String safeReason(String value, String fallback) {
        String candidate = value == null || value.isBlank() ? fallback : value.strip();
        if (!candidate.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("Outcome reason must be a stable uppercase code");
        }
        return candidate;
    }

    private static Instant latest(Instant first, Instant second, Instant third) {
        return first.isAfter(second)
                ? (first.isAfter(third) ? first : third)
                : (second.isAfter(third) ? second : third);
    }

    private static String canonicalAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private static String databaseAttemptState(ExecutionAttemptState state) {
        return switch (state) {
            case CREATED -> "CREATED";
            case DISPATCHED -> "DISPATCHED";
            case CONFIRMED_NOT_EXECUTED -> "KNOWN_NOT_SENT";
            case CONFIRMED_EXECUTED -> "SUCCEEDED";
            case AMBIGUOUS -> "OUTCOME_UNKNOWN";
        };
    }

    private static ExecutionAttemptState domainAttemptState(String state) {
        return switch (state) {
            case "CREATED" -> ExecutionAttemptState.CREATED;
            case "DISPATCHED" -> ExecutionAttemptState.DISPATCHED;
            case "KNOWN_NOT_SENT", "REJECTED" -> ExecutionAttemptState.CONFIRMED_NOT_EXECUTED;
            case "SUCCEEDED" -> ExecutionAttemptState.CONFIRMED_EXECUTED;
            case "OUTCOME_UNKNOWN" -> ExecutionAttemptState.AMBIGUOUS;
            default -> throw new IllegalStateException("Unsupported persisted attempt state");
        };
    }

    private static ReconciliationDecision reconciliationDecision(String value) {
        return value == null ? null : ReconciliationDecision.valueOf(value);
    }

    private static String publicDecision(String value) {
        return "REJECT_REVERSAL".equals(value) ? "REJECT" : value;
    }

    private static OriginalTransactionView originalView(OriginalTransaction original) {
        return new OriginalTransactionView(
                original.id().value(),
                original.externalReferenceToken(),
                original.operationType(),
                original.money().amount(),
                original.money().currency().getCurrencyCode(),
                original.state(),
                original.occurredAt());
    }

    private static List<String> allowedActions(ReversalCommandState status, boolean retryDue) {
        List<String> actions = new ArrayList<>(List.of("VIEW", "VIEW_EVIDENCE"));
        if (status == ReversalCommandState.RETRY_SCHEDULED && retryDue) {
            actions.add("REQUEST_SAFE_RETRY");
        }
        if (status == ReversalCommandState.RECONCILIATION_REQUIRED) {
            actions.add("CHECK_OUTCOME");
        }
        return actions;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }

    private static String tokenDigest(String tokenOrDigest) {
        String normalized =
                Objects.requireNonNull(tokenOrDigest, "token must not be null").strip();
        return normalized.matches("[a-f0-9]{64}") ? normalized : sha256(normalized);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        java.sql.Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String originalAggregateSql() {
        return """
                SELECT id, tenant_id, external_reference_token, operation_type, amount, currency,
                       status, occurred_at, updated_at, version
                FROM original_transaction
                WHERE tenant_id = ?
                """;
    }

    private static String reversalAggregateSql() {
        return """
                SELECT rc.id, rc.tenant_id, rc.original_transaction_id, rc.operation_policy_id,
                       rc.policy_version_snapshot AS policy_version,
                       rc.max_attempts_snapshot AS max_attempts,
                       rc.result_timeout_ms_snapshot AS result_timeout_ms,
                       rc.initial_retry_delay_ms_snapshot AS initial_retry_delay_ms,
                       rc.maximum_retry_delay_ms_snapshot AS maximum_retry_delay_ms,
                       rc.operation_type, rc.amount, rc.currency, rc.reason_code, rc.state,
                       rc.failure_code, rc.idempotency_key_hash, rc.request_hash,
                       rc.correlation_id, rc.accepted_at, rc.updated_at, rc.version
                FROM reversal_command rc
                WHERE rc.tenant_id = ?
                """;
    }

    private static String reversalViewSql() {
        return """
                SELECT rc.id, COALESCE(ot.public_reference_token, ot.external_reference_token)
                           AS external_reference_token,
                       rc.operation_type, rc.amount,
                       rc.currency, rc.state, rc.failure_code, rc.correlation_id,
                       rc.accepted_at, rc.updated_at, rc.version,
                       (rc.next_attempt_at IS NULL OR rc.next_attempt_at <= CURRENT_TIMESTAMP)
                           AS retry_due,
                       (SELECT COUNT(*) FROM execution_attempt ea
                        WHERE ea.tenant_id = rc.tenant_id
                          AND ea.reversal_command_id = rc.id) AS attempt_count
                FROM reversal_command rc
                JOIN original_transaction ot
                  ON ot.tenant_id = rc.tenant_id AND ot.id = rc.original_transaction_id
                WHERE rc.tenant_id = ?
                """;
    }

    private static String reconciliationViewSql() {
        return """
                SELECT rc.id, rc.reversal_command_id, rc.state, rc.ambiguity_reason,
                       rd.proposed_outcome,
                       CASE WHEN rd.proposed_by IS NULL THEN NULL ELSE LEFT(ENCODE(SHA256(CONVERT_TO(
                           rc.tenant_id::text || ':' || rd.proposed_by, 'UTF8')), 'hex'), 24) END
                           AS maker_pseudonym,
                       rd.purpose_code, rd.evidence_digest, rc.opened_at, rc.resolved_at
                FROM reconciliation_case rc
                LEFT JOIN reconciliation_decision rd
                  ON rd.tenant_id = rc.tenant_id AND rd.reconciliation_case_id = rc.id
                """;
    }

    private boolean evidenceBelongsToCase(
            TenantId tenantId, ReconciliationCase reconciliationCase, String evidenceDigest) {
        Boolean owned = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM audit_event evidence
                    WHERE evidence.tenant_id = ?
                      AND evidence.evidence_digest = ?
                      AND (
                          (evidence.resource_type = 'REVERSAL_COMMAND' AND evidence.resource_id = ?)
                          OR (evidence.resource_type = 'RECONCILIATION_CASE' AND evidence.resource_id = ?)
                      )
                )
                """,
                Boolean.class,
                tenantId.value(),
                evidenceDigest,
                reconciliationCase.reversalCommandId().value(),
                reconciliationCase.id().value());
        return Boolean.TRUE.equals(owned);
    }

    private static final RowMapper<OriginalTransaction> ORIGINAL_AGGREGATE_MAPPER =
            (resultSet, rowNumber) -> OriginalTransaction.restore(
                    new OriginalTransactionId(resultSet.getObject("id", UUID.class)),
                    new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                    resultSet.getString("external_reference_token").strip(),
                    OperationType.valueOf(resultSet.getString("operation_type")),
                    new Money(
                            resultSet.getBigDecimal("amount"),
                            Currency.getInstance(resultSet.getString("currency").strip())),
                    OriginalTransactionState.valueOf(resultSet.getString("status")),
                    instant(resultSet, "occurred_at"),
                    instant(resultSet, "updated_at"),
                    resultSet.getLong("version"));

    private static final RowMapper<OperationPolicy> OPERATION_POLICY_MAPPER =
            (resultSet, rowNumber) -> OperationPolicy.restore(
                    new OperationPolicyId(resultSet.getObject("id", UUID.class)),
                    new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                    OperationType.valueOf(resultSet.getString("operation_type")),
                    resultSet.getInt("policy_version"),
                    OperationPolicyState.valueOf(resultSet.getString("status")),
                    new RetryPolicy(
                            resultSet.getInt("max_attempts"),
                            Duration.ofMillis(resultSet.getLong("result_timeout_ms")),
                            Duration.ofMillis(resultSet.getLong("initial_retry_delay_ms")),
                            Duration.ofMillis(resultSet.getLong("maximum_retry_delay_ms"))),
                    instant(resultSet, "effective_from"),
                    nullableInstant(resultSet, "effective_to"),
                    new ActorId(resultSet.getString("created_by")),
                    new ActorId(resultSet.getString("submitted_by")),
                    new ActorId(resultSet.getString("approved_by")),
                    instant(resultSet, "created_at"),
                    instant(resultSet, "updated_at"),
                    resultSet.getLong("version"));

    private static final RowMapper<ReversalCommand> REVERSAL_AGGREGATE_MAPPER =
            (resultSet, rowNumber) -> ReversalCommand.restore(
                    new ReversalCommandId(resultSet.getObject("id", UUID.class)),
                    new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                    new OriginalTransactionId(resultSet.getObject("original_transaction_id", UUID.class)),
                    new OperationPolicyId(resultSet.getObject("operation_policy_id", UUID.class)),
                    resultSet.getInt("policy_version"),
                    new RetryPolicy(
                            resultSet.getInt("max_attempts"),
                            Duration.ofMillis(resultSet.getLong("result_timeout_ms")),
                            Duration.ofMillis(resultSet.getLong("initial_retry_delay_ms")),
                            Duration.ofMillis(resultSet.getLong("maximum_retry_delay_ms"))),
                    OperationType.valueOf(resultSet.getString("operation_type")),
                    new Money(
                            resultSet.getBigDecimal("amount"),
                            Currency.getInstance(resultSet.getString("currency").strip())),
                    new ReversalReason(resultSet.getString("reason_code")),
                    ReversalCommandState.valueOf(resultSet.getString("state")),
                    resultSet.getString("failure_code"),
                    resultSet.getString("idempotency_key_hash"),
                    resultSet.getString("request_hash"),
                    new CorrelationId(resultSet.getObject("correlation_id", UUID.class)),
                    instant(resultSet, "accepted_at"),
                    instant(resultSet, "updated_at"),
                    resultSet.getLong("version"));

    private static final RowMapper<ExecutionAttempt> EXECUTION_ATTEMPT_MAPPER =
            (resultSet, rowNumber) -> ExecutionAttempt.restore(
                    new ExecutionAttemptId(resultSet.getObject("id", UUID.class)),
                    new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                    new ReversalCommandId(resultSet.getObject("reversal_command_id", UUID.class)),
                    resultSet.getInt("attempt_number"),
                    domainAttemptState(resultSet.getString("state")),
                    resultSet.getString("error_code"),
                    instant(resultSet, "started_at"),
                    "CREATED".equals(resultSet.getString("state")) ? null : instant(resultSet, "started_at"),
                    instant(resultSet, "result_deadline"),
                    nullableInstant(resultSet, "finished_at"),
                    resultSet.getLong("version"));

    private static final RowMapper<ReconciliationCase> RECONCILIATION_AGGREGATE_MAPPER =
            (resultSet, rowNumber) -> ReconciliationCase.restore(
                    new ReconciliationCaseId(resultSet.getObject("id", UUID.class)),
                    new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                    new ReversalCommandId(resultSet.getObject("reversal_command_id", UUID.class)),
                    resultSet.getString("ambiguity_reason"),
                    ReconciliationCaseState.valueOf(resultSet.getString("state")),
                    reconciliationDecision(resultSet.getString("proposed_outcome")),
                    resultSet.getString("proposed_by") == null ? null : new ActorId(resultSet.getString("proposed_by")),
                    resultSet.getString("approved_by") == null ? null : new ActorId(resultSet.getString("approved_by")),
                    resultSet.getString("domain_purpose_code"),
                    instant(resultSet, "opened_at"),
                    instant(resultSet, "updated_at"),
                    resultSet.getLong("version"));

    private static final RowMapper<ReversalView> REVERSAL_VIEW_MAPPER = (resultSet, rowNumber) -> {
        ReversalCommandState state = ReversalCommandState.valueOf(resultSet.getString("state"));
        return new ReversalView(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("external_reference_token").strip(),
                OperationType.valueOf(resultSet.getString("operation_type")),
                resultSet.getBigDecimal("amount"),
                resultSet.getString("currency").strip(),
                state,
                resultSet.getString("failure_code"),
                resultSet.getObject("correlation_id", UUID.class),
                resultSet.getInt("attempt_count"),
                instant(resultSet, "accepted_at"),
                instant(resultSet, "updated_at"),
                resultSet.getLong("version"),
                allowedActions(state, resultSet.getBoolean("retry_due")));
    };

    private static final RowMapper<OriginalTransactionView> ORIGINAL_VIEW_MAPPER =
            (resultSet, rowNumber) -> new OriginalTransactionView(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("external_reference_token").strip(),
                    OperationType.valueOf(resultSet.getString("operation_type")),
                    resultSet.getBigDecimal("amount"),
                    resultSet.getString("currency").strip(),
                    OriginalTransactionState.valueOf(resultSet.getString("status")),
                    instant(resultSet, "occurred_at"));

    private static final RowMapper<ReconciliationCaseView> RECONCILIATION_VIEW_MAPPER =
            (resultSet, rowNumber) -> new ReconciliationCaseView(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("reversal_command_id", UUID.class),
                    ReconciliationCaseState.valueOf(resultSet.getString("state")),
                    resultSet.getString("ambiguity_reason"),
                    publicDecision(resultSet.getString("proposed_outcome")),
                    resultSet.getString("maker_pseudonym"),
                    resultSet.getString("purpose_code"),
                    resultSet.getString("evidence_digest"),
                    instant(resultSet, "opened_at"),
                    nullableInstant(resultSet, "resolved_at"));

    /**
     * Holds the fingerprint and safely replayable resource for one serialized mutation.
     *
     * @param requestHash canonical request SHA-256
     * @param resourceId safely replayable resource
     * @author Daniel Jr Nkulu
     */
    private record IdempotencyHit(String requestHash, UUID resourceId) {}
}
