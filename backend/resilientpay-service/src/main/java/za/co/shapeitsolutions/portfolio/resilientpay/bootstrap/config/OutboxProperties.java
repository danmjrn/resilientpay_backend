package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Defines bounded polling, leasing, and retry settings for transactional outbox publication.
 * The values prevent unbounded queues or retry amplification in the messaging adapter.
 *
 * @param batchSize maximum events claimed by one poll
 * @param leaseDuration duration of an outbox worker lease
 * @param pollDelay delay between polling cycles
 * @param maxAttempts terminal publication-attempt limit
 * @param confirmTimeout maximum wait for one RabbitMQ publisher confirmation
 * @param coldRetryDelay minimum pause before an exhausted retryable event enters another bounded cycle
 * @param maxRecoveryCycles maximum cold recovery cycles before terminal quarantine
 * @author Daniel Jr Nkulu
 */
@Validated
@ConfigurationProperties("resilientpay.outbox")
public record OutboxProperties(
        @Min(1) @Max(500) int batchSize,
        @NotNull Duration leaseDuration,
        @NotNull Duration pollDelay,
        @Min(1) @Max(30) int maxAttempts,
        @NotNull Duration confirmTimeout,
        @NotNull Duration coldRetryDelay,
        @Min(1) @Max(10) int maxRecoveryCycles) {}
