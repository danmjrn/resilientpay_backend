package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Supplies resource-server audience and evidence-signing configuration from external secrets.
 * The HMAC key is never written to logs or API responses.
 *
 * @param audience required OAuth2 token audience
 * @param evidenceHmacKey HMAC key used for evidence and event integrity
 * @param evidenceKeyId non-secret identifier for the active signing key
 * @param approvalAcr exact OIDC authentication context required for checker approval
 * @author Daniel Jr Nkulu
 */
@Validated
@ConfigurationProperties("resilientpay.security")
public record ResilientPaySecurityProperties(
        @NotBlank String audience,
        @NotBlank String evidenceHmacKey,
        @NotBlank String evidenceKeyId,
        @NotBlank String approvalAcr) {}
