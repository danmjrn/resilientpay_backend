package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ResilientPaySecurityProperties;

/**
 * Verifies that production decoder construction avoids issuer discovery while retaining external
 * issuer, timestamp and resource-audience validation. Validity probes stay well outside clock skew
 * rather than testing a wall-clock boundary.
 *
 * @author Daniel Jr Nkulu
 */
class OidcJwtDecoderTest {
    private static final String ISSUER = "https:" + "/" + "/identity.example.test/realms/resilientpay";
    private static final String AUDIENCE = "resilientpay-api";

    @Test
    void configuredInternalJwkUriDoesNotTriggerExternalIssuerDiscovery() {
        String internalJwkUri = "http:" + "/" + "/keycloak.internal:8080/realms/resilientpay/certs";

        JwtDecoder decoder = new SecurityConfiguration()
                .oidcJwtDecoder(
                        ISSUER,
                        internalJwkUri,
                        new ResilientPaySecurityProperties(AUDIENCE, "test-key", "test-v1", "urn:resilientpay:aal2"));

        assertInstanceOf(NimbusJwtDecoder.class, decoder);
    }

    @Test
    void validatorRequiresBothExternalIssuerAndAudience() {
        OAuth2TokenValidator<Jwt> validator = SecurityConfiguration.oidcValidator(ISSUER, AUDIENCE);

        assertFalse(validator.validate(jwt(ISSUER, AUDIENCE)).hasErrors());
        assertTrue(validator.validate(jwt(ISSUER + "/wrong", AUDIENCE)).hasErrors());
        assertTrue(validator.validate(jwt(ISSUER, "different-api")).hasErrors());
    }

    @Test
    void validatorRejectsExpiredTokenWithOtherwiseValidIssuerAndAudience() {
        OAuth2TokenValidator<Jwt> validator = SecurityConfiguration.oidcValidator(ISSUER, AUDIENCE);
        Instant now = Instant.now();
        // Ten minutes beyond expiry avoids relying on a configured clock-skew boundary.
        Jwt expired = jwtBuilder(ISSUER, AUDIENCE)
                .issuedAt(now.minusSeconds(3600))
                .expiresAt(now.minusSeconds(600))
                .build();

        assertFalse(validator.validate(jwt(ISSUER, AUDIENCE)).hasErrors());
        assertTrue(validator.validate(expired).hasErrors());
    }

    @Test
    void validatorRejectsNotYetValidTokenWithOtherwiseValidIssuerAndAudience() {
        OAuth2TokenValidator<Jwt> validator = SecurityConfiguration.oidcValidator(ISSUER, AUDIENCE);
        Instant now = Instant.now();
        // A future not-before claim must fail even when signature-independent claims are valid.
        Jwt notYetValid = jwtBuilder(ISSUER, AUDIENCE)
                .notBefore(now.plusSeconds(600))
                .expiresAt(now.plusSeconds(3600))
                .build();

        assertFalse(validator.validate(jwt(ISSUER, AUDIENCE)).hasErrors());
        assertTrue(validator.validate(notYetValid).hasErrors());
    }

    @Test
    @DisplayName("R12: unavailable internal JWK endpoint fails decoding closed without token leakage")
    void unavailableJwkEndpointFailsProtectedDecodeClosedWithoutLeakingToken() {
        String marker = "r12-sensitive-token-marker";
        String token = signedShapeWithoutARealSignature(marker);
        JwtDecoder decoder = new SecurityConfiguration()
                .oidcJwtDecoder(
                        ISSUER,
                        "http:" + "/" + "/127.0.0.1:1/unreachable-jwks",
                        new ResilientPaySecurityProperties(AUDIENCE, "test-key", "test-v1", "urn:resilientpay:aal2"));

        JwtException failure = assertTimeoutPreemptively(
                Duration.ofSeconds(3), () -> assertThrows(JwtException.class, () -> decoder.decode(token)));

        assertFalse(failure.toString().contains(token));
        assertFalse(failure.toString().contains(marker));
    }

    private static Jwt jwt(String issuer, String audience) {
        return jwtBuilder(issuer, audience).build();
    }

    private static Jwt.Builder jwtBuilder(String issuer, String audience) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("synthetic-token")
                .header("alg", "RS256")
                .issuer(issuer)
                .audience(List.of(audience))
                .subject("subject-123")
                .issuedAt(now.minusSeconds(5))
                .expiresAt(now.plusSeconds(300));
    }

    private static String signedShapeWithoutARealSignature(String marker) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString(
                "{\"alg\":\"RS256\",\"kid\":\"unavailable-key\"}".getBytes(StandardCharsets.UTF_8));
        String claims = encoder.encodeToString(
                ("""
                        {"iss":"%s","aud":["%s"],"sub":"%s","exp":4102444800}
                        """).formatted(ISSUER, AUDIENCE, marker).strip().getBytes(StandardCharsets.UTF_8));
        String signature = encoder.encodeToString(new byte[256]);
        return header + "." + claims + "." + signature;
    }
}
