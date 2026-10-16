package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Verifies constructor binding for the immutable simulator runtime configuration.
 *
 * @author Daniel Jr Nkulu
 */
class SimulatorPropertiesBindingTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class)
            .withPropertyValues(
                    "resilientpay.simulator.scenario=SUCCEED",
                    "resilientpay.simulator.delay=25ms",
                    "resilientpay.simulator.message-hmac-key=external-message-integrity-secret-32-characters",
                    "resilientpay.simulator.signature-key-id=test-key-v1",
                    "resilientpay.simulator.fault-injection-enabled=false",
                    "resilientpay.simulator.publish-confirm-timeout=3s",
                    "resilientpay.simulator.journal-directory=/tmp/resilientpay-binding-test");

    @Test
    void bindsCanonicalRecordConstructorWhenConvenienceConstructorAlsoExists() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            SimulatorProperties properties = context.getBean(SimulatorProperties.class);
            assertEquals(SimulatorScenario.SUCCEED, properties.scenario());
            assertEquals(Duration.ofMillis(25), properties.delay());
            assertEquals("test-key-v1", properties.signatureKeyId());
            assertEquals(Duration.ofSeconds(3), properties.publishConfirmTimeout());
            assertEquals(Path.of("/tmp/resilientpay-binding-test"), properties.journalDirectory());
        });
    }

    /**
     * Registers only the immutable properties bean under test.
     *
     * @author Daniel Jr Nkulu
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SimulatorProperties.class)
    static class BindingConfiguration {}
}
