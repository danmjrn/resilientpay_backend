package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Verifies that Spring selects the runtime constructors of components with test conveniences.
 *
 * @author Daniel Jr Nkulu
 */
class SimulatorSpringWiringTest {

    @Test
    void wiresJournalAndProcessorDespiteTheirAdditionalTestConstructors(@TempDir Path temporaryDirectory) {
        SimulatorProperties properties = new SimulatorProperties(
                SimulatorScenario.SUCCEED,
                Duration.ZERO,
                "external-message-integrity-secret-32-characters",
                "test-key-v1",
                false,
                Duration.ofSeconds(2),
                temporaryDirectory.resolve("journal"));

        new ApplicationContextRunner()
                .withUserConfiguration(WiringConfiguration.class)
                .withBean(SimulatorProperties.class, () -> properties)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(SimulatorExecutionJournal.class));
                    assertNotNull(context.getBean(SyntheticSwitchProcessor.class));
                });
    }

    /**
     * Imports the production components with their only non-runtime dependency.
     *
     * @author Daniel Jr Nkulu
     */
    @Configuration(proxyBeanMethods = false)
    @Import({SimulatorExecutionJournal.class, SyntheticSwitchProcessor.class})
    static class WiringConfiguration {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
