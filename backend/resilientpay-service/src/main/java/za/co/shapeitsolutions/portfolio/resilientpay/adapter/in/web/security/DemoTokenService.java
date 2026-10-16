package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ResilientPaySecurityProperties;

/**
 * Issues short-lived HS256 access tokens solely inside the explicit local demo profile.
 *
 * @author Daniel Jr Nkulu
 */
@Service
@Profile("demo")
public final class DemoTokenService {
    /** Synthetic issuer accepted only by the demo/test decoder. */
    public static final String ISSUER = "urn:resilientpay:local-demo";

    /** Stable database tenant represented by the local-only {@code demo-tenant} alias. */
    public static final UUID DEMO_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final Duration LIFETIME = Duration.ofMinutes(30);
    private final Clock clock;
    private final ResilientPaySecurityProperties properties;
    private final byte[] signingKey;

    /** Creates the issuer from the external local-demo signing input. */
    public DemoTokenService(Clock clock, ResilientPaySecurityProperties properties) {
        this.clock = clock;
        this.properties = properties;
        this.signingKey = deriveKey(properties.evidenceHmacKey());
    }

    /** Issues one token for a bounded demo role and synthetic tenant. */
    public String issue(DemoRole role, UUID tenantId) {
        Instant issuedAt = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(properties.audience())
                .subject("demo-" + role.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                .issueTime(Date.from(issuedAt))
                .notBeforeTime(Date.from(issuedAt.minusSeconds(1)))
                .expirationTime(Date.from(issuedAt.plus(LIFETIME)))
                .jwtID(UUID.randomUUID().toString())
                .claim("tenant_id", tenantId.toString())
                .claim("roles", List.of(role.name()))
                .claim("scope", role.scopes())
                .claim("acr", role.assuranceLevel())
                .build();
        SignedJWT token = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256)
                        .type(JOSEObjectType.JWT)
                        .build(),
                claims);
        try {
            token.sign(new MACSigner(signingKey));
            return token.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Local demo token signing is unavailable", exception);
        }
    }

    /** Resolves only the stable seeded tenant alias or its canonical UUID. */
    public UUID tenant(String tenantValue) {
        if (tenantValue == null || tenantValue.isBlank() || "demo-tenant".equals(tenantValue)) {
            return DEMO_TENANT_ID;
        }
        if (DEMO_TENANT_ID.toString().equals(tenantValue)) {
            return DEMO_TENANT_ID;
        }
        throw new IllegalArgumentException("Only the seeded local demo tenant is available");
    }

    /** Derives a fixed-size HS256 key without exposing configuration text to token payloads. */
    public static byte[] deriveKey(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }
}
