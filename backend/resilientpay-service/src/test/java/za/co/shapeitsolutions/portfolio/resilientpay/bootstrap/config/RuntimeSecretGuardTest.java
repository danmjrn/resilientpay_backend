package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Verifies production-style startup fails closed without every required secret.
 *
 * @author Daniel Jr Nkulu
 */
class RuntimeSecretGuardTest {
    @Test
    void rejectsMissingNonDemoSecrets() {
        assertThrows(IllegalStateException.class, () -> new RuntimeSecretGuard(new MockEnvironment()));
    }

    @Test
    void acceptsExternallySuppliedNonDemoSecrets() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.password", "database-secret")
                .withProperty("spring.rabbitmq.password", "rabbit-secret")
                .withProperty("spring.data.redis.password", "redis-secret")
                .withProperty("resilientpay.security.evidence-hmac-key", "evidence-secret")
                .withProperty("resilientpay.messaging.hmac-key", "message-secret");

        assertDoesNotThrow(() -> new RuntimeSecretGuard(environment));
    }
}
