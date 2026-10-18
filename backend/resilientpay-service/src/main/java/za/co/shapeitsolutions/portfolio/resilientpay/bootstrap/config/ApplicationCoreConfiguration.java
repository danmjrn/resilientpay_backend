package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence.DemoOrchestrationStore;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence.SpringTransactionBoundary;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.OutboxOperations;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.ReversalOperations;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.WorkflowBackgroundOperations;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.AuditStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.DeadlineReconciliationStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.LifecycleProjectionStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.MessageQuarantineStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.MutationAdmissionPort;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OrchestrationStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OutboxPublicationAudit;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OutboxStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.OutcomeStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.SecurityAuditPort;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.TransactionBoundary;
import za.co.shapeitsolutions.portfolio.resilientpay.application.service.AuditApplicationService;
import za.co.shapeitsolutions.portfolio.resilientpay.application.service.OutboxApplicationService;
import za.co.shapeitsolutions.portfolio.resilientpay.application.service.ReversalApplicationService;
import za.co.shapeitsolutions.portfolio.resilientpay.application.service.WorkflowBackgroundService;

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

    /** Adapts application-selected database scopes to the deployment transaction manager. */
    @Bean
    @Profile("!test")
    TransactionBoundary transactionBoundary(PlatformTransactionManager transactionManager) {
        return new SpringTransactionBoundary(transactionManager);
    }

    /** Keeps independent security evidence separate from rollback-prone workflow mutations. */
    @Bean
    @Profile("!test")
    AuditApplicationService auditApplicationService(AuditStore store, TransactionBoundary transactions, Clock clock) {
        return new AuditApplicationService(store, transactions, clock);
    }

    /** Owns short background database units, excluding broker transport and scheduling. */
    @Bean
    @Profile("!test")
    WorkflowBackgroundOperations workflowBackgroundOperations(
            TransactionBoundary transactions,
            OutcomeStore outcomes,
            DeadlineReconciliationStore deadlines,
            LifecycleProjectionStore projection,
            MessageQuarantineStore quarantine) {
        return new WorkflowBackgroundService(transactions, outcomes, deadlines, projection, quarantine);
    }

    /** Owns distinct outbox database phases; Rabbit send and confirm wait remain outside them. */
    @Bean
    @Profile("!test")
    OutboxOperations outboxOperations(
            TransactionBoundary transactions, OutboxStore store, OutboxPublicationAudit publicationAudit) {
        return new OutboxApplicationService(transactions, store, publicationAudit);
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
            TransactionBoundary transactions,
            ResilientPaySecurityProperties securityProperties) {
        return new ReversalApplicationService(
                store, mutationAdmission, securityAudit, transactions, securityProperties.approvalAcr());
    }

    /** Runs disposable process-local test work directly; it is never selected for PostgreSQL. */
    @Bean
    @Profile("test")
    TransactionBoundary testTransactionBoundary() {
        return new TransactionBoundary() {
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
        };
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
