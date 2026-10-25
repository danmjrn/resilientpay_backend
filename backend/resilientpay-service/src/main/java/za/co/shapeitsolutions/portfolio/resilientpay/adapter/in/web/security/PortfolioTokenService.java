package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.PortfolioSession;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.PortfolioProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ResilientPaySecurityProperties;

/**
 * Issues bounded synthetic persona tokens whose authority is bound to an active visitor workspace.
 *
 * @author Daniel Jr Nkulu
 */
@Service
@Profile("portfolio")
public final class PortfolioTokenService {
    /** Issuer accepted exclusively by the hosted portfolio decoder. */
    public static final String ISSUER = "urn:resilientpay:portfolio";

    private final Clock clock;
    private final String audience;
    private final byte[] signingKey;

    /** Creates the issuer using an independent key that is never shared with evidence signing. */
    public PortfolioTokenService(Clock clock, PortfolioProperties portfolio, ResilientPaySecurityProperties security) {
        this.clock = clock;
        this.audience = security.audience();
        this.signingKey = HexFormat.of().parseHex(portfolio.jwtKey());
    }

    /** Returns a short-lived token, capped by the active workspace's absolute expiration. */
    public String issue(PortfolioSession session, DemoRole role) {
        Instant now = clock.instant();
        Instant expiry =
                now.plusSeconds(600).isBefore(session.expiresAt()) ? now.plusSeconds(600) : session.expiresAt();
        if (!expiry.isAfter(now)) {
            throw new IllegalArgumentException("Portfolio workspace has expired");
        }
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(audience)
                .subject(subject(session.id(), role))
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .jwtID(UUID.randomUUID().toString())
                .claim("sid", session.id().toString())
                .claim("tenant_id", session.tenantId().toString())
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
            throw new IllegalStateException("Portfolio token issuance is unavailable", exception);
        }
    }

    /** Supplies a distinct pseudonymous actor per workspace and persona. */
    public static String subject(UUID sessionId, DemoRole role) {
        return "portfolio-" + sessionId + "-" + role.name().toLowerCase(Locale.ROOT);
    }
}
