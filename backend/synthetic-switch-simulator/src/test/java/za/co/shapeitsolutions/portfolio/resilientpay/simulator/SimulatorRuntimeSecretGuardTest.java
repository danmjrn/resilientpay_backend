package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/**
 * Verifies the simulator fails closed at non-demo context startup without external secrets.
 *
 * @author Daniel Jr Nkulu
 */
class SimulatorRuntimeSecretGuardTest {
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(SimulatorRuntimeSecretGuard.class);

    @Test
    void nonDemoContextFailsStartupWithoutExternalSecrets() {
        contextRunner.run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure);
            Throwable root = rootCause(failure);
            assertTrue(root instanceof IllegalStateException);
            assertTrue(root.getMessage().contains("Required simulator runtime secret is absent"));
        });
    }

    @Test
    void demoContextAllowsProfileScopedLocalDefaults() {
        contextRunner
                .withPropertyValues("spring.profiles.active=demo")
                .run(context -> assertNull(context.getStartupFailure()));
    }

    @Test
    void rejectsPublishedDemoValuesOutsideDemoProfile() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.rabbitmq.password", "resilientpay-local-only")
                .withProperty("resilientpay.simulator.message-hmac-key", "local-demo-message-key");

        assertThrows(IllegalStateException.class, () -> new SimulatorRuntimeSecretGuard(environment));
    }

    @Test
    void acceptsStrongExternallySuppliedSecrets() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.rabbitmq.password", "external-rabbit-secret-32-characters")
                .withProperty(
                        "resilientpay.simulator.message-hmac-key", "external-message-integrity-secret-32-characters");

        assertDoesNotThrow(() -> new SimulatorRuntimeSecretGuard(environment));
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }
}
