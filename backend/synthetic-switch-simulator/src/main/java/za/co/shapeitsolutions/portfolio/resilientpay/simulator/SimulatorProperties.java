package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/**
 * Defines the bounded simulator outcome, delay, and shared event-integrity key.
 *
 * @param scenario default synthetic outcome scenario
 * @param delay bounded processing delay used only for deterministic demonstrations
 * @param messageHmacKey shared secret used to authenticate the complete message envelope and body
 * @param signatureKeyId safe identifier for the active message-integrity key
 * @param faultInjectionEnabled whether non-success scenarios may be honored
 * @param publishConfirmTimeout maximum wait for broker confirmation of an outcome
 * @param journalDirectory durable single-writer directory for restart-safe execution identities
 * @author Daniel Jr Nkulu
 */
@Validated
@ConfigurationProperties("resilientpay.simulator")
public record SimulatorProperties(
        @NotNull SimulatorScenario scenario,
        @NotNull Duration delay,
        @NotBlank String messageHmacKey,
        @NotBlank String signatureKeyId,
        boolean faultInjectionEnabled,
        @NotNull Duration publishConfirmTimeout,
        @NotNull Path journalDirectory) {
    /** Rejects unsafe or misleading delay configuration. */
    @ConstructorBinding
    public SimulatorProperties {
        if (delay != null && (delay.isNegative() || delay.compareTo(Duration.ofSeconds(5)) > 0)) {
            throw new IllegalArgumentException("Simulator delay must be between zero and five seconds");
        }
        if (!faultInjectionEnabled && scenario != null && scenario != SimulatorScenario.SUCCEED) {
            throw new IllegalArgumentException("Synthetic fault scenarios require explicit local enablement");
        }
        if (publishConfirmTimeout != null
                && (publishConfirmTimeout.isZero()
                        || publishConfirmTimeout.isNegative()
                        || publishConfirmTimeout.compareTo(Duration.ofSeconds(10)) > 0)) {
            throw new IllegalArgumentException("Publish confirmation timeout must be between zero and ten seconds");
        }
        if (journalDirectory != null
                && journalDirectory.toAbsolutePath().normalize().getNameCount() == 0) {
            throw new IllegalArgumentException("Simulator journal directory must not be a filesystem root");
        }
    }

    /** Creates test/local properties with a process-external temporary journal location. */
    public SimulatorProperties(
            SimulatorScenario scenario,
            Duration delay,
            String messageHmacKey,
            String signatureKeyId,
            boolean faultInjectionEnabled,
            Duration publishConfirmTimeout) {
        this(
                scenario,
                delay,
                messageHmacKey,
                signatureKeyId,
                faultInjectionEnabled,
                publishConfirmTimeout,
                Path.of(System.getProperty("java.io.tmpdir"), "resilientpay-simulator-journal"));
    }
}
