package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence.DemoOrchestrationStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.ReversalOperations;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.MutationAdmissionPort;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OrchestrationStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.SecurityAuditPort;
import za.co.shapeitsolutions.portfolio.resilientpay.application.service.ReversalApplicationService;

/**
 * Composes the framework-free application core around environment-specific outbound adapters.
 *
 * @author Daniel Jr Nkulu
 */
@Configuration(proxyBeanMethods = false)
public class ApplicationCoreConfiguration {

    /** Captures whether intentionally synthetic failure controls are active. */
    @Bean
    @Profile("!test")
    RuntimeMode runtimeMode(Environment environment) {
        return new RuntimeMode(environment.acceptsProfiles(Profiles.of("demo", "test")));
    }

    /** Supplies a deliberate requires-new boundary for durable security audit evidence. */
    @Bean
    @Profile("!test")
    TransactionTemplate independentAuditTransaction(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /** Supplies the process-local adapter only to isolated application-context tests. */
    @Bean
    @Profile("test")
    OrchestrationStore demoOrchestrationStore(Clock clock, ResilientPaySecurityProperties securityProperties) {
        return new DemoOrchestrationStore(clock, securityProperties.evidenceHmacKey());
    }

    /** Composes transport adapters around the application-owned inbound port. */
    @Bean
    ReversalOperations reversalOperations(
            OrchestrationStore store,
            MutationAdmissionPort mutationAdmission,
            SecurityAuditPort securityAudit,
            ResilientPaySecurityProperties securityProperties) {
        return new ReversalApplicationService(
                store, mutationAdmission, securityAudit, securityProperties.approvalAcr());
    }

    /** Supplies a no-op disposable admission boundary only with the process-local test adapter. */
    @Bean
    @Profile("test")
    MutationAdmissionPort testMutationAdmissionPort() {
        return (tenantId, action) -> {};
    }

    /** Supplies an isolated no-op audit sink only when the process-local test adapter is active. */
    @Bean
    @Profile("test")
    SecurityAuditPort testSecurityAuditPort() {
        return (actor, action, resourceId, correlationId, reasonCode) -> {};
    }
}
