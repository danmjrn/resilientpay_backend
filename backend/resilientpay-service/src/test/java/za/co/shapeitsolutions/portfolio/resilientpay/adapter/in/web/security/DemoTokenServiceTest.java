package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ResilientPaySecurityProperties;

/**
 * Verifies that local demo tokens are short-lived, tenant-bound, and cryptographically valid.
 *
 * @author Daniel Jr Nkulu
 */
class DemoTokenServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-20T10:15:30Z");
    private static final String KEY = "deterministic-demo-token-test-key";

    @Test
    void demoAliasIssuesValidTenantBoundLeastPrivilegeToken() throws Exception {
        DemoTokenService service = service();

        SignedJWT token =
                SignedJWT.parse(service.issue(DemoRole.RECONCILIATION_CHECKER, service.tenant("demo-tenant")));

        assertTrue(token.verify(new MACVerifier(DemoTokenService.deriveKey(KEY))));
        assertEquals(DemoTokenService.ISSUER, token.getJWTClaimsSet().getIssuer());
        assertEquals(
                DemoTokenService.DEMO_TENANT_ID.toString(),
                token.getJWTClaimsSet().getStringClaim("tenant_id"));
        assertEquals(
                "reversals.read reconciliation.read reconciliation.approve audit.read",
                token.getJWTClaimsSet().getStringClaim("scope"));
        assertEquals("urn:resilientpay:aal2", token.getJWTClaimsSet().getStringClaim("acr"));
        assertEquals(
                NOW.plusSeconds(1800),
                token.getJWTClaimsSet().getExpirationTime().toInstant());
    }

    @Test
    void invalidTenantAliasFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> service().tenant("another-tenant"));
        assertThrows(IllegalArgumentException.class, () -> service().tenant("00000000-0000-0000-0000-000000000002"));
        assertEquals(DemoTokenService.DEMO_TENANT_ID, service().tenant(DemoTokenService.DEMO_TENANT_ID.toString()));
    }

    private static DemoTokenService service() {
        return new DemoTokenService(
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ResilientPaySecurityProperties("resilientpay-api", KEY, "test-v1", "urn:resilientpay:aal2"));
    }
}
