package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

import java.net.URI;
import java.util.HexFormat;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

/**
 * Holds independent hosted-portfolio secrets and one exact HTTPS browser origin.
 *
 * @param origin exact externally configured HTTPS origin
 * @param accessCookieKey independent shared-password gate signing key
 * @param jwtKey separate portfolio bearer signing key
 * @author Daniel Jr Nkulu
 */
@ConfigurationProperties("resilientpay.portfolio")
@Profile("portfolio")
public record PortfolioProperties(String origin, String accessCookieKey, String jwtKey) {
    public PortfolioProperties {
        URI parsed = URI.create(origin == null ? "" : origin);
        if (!"https".equals(parsed.getScheme())
                || parsed.getHost() == null
                || parsed.getRawUserInfo() != null
                || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null
                || !parsed.getRawPath().isEmpty()
                || !origin.equals(parsed.toASCIIString())) {
            throw new IllegalArgumentException("Portfolio requires one exact HTTPS origin");
        }
        validateKey(accessCookieKey);
        validateKey(jwtKey);
        if (java.security.MessageDigest.isEqual(
                HexFormat.of().parseHex(accessCookieKey), HexFormat.of().parseHex(jwtKey))) {
            throw new IllegalArgumentException("Portfolio signing keys must be independent");
        }
    }

    private static void validateKey(String key) {
        if (key == null
                || !key.matches("[a-fA-F0-9]{64,128}")
                || key.length() % 2 != 0
                || key.chars().distinct().count() < 8) {
            throw new IllegalArgumentException("Portfolio requires a non-sentinel independent signing key");
        }
    }

    @Override
    public String toString() {
        return "PortfolioProperties[secrets=REDACTED]";
    }
}
