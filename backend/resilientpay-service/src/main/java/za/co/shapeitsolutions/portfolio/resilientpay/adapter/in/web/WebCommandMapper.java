package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web;

import java.util.Currency;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security.CorrelationIdFilter;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AcceptReversalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApproveReconciliationCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.DemonstrationFailure;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.IngestOriginalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ProposeReconciliationCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.RetryReversalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalReason;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.CorrelationId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.Money;

/**
 * Maps validated HTTP DTOs and generated correlation metadata into application commands.
 *
 * @author Daniel Jr Nkulu
 */
@Component
public final class WebCommandMapper {
    private final boolean demonstrationFailuresEnabled;

    /** Creates the mapper with fail-closed awareness of explicit local profiles. */
    public WebCommandMapper(Environment environment) {
        demonstrationFailuresEnabled = environment.acceptsProfiles(Profiles.of("demo", "test"));
    }

    /** Maps one full reversal acceptance request. */
    public AcceptReversalCommand accept(AcceptReversalRequest request, String idempotencyKey) {
        return new AcceptReversalCommand(
                request.originalTransactionToken(),
                request.operationType(),
                new Money(request.amount(), Currency.getInstance(request.currency())),
                new ReversalReason(request.reason()),
                permittedDemonstrationFailure(request.demonstrationFailure()),
                idempotencyKey,
                correlationId());
    }

    /** Maps one synthetic original-transaction request. */
    public IngestOriginalCommand original(OriginalTransactionRequest request, String idempotencyKey) {
        return new IngestOriginalCommand(
                request.transactionToken(),
                request.operationType(),
                new Money(request.amount(), Currency.getInstance(request.currency())),
                idempotencyKey,
                correlationId());
    }

    /** Maps one proven-safe retry request. */
    public RetryReversalCommand retry(UUID commandId, RetryRequest request, String idempotencyKey) {
        return new RetryReversalCommand(commandId, request.proof(), request.purpose(), idempotencyKey, correlationId());
    }

    /** Maps one maker reconciliation decision. */
    public ProposeReconciliationCommand proposal(
            UUID caseId, ReconciliationDecisionRequest request, String idempotencyKey) {
        return new ProposeReconciliationCommand(
                caseId,
                request.decision().toDomain(),
                request.purpose(),
                request.evidenceDigest(),
                idempotencyKey,
                correlationId());
    }

    /** Maps one distinct-checker reconciliation approval. */
    public ApproveReconciliationCommand approval(
            UUID caseId, ReconciliationApprovalRequest request, String idempotencyKey) {
        return new ApproveReconciliationCommand(caseId, request.purpose(), idempotencyKey, correlationId());
    }

    private static CorrelationId correlationId() {
        String value = MDC.get(CorrelationIdFilter.MDC_KEY);
        return new CorrelationId(value == null ? UUID.randomUUID() : UUID.fromString(value));
    }

    private DemonstrationFailure permittedDemonstrationFailure(DemonstrationFailure requested) {
        DemonstrationFailure failure = requested == null ? DemonstrationFailure.NONE : requested;
        if (failure != DemonstrationFailure.NONE && !demonstrationFailuresEnabled) {
            throw new ApplicationException(
                    ApplicationErrorCode.INVALID_REQUEST,
                    "Synthetic failure controls are available only in explicit local profiles");
        }
        return failure;
    }
}
