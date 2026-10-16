package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Defines the tenant-scoped mutation rate-limit window held ephemerally in Redis.
 *
 * @param capacity maximum commands in one window
 * @param window window duration
 * @author Daniel Jr Nkulu
 */
@Validated
@ConfigurationProperties("resilientpay.rate-limit")
public record RateLimitProperties(
        @Min(1) @Max(10000) int capacity, @NotNull Duration window) {}
