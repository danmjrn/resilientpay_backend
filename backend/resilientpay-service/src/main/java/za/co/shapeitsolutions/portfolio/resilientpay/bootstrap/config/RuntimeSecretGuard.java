package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails non-demo startup when an authoritative dependency or integrity secret is absent.
 * Secret values are never retained in errors or logs.
 *
 * @author Daniel Jr Nkulu
 */
@Component
@Profile("!demo & !test")
public final class RuntimeSecretGuard {
    private static final List<String> REQUIRED_SECRET_PROPERTIES = List.of(
            "spring.datasource.password",
            "spring.rabbitmq.password",
            "spring.data.redis.password",
            "resilientpay.security.evidence-hmac-key",
            "resilientpay.messaging.hmac-key");

    /** Validates presence without exposing any configured value. */
    public RuntimeSecretGuard(Environment environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        for (String property : REQUIRED_SECRET_PROPERTIES) {
            String value = environment.getProperty(property);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Required runtime secret is absent: " + property);
            }
        }
    }
}
