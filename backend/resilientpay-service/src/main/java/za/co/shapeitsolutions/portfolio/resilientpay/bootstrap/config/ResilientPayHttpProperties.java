package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds the explicit browser origins permitted to call the tenant-scoped HTTP API.
 *
 * @param allowedOrigins exact trusted origins without wildcard expansion
 * @author Daniel Jr Nkulu
 */
@Validated
@ConfigurationProperties("resilientpay.http")
public record ResilientPayHttpProperties(@NotEmpty List<@NotBlank String> allowedOrigins) {
    public ResilientPayHttpProperties {
        allowedOrigins = List.copyOf(allowedOrigins);
    }
}
