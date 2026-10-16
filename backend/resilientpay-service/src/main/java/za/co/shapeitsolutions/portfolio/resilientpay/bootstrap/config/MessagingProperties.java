package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Defines the versioned RabbitMQ topology and message-integrity boundary.
 *
 * @param exchange durable lifecycle topic exchange
 * @param acceptedQueue bounded lifecycle acceptance queue
 * @param originalStatusQueue bounded original-status notification queue
 * @param executionQueue synthetic-switch execution queue
 * @param outcomeQueue orchestrator outcome queue
 * @param quarantineExchange dead-letter exchange for exhausted deliveries
 * @param quarantineQueue bounded operator quarantine queue
 * @param acceptedRoutingKey accepted-command routing key
 * @param originalStatusRoutingKey original-status notification routing key
 * @param executionRoutingKey execution-request routing key
 * @param outcomeRoutingKey observed-outcome routing key
 * @param quarantineRoutingKey exhausted-delivery routing key
 * @param executionQuarantineRoutingKey exhausted execution-delivery routing key
 * @param consumerName stable inbox consumer name
 * @param hmacKey externally supplied message HMAC key
 * @param keyId non-secret active message key identifier
 * @param maximumPayloadBytes maximum accepted JSON body size
 * @param retryInitialInterval first transient consumer retry delay
 * @param retryMaximumInterval bounded transient consumer retry delay
 * @param retryAttempts bounded listener attempt count
 * @author Daniel Jr Nkulu
 */
@Validated
@ConfigurationProperties("resilientpay.messaging")
public record MessagingProperties(
        @NotBlank String exchange,
        @NotBlank String acceptedQueue,
        @NotBlank String originalStatusQueue,
        @NotBlank String executionQueue,
        @NotBlank String outcomeQueue,
        @NotBlank String quarantineExchange,
        @NotBlank String quarantineQueue,
        @NotBlank String acceptedRoutingKey,
        @NotBlank String originalStatusRoutingKey,
        @NotBlank String executionRoutingKey,
        @NotBlank String outcomeRoutingKey,
        @NotBlank String quarantineRoutingKey,
        @NotBlank String executionQuarantineRoutingKey,
        @NotBlank String consumerName,
        @NotBlank String hmacKey,
        @NotBlank String keyId,
        @Min(1024) @Max(1048576) int maximumPayloadBytes,
        @NotNull Duration retryInitialInterval,
        @NotNull Duration retryMaximumInterval,
        @Min(1) @Max(10) int retryAttempts) {}
