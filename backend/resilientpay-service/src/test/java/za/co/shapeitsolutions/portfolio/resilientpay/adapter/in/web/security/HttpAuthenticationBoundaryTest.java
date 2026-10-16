package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.Filter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.invocation.Invocation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.OperationPolicyController;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.OriginalTransactionController;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.ReconciliationController;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.ReversalCommandController;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ActorContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.MutationAdmissionPort;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OrchestrationStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.SecurityAuditPort;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Exercises every protected OpenAPI operation through the real HTTP security filter chain, actual
 * Nimbus signature verification, production issuer/audience/time validation, real MVC controllers,
 * and real application scope checks. Each explicitly named operation test runs all six invalid
 * authentication conditions, one required-scope denial and a least-scope valid positive control.
 * Only downstream persistence, mutation admission and security audit ports are boundary doubles;
 * these tests do not prove database tenant isolation, persistence, JWK availability or runtime TLS.
 * No JWT decoder, authenticated principal, controller or application authorization decision is mocked.
 *
 * @author Daniel Jr Nkulu
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class HttpAuthenticationBoundaryTest {
    private static final String ROOT = "/api/v1/";
    private static final String RESOURCE = HttpAuthenticationBoundaryFixture.RESOURCE_ID.toString();
    private static final String PRIVATE_CLAIM_CANARY = "http-boundary-private-claim-canary";
    private static final List<String> SCOPES = List.of(
            "reversals.write",
            "reversals.read",
            "reversals.retry",
            "audit.read",
            "transactions.write",
            "transactions.read",
            "policies.read",
            "reconciliation.read",
            "reconciliation.decide",
            "reconciliation.approve");
    private static final List<TokenCondition> INVALID_AUTHENTICATION = List.of(
            TokenCondition.ABSENT,
            TokenCondition.WRONG_ISSUER,
            TokenCondition.WRONG_AUDIENCE,
            TokenCondition.EXPIRED,
            TokenCondition.NOT_YET_VALID,
            TokenCondition.INVALID_SIGNATURE);
    private static final String ACCEPT_BODY = """
            {"originalTransactionToken":"synth-http-boundary-0001","operationType":"CARD_PURCHASE",
             "amount":120.00,"currency":"ZAR","reason":"Synthetic boundary verification",
             "demonstrationFailure":"NONE"}
            """;
    private static final String ORIGINAL_BODY = """
            {"transactionToken":"synth-http-boundary-0001","operationType":"CARD_PURCHASE",
             "amount":120.00,"currency":"ZAR"}
            """;
    private static final String RETRY_BODY = """
            {"proof":"CONFIRMED_NOT_EXECUTED","purpose":"Synthetic boundary verification"}
            """;
    private static final String PROPOSAL_BODY = """
            {"decision":"CONFIRM_NOT_EXECUTED","purpose":"Synthetic boundary verification",
             "evidenceDigest":"%s"}
            """.formatted("a".repeat(64));
    private static final String APPROVAL_BODY = """
            {"purpose":"Synthetic boundary verification"}
            """;

    private AnnotationConfigWebApplicationContext context;
    private MockMvc http;
    private KeyPair signingKeys;
    private KeyPair untrustedSigningKeys;
    private OrchestrationStore store;
    private MutationAdmissionPort admission;
    private SecurityAuditPort audit;

    @BeforeAll
    void startRealHttpSecurityBoundary() throws Exception {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        // Disable only the production remote-JWK bean. The replacement is a real Nimbus decoder
        // using ephemeral local RSA keys and the same production oidcValidator, never a mock.
        context.getEnvironment().setActiveProfiles("test");
        context.register(HttpAuthenticationBoundaryFixture.class);
        context.refresh();
        http = MockMvcBuilders.webAppContextSetup(context)
                // The production security filter precedes an unordered servlet component filter.
                // Authentication failures therefore use SecurityProblemWriter's safe generated UUID.
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class), new CorrelationIdFilter())
                .build();
        signingKeys = context.getBean(KeyPair.class);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        untrustedSigningKeys = generator.generateKeyPair();
        store = context.getBean(OrchestrationStore.class);
        admission = context.getBean(MutationAdmissionPort.class);
        audit = context.getBean(SecurityAuditPort.class);
    }

    @AfterAll
    void closeHttpBoundary() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void acceptReversalCommandEnforcesAuthenticationBoundary() throws Exception {
        verifyBoundary(new Operation(
                "acceptReversalCommand",
                "POST",
                ROOT + "reversalcommands",
                ACCEPT_BODY,
                "reversals.write",
                "accept",
                ReversalCommandController.class,
                "accept",
                202,
                "ACCEPT_REVERSAL"));
    }

    @Test
    void listReversalCommandsEnforcesAuthenticationBoundary() throws Exception {
        verifyBoundary(new Operation(
                "listReversalCommands",
                "GET",
                ROOT + "reversalcommands",
                null,
                "reversals.read",
                "listReversals",
                ReversalCommandController.class,
                "list",
                200,
                "LIST_REVERSALS"));
    }

    @Test
    void getReversalCommandEnforcesAuthenticationBoundary() throws Exception {
        verifyBoundary(new Operation(
                "getReversalCommand",
                "GET",
                ROOT + "reversalcommands/" + RESOURCE,
                null,
                "reversals.read",
                "getReversal",
                ReversalCommandController.class,
                "get",
                200,
                "READ_REVERSAL"));
    }

    @Test
    void requestSafeReversalRetryEnforcesAuthenticationBoundary() throws Exception {
        verifyBoundary(new Operation(
                "requestSafeReversalRetry",
                "POST",
                ROOT + "reversalcommands/" + RESOURCE + "/retry",
                RETRY_BODY,
                "reversals.retry",
                "retry",
                ReversalCommandController.class,
                "retry",
                202,
                "RETRY_REVERSAL"));
    }

    @Test
    void getReversalEvidenceEnforcesAuthenticationBoundary() throws Exception {
        verifyBoundary(new Operation(
                "getReversalEvidence",
                "GET",
                ROOT + "reversalcommands/" + RESOURCE + "/evidence",
                null,
                "audit.read",
                "evidence",
                ReversalCommandController.class,
                "evidence",
                200,
                "READ_AUDIT_EVIDENCE"));
    }

    @Test
    void ingestSyntheticOriginalTransactionEnforcesAuthenticationBoundary() throws Exception {
        verifyBoundary(new Operation(
                "ingestSyntheticOriginalTransaction",
                "POST",
                ROOT + "originaltransactions",
                ORIGINAL_BODY,
                "transactions.write",
                "ingestOriginal",
                OriginalTransactionController.class,
                "ingest",
                201,
                "INGEST_ORIGINAL"));
    }

    @Test
    void listOriginalTransactionsEnforcesAuthenticationBoundary() throws Exception {
        verifyBoundary(new Operation(
                "listOriginalTransactions",
                "GET",
                ROOT + "originaltransactions",
                null,
                "transactions.read",
                "listOriginals",
                OriginalTransactionController.class,
                "list",
                200,
                "LIST_ORIGINALS"));
    }

    @Test
    void listOperationPolicysSourceCompatibleEnforcesAuthenticationBoundary() throws Exception {
        verifyBoundary(new Operation(
                "listOperationPolicysSourceCompatible",
                "GET",
                ROOT + "operationpolicys",
                null,
                "policies.read",
                "listPolicies",
                OperationPolicyController.class,
                "listSourceCompatible",
                200,
                "LIST_POLICIES"));
    }

    @Test
    void listOperationPoliciesCanonicalAliasEnforcesAuthenticationBoundary() throws Exception {
        verifyBoundary(new Operation(
                "listOperationPoliciesCanonicalAlias",
                "GET",
                ROOT + "operationpolicies",
                null,
                "policies.read",
                "listPolicies",
                OperationPolicyController.class,
                "listCanonicalAlias",
                200,
                "LIST_POLICIES"));
    }

    @Test
    void listReconciliationCasesEnforcesAuthenticationBoundary() throws Exception {
        verifyBoundary(new Operation(
                "listReconciliationCases",
                "GET",
                ROOT + "reconciliationcases",
                null,
                "reconciliation.read",
                "listReconciliationCases",
                ReconciliationController.class,
                "list",
                200,
                "LIST_RECONCILIATION_CASES"));
    }

    @Test
    void proposeReconciliationDecisionEnforcesAuthenticationBoundary() throws Exception {
        verifyBoundary(new Operation(
                "proposeReconciliationDecision",
                "POST",
                ROOT + "reconciliationcases/" + RESOURCE + "/decisions",
                PROPOSAL_BODY,
                "reconciliation.decide",
                "propose",
                ReconciliationController.class,
                "propose",
                202,
                "PROPOSE_RECONCILIATION"));
    }

    @Test
    void approveReconciliationDecisionEnforcesAuthenticationBoundary() throws Exception {
        verifyBoundary(new Operation(
                "approveReconciliationDecision",
                "POST",
                ROOT + "reconciliationcases/" + RESOURCE + "/approvals",
                APPROVAL_BODY,
                "reconciliation.approve",
                "approve",
                ReconciliationController.class,
                "approve",
                200,
                "APPROVE_RECONCILIATION"));
    }

    private void verifyBoundary(Operation operation) throws Exception {
        for (TokenCondition condition : INVALID_AUTHENTICATION) {
            clearInvocations(store, admission, audit);
            String token = token(condition, operation.requiredScope());
            String label = operation.operationId() + ":" + condition;
            MvcResult result = exchange(operation, token);
            assertProblem(result, 401, "AUTHENTICATION_REQUIRED", "Authentication required", 5, label, token);
            assertNull(result.getHandler(), label + " must be rejected before MVC dispatch");
            assertNoCalls(store, label + " reached the persistence port");
            assertNoCalls(admission, label + " reached mutation admission");
            assertNoCalls(audit, label + " reached the application audit port");
        }

        clearInvocations(store, admission, audit);
        String missingScopeToken = token(TokenCondition.MISSING_REQUIRED_SCOPE, operation.requiredScope());
        String scopeLabel = operation.operationId() + ":MISSING_REQUIRED_SCOPE";
        MvcResult denied = exchange(operation, missingScopeToken);
        JsonNode scopeProblem =
                assertProblem(denied, 403, "ACCESS_DENIED", "Access denied", 7, scopeLabel, missingScopeToken);
        assertController(denied, operation, scopeLabel);
        assertTrue(
                "The authenticated actor is not permitted to perform this action"
                        .equals(scopeProblem.path("detail").asText()),
                scopeLabel + " has unstable or non-redacted denial detail");
        assertTrue(operation.path().equals(scopeProblem.path("instance").asText()), scopeLabel + " has wrong instance");
        assertTrue(
                HttpAuthenticationBoundaryFixture.CORRELATION_ID
                        .toString()
                        .equals(scopeProblem.path("correlationId").asText()),
                scopeLabel + " lost the validated public request correlation");
        assertNoCalls(store, scopeLabel + " reached the persistence port");
        assertNoCalls(admission, scopeLabel + " reached mutation admission");
        List<Invocation> denialAudit = List.copyOf(mockingDetails(audit).getInvocations());
        assertEquals(1, denialAudit.size(), scopeLabel + " must record exactly one denial audit");
        assertTrue(
                "recordAuthorizationDenied"
                        .equals(denialAudit.getFirst().getMethod().getName()),
                scopeLabel + " used the wrong audit action");
        Object[] denialArguments = denialAudit.getFirst().getArguments();
        assertTrue(operation.denialAction().equals(denialArguments[1]), scopeLabel + " has wrong denied operation");
        assertTrue("MISSING_REQUIRED_AUTHORITY".equals(denialArguments[4]), scopeLabel + " has wrong denial reason");

        clearInvocations(store, admission, audit);
        String validToken = token(TokenCondition.VALID, operation.requiredScope());
        String validLabel = operation.operationId() + ":VALID_LEAST_SCOPE";
        MvcResult accepted = exchange(operation, validToken);
        assertEquals(operation.successStatus(), accepted.getResponse().getStatus(), validLabel + " was not accepted");
        assertController(accepted, operation, validLabel);
        assertRedacted(accepted.getResponse(), validToken, validLabel);
        assertTrue(
                HttpAuthenticationBoundaryFixture.CORRELATION_ID
                        .toString()
                        .equals(accepted.getResponse().getHeader(CorrelationIdFilter.HEADER_NAME)),
                validLabel + " lost the validated public request correlation");
        List<Invocation> calls = List.copyOf(mockingDetails(store).getInvocations());
        assertEquals(1, calls.size(), validLabel + " must invoke one application-selected store operation");
        assertTrue(
                operation.storeMethod().equals(calls.getFirst().getMethod().getName()),
                validLabel + " reached the wrong store method");
        Object principalArgument = calls.getFirst().getArgument(0);
        TenantId selectedTenant =
                principalArgument instanceof ActorContext actor ? actor.tenantId() : (TenantId) principalArgument;
        assertTrue(
                HttpAuthenticationBoundaryFixture.TENANT_ID.equals(selectedTenant.value()),
                validLabel + " did not derive its tenant from the verified JWT");
        assertEquals(
                "POST".equals(operation.method()) ? 1 : 0,
                mockingDetails(admission).getInvocations().size(),
                validLabel + " has unexpected admission calls");
        assertEquals(
                "evidence".equals(operation.storeMethod()) ? 1 : 0,
                mockingDetails(audit).getInvocations().size(),
                validLabel + " has unexpected audit calls");
    }

    private MvcResult exchange(Operation operation, String token) throws Exception {
        MockHttpServletRequestBuilder request =
                "GET".equals(operation.method()) ? get(operation.path()) : post(operation.path());
        request.accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                .header(CorrelationIdFilter.HEADER_NAME, HttpAuthenticationBoundaryFixture.CORRELATION_ID.toString());
        if (operation.body() != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "http-authentication-boundary-idempotency")
                    .content(operation.body());
        }
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return http.perform(request).andReturn();
    }

    private static JsonNode assertProblem(
            MvcResult result, int status, String code, String title, int fieldCount, String label, String token)
            throws Exception {
        MockHttpServletResponse response = result.getResponse();
        assertEquals(status, response.getStatus(), label + " has wrong HTTP denial status");
        String contentType = response.getContentType();
        assertTrue(
                contentType != null && contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE),
                label + " did not return Problem Details");
        assertRedacted(response, token, label);
        JsonNode problem;
        try {
            problem = new JsonMapper().readTree(response.getContentAsByteArray());
        } catch (RuntimeException exception) {
            throw new AssertionError(label + " returned non-JSON Problem Details");
        }
        assertTrue(problem != null && problem.isObject(), label + " did not return a problem object");
        assertEquals(fieldCount, problem.size(), label + " exposed unexpected problem fields");
        assertEquals(status, problem.path("status").asInt(), label + " has wrong problem status");
        assertTrue(code.equals(problem.path("code").asText()), label + " has wrong problem code");
        assertTrue(title.equals(problem.path("title").asText()), label + " has wrong problem title");
        String expectedType = "urn:resilientpay:problem:"
                + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        assertTrue(expectedType.equals(problem.path("type").asText()), label + " has wrong problem type");
        String correlation = problem.path("correlationId").asText();
        try {
            assertTrue(
                    UUID.fromString(correlation).toString().equals(correlation),
                    label + " has noncanonical correlation");
        } catch (IllegalArgumentException exception) {
            throw new AssertionError(label + " has missing or unsafe public correlation");
        }
        assertTrue(
                correlation.equals(response.getHeader(CorrelationIdFilter.HEADER_NAME)),
                label + " lost response correlation");
        return problem;
    }

    private static void assertRedacted(MockHttpServletResponse response, String token, String label) throws Exception {
        String exposed = response.getContentAsString()
                + response.getHeaderNames().stream()
                        .flatMap(name -> response.getHeaders(name).stream())
                        .toList();
        assertFalse(exposed.contains(PRIVATE_CLAIM_CANARY), label + " exposed private token claims");
        if (token != null) {
            assertFalse(exposed.contains(token), label + " exposed the bearer token");
            for (String segment : token.split("\\.")) {
                assertFalse(exposed.contains(segment), label + " exposed an encoded JWT segment");
            }
        }
    }

    private static void assertController(MvcResult result, Operation operation, String label) {
        assertTrue(result.getHandler() instanceof HandlerMethod, label + " did not reach a real MVC controller");
        HandlerMethod handler = (HandlerMethod) result.getHandler();
        assertTrue(
                operation.controllerType().isAssignableFrom(handler.getBeanType()),
                label + " reached wrong controller");
        assertTrue(
                operation.controllerMethod().equals(handler.getMethod().getName()), label + " reached wrong handler");
    }

    private static void assertNoCalls(Object boundary, String label) {
        assertTrue(mockingDetails(boundary).getInvocations().isEmpty(), label);
    }

    private String token(TokenCondition condition, String requiredScope) throws Exception {
        if (condition == TokenCondition.ABSENT) {
            return null;
        }
        Instant now = Instant.now();
        Instant issuedAt = condition == TokenCondition.EXPIRED ? now.minusSeconds(3600) : now.minusSeconds(5);
        Instant notBefore = condition == TokenCondition.NOT_YET_VALID ? now.plusSeconds(600) : issuedAt;
        Instant expiresAt = condition == TokenCondition.EXPIRED ? now.minusSeconds(600) : now.plusSeconds(3600);
        String issuer = condition == TokenCondition.WRONG_ISSUER
                ? HttpAuthenticationBoundaryFixture.ISSUER + "/wrong"
                : HttpAuthenticationBoundaryFixture.ISSUER;
        String audience = condition == TokenCondition.WRONG_AUDIENCE
                ? "different-resource-api"
                : HttpAuthenticationBoundaryFixture.AUDIENCE;
        String scopes = condition == TokenCondition.MISSING_REQUIRED_SCOPE
                ? String.join(
                        " ",
                        SCOPES.stream()
                                .filter(scope -> !scope.equals(requiredScope))
                                .toList())
                : requiredScope;
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(PRIVATE_CLAIM_CANARY)
                .issueTime(Date.from(issuedAt))
                .notBeforeTime(Date.from(notBefore))
                .expirationTime(Date.from(expiresAt))
                .claim("tenant_id", HttpAuthenticationBoundaryFixture.TENANT_ID.toString())
                .claim("scope", scopes)
                .claim("roles", List.of())
                .claim("acr", HttpAuthenticationBoundaryFixture.ASSURANCE)
                .claim("private_probe", PRIVATE_CLAIM_CANARY)
                .build();
        SignedJWT signed = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .build(),
                claims);
        KeyPair keys = condition == TokenCondition.INVALID_SIGNATURE ? untrustedSigningKeys : signingKeys;
        signed.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
        return signed.serialize();
    }

    /**
     * Names safe, fixed authentication conditions without embedding a bearer token in test output.
     * Timestamp cases are ten minutes outside the validity window to avoid clock-skew ambiguity.
     *
     * @author Daniel Jr Nkulu
     */
    private enum TokenCondition {
        ABSENT,
        WRONG_ISSUER,
        WRONG_AUDIENCE,
        EXPIRED,
        NOT_YET_VALID,
        INVALID_SIGNATURE,
        MISSING_REQUIRED_SCOPE,
        VALID
    }

    /**
     * Maps one published operation to its actual route, handler and least-privilege store action.
     *
     * @param operationId canonical OpenAPI operation identity
     * @param method literal HTTP method
     * @param path concrete valid HTTP path with synthetic resource identifiers
     * @param body syntactically valid mutation JSON, or null for a query
     * @param requiredScope the one scope required by this application operation
     * @param storeMethod expected persistence-port method reached only by the valid control
     * @param controllerType real MVC controller class
     * @param controllerMethod real MVC handler method
     * @param successStatus published success status for the boundary control
     * @param denialAction safe authorization-audit action for a missing required scope
     * @author Daniel Jr Nkulu
     */
    private record Operation(
            String operationId,
            String method,
            String path,
            String body,
            String requiredScope,
            String storeMethod,
            Class<?> controllerType,
            String controllerMethod,
            int successStatus,
            String denialAction) {}
}
