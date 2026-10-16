package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Defines bounded scheduling settings for recovery and reconciliation scans.
 *
 * @param pollDelay delay between scans
 * @param batchSize maximum cases processed by one scan
 * @param evidenceWindow bounded operator evidence window for a new ambiguous case
 * @author Daniel Jr Nkulu
 */
@Validated
@ConfigurationProperties("resilientpay.reconciliation")
public record ReconciliationProperties(
        @NotNull Duration pollDelay,
        @Min(1) @Max(200) int batchSize,
        @NotNull Duration evidenceWindow) {}
