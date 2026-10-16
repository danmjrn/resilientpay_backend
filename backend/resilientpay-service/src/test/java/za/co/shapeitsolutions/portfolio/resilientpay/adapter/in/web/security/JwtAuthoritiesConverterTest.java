package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ResilientPayHttpProperties;

/**
 * Verifies whitespace-delimited OAuth scopes and audited Keycloak realm-role scope mappings.
 *
 * @author Daniel Jr Nkulu
 */
class JwtAuthoritiesConverterTest {

    @Test
    void directScopesSplitAcrossAllOAuthWhitespace() {
        Jwt jwt = jwt(Map.of("scope", "reversals.read   audit.read\npolicies.read"));

        Set<String> authorities = names(new JwtAuthoritiesConverter().convert(jwt));

        assertEquals(Set.of("SCOPE_reversals.read", "SCOPE_audit.read", "SCOPE_policies.read"), authorities);
    }

    @Test
    void keycloakRolesMapToDocumentedLeastPrivilegeScopes() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("PAYMENT_ENGINEER", "APPROVER", "UNKNOWN_ROLE"))));

        Set<String> authorities = names(new JwtAuthoritiesConverter().convert(jwt));

        assertTrue(authorities.containsAll(Set.of(
                "ROLE_PAYMENT_ENGINEER",
                "ROLE_APPROVER",
                "ROLE_UNKNOWN_ROLE",
                "SCOPE_reversals.write",
                "SCOPE_reversals.retry",
                "SCOPE_reconciliation.approve",
                "SCOPE_audit.read")));
        assertTrue(!authorities.contains("SCOPE_transactions.write"));
        assertTrue(!authorities.contains("SCOPE_reconciliation.decide"));
    }

    @Test
    void directAndMappedScopesAreUnionedWithoutDuplicates() {
        Jwt jwt = jwt(Map.of(
                "scope",
                "reversals.read",
                "roles",
                List.of("OPERATIONS_ANALYST"),
                "realm_access",
                Map.of("roles", List.of("OPERATIONS_ANALYST"))));

        List<String> authorities = new JwtAuthoritiesConverter()
                .convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();

        assertEquals(authorities.size(), Set.copyOf(authorities).size());
        assertTrue(authorities.contains("SCOPE_reconciliation.decide"));
    }

    @Test
    void corsAllowsOnlyExplicitUiOriginAndRequiredApiHeaders() {
        CorsConfigurationSource source = new SecurityConfiguration()
                .corsConfigurationSource(new ResilientPayHttpProperties(List.of("http:" + "/" + "/localhost:3000")));
        CorsConfiguration configuration =
                source.getCorsConfiguration(new MockHttpServletRequest("OPTIONS", "/api/v1/demo/token"));

        assertEquals(List.of("http:" + "/" + "/localhost:3000"), configuration.getAllowedOrigins());
        assertTrue(configuration.getAllowedMethods().containsAll(List.of("GET", "POST", "OPTIONS")));
        assertTrue(configuration
                .getAllowedHeaders()
                .containsAll(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-ID")));
        assertEquals(Boolean.FALSE, configuration.getAllowCredentials());
    }

    @Test
    void prometheusScrapeIsAnonymousOnlyInExplicitLocalDemoProfile() {
        MockEnvironment demo = new MockEnvironment();
        demo.setActiveProfiles("demo");
        MockHttpServletRequest metrics = new MockHttpServletRequest("GET", "/actuator/prometheus");
        MockHttpServletRequest application = new MockHttpServletRequest("GET", "/api/v1/reversalcommands");

        assertTrue(SecurityConfiguration.publicMetricsMatcher(demo).matches(metrics));
        assertTrue(!SecurityConfiguration.publicMetricsMatcher(demo).matches(application));
        assertTrue(!SecurityConfiguration.publicMetricsMatcher(new MockEnvironment())
                .matches(metrics));
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("synthetic-token")
                .header("alg", "none")
                .subject("subject-123")
                .issuedAt(Instant.parse("2026-08-20T10:00:00Z"))
                .expiresAt(Instant.parse("2026-08-20T11:00:00Z"));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static Set<String> names(Iterable<GrantedAuthority> authorities) {
        java.util.Spliterator<GrantedAuthority> spliterator = authorities.spliterator();
        return java.util.stream.StreamSupport.stream(spliterator, false)
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
    }
}
