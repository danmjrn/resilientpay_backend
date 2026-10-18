package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.ActorContextFactory;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.ApiExceptionHandler;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.OperationPolicyController;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.OriginalTransactionController;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.ReconciliationController;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.ReversalCommandController;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.WebCommandMapper;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AcceptanceResult;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.OriginalTransactionView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ReconciliationCaseView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ReversalView;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.ReversalOperations;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.MutationAdmissionPort;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OrchestrationStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.SecurityAuditPort;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.TransactionBoundary;
import za.co.shapeitsolutions.portfolio.resilientpay.application.service.ReversalApplicationService;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ResilientPayHttpProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reconciliation.ReconciliationCaseState;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.reversal.ReversalCommandState;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.transaction.OriginalTransactionState;

/**
 * Hosts the actual MVC adapters, resource-server filter chain, issuer/audience/time validator and
 * application authorization service around observable downstream boundary doubles. RSA keys exist
 * only in memory. This fixture proves HTTP authorization ordering, not persistence, tenant query
 * isolation, broker delivery, identity-provider availability, or financial state transitions.
 *
 * @author Daniel Jr Nkulu
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableWebMvc
@EnableWebSecurity
@Import({
    SecurityConfiguration.class,
    ApiAuthenticationEntryPoint.class,
    ApiAccessDeniedHandler.class,
    ApiExceptionHandler.class,
    ActorContextFactory.class,
    WebCommandMapper.class,
    ReversalCommandController.class,
    OriginalTransactionController.class,
    OperationPolicyController.class,
    ReconciliationController.class
})
class HttpAuthenticationBoundaryFixture {
    static final String ISSUER = "https:" + "/" + "/identity.example.test/realms/http-boundary";
    static final String AUDIENCE = "resilientpay-api";
    static final String ASSURANCE = "urn:resilientpay:aal2";
    static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID RESOURCE_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    static final UUID CORRELATION_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final Instant FIXTURE_TIME = Instant.parse("2026-09-06T00:00:00Z");

    /** Creates a fresh signing identity without network, JWK retrieval, or retained private material. */
    @Bean
    KeyPair authenticationBoundaryKeys() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** Uses real RS256 verification and precisely the production issuer/audience/timestamp validator. */
    @Bean
    JwtDecoder authenticationBoundaryJwtDecoder(KeyPair authenticationBoundaryKeys) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) authenticationBoundaryKeys.getPublic())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(SecurityConfiguration.oidcValidator(ISSUER, AUDIENCE));
        return decoder;
    }

    /** Provides the production CORS configuration with one bounded, unused local test origin. */
    @Bean
    ResilientPayHttpProperties authenticationBoundaryHttpProperties() {
        return new ResilientPayHttpProperties(List.of("http://localhost:3000"));
    }

    /** Keeps scope and approval-assurance decisions in the real application service. */
    @Bean
    ReversalOperations authenticationBoundaryOperations(
            OrchestrationStore store, MutationAdmissionPort admission, SecurityAuditPort audit) {
        return new ReversalApplicationService(
                store,
                admission,
                audit,
                new TransactionBoundary() {
                    @Override
                    public <T> T required(Supplier<T> work) {
                        return work.get();
                    }

                    @Override
                    public <T> T readOnly(Supplier<T> work) {
                        return work.get();
                    }

                    @Override
                    public <T> T requiresNew(Supplier<T> work) {
                        return work.get();
                    }
                },
                ASSURANCE);
    }

    /** Records downstream calls and supplies safe representations solely for positive HTTP controls. */
    @Bean
    OrchestrationStore authenticationBoundaryStore() {
        return mock(OrchestrationStore.class, invocation -> switch (invocation
                .getMethod()
                .getName()) {
            case "accept" -> new AcceptanceResult(reversal(), false);
            case "getReversal", "retry" -> reversal();
            case "ingestOriginal" ->
                new OriginalTransactionView(
                        RESOURCE_ID,
                        "synth-http-boundary-0001",
                        OperationType.CARD_PURCHASE,
                        new BigDecimal("120.00"),
                        "ZAR",
                        OriginalTransactionState.POSTED,
                        FIXTURE_TIME);
            case "propose", "approve" ->
                new ReconciliationCaseView(
                        RESOURCE_ID,
                        RESOURCE_ID,
                        ReconciliationCaseState.DECISION_PROPOSED,
                        "RESULT_TIMEOUT",
                        "CONFIRM_NOT_EXECUTED",
                        "synthetic-maker",
                        "Synthetic boundary review",
                        "a".repeat(64),
                        FIXTURE_TIME,
                        null);
            case "listReversals", "evidence", "listOriginals", "listPolicies", "listReconciliationCases" -> List.of();
            default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    /** Records whether a rejected authentication or authorization reached mutation admission. */
    @Bean
    MutationAdmissionPort authenticationBoundaryAdmission() {
        return mock(MutationAdmissionPort.class);
    }

    /** Records the real service's scope-denial audit without substituting its authorization decision. */
    @Bean
    SecurityAuditPort authenticationBoundaryAudit() {
        return mock(SecurityAuditPort.class);
    }

    private static ReversalView reversal() {
        return new ReversalView(
                RESOURCE_ID,
                "synth-http-boundary-0001",
                OperationType.CARD_PURCHASE,
                new BigDecimal("120.00"),
                "ZAR",
                ReversalCommandState.ACCEPTED,
                null,
                CORRELATION_ID,
                0,
                FIXTURE_TIME,
                FIXTURE_TIME,
                0,
                List.of());
    }
}
