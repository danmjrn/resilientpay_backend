package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import java.util.Map;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails non-demo simulator startup when broker or message-integrity secrets are absent or unsafe.
 * Configured values are never retained in exceptions or logs.
 *
 * @author Daniel Jr Nkulu
 */
@Component
@Profile("!demo & !test")
public final class SimulatorRuntimeSecretGuard {
    private static final int MINIMUM_SECRET_LENGTH = 24;
    private static final Map<String, String> FORBIDDEN_LOCAL_VALUES = Map.of(
            "spring.rabbitmq.password", "resilientpay-local-only",
            "resilientpay.simulator.message-hmac-key", "local-demo-message-key");

    /** Validates non-demo secret presence and rejects published local demonstration values. */
    public SimulatorRuntimeSecretGuard(Environment environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        FORBIDDEN_LOCAL_VALUES.forEach((property, forbiddenValue) -> validate(environment, property, forbiddenValue));
    }

    private static void validate(Environment environment, String property, String forbiddenValue) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required simulator runtime secret is absent: " + property);
        }
        if (value.length() < MINIMUM_SECRET_LENGTH || value.equals(forbiddenValue)) {
            throw new IllegalStateException("Simulator runtime secret is unsafe: " + property);
        }
    }
}
