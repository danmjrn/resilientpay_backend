package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.CannotCreateTransactionException;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security.CorrelationIdFilter;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ProposeReconciliationCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation.ReconciliationDecision;

/**
 * Verifies canonical command mapping and redacted correlated Problem Details responses.
 *
 * @author Daniel Jr Nkulu
 */
class WebContractTest {

    @AfterEach
    void clearCorrelation() {
        MDC.clear();
    }

    @Test
    void reconciliationMapperCarriesRequiredIdempotencyAndPublicRejectMeaning() {
        UUID correlationId = UUID.randomUUID();
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId.toString());
        String key = "maker-key-0000001";

        ProposeReconciliationCommand command = new WebCommandMapper(new MockEnvironment())
                .proposal(
                        UUID.randomUUID(),
                        new ReconciliationDecisionRequest(
                                PublicReconciliationDecision.REJECT, "Evidence proves rejection", "a".repeat(64)),
                        key);

        assertEquals(key, command.idempotencyKey());
        assertEquals(ReconciliationDecision.REJECT_REVERSAL, command.decision());
        assertEquals(correlationId, command.correlationId().value());
    }

    @Test
    void unexpectedFailureIsRedactedAndKeepsCorrelation() {
        UUID correlationId = UUID.randomUUID();
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId.toString());
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reversalcommands");

        ResponseEntity<ProblemResponse> response =
                new ApiExceptionHandler().unexpected(new IllegalStateException("database password leaked"), request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(
                ApplicationErrorCode.INTERNAL_ERROR.name(), response.getBody().code());
        assertEquals(correlationId.toString(), response.getBody().correlationId());
        assertFalse(response.getBody().detail().contains("password"));
    }

    @Test
    void stableDependencyAndRateLimitErrorsMapToCanonicalStatuses() {
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reversalcommands");
        ApiExceptionHandler handler = new ApiExceptionHandler();

        ResponseEntity<ProblemResponse> rateLimited = handler.application(
                new ApplicationException(ApplicationErrorCode.RATE_LIMITED, "Mutation rate limit exceeded"), request);
        ResponseEntity<ProblemResponse> unavailable = handler.application(
                new ApplicationException(
                        ApplicationErrorCode.DEPENDENCY_UNAVAILABLE, "Mutation admission dependency is unavailable"),
                request);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rateLimited.getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatusCode());
    }

    @Test
    void unavailableDatabaseConnectionsMapToRedactedDependencyProblem() {
        UUID correlationId = UUID.randomUUID();
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId.toString());
        HttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reversalcommands");
        ApiExceptionHandler handler = new ApiExceptionHandler();

        for (Exception exception : new Exception[] {
            new CannotCreateTransactionException("database password leaked"),
            new DataAccessResourceFailureException("database password leaked")
        }) {
            ResponseEntity<ProblemResponse> response = handler.dependencyUnavailable(exception, request);

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
            assertEquals(
                    ApplicationErrorCode.DEPENDENCY_UNAVAILABLE.name(),
                    response.getBody().code());
            assertEquals(correlationId.toString(), response.getBody().correlationId());
            assertFalse(response.getBody().detail().contains("password"));
        }
    }

    @Test
    void actorContextRequiresCanonicalTokenTenantClaim() {
        Jwt missingTenant = Jwt.withTokenValue("synthetic-token")
                .header("alg", "none")
                .subject("subject-123")
                .build();

        ApplicationException failure = assertThrows(
                ApplicationException.class,
                () -> new ActorContextFactory().from(new JwtAuthenticationToken(missingTenant)));

        assertEquals(ApplicationErrorCode.AUTHENTICATION_REQUIRED, failure.code());
        Jwt valid = Jwt.withTokenValue("synthetic-token")
                .header("alg", "none")
                .subject("subject-123")
                .claim("tenant_id", "00000000-0000-0000-0000-000000000001")
                .build();
        assertEquals(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new ActorContextFactory()
                        .from(new JwtAuthenticationToken(valid))
                        .tenantId()
                        .value());
    }

    @Test
    void nonDemoProfileRejectsSyntheticFailureControls() {
        AcceptReversalRequest request = new AcceptReversalRequest(
                "synth-txn-transfer-001",
                za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType.ACCOUNT_TRANSFER,
                new java.math.BigDecimal("1250.00"),
                "ZAR",
                "Synthetic correction",
                za.co.shapeitsolutions.portfolio.resilientpay.application.model.DemonstrationFailure.AMBIGUOUS);

        ApplicationException failure = assertThrows(
                ApplicationException.class,
                () -> new WebCommandMapper(new MockEnvironment()).accept(request, "failure-key-0001"));

        assertEquals(ApplicationErrorCode.INVALID_REQUEST, failure.code());
        MockEnvironment demo = new MockEnvironment();
        demo.setActiveProfiles("demo");
        assertEquals(
                za.co.shapeitsolutions.portfolio.resilientpay.application.model.DemonstrationFailure.AMBIGUOUS,
                new WebCommandMapper(demo).accept(request, "failure-key-0001").demonstrationFailure());
    }
}
